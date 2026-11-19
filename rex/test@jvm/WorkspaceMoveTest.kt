package org.example.kotlintest.rex

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where Tono's files go on each system, and the move there from the one file there was before: adr-001, backlog-049. */
class WorkspaceMoveTest {
    // A home of this test's own. Nothing here reads the real environment or the real home directory.
    private val home = Files.createTempDirectory("tono-home")
    private val environment = mapOf("HOME" to home.toString())
    private val oldFile = home.resolve(".config/kotlin-test/workspace.json")
    private val settingsFile = home.resolve(".config/tono/settings.json")
    private val stateFile = home.resolve(".local/state/tono/state.json")

    /** A file of the kind there was before: settings, state, and a key from a Tono not yet written. */
    private val old = """
        {
          "hosts": [
            { "label": "Local", "endpoint": "unix://${home.resolve("absent.sock")}" },
            { "label": "studio", "endpoint": "https://studio.invalid", "icon": "macstudio" }
          ],
          "last_host": "studio",
          "sessions": { "Local": "session:1", "studio": "session:7" },
          "notifications": { "system_notifications": true, "blocked": true, "error": true, "done": false, "program_messages": true },
          "terminal": { "copy_on_selection": true, "primary_selection": false },
          "tabs": { "session:1": "window:3" },
          "collapsed_sessions": ["session:2"],
          "muted_tabs": [ { "endpoint": "unix:///run/rex.sock", "session": "session:1", "window": "window:4" } ],
          "shortcuts": { "bindings": [ { "keys": ["ctrl+shift+k"], "action": "tab.next" } ] },
          "appearance": { "theme": "nord", "font_size": 15 },
          "client_label": "desk",
          "from_a_newer_tono": { "kept": true }
        }
    """.trimIndent()

    private fun writeOld(text: String = old) {
        Files.createDirectories(oldFile.parent)
        Files.writeString(oldFile, text)
    }

    private fun start(environment: Map<String, String> = this.environment) = Workspace.loadDefault(environment, "Linux")

    private fun files(directory: Path): List<String> =
        if (Files.notExists(directory)) emptyList() else Files.walk(directory).use { all -> all.filter(Files::isRegularFile).map { directory.relativize(it).toString() }.sorted().toList() }

    @Test
    fun `on Linux the files follow the XDG directories`() {
        val files = Workspace.defaultFiles(mapOf("HOME" to "/home/me", "XDG_CONFIG_HOME" to "/cfg", "XDG_STATE_HOME" to "/var/state"), "Linux")
        assertEquals("/cfg/tono/settings.json", files.settings.toString())
        assertEquals("/var/state/tono/state.json", files.state.toString())
    }

    @Test
    fun `without them, or with ones that are empty or relative, they are under the home directory`() {
        val usual = TonoFiles(Path.of("/home/me/.config/tono/settings.json"), Path.of("/home/me/.local/state/tono/state.json"))
        assertEquals(usual, Workspace.defaultFiles(mapOf("HOME" to "/home/me"), "Linux"))
        assertEquals(usual, Workspace.defaultFiles(mapOf("HOME" to "/home/me", "XDG_CONFIG_HOME" to "", "XDG_STATE_HOME" to ""), "Linux"))
        // The specification: a relative path in one of these is invalid, and to be ignored.
        assertEquals(usual, Workspace.defaultFiles(mapOf("HOME" to "/home/me", "XDG_CONFIG_HOME" to "cfg", "XDG_STATE_HOME" to "./state"), "Linux"))
        // One of the two is enough to move one of the two.
        assertEquals("/s/tono/state.json", Workspace.defaultFiles(mapOf("HOME" to "/home/me", "XDG_STATE_HOME" to "/s"), "Linux").state.toString())
        assertEquals(usual.settings, Workspace.defaultFiles(mapOf("HOME" to "/home/me", "XDG_STATE_HOME" to "/s"), "Linux").settings)
        // A system this doesn't know by name is taken for a Unix.
        assertEquals(usual, Workspace.defaultFiles(mapOf("HOME" to "/home/me"), "FreeBSD"))
    }

