package org.example.kotlintest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import org.example.kotlintest.rex.HostReach
import org.example.kotlintest.rex.HostState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import org.example.kotlintest.rex.KeyChord
import org.example.kotlintest.rex.Shortcut
import org.example.kotlintest.rex.ShortcutTarget
import org.example.kotlintest.rex.TonoClient

/*
 * The settings: everything that can be chosen in Tono, in one place, after Rex.app's
 * Settings… and with its names where a setting is the same. On the desktop they are a
 * window of their own, an ordinary one that a tiling window manager puts beside the main
 * window, so they have to look right at whatever width that gives them. In a browser, which
 * has no such windows, they are a panel over the app.
 *
 * Nothing is saved or cancelled. A switch is the client's (RexClient.sounds and the rest):
 * turning it applies at once, here and in the main window, and is kept in the workspace, as
 * the palette's toggles are. Turned in the palette, it shows here as promptly.
 */

/**
 * The parts of the settings, in the order they are listed, which is Rex.app's for the ones
 * it has too. Server comes first and stands apart, as there: it is the server the app
 * follows, and says what this app is and where it keeps things.
 */
internal enum class SettingsSection(val title: String, val summary: String) {
    Server("Server", "The server this app follows, and where things are kept"),
    Terminal("Terminal", "What selecting text copies"),
    Shortcuts("Shortcuts", "The keys for every action, and changing them"),
    Notifications("Notifications", "Sounds and notifications for tabs out of view"),
    RemoteHosts("Remote Hosts", "The other Rex servers this app connects to"),
}

/** How the settings are laid out, which goes by how wide they are. */
internal enum class SettingsLayout {
    /** The sections listed down the left, and the chosen one beside the list. */
    Wide,

    /** One column: the list of sections, or the one chosen from it with a way back. */
    Narrow,
}

/** From this width on there is room for the list of sections beside a section. A half of most screens is wider; a third often isn't. */
internal val SETTINGS_WIDE = 640.dp

internal fun settingsLayout(width: Dp): SettingsLayout = if (width >= SETTINGS_WIDE) SettingsLayout.Wide else SettingsLayout.Narrow

/**
 * Somewhere in the settings: a section, or something opened within one.
 *
 * @param detail what is open within the section, by a name the section knows it by, or null for the section itself
 */
internal data class SettingsPlace(val section: SettingsSection, val detail: String? = null)

/** One setting, for finding it by name: where it is, what it is called there, and other words for it. */
internal class SettingEntry(val section: SettingsSection, val title: String, val keywords: String = "")

/** Everything Search Settings can find. A section is found by its own name too. */
internal val SETTING_ENTRIES: List<SettingEntry> = listOf(
    SettingEntry(SettingsSection.Server, "Server", "home rex about status"),
    SettingEntry(SettingsSection.Server, "Version", "about tono app"),
    SettingEntry(SettingsSection.Server, "Workspace", "file path json settings kept saved"),
    SettingEntry(SettingsSection.Server, "Home server", "rex local socket address api revision running since process account sessions listeners"),
    SettingEntry(SettingsSection.Server, "Start Server", "no server start at login install rex systemd rex.socket"),
    SettingEntry(SettingsSection.Server, "Tailscale", "tailnet sign in https address"),
    SettingEntry(SettingsSection.Server, "Client Info", "label name client rex client ls"),
    SettingEntry(SettingsSection.Server, "Configuration file", "init.lua config rex lua path"),
    SettingEntry(SettingsSection.Terminal, "Terminal", "selection"),
    SettingEntry(SettingsSection.Terminal, "Copy on Selection", "copy select clipboard automatic mouse drag highlight"),
    SettingEntry(SettingsSection.Terminal, "Primary Selection", "middle click button paste mouse x11 wayland linux"),
    SettingEntry(SettingsSection.Shortcuts, "Shortcuts", "keyboard keys key bindings keybindings hotkey record rebind change"),
    SettingEntry(SettingsSection.Shortcuts, "Reset All Shortcuts", "default restore keys bindings"),
    SettingEntry(SettingsSection.Notifications, "Notifications", "alerts program activity"),
    SettingEntry(SettingsSection.Notifications, "Play Activity Sounds", "sound audio chime alert beep bell mute silence"),
    SettingEntry(SettingsSection.Notifications, "Show System Notifications", "desktop notification notify alert popup banner allow permission browser"),
    SettingEntry(SettingsSection.Notifications, "Blocked", "waiting question permission approval sign in needs you notification"),
    SettingEntry(SettingsSection.Notifications, "Error", "failed failure notification"),
    SettingEntry(SettingsSection.Notifications, "Done", "finished complete notification"),
    SettingEntry(SettingsSection.Notifications, "Terminal Notifications", "osc 9 777 program messages desktop notification notify-send"),
    SettingEntry(SettingsSection.RemoteHosts, "Remote Hosts", "host hosts server servers machine machines connect address label icon order reorder edit remove status"),
    SettingEntry(SettingsSection.RemoteHosts, "Add Remote Host…", "new host server connect tailscale tailnet address"),
)

