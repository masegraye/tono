package org.example.kotlintest

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.kotlintest.rex.HostConnection
import org.example.kotlintest.rex.HostState
import org.example.kotlintest.rex.NotificationSwitches
import org.example.kotlintest.rex.RexClient
import org.example.kotlintest.rex.SavedHost
import org.example.kotlintest.rex.SelectionSwitches
import org.example.kotlintest.rex.Workspace
import org.example.kotlintest.rex.WorkspaceFile
import org.example.kotlintest.rex.load
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.awt.event.InputEvent
import java.awt.event.KeyEvent as AwtKeyEvent

/** The settings: how they are laid out, what opens and closes them, and that their switches are the app's. */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
class SettingsTest {
    private val directory = Files.createTempDirectory("workspace")
    private val file = directory.resolve("workspace.json")

    /** The app with a host that isn't there, so no server is asked, and a workspace in a file of its own. */
    private fun <T> withApp(windowed: Boolean = false, block: suspend (RexUiState, RexCommands) -> T): T = runBlocking {
        RexClient(this, Workspace(WorkspaceFile(file), listOf(SavedHost("Local", "unix://${directory.resolve("absent.sock")}")))).use { client ->
            val ui = RexUiState(client).also { it.settingsWindowed = windowed }
            val commands = RexCommands(ui).also { it.declare() }
            // As the app's window does for as long as it is there.
            val following = launch { ui.followSettings() }
            try {
                block(ui, commands)
            } finally {
                following.cancel()
            }
        }
    }

    private suspend fun eventually(what: () -> Boolean) = withTimeout(5_000) { while (!what()) delay(5) }

    private fun RexCommands.stateOf(action: String, ui: RexUiState) = actions.first { it.name == action }.state!!.invoke(ui)

    @Test
    fun `the list of sections is beside a section from 640 wide, and before it when narrower`() {
        assertEquals(SettingsLayout.Narrow, settingsLayout(420.dp))
        assertEquals(SettingsLayout.Narrow, settingsLayout(639.dp))
        assertEquals(SettingsLayout.Wide, settingsLayout(640.dp))
        assertEquals(SettingsLayout.Wide, settingsLayout(700.dp))
        assertEquals(SettingsLayout.Wide, settingsLayout(1100.dp))
    }

    @Test
    fun `Settings has Rex's name and title, and Control where the Mac has Command`() {
        val action = clientActions().first { it.name == "client.settings" }
        assertEquals("Settings…", action.title)
        assertEquals(listOf("ctrl+,"), action.keys)
        assertTrue(action.inPalette)
    }

    @Test
    fun `asking for the settings opens them, and asking again brings them forward without opening more`() = withApp { ui, commands ->
        assertFalse(ui.settingsOpen)
        assertTrue(commands.run("client.settings", origin = "test"))
        assertTrue(ui.settingsOpen)
        val raised = ui.settingsRaises
        assertTrue(commands.run("client.settings", origin = "test"))
        assertTrue(ui.settingsOpen)
        assertEquals(raised + 1, ui.settingsRaises)
        ui.closeSettings()
        assertFalse(ui.settingsOpen)
    }

    @Test
    fun `as a panel the settings are over the terminals, and in a window of their own they are not`() {
        withApp(windowed = false) { ui, _ ->
            ui.openSettings()
            assertTrue(ui.settingsPanelOpen)
            assertTrue(ui.overlayOpen)
        }
        withApp(windowed = true) { ui, _ ->
            ui.openSettings()
            assertTrue(ui.settingsOpen)
            assertFalse(ui.settingsPanelOpen)
            assertFalse(ui.overlayOpen)
        }
    }

    @Test
    fun `a switch turned in the settings is the palette's, and is kept`() = withApp { ui, commands ->
        assertEquals("on", commands.stateOf("client.setting.notifications.activity.sounds", ui))
        // What the settings' switches do.
        ui.switchSounds(false)
        ui.notifications = ui.notifications.copy(blocked = false, programMessages = false)
        ui.selection = ui.selection.copy(copyOnSelection = true)

        assertEquals("off", commands.stateOf("client.setting.notifications.activity.sounds", ui))
        assertEquals("off", commands.stateOf("client.setting.notifications.macos.terminal_notifications", ui))
        assertEquals("on", commands.stateOf("client.setting.terminal.selection.copy_on_select", ui))
        assertFalse(ui.client.sounds.value)
        assertEquals(NotificationSwitches(blocked = false, programMessages = false), ui.client.notifications.value)

        val kept = Workspace.load(file)
        assertFalse(kept.sounds)
        assertEquals(NotificationSwitches(blocked = false, programMessages = false), kept.notifications)
        assertEquals(SelectionSwitches(copyOnSelection = true), kept.selection)
    }

