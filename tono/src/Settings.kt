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
import org.example.kotlintest.rex.HostState
import org.example.kotlintest.rex.KeyChord
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

/** The parts of the settings, in the order they are listed. */
internal enum class SettingsSection(val title: String, val summary: String) {
    Notifications("Notifications", "Sounds and notifications for tabs out of view"),
    Terminal("Terminal", "What selecting text copies"),
    About("About", "Versions, and where things are kept"),
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

/** What of the settings is showing. It outlives the window, so that opening them again shows the section they were left on. */
@Stable
internal class SettingsState {
    /** The section chosen, or null if none has been: the first then shows beside the list, and the list alone where there is one column. */
    var section: SettingsSection? by mutableStateOf(null)

    /** A question the settings are asking, which is theirs to show: on the desktop they are not in the main window. */
    var confirmation: Confirmation? by mutableStateOf(null)

    /** The settings were closed: nothing is left waiting for an answer. */
    fun closed() {
        confirmation = null
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
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
            .pointerInput(Unit) { detectTapGestures { ui.closeSettings() } },
    ) {
        // On a phone the panel is the whole screen, and the browser's back is how one goes
        // back: from a section to the list, and from the list out of the settings. It never
        // leaves the page while they are open.
        val inSection = settingsLayout(minOf(maxWidth, SETTINGS_PANEL_WIDTH)) == SettingsLayout.Narrow && ui.settings.section != null
        WatchBrowserBack(depth = if (inSection) 2 else 1) { settingsBack(ui, inSection) }
        Box(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().widthIn(max = SETTINGS_PANEL_WIDTH).fillMaxWidth()
                // Clicks inside the panel stop here instead of closing it.
                .pointerInput(Unit) { detectTapGestures { } }
                .focusRequester(focus).focusable()
                .onPreviewKeyEvent { settingsKey(ui, it) },
        ) {
            SettingsContent(ui, commands, Modifier.fillMaxSize(), onClose = ui::closeSettings)
        }
    }
}

/** One step back in a panel: out of a section that was chosen from the list, and otherwise out of the settings. */
internal fun settingsBack(ui: RexUiState, inSection: Boolean) {
    if (inSection) ui.settings.section = null else ui.closeSettings()
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
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            when (settingsLayout(maxWidth)) {
                SettingsLayout.Wide -> {
                    val shown = state.section ?: SettingsSection.entries.first()
                    Row(Modifier.fillMaxSize()) {
                        Column(Modifier.width(SECTION_LIST_WIDTH).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
                            Text("Settings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 12.dp))
                            for (section in SettingsSection.entries) {
                                SectionRow(section, selected = section == shown, summary = false, onClick = { state.section = section })
                            }
                        }
                        // The section is on a panel raised over the window's surface, as the terminals are in the main window.
                        SectionPanel(Modifier.weight(1f).fillMaxHeight().padding(top = PANEL_GAP, end = PANEL_GAP, bottom = PANEL_GAP)) {
                            SectionHeading(shown.title, onBack = null, onClose = onClose)
                            SectionBody(shown, ui, commands)
                        }
                    }
                }
                SettingsLayout.Narrow -> {
                    val shown = state.section
                    if (shown == null) {
                        Column(Modifier.fillMaxSize()) {
                            SectionHeading("Settings", onBack = null, onClose = onClose)
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
                                for (section in SettingsSection.entries) {
                                    SectionRow(section, selected = false, summary = true, onClick = { state.section = section })
                                }
                            }
                        }
                    } else {
                        SectionPanel(Modifier.fillMaxSize().padding(PANEL_GAP)) {
                            SectionHeading(shown.title, onBack = { state.section = null }, onClose = onClose)
                            SectionBody(shown, ui, commands)
                        }
                    }
                }
            }
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

private val SECTION_LIST_WIDTH = 200.dp

/** Nothing to press is smaller than this either way: a finger needs it, and the settings are a phone's too. */
internal val TOUCH_TARGET = 48.dp
private val PANEL_GAP = 6.dp

/** No line of settings is wider than this, however wide the window: a switch a screen's width from its name is hard to pair with it. */
private val SECTION_WIDTH = 720.dp

/** One section in the list of them. With [summary], a line under its name says what is in it, and an arrow that it leads somewhere. */
@Composable
private fun SectionRow(section: SettingsSection, selected: Boolean, summary: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .heightIn(min = TOUCH_TARGET)
            .padding(start = 14.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(section.title, style = MaterialTheme.typography.bodyMedium, color = if (selected) colors.onSecondaryContainer else colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (summary) Text(section.summary, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        if (summary) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/** What a section is drawn on: the terminal's color, rounded and quietly outlined, as the main window's panel is. */
@Composable
private fun SectionPanel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.clip(shape).background(MaterialTheme.colorScheme.background).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape), content = content)
}

/** The line over a section, or over the list of them: its name, with the way back before it and the way out after, where there is one. */
@Composable
private fun SectionHeading(title: String, onBack: (() -> Unit)?, onClose: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 20.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) HeadingButton(Icons.AutoMirrored.Filled.ArrowBack, "Back to Settings", onBack)
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).heightIn(min = TOUCH_TARGET).wrapContentHeight().padding(start = if (onBack != null) 2.dp else 0.dp))
        if (onClose != null) HeadingButton(Icons.Filled.Close, "Close Settings", onClose)
    }
}