/**
 * The settings matching every word of [query], in the order the sections list them. A word
 * matches a setting's name, its section's, or its other words. Nothing typed finds nothing:
 * the list of sections stands then.
 */
internal fun searchSettings(query: String, entries: List<SettingEntry> = SETTING_ENTRIES): List<SettingEntry> {
    val words = query.lowercase().split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return entries.filter { entry ->
        val text = "${entry.title} ${entry.section.title} ${entry.keywords}".lowercase()
        words.all { it in text }
    }
}

/**
 * What of the settings is showing, and where one has been in them. It outlives the window,
 * so that opening them again shows the place they were left at.
 */
@Stable
internal class SettingsState {
    /** Where one is, or null if nowhere has been chosen: the first section then shows beside the list, and the list alone where there is one column. */
    var place: SettingsPlace? by mutableStateOf(null)
        private set

    /** The places before this one, oldest first, and the ones gone back from, nearest first. */
    private val before = mutableStateListOf<SettingsPlace?>()
    private val after = mutableStateListOf<SettingsPlace?>()

    /** The section showing, if one was chosen. Setting it goes there, as choosing it in the list does. */
    var section: SettingsSection?
        get() = place?.section
        set(section) = go(section?.let { SettingsPlace(it) })

    /** Goes somewhere, remembering where from. */
    fun go(to: SettingsPlace?) {
        if (to == place) return
        before += place
        after.clear()
        place = to
    }

    val canGoBack: Boolean get() = before.isNotEmpty()
    val canGoForward: Boolean get() = after.isNotEmpty()

    /** Back to where one was before, as a browser goes back. */
    fun back() {
        if (before.isEmpty()) return
        after.add(0, place)
        place = before.removeAt(before.lastIndex)
    }

    fun forward() {
        if (after.isEmpty()) return
        before += place
        place = after.removeAt(0)
    }

    /**
     * How many steps [place] is from the list of sections: none for the list itself, one for a
     * section, two for something open within it.
     */
    val depth: Int get() = place.let { if (it == null) 0 else if (it.detail == null) 1 else 2 }

    /** Stands somewhere else without having gone there: what was showing has a new name, or is gone, and isn't a place to come back to. */
    fun replace(with: SettingsPlace?) {
        place = with
    }

    /** One step out, towards the list of sections: what the way back does where there is one column. False if there was nowhere to go. */
    fun up(): Boolean {
        val here = place ?: return false
        go(if (here.detail != null) here.copy(detail = null) else null)
        return true
    }

    /** What is typed in Search Settings, over the list of sections. */
    var query by mutableStateOf("")

    /** A question the settings are asking, which is theirs to show: on the desktop they are not in the main window. */
    var confirmation: Confirmation? by mutableStateOf(null)

    /** The action whose shortcut is being recorded, while it is: see ChangeShortcut.kt. Every key is the recorder's meanwhile. */
    var shortcutEdit: ShortcutTarget? by mutableStateOf(null)

    /** What is typed in the search field over the shortcuts. */
    var shortcutQuery by mutableStateOf("")

    /** The settings were closed: nothing is left waiting for an answer. */
    fun closed() {
        confirmation = null
        shortcutEdit = null
    }
}

/**
 * A key pressed while the settings have the keyboard. True if it was theirs: Escape closes
 * a panel, Control+W closes a window, as it does for windows elsewhere, and the key that
 * opens the settings is taken, since they are open.
 */
