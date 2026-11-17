package org.example.kotlintest.rex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ESC = "\u001b"

class RexEndpointTest {
    @Test
    fun `reads the endpoint forms the rex cli accepts`() {
        assertEquals(RexEndpoint.UnixSocket("/run/user/1000/rex/server.sock"), RexEndpoint.parse("unix:///run/user/1000/rex/server.sock"))
        assertEquals(RexEndpoint.UnixSocket("/tmp/rex.sock"), RexEndpoint.parse("/tmp/rex.sock"))
        assertEquals(RexEndpoint.Network("wss://studio.tail1234.ts.net"), RexEndpoint.parse("https://studio.tail1234.ts.net/"))
        assertEquals(RexEndpoint.Network("ws://127.0.0.1:7297"), RexEndpoint.parse("http://127.0.0.1:7297"))
        assertEquals(RexEndpoint.Network("wss://studio.tail1234.ts.net"), RexEndpoint.parse(" studio.tail1234.ts.net "))
    }

    @Test
    fun `refuses what it cannot connect to`() {
        assertFailsWith<IllegalArgumentException> { RexEndpoint.parse("ssh://me@studio") }
        assertFailsWith<IllegalArgumentException> { RexEndpoint.parse("ftp://studio") }
        assertFailsWith<IllegalArgumentException> { RexEndpoint.parse("") }
    }

    @Test
    fun `the local server comes from the environment`() {
        assertEquals("unix:///custom.sock", RexEndpoint.local(mapOf("REX_SERVER" to "unix:///custom.sock")))
        assertEquals("unix:///run/user/42/rex/server.sock", RexEndpoint.local(mapOf("XDG_RUNTIME_DIR" to "/run/user/42")))
    }
}

class RexStreamScannerTest {
    private val nonce = 7UL

    /** Feeds [chunks] and returns what came out, in order: output as text, messages as their type in brackets. */
    private fun scan(vararg chunks: String): List<String> {
        val scanner = RexStreamScanner(nonce)
        val result = ArrayList<String>()
        for (chunk in chunks) {
            scanner.feed(
                chunk.toByteArray(), 0,
                onOutput = { bytes, offset, length -> result += String(bytes, offset, length) },
                onMessage = { result += "[${it["type"]}]" },
            )
        }
        // Output may be reported in pieces; where they are split means nothing.
        return result.fold(ArrayList()) { merged, piece ->
            if (merged.isNotEmpty() && !piece.startsWith("[") && !merged.last().startsWith("[")) merged[merged.lastIndex] += piece else merged += piece
            merged
        }
    }

    private fun message(type: String, nonce: ULong = this.nonce) = "${ESC}_rex1;$nonce;{\"type\":\"$type\"}$ESC\\"

    @Test
    fun `output passes through untouched`() {
        assertEquals(listOf("plain ${ESC}[31mred"), scan("plain ${ESC}[31mred"))
    }

    @Test
    fun `messages are reported where they sit in the output`() {
        assertEquals(
            listOf("before", "[\"resize\"]", "between", "[\"theme\"]", "after"),
            scan("before${message("resize")}between${message("theme")}after"),
        )
    }

    @Test
    fun `a message split across chunks is put back together`() {
        val text = "a${message("resize")}b"
        for (cut in 1 until text.length) {
            assertEquals(listOf("a", "[\"resize\"]", "b"), scan(text.substring(0, cut), text.substring(cut)), "cut at $cut")
        }
    }

    @Test
    fun `a look-alike from a program is output, not a message`() {
        val forged = message("resize", nonce = 8UL)
        assertEquals(listOf("x${forged}y"), scan("x${forged}y"))
    }

    @Test
    fun `an escape at the end of a chunk is not lost`() {
        assertEquals(listOf("tail$ESC[0m"), scan("tail$ESC", "[0m"))
    }
}

class SnapshotLengthTest {
    private fun record(tag: Int, payload: Int) =
        byteArrayOf(tag.toByte(), 0, payload.toByte(), 0, 0, 0, 0, 0, 0, 0) + ByteArray(payload)

