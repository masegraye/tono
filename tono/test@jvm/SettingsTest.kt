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
    fun `while a shortcut is being recorded no key is the settings' own`() = withApp(windowed = true) { ui, commands ->
        ui.openSettings()
        ui.settings.shortcutEdit = commands.shortcutTargets(ui.client.keymap.value).first()
        assertFalse(settingsKey(ui, key(AwtKeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK)))
        assertFalse(settingsKey(ui, key(AwtKeyEvent.VK_COMMA, InputEvent.CTRL_DOWN_MASK)))
        assertTrue(ui.settingsOpen)
        // Closing the settings ends the recording with them.
        ui.closeSettings()
        assertNull(ui.settings.shortcutEdit)
    }

    @Test
    fun `the shortcuts list every action with its keys and where they come from, and can be searched`() = withApp { ui, commands ->
        val client = ui.client
        fun listed() = client.shortcutList(commands.shortcutTargets(client.keymap.value))
        val all = listed()
        // Every action of the palette is there, and each tab's digit.
        assertTrue(all.size > 60)
        assertEquals("Ctrl+,", all.single { it.target.action == "client.settings" }.keysShown())
        assertEquals("default", all.single { it.target.action == "client.settings" }.sourceShown())
        assertEquals("no shortcut", all.single { it.target.action == "client.shortcuts.edit" }.sourceShown())
        assertEquals(all, filterShortcuts(all, "  "))

        // By title, by category, by the action's name, and by key.
        assertEquals(listOf("Settings…"), filterShortcuts(all, "settings").map { it.target.title })
        assertEquals(listOf("client.find.open", "client.find.next", "client.find.previous"), filterShortcuts(all, "client.find").map { it.target.action })
        assertEquals(setOf("Find"), filterShortcuts(all, "find match").map { it.target.category }.toSet())
        assertEquals(listOf("Change Session…"), filterShortcuts(all, "ctrl+k").map { it.target.title })
        assertEquals(listOf("Split Pane Right"), filterShortcuts(all, "split right").map { it.target.title })
        assertTrue(filterShortcuts(all, "no such action").isEmpty())

        // By where a key comes from: what was changed here is found by the app's name.
        assertTrue(filterShortcuts(all, "changed in tono").isEmpty())
        client.setShortcut("session.switch", null, org.example.kotlintest.rex.KeyChord.parse("ctrl+shift+k")!!)
        eventually { client.keymap.value.shortcutFor("session.switch")?.display() == "Ctrl+Shift+K" }
        val changed = listed()
        assertEquals(listOf("Change Session…" to "Tono"), filterShortcuts(changed, "ctrl+shift+k").map { it.target.title to it.sourceShown() })
        assertEquals(1, changed.count { it.changed })
        assertEquals("1 changed in Tono", changedShortcutsWords(changed.count { it.changed }))

        // Reset All puts everything back, and is kept.
        client.resetAllShortcuts()
        eventually { client.keymap.value.shortcutFor("session.switch")?.display() == "Ctrl+K" }
        assertEquals(0, listed().count { it.changed })
        assertTrue(Workspace.load(file).shortcuts.isEmpty)
    }

    @Test
    fun `Reset All Shortcuts asks first, and says how many`() {
        var reset = 0
        val one = resetAllShortcutsQuestion(1) { reset++ }
        assertEquals("Reset all shortcuts?", one.title)
        assertTrue(one.text.startsWith("The shortcut changed in Tono goes back to its default"))
        assertTrue(resetAllShortcutsQuestion(3) {}.text.startsWith("The 3 shortcuts changed in Tono go back to their defaults"))
        assertEquals(0, reset)
        one.onConfirm()
        assertEquals(1, reset)
    }

    @Test
    fun `shortcuts can be changed once a key that only a keyboard sends has been seen`() = withApp { ui, _ ->
        // As in a browser, where a phone has no such keys.
        ui.keyboardSeen = false
        ui.sawKey(org.example.kotlintest.rex.KeyChord("a"))
        ui.sawKey(org.example.kotlintest.rex.KeyChord("enter"))
        assertFalse(ui.keyboardSeen)
        ui.sawKey(org.example.kotlintest.rex.KeyChord("k", ctrl = true))
        assertTrue(ui.keyboardSeen)
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
    fun `back in a panel goes a step out at a time where there is one column, and then out of the settings`() = withApp { ui, _ ->
        ui.openSettings()
        ui.settings.go(SettingsPlace(SettingsSection.Terminal, detail = "something"))
        assertEquals(2, ui.settings.depth)
        settingsBack(ui, narrow = true)
        assertEquals(SettingsPlace(SettingsSection.Terminal), ui.settings.place)
        settingsBack(ui, narrow = true)
        assertNull(ui.settings.place)
        assertEquals(0, ui.settings.depth)
        assertTrue(ui.settingsOpen)
        settingsBack(ui, narrow = true)
        assertFalse(ui.settingsOpen)

        // Where the list is always beside the section, back has only the settings to leave.
        ui.openSettings()
        ui.settings.section = SettingsSection.Shortcuts
        settingsBack(ui, narrow = false)
        assertFalse(ui.settingsOpen)
        assertEquals(SettingsSection.Shortcuts, ui.settings.section, "and they open again where they were")
    }

    @Test
    fun `back and forward go through the places one has been`() {
        val settings = SettingsState()
        assertFalse(settings.canGoBack)
        assertFalse(settings.canGoForward)
        settings.section = SettingsSection.Terminal
        settings.section = SettingsSection.Terminal // choosing where one is goes nowhere
        settings.section = SettingsSection.Notifications
        settings.go(SettingsPlace(SettingsSection.Notifications, "a detail"))
        assertEquals("a detail", placeTitle(settings.place!!))

        settings.back()
        assertEquals(SettingsPlace(SettingsSection.Notifications), settings.place)
        assertEquals("Notifications", placeTitle(settings.place!!))
        settings.back()
        assertEquals(SettingsSection.Terminal, settings.section)
        assertTrue(settings.canGoForward)
        settings.forward()
        assertEquals(SettingsSection.Notifications, settings.section)

        // Going somewhere new from the middle forgets what was ahead.
        settings.section = SettingsSection.Shortcuts
        assertFalse(settings.canGoForward)
        settings.back(); settings.back(); settings.back()
        assertNull(settings.place, "all the way back is where it started: nowhere chosen")
        assertFalse(settings.canGoBack)
    }

    @Test
    fun `the sections are Rex's, in its order, with the server first`() {
        assertEquals(listOf("Server", "Terminal", "Shortcuts", "Notifications", "Remote Hosts"), SettingsSection.entries.map { it.title })
    }

    @Test
    fun `Search Settings finds a setting by its name, its section or another word for it`() {
        assertTrue(searchSettings("").isEmpty())
        assertTrue(searchSettings("   ").isEmpty())
        assertEquals(listOf("Copy on Selection" to SettingsSection.Terminal), searchSettings("copy").map { it.title to it.section })
        assertEquals(listOf("Play Activity Sounds"), searchSettings("sound").map { it.title })
        assertEquals(listOf("Terminal Notifications"), searchSettings("osc").map { it.title })
        assertEquals(listOf("Configuration file"), searchSettings("init.lua").map { it.title })
        // A section's name finds everything in it, the section itself first.
        assertEquals(listOf("Terminal", "Copy on Selection", "Primary Selection", "Terminal Notifications"), searchSettings("terminal").map { it.title })
        assertEquals(setOf(SettingsSection.Shortcuts), searchSettings("shortcuts").map { it.section }.toSet())
        assertEquals(listOf("Show System Notifications"), searchSettings("system notif").map { it.title })
        assertTrue(searchSettings("no such setting").isEmpty())
        // Every section can be found, and every entry is in a section that exists.
        for (section in SettingsSection.entries) assertTrue(searchSettings(section.title).any { it.section == section }, section.title)
    }

    @Test
    fun `hosts someone arranged are listed in that order, after the home server, reachable or not`() {
        val up = HostState(connection = HostConnection.Connected)
        val down = HostState(connection = HostConnection.Disconnected("gone"), failed = true)
        val hosts = listOf("zeta" to up, "Local" to up, "alpha" to down, "beta" to up, "new" to up, "lost" to down)
        // Unarranged: the connected ones by name, then the ones that are down.
        assertEquals(listOf("Local", "beta", "new", "zeta", "alpha", "lost"), sidebarOrder(hosts, home = "Local") { it }.map { it.first })
        // Arranged: as arranged, then any that weren't there to arrange, by the old rule.
        assertEquals(listOf("Local", "alpha", "zeta", "beta", "new", "lost"), sidebarOrder(hosts, home = "Local", order = listOf("alpha", "zeta", "beta", "gone")) { it }.map { it.first })
        // The home server is first whatever the order says.
        assertEquals("Local", sidebarOrder(hosts, home = "Local", order = listOf("zeta", "Local")) { it }.first().first)
    }

    @Test
    fun `a host's line says how reaching it stands, in Rex's words`() {
        assertEquals("Connected", hostStatusLine(org.example.kotlintest.rex.HostReach.Connected))
        assertEquals("Connecting…", hostStatusLine(org.example.kotlintest.rex.HostReach.Connecting))
        assertEquals("Reconnecting…", hostStatusLine(org.example.kotlintest.rex.HostReach.Reconnecting))
        assertEquals("Couldn't connect", hostStatusLine(org.example.kotlintest.rex.HostReach.Down))
    }

    @Test
    fun `every host says where it is kept, and what a change to it touches`() {
        val listed = org.example.kotlintest.rex.HostListing("host:1", "studio", listOf("studio.ts.net"), "macstudio", source = null, addedFrom = "laptop")
        val declared = listed.copy(source = "init.lua:4", addedFrom = null)
        val home = hostSourceWords(org.example.kotlintest.rex.HostSource.Home, "Local", null)
        assertTrue("home server" in home && "can't be removed" in home)
        val list = hostSourceWords(org.example.kotlintest.rex.HostSource.HomeList, "Local", listed)
        assertTrue("Local's host list" in list && "Every device" in list && "added from laptop" in list)
        val file = hostSourceWords(org.example.kotlintest.rex.HostSource.ConfigurationFile, "Local", declared)
        assertTrue("configuration file" in file && "init.lua:4" in file && "takes the declared host's place" in file && "file itself isn't changed" in file)
        assertTrue("alone" in hostSourceWords(org.example.kotlintest.rex.HostSource.ThisApp, "Local", null))
    }

    @Test
    fun `a picture is kept under the name Rex for Mac gives it, which reads back as the same picture`() {
        for (icon in HostIcon.entries) assertEquals(icon, HostIcon.named(icon.listName), icon.name)
        assertEquals(listOf("globe", "server.rack", "cloud", "macbook", "macmini", "macstudio", "desktopcomputer"), HostIcon.entries.map { it.listName })
        assertEquals(HostIcon.entries.size, HostIcon.entries.map { it.title }.toSet().size)
    }

    @Test
    fun `addresses are typed one to a line`() {
        assertEquals(listOf("studio.ts.net", "192.168.1.20:7297"), addressesTyped(" studio.ts.net \n\n192.168.1.20:7297\n"))
        assertTrue(addressesTyped("  \n").isEmpty())
    }

    @Test
    fun `a host's details and the form that adds one are places within Remote Hosts`() {
        val details = SettingsPlace(SettingsSection.RemoteHosts, hostDetail("studio"))
        assertEquals("studio", placeTitle(details))
        assertEquals("studio", hostOfDetail(details.detail))
        assertEquals("Add Remote Host", placeTitle(SettingsPlace(SettingsSection.RemoteHosts, ADD_HOST_DETAIL)))
        assertNull(hostOfDetail(ADD_HOST_DETAIL))
        assertEquals("Remote Hosts", placeTitle(SettingsPlace(SettingsSection.RemoteHosts)))
        // A host called "add" is still a host.
        assertEquals("add", hostOfDetail(hostDetail("add")))
        assertEquals(listOf("Remote Hosts", "Add Remote Host…"), searchSettings("host").map { it.title })
    }

    @Test
    fun `removing a host asks in the palette's words, which say what it leaves`() = withApp { ui, _ ->
        val host = ui.client.hosts.value.first()
        val question = removeHostQuestion(host) {}
        assertEquals("Remove host “Local”?", question.title)
        assertEquals("This app stops connecting to it. Its sessions keep running.", question.text)
        assertEquals("Remove Host", question.confirmLabel)
    }

    @Test
    fun `a host's state is said in the sidebar's words, with one for a connected host`() {
        assertEquals("Connected", hostStatusWords(org.example.kotlintest.rex.HostReach.Connected))
        assertEquals("Connecting", hostStatusWords(org.example.kotlintest.rex.HostReach.Connecting))
        assertEquals("Not connected", hostStatusWords(org.example.kotlintest.rex.HostReach.Down))
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