internal fun settingsKey(ui: RexUiState, event: KeyEvent): Boolean {
    val chord = keyChord(event) ?: return false
    val down = event.type == KeyEventType.KeyDown
    if (down) ui.sawKey(chord)
    // Keys being recorded as a shortcut are shortcuts already, most of them, and none of them is to act.
    if (ui.settings.shortcutEdit != null) return false
    val closes = if (ui.settingsWindowed) chord == CLOSE_WINDOW else chord == KeyChord("escape")
    return when {
        closes -> true.also { if (down) ui.closeSettings() }
        ui.client.keymap.value.bindingsFor(chord).firstOrNull()?.action == "client.settings" -> true.also { if (down) ui.openSettings() }
        else -> false
    }
}

private val CLOSE_WINDOW = KeyChord("w", ctrl = true)

/**
 * The settings as a panel over the app, for where they have no window of their own: the
 * full height of the page at its right edge, as wide as a settings window is by default or
 * as the page if that is narrower. Escape closes it, and so do its close button and a click
 * outside it.
 */
@Composable
internal fun SettingsPanel(ui: RexUiState, commands: RexCommands) {
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
            .pointerInput(Unit) { detectTapGestures { ui.closeSettings() } },
    ) {
        // On a phone the panel is the whole screen, and the browser's back is how one goes
        // back: out of what is open in a section, from a section to the list, and from the
        // list out of the settings. It never leaves the page while they are open.
        val narrow = settingsLayout(minOf(maxWidth, SETTINGS_PANEL_WIDTH)) == SettingsLayout.Narrow
        WatchBrowserBack(depth = 1 + if (narrow) ui.settings.depth else 0) { settingsBack(ui, narrow) }
        Box(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().widthIn(max = SETTINGS_PANEL_WIDTH).fillMaxWidth()
                // Clicks inside the panel stop here instead of closing it.
                .pointerInput(Unit) { detectTapGestures { } }
                .onPreviewKeyEvent { settingsKey(ui, it) },
        ) {
            SettingsContent(ui, commands, Modifier.fillMaxSize(), onClose = ui::closeSettings)
        }
    }
}

/**
 * One step back in a panel, as the browser's back takes it: where there is one column, out
 * of whatever was chosen, one step at a time, and from the list of sections, or where the
 * list is always there, out of the settings.
 */
internal fun settingsBack(ui: RexUiState, narrow: Boolean) {
    if (!(narrow && ui.settings.up())) ui.closeSettings()
}

/** How wide the panel is where the page has the room, which is also how wide a settings window opens where windows float. */
internal val SETTINGS_PANEL_WIDTH = 760.dp

/**
 * The settings themselves, filling whatever they are given.
 *
 * @param onClose closes them, where it is theirs to offer: a panel has a close button, and
 *   a window has its own
 */
@Composable
internal fun SettingsContent(ui: RexUiState, commands: RexCommands, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    val state = ui.settings
    // The settings keep the keyboard while nothing of theirs is open over them, so that the
    // keys that close them are heard, and a tap beside a text field takes the keyboard from it.
    val focus = remember { FocusRequester() }
    val overlaid = state.shortcutEdit != null || state.confirmation != null
    LaunchedEffect(overlaid) { if (!overlaid) focus.requestFocus() }
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        BoxWithConstraints(Modifier.fillMaxSize().focusRequester(focus).focusable().pointerInput(Unit) { detectTapGestures { focus.requestFocus() } }) {
            when (settingsLayout(maxWidth)) {
                SettingsLayout.Wide -> {
                    val shown = state.place ?: SettingsPlace(SettingsSection.entries.first())
                    Row(Modifier.fillMaxSize()) {
                        SectionList(ui, shown.section, summaries = false, Modifier.width(SECTION_LIST_WIDTH).fillMaxHeight())
                        // The section is on a panel raised over the window's surface, as the terminals are in the main window.
                        SectionPanel(Modifier.weight(1f).fillMaxHeight().padding(top = PANEL_GAP, end = PANEL_GAP, bottom = PANEL_GAP)) {
                            // Back and forward through the places one has been, as in Rex.app's settings.
                            SectionHeading(placeTitle(shown), onClose = onClose) {
                                HeadingButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", state::back, enabled = state.canGoBack)
                                HeadingButton(Icons.AutoMirrored.Filled.ArrowForward, "Forward", state::forward, enabled = state.canGoForward)
                            }
                            SectionBody(shown, ui, commands, wide = true)
                        }
                    }
                }
                SettingsLayout.Narrow -> {
                    val shown = state.place
                    if (shown == null) {
                        Column(Modifier.fillMaxSize()) {
                            SectionHeading("Settings", onClose = onClose)
                            SectionList(ui, selected = null, summaries = true, Modifier.fillMaxSize())
                        }
                    } else {
                        SectionPanel(Modifier.fillMaxSize().padding(PANEL_GAP)) {
                            // One way back, a step out at a time: the same step the browser's back takes on a phone.
                            SectionHeading(placeTitle(shown), onClose = onClose) {
                                HeadingButton(Icons.AutoMirrored.Filled.ArrowBack, if (shown.detail != null) "Back to ${shown.section.title}" else "Back to Settings", { state.up() })
                            }
                            SectionBody(shown, ui, commands, wide = false)
                        }
                    }
                }
            }
        }
        state.shortcutEdit?.let { target ->
            ShortcutRecorder(ui.client, commands, target, onClose = { state.shortcutEdit = null }, ask = { state.confirmation = it }, onDone = {})
        }
        state.confirmation?.let { pending ->
            val dismiss = { state.confirmation = null }
            val confirmFocus = remember { FocusRequester() }
            LaunchedEffect(pending) { confirmFocus.requestFocus() }
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(pending.title) },
                text = { Text(pending.text) },
                confirmButton = {
                    PrimaryDialogButton(pending.confirmLabel, onClick = { dismiss(); pending.onConfirm() }, Modifier.focusRequester(confirmFocus).onEscape(dismiss), destructive = pending.destructive)
                },
                dismissButton = { SecondaryDialogButton("Cancel", dismiss) },
            )
        }
    }
}