    private val envelope = "GHOSTSNP".toByteArray() + byteArrayOf(1, 0)
    private val snapshot = envelope + record(tag = 1, payload = 5) + record(tag = 5, payload = 0) + record(tag = 6, payload = 0)

    @Test
    fun `finds where a complete snapshot ends`() {
        val stream = snapshot + "live output".toByteArray()
        assertEquals(snapshot.size, snapshotLength(stream, stream.size))
    }

    @Test
    fun `asks for more while the snapshot is incomplete`() {
        for (size in 0 until snapshot.size) assertNull(snapshotLength(snapshot, size), "with $size bytes")
    }

    @Test
    fun `rejects a stream that is not a snapshot`() {
        assertFailsWith<IllegalArgumentException> { snapshotLength("not a snapshot".toByteArray(), 14) }
    }
}

class SessionViewTest {
    private fun view(json: String): SessionView = parseSessionView(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun `a split becomes two panes with their own rectangles`() {
        val view = view(
            """
            {"revision": 82, "session_id": "session:1", "label": "work", "active_window_id": "window:b",
             "windows": [
               {"window_id": "window:a", "label": "one", "layers": []},
               {"window_id": "window:b", "label": "two", "focused_block_id": "block:right",
                "layers": [{"kind": "tiled", "bounds": [0,0,1,1],
                  "layout": {"rect": [0,0,1,1], "split": {"direction": "horizontal", "ratio": 0.25,
                    "before": {"rect": [0,0,0.25,1], "block_id": "block:left"},
                    "after": {"rect": [0.25,0,0.75,1], "block_id": "block:right"}}},
                  "blocks": [
                    {"block_id": "block:left", "label": "shell", "creator_name": "com.superlogical.terminal", "owner": "client:me"},
                    {"block_id": "block:right", "label": "logs", "creator_name": "com.superlogical.terminal"}]}]}]}
            """,
        )

        assertEquals(82, view.revision)
        assertEquals(listOf("one", "two"), view.windows.map { it.label })
        val window = view.activeWindow!!
        assertEquals("window:b", window.id)
        assertEquals("block:right", window.focusedBlockId)
        assertEquals(listOf("block:left", "block:right"), window.panes.map { it.blockId })
        assertEquals(PaneRect(0f, 0f, 0.25f, 1f), window.panes[0].rect)
        assertEquals(PaneRect(0.25f, 0f, 0.75f, 1f), window.panes[1].rect)
        assertEquals("client:me", window.panes[0].ownerClientId)
        assertNull(window.panes[1].ownerClientId)
        assertTrue(window.panes.all { it.isTerminal && !it.floating })
    }

    @Test
    fun `floating layers come after the tiled one and minimized ones are left out`() {
        val view = view(
            """
            {"revision": 1, "session_id": "session:1", "label": "work", "active_window_id": "window:a",
             "windows": [{"window_id": "window:a", "label": "one", "layers": [
               {"kind": "tiled", "layout": {"rect": [0,0,1,1], "block_id": "block:main"}, "blocks": [{"block_id": "block:main", "creator_name": "com.superlogical.terminal"}]},
               {"kind": "floating", "layout": {"rect": [0.2,0.2,0.5,0.5], "block_id": "block:float"}, "blocks": [{"block_id": "block:float", "creator_name": "com.superlogical.terminal"}]},
               {"kind": "floating", "state": "minimized", "layout": {"rect": [0,0,1,1], "block_id": "block:hidden"}, "blocks": []},
               {"kind": "tiled", "layout": null, "blocks": []}]}]}
            """,
        )

        val panes = view.activeWindow!!.panes
        assertEquals(listOf("block:main", "block:float"), panes.map { it.blockId })
        assertEquals(listOf(false, true), panes.map { it.floating })
    }

    @Test
    fun `an active window that no longer exists falls back to the first`() {
        val view = view("""{"revision": 1, "session_id": "s", "label": "l", "active_window_id": "window:gone", "windows": [{"window_id": "window:a", "label": "one", "layers": []}]}""")
        assertEquals("window:a", view.activeWindow?.id)
    }
}

class WorkspaceTest {
    @Test
    fun `what is saved is read back`() {
        val file = Files.createTempDirectory("workspace").resolve("nested/workspace.json")
        val workspace = Workspace(file, hosts = listOf(SavedHost("Local", "unix:///run/rex.sock"), SavedHost("studio", "https://studio.ts.net")))
        workspace.lastHost = "studio"
        workspace.sessions["Local"] = "session:1"
        workspace.collapsedHosts = setOf("studio")
        workspace.collapsedSessions = setOf("session:2", "session:3")
        workspace.save()

        val loaded = Workspace.load(file)

        assertEquals(workspace.hosts, loaded.hosts)
        assertEquals("studio", loaded.lastHost)
        assertEquals(mapOf("Local" to "session:1"), loaded.sessions)
        assertEquals(setOf("studio"), loaded.collapsedHosts)
        assertEquals(setOf("session:2", "session:3"), loaded.collapsedSessions)
        file.parent.parent.toFile().deleteRecursively()
    }

