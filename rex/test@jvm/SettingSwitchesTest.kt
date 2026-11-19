package org.example.kotlintest.rex

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The switches a person sets, which a settings window and a palette both show and both change. */
class SettingSwitchesTest {
    private val directory = Files.createTempDirectory("workspace")
    private val file = directory.resolve("workspace.json")

    // A host that isn't there, so no server is asked.
    private val hosts = listOf(SavedHost("Local", "unix://${directory.resolve("absent.sock")}"))

    @Test
    fun `a switch starts as the workspace has it, and a change is heard at once and kept`() = runBlocking {
        RexClient(this, Workspace(WorkspaceFile(file), hosts, selection = SelectionSwitches(copyOnSelection = true))).use { client ->
            assertTrue(client.sounds.value)
            assertEquals(NotificationSwitches(), client.notifications.value)
            assertEquals(SelectionSwitches(copyOnSelection = true), client.selection.value)

            // Someone watching hears of each change, as a settings window does.
            val heard = async { withTimeout(5_000) { client.notifications.take(3).toList() } }
            yield()
            client.setNotifications(NotificationSwitches(done = false))
            client.notifications.first { !it.done }
            yield()
            client.setNotifications(NotificationSwitches(done = false, programMessages = false))
            assertEquals(listOf(NotificationSwitches(), NotificationSwitches(done = false), NotificationSwitches(done = false, programMessages = false)), heard.await())

            client.setSounds(false)
            client.setSelection(SelectionSwitches(primarySelection = true))
            assertFalse(client.sounds.value)
            assertEquals(SelectionSwitches(primarySelection = true), client.selection.value)
            // The workspace the client was given is the same one, changed.
            assertFalse(client.workspace.sounds)
        }
        val kept = Workspace.load(file)
        assertFalse(kept.sounds)
        assertEquals(NotificationSwitches(done = false, programMessages = false), kept.notifications)
        assertEquals(SelectionSwitches(primarySelection = true), kept.selection)

        RexClient(this, kept).use { client ->
            assertFalse(client.sounds.value)
            assertEquals(NotificationSwitches(done = false, programMessages = false), client.notifications.value)
        }
    }

    @Test
    fun `setting a switch to what it is writes nothing`() = runBlocking {
        RexClient(this, Workspace(WorkspaceFile(file), hosts)).use { client ->
            Files.deleteIfExists(file)
            client.setSounds(true)
            client.setNotifications(NotificationSwitches())
            client.setSelection(SelectionSwitches())
            assertFalse(Files.exists(file))
            client.setSounds(false)
            assertTrue(Files.exists(file))
        }
    }

    @Test
    fun `a workspace says where it is kept, and the keymap where the configuration file is`() {
        assertEquals(file.toAbsolutePath().toString(), Workspace.load(file).location)
        assertEquals(null, Workspace(null).location)
        val answered = parseKeymap(Json.parseToJsonElement("""{"bindings":[],"config":{"path":"/home/me/.config/rex/init.lua","generation":1,"hash":""}}""").jsonObject)
        assertEquals("/home/me/.config/rex/init.lua", answered.configPath)
        assertEquals(null, parseKeymap(Json.parseToJsonElement("""{"bindings":[]}""").jsonObject).configPath)
        assertEquals(null, resolveLocally(emptyList(), ShortcutOverrides()).configPath)
    }