/** What a place is called over it: its section's name, or the name of what is open within the section. */
internal fun placeTitle(place: SettingsPlace): String = when {
    place.detail == null -> place.section.title
    place.section == SettingsSection.RemoteHosts -> hostOfDetail(place.detail) ?: "Add Remote Host"
    else -> place.detail
}

private val SECTION_LIST_WIDTH = 220.dp

/** Nothing to press is smaller than this either way: a finger needs it, and the settings are a phone's too. */
internal val TOUCH_TARGET = 48.dp
private val PANEL_GAP = 6.dp

/** No line of settings is wider than this, however wide the window: a switch a screen's width from its name is hard to pair with it. */
internal val SECTION_WIDTH = 720.dp

/**
 * The list of sections, as in Rex.app's settings: a field that searches every setting by
 * name, then the server, then the sections. While something is typed in the field, the
 * settings it finds are listed in place of the sections, each going to its section.
 *
 * @param summaries whether each section has a line under its name saying what is in it, and
 *   an arrow that it leads somewhere: where the list is all there is to see
 */
@Composable
private fun SectionList(ui: RexUiState, selected: SettingsSection?, summaries: Boolean, modifier: Modifier) {
    val state = ui.settings
    Column(modifier) {
        TextField(
            value = state.query,
            onValueChange = { state.query = it },
            singleLine = true,
            placeholder = { Text("Search Settings", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = MaterialTheme.shapes.medium,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            if (state.query.isBlank()) {
                for (section in SettingsSection.entries) {
                    SectionRow(
                        section, selected = section == selected,
                        // The server's line says which server and how it is, in the sidebar's words.
                        summary = if (section == SettingsSection.Server) homeServerSummary(ui) else section.summary.takeIf { summaries },
                        arrow = summaries,
                        onClick = { state.section = section },
                    ) { SectionIcon(section, ui) }
                    // The server stands apart from the sections under it.
                    if (section == SettingsSection.Server) Spacer(Modifier.height(10.dp))
                }
            } else {
                val found = remember(state.query) { searchSettings(state.query) }
                if (found.isEmpty()) Text("No setting matches", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                for (entry in found) {
                    ListRow(entry.title, entry.section.title.takeIf { it != entry.title }, selected = false, arrow = summaries, onClick = { state.section = entry.section }) { SectionIcon(entry.section, ui) }
                }
            }
        }
    }
}

/** The home server in a line: what it is called here, and how reaching it stands. */
@Composable
private fun homeServerSummary(ui: RexUiState): String {
    val hosts by ui.client.hosts.collectAsState()
    val home = hosts.firstOrNull { it.saved.label == ui.client.homeLabel } ?: return "No home server"
    val state by home.state.collectAsState()
    return "${home.saved.label} · ${hostStatusWords(state.reach)}"
}

/** How reaching a host stands, in a word or two, as the sidebar says it, with the word for a connected one that the sidebar leaves out. */
internal fun hostStatusWords(reach: HostReach): String = (hostReachWords(reach) ?: "connected").replaceFirstChar { it.uppercase() }

@Composable
private fun SectionRow(section: SettingsSection, selected: Boolean, summary: String?, arrow: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) =
    ListRow(section.title, summary, selected, arrow, onClick, icon)

/** One line of the list: a picture, a name, perhaps a line under it, and perhaps an arrow saying that it leads somewhere. */
@Composable
private fun ListRow(title: String, summary: String?, selected: Boolean, arrow: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .heightIn(min = TOUCH_TARGET)
            .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        icon()
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = if (selected) colors.onSecondaryContainer else colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (summary != null) Text(summary, style = MaterialTheme.typography.labelMedium, color = if (selected) colors.onSecondaryContainer.copy(alpha = 0.75f) else colors.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp))
        }
        if (arrow) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/**
 * A section's picture: a small drawing on a rounded square, each section in a color of its
 * own from the theme, as Rex.app's are in colors of its own. The server's is the home
 * server's picture, as its heading in the sidebar has it.
 */
@Composable
internal fun SectionIcon(section: SettingsSection, ui: RexUiState) {
    val colors = MaterialTheme.colorScheme
    val square = when (section) {
        SettingsSection.Server -> colors.primary
        SettingsSection.Terminal -> colors.tertiaryContainer
        SettingsSection.Shortcuts -> colors.secondary
        SettingsSection.Notifications -> colors.error
        SettingsSection.RemoteHosts -> colors.tertiary
    }
    // The drawing is in the window's own dark, or light, whichever the square stands out from.
    val ink = colors.surfaceContainerLowest
    Box(Modifier.size(26.dp).background(square, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
        when (section) {
            SettingsSection.Server -> {
                val icons by ui.client.hostIcons.collectAsState()
                MachineIcon(HostIcon.of(ui.client.homeLabel?.let { icons[it] }, isHome = true), ink)
            }
            SettingsSection.Notifications -> Icon(Icons.Filled.Notifications, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
            SettingsSection.RemoteHosts -> MachineIcon(HostIcon.Globe, ink)
            SettingsSection.Terminal, SettingsSection.Shortcuts -> Canvas(Modifier.size(16.dp)) {
                val unit = size.width / 16f
                val width = 1.6f * unit
                fun at(x: Float, y: Float) = Offset(x * unit, y * unit)
                fun stroke(fromX: Float, fromY: Float, toX: Float, toY: Float) = drawLine(ink, at(fromX, fromY), at(toX, toY), width, StrokeCap.Round)
                if (section == SettingsSection.Terminal) {
                    // A prompt: an arrowhead, and the line where typing goes.
                    stroke(3f, 4.5f, 7f, 8f)
                    stroke(7f, 8f, 3f, 11.5f)
                    stroke(8.5f, 11.5f, 13f, 11.5f)
                } else {
                    // A key: its cap, with a mark on it.
                    drawRoundRect(ink, at(2f, 2.5f), Size(12f * unit, 11f * unit), CornerRadius(2.5f * unit), Stroke(width))
                    stroke(5.5f, 6f, 5.5f, 10f)
                    stroke(5.5f, 8f, 8.5f, 6f)
                    stroke(5.5f, 8f, 10.5f, 10f)
                }
            }
        }
    }
}

/** What a section is drawn on: the terminal's color, rounded and quietly outlined, as the main window's panel is. */
@Composable
private fun SectionPanel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.clip(shape).background(MaterialTheme.colorScheme.background).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape), content = content)
}

/** The line over a place, or over the list of sections: the ways back and forward where there are any, its name, and the way out where there is one. */
@Composable
private fun SectionHeading(title: String, onClose: (() -> Unit)?, arrows: (@Composable RowScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = if (arrows != null) 4.dp else 20.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        arrows?.invoke(this)
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).heightIn(min = TOUCH_TARGET).wrapContentHeight().padding(start = if (arrows != null) 4.dp else 0.dp))
        if (onClose != null) HeadingButton(Icons.Filled.Close, "Close Settings", onClose)
    }
}