    @Test
    fun `on macOS both are in Application Support under the app id, whatever XDG says`() {
        val files = Workspace.defaultFiles(mapOf("HOME" to "/Users/me", "XDG_CONFIG_HOME" to "/cfg", "XDG_STATE_HOME" to "/state"), "Mac OS X")
        assertEquals("/Users/me/Library/Application Support/dev.mg.tono/settings.json", files.settings.toString())
        assertEquals("/Users/me/Library/Application Support/dev.mg.tono/state.json", files.state.toString())
    }

    @Test
    fun `on Windows settings roam and state stays on the machine`() {
        val roaming = "C:\\Users\\me\\AppData\\Roaming"
        val local = "C:\\Users\\me\\AppData\\Local"
        val files = Workspace.defaultFiles(mapOf("USERPROFILE" to "C:\\Users\\me", "APPDATA" to roaming, "LOCALAPPDATA" to local), "Windows 11")
        assertEquals(Path.of(roaming).resolve("Tono").resolve("settings.json"), files.settings)
        assertEquals(Path.of(local).resolve("Tono").resolve("state.json"), files.state)
        val bare = Workspace.defaultFiles(mapOf("USERPROFILE" to "/users/me"), "Windows 11")
        assertEquals(Path.of("/users/me/AppData/Roaming/Tono/settings.json"), bare.settings)
        assertEquals(Path.of("/users/me/AppData/Local/Tono/state.json"), bare.state)
    }

    @Test
    fun `the old file is found the way it was found then`() {
        assertEquals("/cfg/kotlin-test/workspace.json", Workspace.oldFile(mapOf("XDG_CONFIG_HOME" to "/cfg")).toString())
        assertEquals("/home/me/.config/kotlin-test/workspace.json", Workspace.oldFile(mapOf("HOME" to "/home/me")).toString())
    }

