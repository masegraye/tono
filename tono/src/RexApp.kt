package org.example.kotlintest

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.Canvas
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.rememberCoroutineScope
import org.example.kotlintest.rex.nowMillis
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlintest.rex.ActivityState
import org.example.kotlintest.rex.HostConnection
import androidx.compose.ui.platform.LocalWindowInfo
import org.example.kotlintest.rex.HostState
import org.example.kotlintest.rex.Keymap
import org.example.kotlintest.rex.PaneRect
import org.example.kotlintest.rex.PaneView
import org.example.kotlintest.rex.RexHost
import org.example.kotlintest.rex.SessionView
import org.example.kotlintest.rex.TerminalSession
import org.example.kotlintest.rex.TabName
import org.example.kotlintest.rex.WindowView
import kotlin.math.abs

/**
 * The Rex client: every configured server and its sessions down the side, and the selected
 * session's tabs and terminals in the rest of the window. Shortcuts, the command palette
 * and the debug log sit on top.
 */
@Composable
fun RexApp(ui: RexUiState, commands: RexCommands) {
    val hosts by ui.client.hosts.collectAsState()
    val selectedLabel by ui.client.selectedHost.collectAsState()
    val keymap by ui.client.keymap.collectAsState()
    val selected = hosts.firstOrNull { it.saved.label == selectedLabel } ?: hosts.firstOrNull()

    // The client in use is the one terminals take their size from, so say when that is this one.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) { ui.client.setWindowFocused(windowFocused) }

    // A sound for what programs in tabs out of view have just done, if sounds are on.
    LaunchedEffect(ui) {
        val player = Sounds(this) { message, isError -> ui.client.log.add("sound", message, isError = isError) }
        ui.sounds = player
        ui.client.activityNews.collect { if (ui.soundsEnabled) player.request(it) }
    }

    // Shortcuts are looked up here, above everything, so they work wherever the keyboard is.
    // A key that isn't a shortcut carries on down to the focused terminal.
    val rootFocus = remember { FocusRequester() }
    val shortcutKeysDown = remember { HashSet<Long>() }
    LaunchedEffect(Unit) { rootFocus.requestFocus() }

    // Whether Control is down, for the sidebar's shortcut digits. Watched for the whole
    // window rather than through focus, so that it is right whatever has the keyboard, and
    // dropped when the window loses focus, since the release would then never be seen.
    WatchControlKey { ui.controlHeld = it }
    // Things an action starts and doesn't wait for, such as reading the clipboard.
    ui.scope = rememberCoroutineScope()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier.fillMaxSize().focusRequester(rootFocus).focusable().onPreviewKeyEvent { event ->
                if (ui.palette != null || ui.renaming != null) return@onPreviewKeyEvent false // an overlay has the keyboard
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        val chord = keyChord(event) ?: return@onPreviewKeyEvent false
                        commands.handleKey(chord).also { if (it) shortcutKeysDown += event.key.keyCode }
                    }
                    // The release of a key whose press was a shortcut isn't the terminal's either.
                    KeyEventType.KeyUp -> shortcutKeysDown.remove(event.key.keyCode)
                    else -> false
                }
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    if (ui.sidebarVisible) {
                        Sidebar(
                            hosts = hosts,
                            selected = selected,
                            ui = ui,
                            commands = commands,
                            keymap = keymap,
                        )
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    Box(Modifier.weight(1f)) {
                        if (selected != null) HostContent(selected, ui, Modifier.fillMaxSize())
                        if (!ui.sidebarVisible) SidebarReveal(commands, keymap.shortcutFor("client.sidebar.toggle")?.display())
                    }
                }
                if (ui.debugLogVisible) {
                    DebugLogPanel(
                        log = ui.client.log,
                        shortcut = keymap.shortcutFor("client.debug_log.toggle")?.display(),
                        onClose = { ui.debugLogVisible = false; ui.returnFocusToTerminal() },
                    )
                }
            }
            ui.palette?.let { CommandPalette(ui, commands, it) }
            ui.renaming?.let { renaming -> RenameTabDialog(renaming, onDismiss = { ui.renaming = null; ui.returnFocusToTerminal() }) }
            ui.confirmation?.let { pending ->
                val dismiss = { ui.confirmation = null; ui.returnFocusToTerminal() }
                // The confirm button has the keyboard, so Enter confirms and Escape cancels.
                val confirmFocus = remember { FocusRequester() }
                LaunchedEffect(pending) { confirmFocus.requestFocus() }
                AlertDialog(
                    onDismissRequest = dismiss,
                    title = { Text(pending.title) },
                    text = { Text(pending.text) },
                    confirmButton = {
                        PrimaryDialogButton(pending.confirmLabel, onClick = { dismiss(); pending.onConfirm() }, Modifier.focusRequester(confirmFocus), destructive = pending.destructive)
                    },
                    dismissButton = { SecondaryDialogButton("Cancel", dismiss) },
                )
            }
        }
    }
}