/** A button that is a small picture, with room round it for a finger. Faded, and not to be pressed, where there is nothing for it to do. */
@Composable
internal fun HeadingButton(icon: ImageVector, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(Modifier.size(TOUCH_TARGET).clip(MaterialTheme.shapes.small).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.3f), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SectionBody(place: SettingsPlace, ui: RexUiState, commands: RexCommands, wide: Boolean) {
    when (place.section) {
        SettingsSection.Server -> ScrollingSection { ServerSettings(ui) }
        SettingsSection.Terminal -> ScrollingSection { TerminalSettings(ui) }
        SettingsSection.Shortcuts -> ShortcutSettings(ui, commands)
        SettingsSection.Notifications -> ScrollingSection { NotificationSettings(ui) }
        SettingsSection.RemoteHosts -> RemoteHostSettings(ui, place, wide)
    }
}

/** A section that is a column of groups: it scrolls down when there is more than fits, and never sideways. */
@Composable
internal fun ScrollingSection(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 20.dp)) {
        Column(Modifier.widthIn(max = SECTION_WIDTH).fillMaxWidth(), content = content)
    }
}

/** Settings that belong together: a quiet name over a card of them, and under it anything that needs saying about them all. */
@Composable
internal fun SettingsGroup(title: String, note: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Text(title, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 6.dp))
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(colors.surfaceContainerHigh), content = content)
    if (note != null) Text(note, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp))
}

