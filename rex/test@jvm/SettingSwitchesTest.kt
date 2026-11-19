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
}