/**
 * The sidebar: hosts, their sessions, and each session's tabs, as nested headings.
 *
 * Every host has the same weight, with this machine's server first. Hosts and sessions fold
 * away when clicked. Tabs are the things you go to; sessions and hosts are headings over them.
 */
@Composable
private fun Sidebar(
    hosts: List<RexHost>,
    selected: RexHost?,
    ui: RexUiState,
    commands: RexCommands,
    keymap: Keymap,
) {
    val paletteShortcut = keymap.shortcutFor("client.palette.toggle")?.display()
    // Every host's state, read here so that tabs can be numbered across all of them.
    val ordered = sidebarOrder(hosts.map { host -> host to key(host) { host.state.collectAsState().value } }, ui.client.homeLabel) { it.saved.label }
    val activityOnly = ui.activityOnly
    val tabs = linearTabs(ordered.map { (host, state) -> host.saved.label to state }, ui.collapsedHosts, ui.collapsedSessions, activityOnly)
    // The digits appear while Control is held, and stay until it is let go, however many
    // tabs are visited in between. The short delay keeps them from flashing on a quick Control+C.
    val digitAlpha by animateFloatAsState(
        targetValue = if (ui.controlHeld) 1f else 0f,
        animationSpec = if (ui.controlHeld) tween(durationMillis = 140, delayMillis = 160) else tween(durationMillis = 200),
        label = "shortcut digits",
    )

    Column(
        Modifier.width(240.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        // What the sidebar can do, as buttons: hide itself, show only what is active, and
        // find anything. The rest is in the menu at the end.
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolbarButton("Hide Sidebar", keymap.shortcutFor("client.sidebar.toggle")?.display(), onClick = { commands.run("client.sidebar.toggle", origin = "toolbar") }) { SidebarIcon(it) }
            ToolbarButton(
                if (activityOnly) "Show Every Tab" else "Show Only Active Tabs",
                keymap.shortcutFor("client.sidebar.activity.toggle")?.display(),
                onClick = { commands.run("client.sidebar.activity.toggle", origin = "toolbar") },
                active = activityOnly,
            ) { ActivityIcon(it) }
            ToolbarButton("Command Palette", paletteShortcut, onClick = { commands.run("client.palette.toggle", origin = "toolbar") }) { PaletteIcon(it) }
            Spacer(Modifier.weight(1f))
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Command Palette…") },
                        trailingIcon = paletteShortcut?.let { { HintText(it) } },
                        onClick = { menuOpen = false; commands.run("client.palette.toggle", origin = "menu") },
                    )
                    DropdownMenuItem(text = { Text("Add Host…") }, onClick = { menuOpen = false; commands.run("client.host.add", origin = "menu") })
                    DropdownMenuItem(text = { Text("UI Showcase") }, onClick = { menuOpen = false; commands.run("client.showcase.toggle", origin = "menu") })
                }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            // Showing only what is active: hosts and sessions with nothing active drop out, and
            // folding is set aside, since the point is to see everything that wants attention.
            val listed = if (!activityOnly) ordered else ordered.filter { (host, state) -> tabs.any { it.host == host.saved.label } && state.connection == HostConnection.Connected }
            if (activityOnly && listed.isEmpty()) {
                HintText("Nothing is active: no program is working, waiting for you, failed or just finished.", Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
            }
            for ((host, state) in listed) key(host) {
                val label = host.saved.label
                val hostCollapsed = !activityOnly && label in ui.collapsedHosts
                HostHeader(
                    host = host,
                    state = state,
                    isHome = label == ui.client.homeLabel,
                    collapsed = hostCollapsed,
                    // Folded away, a heading still shows that something under it is busy.
                    activity = if (hostCollapsed) state.activity.values.maxOrNull() ?: ActivityState.Idle else ActivityState.Idle,
                    onClick = { ui.toggleHost(label) },
                    onNewSession = { ui.newSession(host) },
                    onRetry = host::retry,
                )
                // A host's sessions can be dragged into another order, and so can a session's
                // tabs. Both orders are the server's, so they change for every client.
                if (!hostCollapsed) HostSessions(host, state, selected, ui, tabs, digitAlpha, activityOnly)
            }
        }
    }
}