    @Test
    fun `a missing or broken file is an empty workspace`() {
        val directory = Files.createTempDirectory("workspace")
        assertTrue(Workspace.load(directory.resolve("absent.json")).hosts.isEmpty())
        val broken = directory.resolve("broken.json").also { Files.writeString(it, "{ not json") }
        assertTrue(Workspace.load(broken).hosts.isEmpty())
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `the workspace lives under the config directory`() {
        assertEquals("/cfg/kotlin-test/workspace.json", Workspace.defaultFile(mapOf("XDG_CONFIG_HOME" to "/cfg")).toString())
        assertEquals("/home/me/.config/kotlin-test/workspace.json", Workspace.defaultFile(mapOf("HOME" to "/home/me")).toString())
    }
}

class RexKeyTest {
    @Test
    fun `a key is sent in the shape client_key takes`() {
        val json: JsonObject = RexKey("key_a", "shift", "shift", "A", 'a'.code, RexKey.Action.Press).toJson()
        assertEquals(
            """{"key":"key_a","mods":"shift","consumed_mods":"shift","text":"A","unshifted_codepoint":97,"composing":false,"action":"press"}""",
            json.toString(),
        )
    }
}

class ActivityStateTest {
    private fun pane(blockId: String) = PaneView(blockId, PaneRect.Full, label = null, ownerClientId = null, isTerminal = true, floating = false)
    private fun window(vararg blocks: String) = WindowView("w", "w", focusedBlockId = null, zoomedBlockId = null, panes = blocks.map(::pane))

    @Test
    fun `states are read as the server spells them`() {
        assertEquals(ActivityState.Working, ActivityState.parse("working"))
        assertEquals(ActivityState.Blocked, ActivityState.parse("blocked"))
        assertEquals(ActivityState.Done, ActivityState.parse("done"))
        assertEquals(ActivityState.Idle, ActivityState.parse("something new"))
        assertEquals(ActivityState.Idle, ActivityState.parse(null))
    }

    @Test
    fun `the state that most wants attention wins`() {
        assertEquals(ActivityState.Blocked, listOf(ActivityState.Working, ActivityState.Blocked, ActivityState.Error).max())
        assertEquals(ActivityState.Error, listOf(ActivityState.Working, ActivityState.Done, ActivityState.Error).max())
        assertEquals(ActivityState.Working, listOf(ActivityState.Idle, ActivityState.Done, ActivityState.Working).max())
    }