    // What Mase's server answered on 2026-11-19, with the names changed, and whoami and version from a scratch one.
    private val status = Json.parseToJsonElement(
        """{"pid":1670719,"instance_id":"c332","host_id":"3d0b","started_at":"2026-11-10T02:09:42.429156488Z","exit_empty":false,"session_count":2,
            "tailscale":{"state":"running","dns_name":"work-rex.tail1234.ts.net","account":{"login_name":"me@example.com"},"tailnet_name":"example.com","allow_tailnet_members":false,
              "https":{"state":"available","url":"https://work-rex.tail1234.ts.net"}},
            "api_revision":12,"config":{"generation":3,"hash":""},
            "listeners":[{"endpoint":"login-server:///run/user/1000/rex/login-server.sock","addresses":["/run/user/1000/rex/login-server.sock"],"transport":"unix","admission":"login_server"},
              {"endpoint":"unix:///run/user/1000/rex/server.sock","addresses":["/run/user/1000/rex/server.sock"],"transport":"unix","admission":"peer_credentials","primary":true}]}""",
    ).jsonObject
    private val version = Json.parseToJsonElement("""{"rex_version":"0.0.1794270332-g4ba7cc","platform":"linux_amd64","commit":"4ba7","built_at":"2026-11-10T00:25:32Z","dirty":false,"api_revision":12}""").jsonObject
    private val whoami = Json.parseToJsonElement("""{"principal":{"kind":"peer","transport":"unix","display":"uid 1000","uid":1000},"account":"mase"}""").jsonObject

    @Test
    fun `what a server says about itself is read from its three answers`() {
        val info = parseServerInfo(status, version, whoami)
        assertEquals("0.0.1794270332-g4ba7cc", info.version)
        assertEquals("linux_amd64", info.platform)
        assertEquals(12, info.apiRevision)
        assertEquals(1670719, info.pid)
        assertEquals("2026-11-10 02:09 UTC", info.startedShown)
        assertEquals("mase", info.account)
        assertEquals(2, info.sessionCount)
        assertEquals(3, info.configGeneration)
        assertEquals(listOf("login server", "Unix socket"), info.listeners.map { it.kind })
        assertEquals(listOf(false, true), info.listeners.map { it.primary })
        assertEquals(ServerTailscale("running", "work-rex.tail1234.ts.net", "https://work-rex.tail1234.ts.net", "me@example.com", "example.com"), info.tailscale)
        assertEquals("On", info.tailscale?.stateWords)
    }

    @Test
    fun `a server that says less is shown for what it says`() {
        // No version or whoami: an old server, or a caller it tells less.
        val bare = parseServerInfo(Json.parseToJsonElement("""{"pid":7,"started_at":"yesterday","session_count":0,"config":{"generation":1,"hash":""},"tailscale":{"state":"needs_login","auth_url":"https://login.example/a","allow_tailnet_members":false}}""").jsonObject, null, null)
        assertEquals(null, bare.version)
        assertEquals(null, bare.apiRevision)
        assertEquals("yesterday", bare.startedShown, "a time it can't read is shown as written")
        assertTrue(bare.listeners.isEmpty())
        assertTrue(bare.tailscale!!.needsSignIn)
        assertEquals("https://login.example/a", bare.tailscale?.signInLink)
        // States this app doesn't know are taken as starting; off is off.
        assertEquals("Starting…", ServerTailscale("something_new").stateWords)
        assertEquals("Off", ServerTailscale("disabled").stateWords)
        assertEquals(ServerInfo(), parseServerInfo(null, null, null))
    }

    @Test
    fun `the label this client gives itself is kept, and an empty one goes back to the one it started with`() = runBlocking {
        RexClient(this, Workspace(WorkspaceFile(file), hosts), info = TonoClient.labelled("phone")).use { client ->
            assertEquals("phone", client.clientLabel.value)
            // No server is connected to refuse it, so it is kept for when one is.
            assertEquals(null, client.setClientLabel("  desk  "))
            assertEquals("desk", client.clientLabel.value)
        }
        assertEquals("desk", Workspace.load(file).clientLabel)
        RexClient(this, Workspace.load(file), info = TonoClient.labelled("phone")).use { client ->
            assertEquals("desk", client.clientLabel.value)
            assertEquals(null, client.setClientLabel(""))
            assertEquals("phone", client.clientLabel.value)
        }
        assertEquals(null, Workspace.load(file).clientLabel)
    }
}