/** The sessions of one host, each a heading over its tabs. */
@Composable
private fun HostSessions(
    host: RexHost,
    state: HostState,
    selected: RexHost?,
    ui: RexUiState,
    tabs: List<NavigationStop>,
    digitAlpha: Float,
    activityOnly: Boolean,
) {
    fun windowsOf(sessionId: String) = state.views[sessionId]?.windows.orEmpty().filter { !activityOnly || state.activityOf(it) != ActivityState.Idle }
    val sessions = if (activityOnly) state.sessions.filter { windowsOf(it.id).isNotEmpty() } else state.sessions
    val sessionOrder = rememberReorder(sessions.map { it.id }) { session, before -> host.moveSession(session, before) }
    Column {
        for (session in sessions) key(session.id) {
            val windows = windowsOf(session.id)
            val collapsed = !activityOnly && session.id in ui.collapsedSessions
            // A session is dragged by its heading and moves whole, tabs and all.
            Column(Modifier.reorderItem(sessionOrder, session.id).dragOutline(sessionOrder.isDragging(session.id))) {
                SessionHeader(
                    label = session.label,
                    collapsed = collapsed,
                    tabCount = windows.size,
                    activity = if (collapsed) windows.maxOfOrNull(state::activityOf) ?: ActivityState.Idle else ActivityState.Idle,
                    onClick = { ui.toggleSession(session.id) },
                    onNewTab = { ui.newTab(host, session.id) },
                    modifier = Modifier.reorderHandle(sessionOrder, session.id),
                )
                if (!collapsed) SessionTabs(host, state, session.id, windows, showing = host === selected && session.id == state.selectedSessionId, ui, tabs, digitAlpha)
            }
        }
    }
}

/** The tabs of one session. */
@Composable
private fun SessionTabs(
    host: RexHost,
    state: HostState,
    sessionId: String,
    windows: List<WindowView>,
    showing: Boolean,
    ui: RexUiState,
    tabs: List<NavigationStop>,
    digitAlpha: Float,
) {
    val tabOrder = rememberReorder(windows.map { it.id }) { tab, before -> host.moveTab(sessionId, tab, before) }
    var lastClick by remember { mutableStateOf<Pair<String, Long>?>(null) }
    Column {
        for (tab in windows) key(tab.id) {
            val stop = NavigationStop(host.saved.label, sessionId, tab.id)
            TabRow(
                name = state.nameOf(tab),
                digit = tabShortcutDigit(tabs.indexOf(stop), tabs.size),
                digitAlpha = digitAlpha,
                showing = showing && tab.id == state.shownWindow(sessionId)?.id,
                activity = state.activityOf(tab),
                dragging = tabOrder.isDragging(tab.id),
                // A second click soon after the first renames the tab. The first still goes
                // to it at once: waiting to see whether a second follows would make every
                // switch feel slow.
                onClick = {
                    val now = nowMillis()
                    val again = lastClick?.let { (clicked, at) -> clicked == tab.id && now - at < DOUBLE_CLICK_MILLIS } == true
                    lastClick = if (again) null else tab.id to now
                    if (again) ui.renameTab(host, sessionId, tab) else ui.go(stop)
                },
                modifier = Modifier.reorderItem(tabOrder, tab.id).reorderHandle(tabOrder, tab.id),
            )
        }
    }
}

/**
 * The way back to a hidden sidebar for a hand on the mouse: a button in the top corner that
 * appears when the pointer is there. It is out of sight the rest of the time, since with the
 * sidebar hidden the terminals have the whole window.
 */
@Composable
private fun SidebarReveal(commands: RexCommands, shortcut: String?) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val alpha by animateFloatAsState(if (hovered) 1f else 0f, hoverFade(hovered), label = "sidebar reveal")
    Box(Modifier.size(56.dp).hoverable(interaction)) {
        if (alpha > 0f) {
            Box(Modifier.padding(6.dp).alpha(alpha).background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small)) {
                ToolbarButton("Show Sidebar", shortcut, onClick = { commands.run("client.sidebar.toggle", origin = "toolbar") }) { SidebarIcon(it) }
            }
        }
    }
}

/**
 * Marks what is being dragged: a soft panel and outline around all of it, so a session shows
 * as one piece with its tabs. Drawn within its own bounds, so nothing shifts when it appears.
 */