    @Test
    fun `a tab shows the most pressing thing any of its panes is doing`() {
        val state = HostState(activity = mapOf("block:a" to ActivityState.Working, "block:b" to ActivityState.Blocked))
        assertEquals(ActivityState.Blocked, state.activityOf(window("block:a", "block:b", "block:c")))
        assertEquals(ActivityState.Working, state.activityOf(window("block:a", "block:c")))
        assertEquals(ActivityState.Idle, state.activityOf(window("block:c")))
        assertEquals(ActivityState.Idle, state.activityOf(window()))
    }
}

class TabNamesTest {
    private fun window(id: String, label: String, focused: String? = "block:a") =
        WindowView(id, label, focusedBlockId = focused, zoomedBlockId = null, panes = listOf(PaneView("block:a", PaneRect.Full, "shell", null, isTerminal = true, floating = false)))

    private fun process(text: String) = programName(Json.parseToJsonElement(text).jsonObject)

    @Test
    fun `a program is called by its script, then how it was invoked, then its executable`() {
        assertEquals("bash", process("""{"foreground":{"name":"bash","argv0":"-bash","path":"/usr/bin/bash"}}"""))
        assertEquals("btop", process("""{"foreground":{"name":"btop","argv0":"/usr/bin/btop"}}"""))
        assertEquals("deploy.py", process("""{"foreground":{"name":"python3.12","argv0":"python3","script":"/home/u/bin/deploy.py"}}"""))
        assertEquals("zsh", process("""{"child":{"name":"zsh"}}"""), "a reply that can't name the foreground still names the terminal's own program")
        assertNull(process("""{"foreground":{"name":""}}"""))
        assertNull(programName(null))
    }

    @Test
    fun `only the server's own placeholder counts as an unnamed tab`() {
        assertTrue(window("window:01a1-89abcdef", "Window 89abcdef").hasDefaultLabel())
        assertTrue(window("window:01a1-89abcdef", "window:01a1-89abcdef").hasDefaultLabel())
        assertTrue(window("window:01a1-89abcdef", "").hasDefaultLabel())
        assertTrue(!window("window:01a1-89abcdef", "DesktopApp").hasDefaultLabel())
        assertTrue(!window("window:01a1-89abcdef", "Window 12345678").hasDefaultLabel(), "another window's placeholder is a name someone typed")
        assertTrue(!window("window:01a1-89abcdef", "Window").hasDefaultLabel())
    }

    @Test
    fun `an unnamed tab is called by the program in its focused pane, and a given name wins`() {
        val unnamed = window("window:01a1-89abcdef", "Window 89abcdef")
        val state = HostState(programs = mapOf("block:a" to ForegroundProgram("nvim", cwd = "/home/u/dev/rex", home = "/home/u")))
        assertEquals(TabName("nvim", "~/dev/rex"), state.nameOf(unnamed))
        assertEquals(TabName("logs"), state.nameOf(unnamed.copy(label = "logs")), "a given name stands alone")
        assertEquals(TabName("Window 89abcdef"), HostState().nameOf(unnamed), "the placeholder shows until the server names a program")
    }

    private fun program(text: String, known: ForegroundProgram? = null) = foregroundProgram(Json.parseToJsonElement(text).jsonObject, known)

    @Test
    fun `a program is where it runs, or where the nearest process above it does`() {
        assertEquals(
            ForegroundProgram("bash", "/home/u", "/home/u"),
            program("""{"home":"/home/u","child":{"name":"bash","cwd":"/home/u"},"foreground":{"name":"bash","argv0":"-bash","cwd":"/home/u"}}"""),
        )
        // Under sudo the program's own directory can't be read; the shell it was started from is close enough.
        assertEquals(
            "/srv/app",
            program("""{"foreground":{"name":"vim"},"ancestors":[{"name":"sudo"},{"name":"zsh","cwd":"/srv/app"}]}""")?.cwd,
        )
        assertNull(program("""{"foreground":{"name":"btop"}}""")?.cwd)
        assertNull(program("""{"foreground":{"name":"btop","cwd":"relative"}}""")?.cwd, "only an absolute path is a place")
        assertNull(program("""{"home":"/home/u"}"""), "no program, nothing to show")
    }