@Composable
internal fun RowDivider() = HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

/**
 * A setting that is on or off. The whole line turns it, not only the switch.
 *
 * @param enabled false where something else has turned off what this is part of. It still
 *   shows how it is set, faded.
 */
@Composable
internal fun SwitchRow(title: String, description: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = TOUCH_TARGET)
            .padding(start = 14.dp, end = 12.dp, top = 9.dp, bottom = 9.dp)
            .alpha(if (enabled) 1f else 0.5f),
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        // Drawn as it is set either way: the line's fading says that it doesn't count just now.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Something to read, not to set: what it is, over what it says, which can be selected and copied. */
@Composable
internal fun InfoRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp)) }
    }
}

/**
 * Keeps the shortcuts matching every word of [query], in the order given. A word matches an
 * action's title, category, name or keywords, its keys as shown (`ctrl+k`), or where they
 * come from (`tono`, `init.lua`, `default`).
 */
internal fun filterShortcuts(shortcuts: List<Shortcut>, query: String): List<Shortcut> {
    val words = query.lowercase().split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return shortcuts
    return shortcuts.filter { shortcut ->
        val target = shortcut.target
        val text = listOf(target.title, target.category, target.action, target.keywords, shortcut.keysShown().orEmpty(), shortcut.sourceShown()).joinToString(" ").lowercase()
        words.all { it in text }
    }
}

/** How many shortcuts this app has changed, in words, for the line over the list. */
internal fun changedShortcutsWords(changed: Int): String = when (changed) {
    0 -> "None changed in $APP_NAME"
    1 -> "1 changed in $APP_NAME"
    else -> "$changed changed in $APP_NAME"
}

/** The question Reset All Shortcuts asks before it does anything. */
internal fun resetAllShortcutsQuestion(changed: Int, reset: () -> Unit) = Confirmation(
    title = "Reset all shortcuts?",
    text = "${if (changed == 1) "The shortcut" else "The $changed shortcuts"} changed in $APP_NAME ${if (changed == 1) "goes" else "go"} back to ${if (changed == 1) "its default" else "their defaults"}, or to what Rex's configuration file says. The file itself isn't touched.",
    confirmLabel = "Reset All",
    onConfirm = reset,
)

/** Where Rex's documentation says how keys are bound for every client. */
internal const val SHORTCUTS_DOCUMENTATION = "https://www.superlogical.com/rex/docs/customize/keyboard-shortcuts"

/**
 * Every action with its keys and where they come from, and the means to change them, which
 * are Change Shortcut…'s (ChangeShortcut.kt, rex/src/Shortcuts.kt). The list is read and not
 * changed until a real keyboard has been seen, since a shortcut is recorded by pressing it.
 */