@Composable
private fun Modifier.dragOutline(dragging: Boolean): Modifier =
    if (!dragging) this
    else background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), MaterialTheme.shapes.small)

/**
 * A host: a heading over its sessions. Clicking it folds them away. With the pointer over
 * it, a plus appears that creates a session on this host.
 */
@Composable
private fun HostHeader(
    host: RexHost,
    state: HostState,
    /** Whether this is the machine the app is running on. */
    isHome: Boolean,
    collapsed: Boolean,
    activity: ActivityState,
    onClick: () -> Unit,
    onNewSession: () -> Unit,
    onRetry: () -> Unit,
) {
    val connection = state.connection
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // A host that is down is tried again every few seconds. It stays marked as down between
    // tries, so its dot doesn't blink for as long as it is.
    var failed by remember(host) { mutableStateOf(false) }
    when (connection) {
        HostConnection.Connected -> failed = false
        is HostConnection.Disconnected -> failed = true
        HostConnection.Connecting -> {}
    }
    val connected = connection == HostConnection.Connected
    // A host is a machine, and its heading has a picture of one: that is what makes where one
    // computer ends and the next begins show at a glance. Its name is quieter than a tab's,
    // because tabs are what you are looking for and the heading only says where you are. The
    // picture is also all a host says about its connection (why one can't be reached is in
    // the debug log). A host that isn't connected has nothing under it to fold away.
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val faded = muted.copy(alpha = 0.55f)
    Column(
        Modifier.fillMaxWidth().hoverable(interaction)
            .clickable(enabled = connected, onClick = onClick)
            .padding(start = 12.dp, end = 6.dp, top = 12.dp, bottom = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                connected -> MachineIcon(remote = !isHome, tint = muted)
                // Down, and being tried again every few seconds: the same picture, in the
                // error color. A warning sign would shout from the bottom of the list about
                // a machine that is merely switched off.
                failed -> MachineIcon(remote = !isHome, tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f))
                else -> MachineIcon(remote = !isHome, tint = faded)
            }
            Text(
                host.saved.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = if (connected) muted else faded,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            ActivityDots(activity)
            when {
                connected -> {
                    HeaderButton(Icons.Filled.Add, "New session on ${host.saved.label}", visible = hovered, onClick = onNewSession)
                    FoldMark(collapsed, visible = collapsed || hovered)
                }
                // One that is down is tried again every few seconds anyway; this tries it now.
                // An address that can't be read will never connect, so there is nothing to try.
                (connection as? HostConnection.Disconnected)?.permanent != true ->
                    HeaderButton(Icons.Filled.Refresh, "Try ${host.saved.label} again now", visible = hovered, onClick = onRetry)
            }
        }
        if (!collapsed && connected && state.sessions.isEmpty()) HintText("No sessions", Modifier.padding(top = 4.dp))
    }
}

/**
 * A session: a small, quiet heading over its tabs. Clicking it folds them away. With the
 * pointer over it, a plus appears that opens a tab in this session.
 */
@Composable
private fun SessionHeader(
    label: String,
    collapsed: Boolean,
    tabCount: Int,
    activity: ActivityState,
    onClick: () -> Unit,
    onNewTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().hoverable(interaction).clickable(onClick = onClick).padding(start = 14.dp, end = 6.dp, top = 7.dp, bottom = 0.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = quiet,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (collapsed) {
            ActivityDots(activity)
            // How many tabs are folded away.
            Text(tabCount.toString(), style = MaterialTheme.typography.labelSmall, color = quiet, modifier = Modifier.padding(start = 6.dp, end = 5.dp))
        }
        HeaderButton(Icons.Filled.Add, "New tab in $label", visible = hovered, onClick = onNewTab)
        FoldMark(collapsed, visible = collapsed || hovered)
    }
}

/**
 * A small picture of a machine, for a host's heading: a screen on its stand for this
 * computer, and a pair of stacked units, as in a rack, for one reached over the network.
 * Drawn here, in lines the weight of the text beside it, so it needs no icon font and takes
 * whatever color the theme gives it.
 */