    @Test
    fun `an event that leaves the home directory out keeps the one already known`() {
        val before = ForegroundProgram("zsh", "/home/u", "/home/u")
        val after = program("""{"foreground":{"name":"make","cwd":"/home/u/dev/rex"}}""", before)
        assertEquals("~/dev/rex", after?.directory)
    }

    @Test
    fun `a directory is shown from home, and a long one by its last folders`() {
        assertEquals("~", shortDirectory("/home/u", "/home/u"))
        assertEquals("~", shortDirectory("/home/u", "/home/u/"))
        assertEquals("~/dev/rex", shortDirectory("/home/u/dev/rex", "/home/u"))
        assertEquals("/home/user2", shortDirectory("/home/user2", "/home/u"), "a name that only starts like home isn't under it")
        assertEquals("/etc/nginx", shortDirectory("/etc/nginx", null))
        assertEquals("…/data_tools/atlas", shortDirectory("/home/u/dev/data_tools/atlas", "/home/u", room = 18))
        assertEquals("…/a-very-long-folder-name-indeed", shortDirectory("/srv/a-very-long-folder-name-indeed", null, room = 18), "the last folder always shows")
    }
}

class TailnetTest {
    private val status = Json.parseToJsonElement(
        """
        {"Self":{"HostName":"omwork","DNSName":"omwork.tail1234.ts.net.","Online":true},
         "Peer":{
           "k1":{"HostName":"studio-rex","DNSName":"studio-rex.tail1234.ts.net.","Online":false},
           "k2":{"HostName":"Laptop-Rex","DNSName":"laptop-rex.tail1234.ts.net.","Online":true},
           "k3":{"HostName":"phone","DNSName":"phone.tail1234.ts.net.","Online":true},
           "k4":{"HostName":"rexford","DNSName":"rexford.tail1234.ts.net.","Online":true},
           "k5":{"HostName":"ghost-rex","DNSName":"","Online":false}}}
        """,
    ).jsonObject

    @Test
    fun `the rex servers on a tailnet are the devices named for one, those that are up first`() {
        assertEquals(
            listOf(
                TailnetServer("laptop-rex", "laptop-rex.tail1234.ts.net", online = true),
                TailnetServer("studio-rex", "studio-rex.tail1234.ts.net", online = false),
            ),
            Tailnet.parseStatus(status),
        )
        val studio = Tailnet.parseStatus(status).last()
        assertEquals("studio", studio.label)
        assertEquals("https://studio-rex.tail1234.ts.net", studio.endpoint)
        assertEquals(emptyList(), Tailnet.parseStatus(JsonObject(emptyMap())), "Tailscale stopped, or not signed in")
    }

    @Test
    fun `two spellings of one address are the same place`() {
        val key = endpointKey("studio-rex.tail1234.ts.net")
        assertEquals(key, endpointKey("https://Studio-Rex.tail1234.ts.net/"))
        assertEquals(key, endpointKey("wss://studio-rex.tail1234.ts.net"))
        assertEquals(key, endpointKey(" studio-rex.tail1234.ts.net. "))
        assertTrue(key != endpointKey("http://studio-rex.tail1234.ts.net"), "plain HTTP is another listener")
        assertTrue(key != endpointKey("studio-rex.tail1234.ts.net:7297"))
        assertEquals(endpointKey("unix:///run/rex/server.sock"), endpointKey("/run/rex/server.sock"))
    }

    @Test
    fun `a host known only by its address is labelled by its machine`() {
        assertEquals("studio", suggestedHostLabel("https://studio-rex.tail1234.ts.net"))
        assertEquals("studio", suggestedHostLabel("studio-rex.tail1234.ts.net"))
        assertEquals("build", suggestedHostLabel("http://build:7297"))
        assertEquals("10.0.0.5", suggestedHostLabel("10.0.0.5:7297"))
        assertEquals("rex", suggestedHostLabel("unix:///tmp/rex.sock"))
    }
}