@Composable
private fun ShortcutSettings(ui: RexUiState, commands: RexCommands) {
    val client = ui.client
    val state = ui.settings
    val keymap by client.keymap.collectAsState()
    val overrides by client.shortcuts.collectAsState()
    val all = remember(keymap, overrides) { client.shortcutList(commands.shortcutTargets(keymap)) }
    val shown = remember(all, state.shortcutQuery) { filterShortcuts(all, state.shortcutQuery) }
    val changed = all.count { it.changed }
    val canChange = ui.keyboardSeen
    // A keyboard on the screen goes when the list does, as when the settings close.
    val screenKeyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(Unit) { onDispose { screenKeyboard?.hide() } }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Column(Modifier.widthIn(max = SECTION_WIDTH).fillMaxWidth()) {
            // The search field stays put over the list, where a keyboard on the screen leaves it in view.
            TextField(
                value = state.shortcutQuery,
                onValueChange = { state.shortcutQuery = it },
                singleLine = true,
                placeholder = { Text("Search actions and keys", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = MaterialTheme.shapes.medium,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 20.dp)) {
                item {
                    Column(Modifier.padding(start = 6.dp, end = 6.dp, top = 12.dp)) {
                        Text(
                            "Keys set here are this client's alone, and sit above Rex's configuration file. To change a key for every client, bind it with rex.bind in ${keymap.configPath ?: "init.lua"}.",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Keyboard shortcuts in the Rex documentation",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp).clip(MaterialTheme.shapes.extraSmall)
                                .clickable { openLink(SHORTCUTS_DOCUMENTATION) { message, isError -> client.log.add("links", message, isError = isError) } }
                                // The words are small, and what is pressed is the height a finger needs.
                                .heightIn(min = TOUCH_TARGET).wrapContentHeight(),
                        )
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 6.dp).heightIn(min = TOUCH_TARGET), verticalAlignment = Alignment.CenterVertically) {
                        if (canChange) {
                            Text(changedShortcutsWords(changed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            TextButton(
                                onClick = { state.confirmation = resetAllShortcutsQuestion(changed) { client.resetAllShortcuts() } },
                                enabled = changed > 0,
                                modifier = Modifier.heightIn(min = TOUCH_TARGET),
                            ) { Text("Reset All Shortcuts") }
                        } else {
                            Text(
                                "Shortcuts are changed from a device with a keyboard: a shortcut is recorded by pressing it. Press one on a keyboard here, and this list can be changed.",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 6.dp, bottom = 6.dp),
                            )
                        }
                    }
                }
                if (shown.isEmpty()) item {
                    Text("No action matches", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 12.dp))
                }
                // By category, in the order the palette lists the actions.
                for ((category, shortcuts) in shown.groupBy { it.target.category.ifEmpty { "Actions" } }) {
                    item(key = category) {
                        Column {
                            SettingsGroup(category) {
                                shortcuts.forEachIndexed { index, shortcut ->
                                    if (index > 0) RowDivider()
                                    ShortcutRow(
                                        shortcut, canChange,
                                        onClick = { state.shortcutEdit = shortcut.target },
                                        onReset = { resetShortcutAsking(client, commands, shortcut.target) { state.confirmation = it } },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One action: its name over where its keys come from, the keys, and, where this app changed
 * them, the way to put them back. Pressing the line records new keys for it.
 *
 * @param canChange false where shortcuts can only be read: nothing on the line can be pressed
 */
@Composable
private fun ShortcutRow(shortcut: Shortcut, canChange: Boolean, onClick: () -> Unit, onReset: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .then(if (canChange) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = TOUCH_TARGET)
            .padding(start = 14.dp, end = 14.dp),
    ) {
        Column(Modifier.weight(1f).padding(vertical = 5.dp)) {
            Text(shortcut.target.title, style = MaterialTheme.typography.bodyMedium)
            Text(shortcut.sourceShown(), style = MaterialTheme.typography.labelMedium, color = if (shortcut.changed) colors.primary else colors.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp))
        }
        // Before the keys, so that the keys line up down the list whether or not a line has it.
        if (canChange && shortcut.changed) HeadingButton(Icons.Filled.Refresh, "Reset ${shortcut.target.title} to its default", onReset)
        shortcut.keysShown()?.let { keys ->
            Text(keys, style = MaterialTheme.typography.labelLarge, color = colors.onSurface, textAlign = TextAlign.End, modifier = Modifier.padding(start = 12.dp, top = 5.dp, bottom = 5.dp).widthIn(max = 200.dp))
        }
    }
}

/**
 * Sounds and desktop notifications for what programs in tabs out of view report. The groups
 * and names are Rex.app's (Settings > Notifications), and the kinds of news are told apart
 * as Rex Rune tells them.
 */
@Composable
private fun NotificationSettings(ui: RexUiState) {
    val switches = ui.notifications
    SettingsGroup("Program Activity", note = if (switches.desktop) null else "With system notifications off, none of the kinds below is shown.") {
        SwitchRow("Play Activity Sounds", "Play a sound when a program in a tab you aren't looking at is blocked, reports an error, or finishes.", ui.soundsEnabled) { ui.switchSounds(it) }
        RowDivider()
        SwitchRow(
            "Show System Notifications",
            "Show a desktop notification when a program in a tab you aren't looking at reports a status. Clicking it opens the program's tab.",
            switches.desktop,
        ) { ui.switchSystemNotifications(it) }
        // Whether they can be shown at all, where that is in doubt: a browser has to allow them.
        val access = remember(ui.notificationAnswers) { notificationAccess() }
        notificationAccessWords(access, runsInBrowser, notificationsMissing())?.let { words ->
            RowDivider()
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp).heightIn(min = TOUCH_TARGET), verticalAlignment = Alignment.CenterVertically) {
                Text(words, style = MaterialTheme.typography.labelMedium, color = if (access == NotificationAccess.Granted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                // Offered only where asking can work: a browser that hasn't been asked. Pressing the button is what lets it ask.
                if (access == NotificationAccess.Undecided) TextButton(onClick = { ui.notifier.askAccess() }, modifier = Modifier.heightIn(min = TOUCH_TARGET)) { Text("Allow Notifications") }
            }
        }
    }
    SettingsGroup("Notify When a Program Is") {
        SwitchRow("Blocked", "It started waiting for you: a question, a permission, a sign-in.", switches.blocked, enabled = switches.desktop) { ui.notifications = ui.notifications.copy(blocked = it) }
        RowDivider()
        SwitchRow("Error", "It reported an error.", switches.error, enabled = switches.desktop) { ui.notifications = ui.notifications.copy(error = it) }
        RowDivider()
        SwitchRow("Done", "It finished.", switches.done, enabled = switches.desktop) { ui.notifications = ui.notifications.copy(done = it) }
    }
    SettingsGroup("System Notifications") {
        SwitchRow("Terminal Notifications", "Let programs send notifications of their own, with OSC 9 or OSC 777.", switches.programMessages, enabled = switches.desktop) {
            ui.notifications = ui.notifications.copy(programMessages = it)
        }
    }
}

/**
 * What to say about whether notifications can be shown here, or null where there is nothing
 * to say: on the desktop, when there is a program to show them with.
 *
 * @param missing why there are none, where there are none
 */
internal fun notificationAccessWords(access: NotificationAccess, browser: Boolean, missing: NotificationsMissing = NotificationsMissing.Unknown): String? = when (access) {
    NotificationAccess.Granted -> if (browser) "This browser allows notifications from this page." else null
    NotificationAccess.Undecided -> "This browser hasn't been asked whether this page may show notifications."
    NotificationAccess.Refused -> "This browser has been told not to let this page show notifications. Its own settings for the site can change that."
    NotificationAccess.Unavailable -> when {
        !browser -> "There is no notify-send command here to show notifications with."
        missing == NotificationsMissing.NeedsHomeScreen -> "Notifications aren't available here. On an iPhone or iPad a browser shows them only for a web app opened from the Home Screen: add this page to it with Share, then Add to Home Screen."
        missing == NotificationsMissing.Insecure -> "Notifications aren't available here: a browser shows them only for a page served over HTTPS, or from this machine."
        else -> "Notifications aren't available in this browser."
    }
}

/** What selecting text in a terminal does. Rex.app's Settings > Terminal > Selection, and Rex Rune's second switch beside it. */
@Composable
private fun TerminalSettings(ui: RexUiState) {
    val switches = ui.selection
    SettingsGroup("Selection") {
        SwitchRow("Copy on Selection", "Automatically copy selected terminal text to the clipboard.", switches.copyOnSelection) { ui.selection = ui.selection.copy(copyOnSelection = it) }
        RowDivider()
        SwitchRow("Primary Selection", primarySelectionWords(primarySelection()), switches.primarySelection) { ui.selection = ui.selection.copy(primarySelection = it) }
    }
}

/** What the Primary Selection switch does where the app runs, which depends on what there is for a primary selection. */
internal fun primarySelectionWords(primary: PrimarySelection): String = when (primary) {
    PrimarySelection.Desktop -> "Selected terminal text becomes the desktop's primary selection, and a middle click pastes it. The clipboard is left alone."
    PrimarySelection.Clipboard -> "Browsers have no primary selection, so here selecting copies to the clipboard, and a middle click pastes from it."
    PrimarySelection.None -> "This desktop has no primary selection, so this does nothing here."
}