@Composable
private fun MachineIcon(remote: Boolean, tint: Color) {
    Canvas(Modifier.size(16.dp)) {
        val unit = size.width / 16f
        val line = Stroke(width = 1.5f * unit)
        val corner = CornerRadius(1.6f * unit)
        if (remote) {
            for (top in listOf(2.5f, 9f)) {
                drawRoundRect(tint, Offset(1.5f * unit, top * unit), Size(13f * unit, 4.5f * unit), corner, line)
                drawCircle(tint, 0.9f * unit, Offset(4.4f * unit, (top + 2.25f) * unit))
            }
        } else {
            drawRoundRect(tint, Offset(1.5f * unit, 2.5f * unit), Size(13f * unit, 8.5f * unit), corner, line)
            drawLine(tint, Offset(8f * unit, 11f * unit), Offset(8f * unit, 13.5f * unit), line.width)
            drawLine(tint, Offset(4.5f * unit, 13.75f * unit), Offset(11.5f * unit, 13.75f * unit), line.width, StrokeCap.Round)
        }
    }
}

/**
 * A small button at the end of a heading, which fades in while the pointer is over the
 * heading and out when it leaves. Its space is kept either way, so the heading doesn't shift
 * as the pointer passes.
 *
 * It can be pressed whether or not it has faded in. A pointer has to be over it to press it,
 * so it is on its way in by then, and tying the press to the fade would make a quick click
 * depend on which of the two the window heard about first.
 */
@Composable
private fun HeaderButton(icon: ImageVector, description: String, visible: Boolean, onClick: () -> Unit) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, hoverFade(visible), label = "heading button")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(20.dp).alpha(alpha).clip(MaterialTheme.shapes.extraSmall).clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
    }
}

/** How things that belong to a heading come and go with the pointer: in promptly, out a little slower. */
private fun hoverFade(appearing: Boolean) = tween<Float>(durationMillis = if (appearing) 120 else 200)

/** A tab: what you go to. */
@Composable
private fun TabRow(
    name: TabName,
    digit: Int?,
    digitAlpha: Float,
    showing: Boolean,
    activity: ActivityState,
    dragging: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(MaterialTheme.shapes.small)
            .background(
                when {
                    showing -> MaterialTheme.colorScheme.secondaryContainer
                    dragging -> MaterialTheme.colorScheme.surfaceContainerHighest
                    else -> Color.Transparent
                },
            )
            // While it is dragged, an outline sets it apart from the tabs it passes over.
            .border(1.dp, if (dragging) MaterialTheme.colorScheme.outline.copy(alpha = 0.6f) else Color.Transparent, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
    ) {
        // The name comes first and takes what it needs; the directory gets what is left, and
        // loses its start when that isn't enough, since its last folder says the most.
        Row(Modifier.weight(1f)) {
            Text(
                name.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (showing) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alignByBaseline(),
            )
            // Where the tab's program is, in the quiet style of a session heading.
            name.directory?.let { directory ->
                Text(
                    directory,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (showing) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                    modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false).alignByBaseline(),
                )
            }
        }
        ActivityDots(activity)
        ShortcutDigit(digit, digitAlpha)
    }
}

/**
 * The digit that goes to a tab when pressed with Control, in a small rounded square at the
 * row's right edge. Its space is always kept, so nothing moves when the digits appear.
 */