@Composable
private fun HeadingButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(TOUCH_TARGET).clip(MaterialTheme.shapes.small).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SectionBody(section: SettingsSection, ui: RexUiState, commands: RexCommands) {
    when (section) {
        SettingsSection.Notifications -> ScrollingSection { NotificationSettings(ui) }
        SettingsSection.Terminal -> ScrollingSection { TerminalSettings(ui) }
        SettingsSection.About -> ScrollingSection { AboutSettings(ui) }
    }
}

/** A section that is a column of groups: it scrolls down when there is more than fits, and never sideways. */
@Composable
private fun ScrollingSection(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 20.dp)) {
        Column(Modifier.widthIn(max = SECTION_WIDTH).fillMaxWidth(), content = content)
    }
}

/** Settings that belong together: a quiet name over a card of them, and under it anything that needs saying about them all. */
@Composable
private fun SettingsGroup(title: String, note: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Text(title, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 6.dp))
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(colors.surfaceContainerHigh), content = content)
    if (note != null) Text(note, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp))
}

@Composable
private fun RowDivider() = HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

/**
 * A setting that is on or off. The whole line turns it, not only the switch.
 *
 * @param enabled false where something else has turned off what this is part of. It still
 *   shows how it is set, faded.
 */
@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
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
private fun InfoRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp)) }
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

/** What is running, and where what it reads is kept. Nothing here can be changed. */
@Composable
private fun AboutSettings(ui: RexUiState) {
    val client = ui.client
    val hosts by client.hosts.collectAsState()
    val keymap by client.keymap.collectAsState()
    SettingsGroup(APP_NAME) {
        InfoRow("Version", TonoClient.version)
        RowDivider()
        InfoRow("Workspace", client.workspace.location ?: "Not kept: what is set here lasts until $APP_NAME closes.")
    }
    SettingsGroup("Rex") {
        val home = hosts.firstOrNull { it.saved.label == client.homeLabel }
        if (home == null) {
            InfoRow("Home server", "None. No host here is on this machine, so shortcuts are $APP_NAME's own and hosts are kept in the workspace.")
        } else {
            val state by home.state.collectAsState()
            InfoRow("Home server", "${home.saved.label} · ${home.saved.endpoint}")
            RowDivider()
            InfoRow("API revision", homeServerWords(state))
        }
        // The server says where its configuration file is when it answers with the keymap.
        keymap.configPath?.let { path ->
            RowDivider()
            InfoRow("Configuration file", path)
        }
    }
}

/** The API revision this app and its home server speak, or why that isn't known. */
internal fun homeServerWords(state: HostState): String =
    state.apiRevision?.takeIf { hostReachWords(state.reach) == null }?.toString() ?: (hostReachWords(state.reach) ?: "not known yet").replaceFirstChar { it.uppercase() }