    @Test
    fun `the first start moves the old file's contents into the two new ones and leaves it as it was`() {
        writeOld()
        val modified = Files.getLastModifiedTime(oldFile)

        val workspace = start()

        assertEquals(listOf(".config/kotlin-test/moved.txt", ".config/kotlin-test/workspace.json", ".config/tono/settings.json", ".local/state/tono/state.json"), files(home))
        assertEquals(old, Files.readString(oldFile))
        assertEquals(modified, Files.getLastModifiedTime(oldFile))
        assertTrue(settingsFile.toString() in Files.readString(oldFile.resolveSibling("moved.txt")))
        assertTrue(stateFile.toString() in Files.readString(oldFile.resolveSibling("moved.txt")))

        // Each new file is a part of the old one, key for key and value for value, and together they are all of it.
        val before = json(old)
        val settings = json(settingsFile)
        val state = json(stateFile)
        assertEquals(setOf("hosts", "notifications", "terminal", "shortcuts", "appearance", "client_label", "from_a_newer_tono"), settings.keys)
        assertEquals(setOf("last_host", "sessions", "tabs", "collapsed_sessions", "muted_tabs"), state.keys)
        assertEquals(before, kotlinx.serialization.json.JsonObject(settings + state))

        // Rex keeps its state directory to its owner, and so does Tono.
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(stateFile.parent)))

        // What the app then has is what it had.
        val was = Workspace.load(oldFile)
        assertEquals(was.hosts, workspace.hosts)
        assertEquals("studio", workspace.lastHost)
        assertEquals(was.sessions, workspace.sessions)
        assertEquals(was.tabs, workspace.tabs)
        assertEquals(was.collapsedSessions, workspace.collapsedSessions)
        assertEquals(was.mutedTabs, workspace.mutedTabs)
        assertEquals(NotificationSwitches(done = false), workspace.notifications)
        assertEquals(SelectionSwitches(copyOnSelection = true), workspace.selection)
        assertEquals(was.shortcuts, workspace.shortcuts)
        assertFalse(workspace.shortcuts.isEmpty)
        assertEquals(AppearanceChoice(theme = "nord", fontSize = 15), workspace.appearance)
        assertEquals("desk", workspace.clientLabel)
        assertEquals(settingsFile.toString(), workspace.location)
        assertEquals(stateFile.toString(), workspace.stateLocation)
        assertNull(workspace.settingsProblem)
        val notice = workspace.notices.single()
        assertFalse(notice.isError)
        assertTrue(oldFile.toString() in notice.message && settingsFile.toString() in notice.message && stateFile.toString() in notice.message)
    }

    @Test
    fun `it follows the XDG variables, for the old file and the new ones`() {
        val config = home.resolve("cfg")
        val state = home.resolve("st")
        val environment = environment + mapOf("XDG_CONFIG_HOME" to config.toString(), "XDG_STATE_HOME" to state.toString())
        Files.createDirectories(config.resolve("kotlin-test"))
        Files.writeString(config.resolve("kotlin-test/workspace.json"), old)
        val workspace = start(environment)
        assertEquals(listOf("cfg/kotlin-test/moved.txt", "cfg/kotlin-test/workspace.json", "cfg/tono/settings.json", "st/tono/state.json"), files(home))
        assertEquals("desk", workspace.clientLabel)
    }

    @Test
    fun `using the app after the move writes the new files only, and settings only for a setting`() = runBlocking {
        writeOld()
        val workspace = start()
        val settingsBefore = Files.readString(settingsFile)
        RexClient(this, workspace).use { client ->
            assertTrue(client.log.entries.value.any { !it.isError && "moved" in it.message })
            assertEquals(settingsBefore, Files.readString(settingsFile), "starting rewrote nothing")
            client.selectHost("Local")
            assertEquals(settingsBefore, Files.readString(settingsFile), "a switch rewrote no settings")
            assertEquals("Local", json(stateFile).string("last_host"))
            client.setSounds(false)
        }
        val settings = json(settingsFile)
        assertEquals("false", settings["sounds"].toString())
        assertEquals("""{"kept":true}""", settings["from_a_newer_tono"].toString())
        assertEquals("\"macstudio\"", (settings["hosts"] as kotlinx.serialization.json.JsonArray)[1].let { it as kotlinx.serialization.json.JsonObject }["icon"].toString())
        assertEquals(old, Files.readString(oldFile))
    }

    @Test
    fun `a second start reads the new files and never the old one again`() {
        writeOld()
        start().apply {
            clientLabel = "attic"
            lastHost = "Local"
            save()
        }
        val settings = Files.readString(settingsFile)
        val state = Files.readString(stateFile)
        val note = Files.getLastModifiedTime(oldFile.resolveSibling("moved.txt"))
        // An older Tono is still used now and then, and writes its file.
        writeOld(old.replace("\"desk\"", "\"elsewhere\"").replace("\"last_host\": \"studio\"", "\"last_host\": \"nowhere\""))

        val again = start()

        assertEquals("attic", again.clientLabel)
        assertEquals("Local", again.lastHost)
        assertTrue(again.notices.isEmpty())
        again.save()
        assertEquals(settings, Files.readString(settingsFile))
        assertEquals(state, Files.readString(stateFile))
        assertEquals(note, Files.getLastModifiedTime(oldFile.resolveSibling("moved.txt")))
    }

    @Test
    fun `settings already in the new place are never replaced, readable or not`() {
        writeOld()
        Files.createDirectories(settingsFile.parent)
        for (there in listOf("""{"client_label":"mine"}""", "{ a typo", "")) {
            Files.writeString(settingsFile, there)
            val workspace = start()
            assertEquals(there, Files.readString(settingsFile))
            assertEquals(listOf(".config/kotlin-test/workspace.json", ".config/tono/settings.json"), files(home), "nothing was moved")
            assertNull(workspace.lastHost, "the old file wasn't read")
            assertNull(workspace.clientLabel.takeIf { it == "desk" })
            assertEquals(there == "{ a typo", workspace.settingsProblem != null)
        }
        // And the one with the typo stays as it is however the app is used.
        Files.writeString(settingsFile, "{ a typo")
        start().apply {
            sounds = false
            lastHost = "Local"
            save()
        }
        assertEquals("{ a typo", Files.readString(settingsFile))
        assertEquals(old, Files.readString(oldFile))
    }

    @Test
    fun `a move stopped before the settings were written is done again, keeping the state that is there`() {
        writeOld()
        Files.createDirectories(stateFile.parent)
        val there = """{"last_host":"Local","sessions":{}}"""
        Files.writeString(stateFile, there)

        val workspace = start()

        assertEquals(there, Files.readString(stateFile))
        assertEquals("Local", workspace.lastHost)
        assertEquals("desk", workspace.clientLabel)
        assertEquals(json(old).keys - Workspace.STATE_KEYS, json(settingsFile).keys)
        assertEquals(old, Files.readString(oldFile))
    }

    @Test
    fun `an old file that can't be read is left alone, nothing is written, and the move happens once it is corrected`() = runBlocking {
        val broken = old.replace("\"last_host\"", "last_host")
        for (unreadable in listOf(broken, "[]")) {
            writeOld(unreadable)
            val workspace = start()
            assertTrue(workspace.hosts.isEmpty())
            assertTrue(workspace.notices.single().isError)
            assertTrue(oldFile.toString() in workspace.settingsProblem!!)
            // The app starts on defaults, adds the local host, and is used; none of it is saved.
            RexClient(this, workspace).use { client ->
                assertTrue(client.log.entries.value.any { it.isError && oldFile.toString() in it.message })
                client.setSounds(false)
                client.selectHost("Local")
            }
            assertEquals(unreadable, Files.readString(oldFile))
            assertEquals(listOf(".config/kotlin-test/workspace.json"), files(home))
        }

        writeOld()
        val workspace = start()
        assertEquals("desk", workspace.clientLabel)
        assertFalse(workspace.notices.single().isError)
        assertTrue(Files.exists(settingsFile) && Files.exists(stateFile))
    }

    @Test
    fun `with no old file it starts fresh in the new places, and makes nothing in the old one`() = runBlocking {
        val workspace = start()
        assertTrue(workspace.notices.isEmpty())
        assertEquals(emptyList(), files(home), "nothing is written before there is something to keep")
        RexClient(this, workspace).use { client -> client.selectHost("Local") }
        assertEquals(listOf(".config/tono/settings.json", ".local/state/tono/state.json"), files(home))
        assertEquals(setOf("hosts", "notifications", "terminal"), json(settingsFile).keys)
        assertEquals("Local", json(stateFile).string("last_host"))
    }

    @Test
    fun `an empty old file is nothing to move`() {
        writeOld("")
        val workspace = start()
        assertTrue(workspace.notices.isEmpty())
        assertNull(workspace.settingsProblem)
        assertEquals(listOf(".config/kotlin-test/workspace.json"), files(home))
    }

    @Test
    fun `a file is put in place new or not at all`() {
        val file = home.resolve("a/b/settings.json")
        assertTrue(WorkspaceFile(file).writeNew("one"))
        assertFalse(WorkspaceFile(file).writeNew("two"))
        assertEquals("one", Files.readString(file))
        assertEquals(listOf("a/b/settings.json"), files(home), "and nothing is left beside it")
    }

    @Test
    fun `copies that don't read back as written stop the app saving`() {
        // A storage that loses what it is given.
        class Forgetful(override val location: String) : WorkspaceStorage {
            var text: String? = null
            override fun read() = text
            override fun write(text: String) {
                this.text = "{}"
            }
        }
        val settings = Forgetful("settings")
        val state = Forgetful("state")
        val before = CountingStorage(old, "the old file")
        val workspace = Workspace.loadMoving(settings, state, before)
        assertTrue(workspace.notices.single().isError)
        assertTrue(workspace.settingsProblem != null && workspace.stateProblem != null)
        assertEquals(0, before.writes)
        assertEquals(old, before.text)
    }

    @Test
    fun `the move works the same with any storage, as in a browser`() {
        val settings = CountingStorage(location = "tono.settings")
        val state = CountingStorage(location = "tono.state")
        val before = CountingStorage(old, "tono.workspace")
        var told = 0
        val workspace = Workspace.loadMoving(settings, state, before) { told++ }
        assertEquals(1, told)
        assertEquals(old, before.text)
        assertEquals(0, before.writes)
        assertEquals(json(old), kotlinx.serialization.json.JsonObject(json(settings.text!!) + json(state.text!!)))
        assertEquals("desk", workspace.clientLabel)
        // And once moved, never again.
        before.text = "{}"
        assertEquals("desk", Workspace.loadMoving(settings, state, before) { told++ }.clientLabel)
        assertEquals(1, told)
        assertEquals(1 to 1, settings.writes to state.writes)
    }
}