    @Test
    fun `a switch turned in the palette, or anywhere else, shows in the settings`() = withApp { ui, commands ->
        // The palette's toggle.
        commands.run("client.setting.terminal.selection.primary_selection", origin = "test")
        assertTrue(ui.selection.primarySelection)
        assertTrue(ui.client.selection.value.primarySelection)

        // Something else with the same client: another window, say.
        ui.client.setSounds(false)
        ui.client.setNotifications(NotificationSwitches(done = false))
        ui.client.setSelection(SelectionSwitches())
        eventually { !ui.soundsEnabled && !ui.notifications.done && !ui.selection.primarySelection }
        assertEquals("off", commands.stateOf("client.setting.terminal.selection.primary_selection", ui))
    }

    private val source = object : java.awt.Component() {}

    private fun key(code: Int, modifiers: Int = 0, type: Int = AwtKeyEvent.KEY_PRESSED) = KeyEvent(
        key = Key(code),
        type = if (type == AwtKeyEvent.KEY_PRESSED) KeyEventType.KeyDown else KeyEventType.KeyUp,
        isCtrlPressed = modifiers and InputEvent.CTRL_DOWN_MASK != 0,
        nativeEvent = AwtKeyEvent(source, type, 0L, modifiers, code, AwtKeyEvent.CHAR_UNDEFINED, AwtKeyEvent.KEY_LOCATION_STANDARD),
    )

    @Test
    fun `Control+W closes the settings' window, and Escape doesn't`() = withApp(windowed = true) { ui, _ ->
        ui.openSettings()
        assertFalse(settingsKey(ui, key(AwtKeyEvent.VK_ESCAPE)))
        assertFalse(settingsKey(ui, key(AwtKeyEvent.VK_W)), "a w typed into a field")
        assertTrue(ui.settingsOpen)
        assertTrue(settingsKey(ui, key(AwtKeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK)))
        assertFalse(ui.settingsOpen)
        // The release is the window's too, and does nothing more.
        ui.openSettings()
        assertTrue(settingsKey(ui, key(AwtKeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK, AwtKeyEvent.KEY_RELEASED)))
        assertTrue(ui.settingsOpen)
    }

    @Test
    fun `Escape closes the settings' panel, and Control+W is left to the browser`() = withApp(windowed = false) { ui, _ ->
        ui.openSettings()
        assertFalse(settingsKey(ui, key(AwtKeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK)))
        assertTrue(ui.settingsOpen)
        assertTrue(settingsKey(ui, key(AwtKeyEvent.VK_ESCAPE)))
        assertFalse(ui.settingsOpen)
    }

    @Test
    fun `the key that opens the settings is theirs while they are open, and opens nothing more`() = withApp(windowed = true) { ui, _ ->
        ui.openSettings()
        val raised = ui.settingsRaises
        assertTrue(settingsKey(ui, key(AwtKeyEvent.VK_COMMA, InputEvent.CTRL_DOWN_MASK)))
        assertTrue(ui.settingsOpen)
        assertEquals(raised + 1, ui.settingsRaises)
        assertFalse(settingsKey(ui, key(AwtKeyEvent.VK_COMMA)))
    }

    @Test
    fun `what is said about notifications depends on who has to allow them`() {
        assertNull(notificationAccessWords(NotificationAccess.Granted, browser = false))
        assertTrue("notify-send" in notificationAccessWords(NotificationAccess.Unavailable, browser = false)!!)
        for (access in NotificationAccess.entries) assertTrue("browser" in notificationAccessWords(access, browser = true)!!, access.name)
        // Where they can't be had, it says why, as far as that is known, and what would change it.
        assertTrue("Home Screen" in notificationAccessWords(NotificationAccess.Unavailable, browser = true, NotificationsMissing.NeedsHomeScreen)!!)
        assertTrue("HTTPS" in notificationAccessWords(NotificationAccess.Unavailable, browser = true, NotificationsMissing.Insecure)!!)
    }

    @Test
    fun `back in a panel goes from a section to the list, and from the list out of the settings`() = withApp { ui, _ ->
        ui.openSettings()
        ui.settings.section = SettingsSection.Terminal
        settingsBack(ui, inSection = true)
        assertNull(ui.settings.section)
        assertTrue(ui.settingsOpen)
        settingsBack(ui, inSection = false)
        assertFalse(ui.settingsOpen)
    }

    @Test
    fun `closing the settings over a narrow window's sidebar leaves the sidebar showing`() = withApp { ui, _ ->
        ui.narrow = true
        ui.openSettings()
        ui.closeSettings()
        assertTrue(ui.sidebarVisible)
    }

    @Test
    fun `the note on Primary Selection says what a browser does instead`() {
        assertTrue("Browsers have no primary selection" in primarySelectionWords(PrimarySelection.Clipboard))
        assertTrue("clipboard" in primarySelectionWords(PrimarySelection.Clipboard))
        assertTrue("middle click" in primarySelectionWords(PrimarySelection.Desktop))
    }

    @Test
    fun `About gives the home server's API revision once it is connected, and says so until then`() {
        assertEquals("12", homeServerWords(HostState(connection = HostConnection.Connected, reached = true, apiRevision = 12)))
        assertEquals("Connecting", homeServerWords(HostState()))
        assertEquals("Not connected", homeServerWords(HostState(connection = HostConnection.Disconnected("gone"), failed = true)))
    }
}