@Composable
private fun ShortcutDigit(digit: Int?, alpha: Float) {
    val shape = RoundedCornerShape(5.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.padding(start = 6.dp).size(18.dp).alpha(if (digit == null) 0f else alpha)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape),
    ) {
        Text(digit?.toString().orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * Three dots that show what a terminal's program says it is doing: rippling while it works,
 * steady in the accent color while it waits for you, and steady in the error color after a
 * failure. Nothing is shown for a program that is idle or finished.
 */
@Composable
internal fun ActivityDots(state: ActivityState, modifier: Modifier = Modifier) {
    if (state == ActivityState.Idle || state == ActivityState.Done) return
    val color = when (state) {
        ActivityState.Blocked -> MaterialTheme.colorScheme.primary
        ActivityState.Error -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // Where the bright spot is, travelling across the three dots and around again.
    val position = if (state == ActivityState.Working) {
        rememberInfiniteTransition(label = "activity").animateFloat(
            initialValue = 0f, targetValue = 3f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1100, easing = LinearEasing)),
            label = "position",
        ).value
    } else null
    Row(modifier.padding(start = 6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { dot ->
            val brightness = position?.let {
                val distance = abs(it - dot).let { d -> minOf(d, 3f - d) }
                0.25f + 0.75f * (1f - distance).coerceIn(0f, 1f)
            } ?: 1f
            Box(Modifier.size(4.dp).background(color.copy(alpha = brightness), CircleShape))
        }
    }
}

/**
 * A small arrow at the end of a heading: pointing right when its contents are folded away.
 * An unfolded heading shows it only under the pointer, fading in as a hint that it folds.
 */
@Composable
private fun FoldMark(collapsed: Boolean, visible: Boolean) {
    val alpha by animateFloatAsState(if (visible) 0.7f else 0f, hoverFade(visible), label = "fold mark")
    Icon(
        if (collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
        contentDescription = if (collapsed) "Folded" else null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 1.dp).size(16.dp).alpha(alpha),
    )
}

@Composable
private fun HostContent(host: RexHost, ui: RexUiState, modifier: Modifier) {
    val state by host.state.collectAsState()
    val view = state.view
    val window = state.window
    val panes = window?.panes.orEmpty().filter { window?.zoomedBlockId == null || it.blockId == window.zoomedBlockId }
    // Which pane has the keyboard is the session's own state, so every client agrees on it.
    val focusedId = window?.let(::focusedBlockOf)

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.connection != HostConnection.Connected ->
                    Notice(if (state.connection is HostConnection.Disconnected) "Not connected to ${host.saved.label}" else "Connecting to ${host.saved.label}…")
                view == null -> Notice(if (state.sessions.isEmpty()) "This server has no sessions" else "Select a session")
                panes.isEmpty() -> Notice("This tab is empty")
                else -> BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp)) {
                    val width = maxWidth
                    val height = maxHeight
                    for (pane in panes) {
                        val rect = if (window?.zoomedBlockId != null) PaneRect.Full else pane.rect
                        Box(
                            Modifier
                                .offset(x = width * rect.x, y = height * rect.y)
                                .size(width * rect.width, height * rect.height)
                                .padding(3.dp),
                        ) {
                            Pane(
                                pane = pane,
                                terminal = state.terminals[pane.blockId],
                                focused = pane.blockId == focusedId,
                                onFocus = { host.focusBlock(pane.blockId) },
                                host = host,
                                ui = ui,
                            )
                        }
                    }
                }
            }
        }
        StatusBar(host, view, window?.let(state::nameOf), state.terminals[focusedId])
    }
}

@Composable
private fun Pane(pane: PaneView, terminal: TerminalSession?, focused: Boolean, onFocus: () -> Unit, host: RexHost, ui: RexUiState) {
    val shape = MaterialTheme.shapes.small
    Box(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .background(MaterialTheme.colorScheme.background)
            .border(1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(6.dp),
    ) {
        when {
            !pane.isTerminal -> Notice("This block isn't a terminal")
            terminal == null -> Notice("Opening…")
            else -> {
                val status by terminal.status.collectAsState()
                TerminalView(
                    terminal = terminal,
                    focused = focused,
                    onFocusRequested = onFocus,
                    // Never while something is open over the terminals: whatever the keyboard
                    // focus says, what is typed then is meant for the thing on top, and a
                    // key sent here would be typed into a shell and claim its terminal.
                    onKey = { if (!ui.overlayOpen) host.sendKey(pane.blockId, it) },
                    onDesiredGrid = { host.setDesiredGrid(pane.blockId, it) },
                    onPointerOver = { host.pointerOver(pane.blockId) },
                    fontSize = ui.fontSize.sp,
                    focusRequests = ui.focusRequests,
                )
                when (val current = status) {
                    TerminalSession.Status.Connecting -> Notice("Restoring…")
                    is TerminalSession.Status.Ended -> Notice(current.reason)
                    TerminalSession.Status.Live -> {}
                }
            }
        }
    }
}

@Composable
private fun StatusBar(host: RexHost, view: SessionView?, tab: TabName?, terminal: TerminalSession?) {
    val grid = terminal?.grid?.collectAsState()?.value
    val exit = terminal?.exitCode?.collectAsState()?.value
    val separator = "  ·  "
    // Where you are, from the outside in: the computer, the session, the tab, and how large
    // its terminal is. With the sidebar hidden this is the only place that says which tab is
    // on screen, so the tab's name is the one part in full-strength text.
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HintText(listOfNotNull(host.saved.label, view?.label).joinToString(separator))
        if (tab != null) {
            HintText(separator)
            Text(tab.text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            tab.directory?.let { HintText(it, Modifier.padding(start = 8.dp)) }
        }
        val rest = listOfNotNull(grid?.let { "${it.columns}×${it.rows}" }, exit?.let { "exited with $it" })
        if (rest.isNotEmpty()) HintText(separator + rest.joinToString(separator))
    }
}

@Composable
private fun Notice(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** How soon a second click on a tab must follow the first to count as a double click. */
private const val DOUBLE_CLICK_MILLIS = 400L
