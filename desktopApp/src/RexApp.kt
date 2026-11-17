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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
import org.example.kotlintest.rex.HostState
import org.example.kotlintest.rex.PaneRect
import org.example.kotlintest.rex.PaneView
import org.example.kotlintest.rex.RexHost
import org.example.kotlintest.rex.SessionView
import org.example.kotlintest.rex.TerminalSession
import org.example.kotlintest.rex.TabName
import org.example.kotlintest.rex.WindowView
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
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

    // Shortcuts are looked up here, above everything, so they work wherever the keyboard is.
    // A key that isn't a shortcut carries on down to the focused terminal.
    val rootFocus = remember { FocusRequester() }
    val shortcutKeysDown = remember { HashSet<Int>() }
    LaunchedEffect(Unit) { rootFocus.requestFocus() }

    // Whether Control is down, for the sidebar's shortcut digits. Watched for the whole
    // window rather than through focus, so that it is right whatever has the keyboard, and
    // dropped when the window loses focus, since the release would then never be seen.
    DisposableEffect(ui) {
        val listener = AWTEventListener { event ->
            when (event) {
                is KeyEvent -> ui.controlHeld = event.isControlDown
                is WindowEvent -> if (event.id == WindowEvent.WINDOW_LOST_FOCUS || event.id == WindowEvent.WINDOW_DEACTIVATED) ui.controlHeld = false
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.KEY_EVENT_MASK or AWTEvent.WINDOW_EVENT_MASK or AWTEvent.WINDOW_FOCUS_EVENT_MASK)
        onDispose { Toolkit.getDefaultToolkit().removeAWTEventListener(listener) }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier.fillMaxSize().focusRequester(rootFocus).focusable().onPreviewKeyEvent { event ->
                val awt = event.awtEventOrNull ?: return@onPreviewKeyEvent false
                if (ui.palette != null || ui.renaming != null) return@onPreviewKeyEvent false // an overlay has the keyboard
                when (awt.id) {
                    KeyEvent.KEY_PRESSED -> {
                        val chord = keyChord(awt) ?: return@onPreviewKeyEvent false
                        commands.handleKey(chord).also { if (it) shortcutKeysDown += awt.keyCode }
                    }
                    // The release of a key whose press was a shortcut isn't the terminal's either.
                    KeyEvent.KEY_RELEASED -> shortcutKeysDown.remove(awt.keyCode)
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
                            paletteShortcut = keymap.shortcutFor("client.palette.toggle")?.display(),
                        )
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (selected != null) HostContent(selected, ui, Modifier.weight(1f))
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
                        TextButton(onClick = { dismiss(); pending.onConfirm() }, modifier = Modifier.focusRequester(confirmFocus)) { Text(pending.confirmLabel) }
                    },
                    dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
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
    paletteShortcut: String?,
) {
    // Every host's state, read here so that tabs can be numbered across all of them.
    val ordered = sidebarOrder(hosts.map { host -> host to key(host) { host.state.collectAsState().value } }, ui.client.homeLabel) { it.saved.label }
    val tabs = linearTabs(ordered.map { (host, state) -> host.saved.label to state }, ui.collapsedHosts, ui.collapsedSessions)
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
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Rex", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
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
            for ((host, state) in ordered) key(host) {
                val label = host.saved.label
                val hostCollapsed = label in ui.collapsedHosts
                HostHeader(
                    host = host,
                    state = state,
                    collapsed = hostCollapsed,
                    // Folded away, a heading still shows that something under it is busy.
                    activity = if (hostCollapsed) state.activity.values.maxOrNull() ?: ActivityState.Idle else ActivityState.Idle,
                    onClick = { ui.toggleHost(label) },
                    onNewSession = { ui.newSession(host) },
                    onRetry = host::retry,
                )
                // A host's sessions can be dragged into another order, and so can a session's
                // tabs. Both orders are the server's, so they change for every client.
                if (!hostCollapsed) HostSessions(host, state, selected, ui, tabs, digitAlpha)
            }
        }
    }
}

/** The sessions of one host, each a heading over its tabs. */
@Composable
private fun HostSessions(host: RexHost, state: HostState, selected: RexHost?, ui: RexUiState, tabs: List<NavigationStop>, digitAlpha: Float) {
    val sessionOrder = rememberReorder(state.sessions.map { it.id }) { session, before -> host.moveSession(session, before) }
    Column {
        for (session in state.sessions) key(session.id) {
            val windows = state.views[session.id]?.windows.orEmpty()
            val collapsed = session.id in ui.collapsedSessions
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
                    val now = System.currentTimeMillis()
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
    // The dot is all a host says about its connection; why one can't be reached is in the
    // debug log. A host that isn't connected has nothing under it to fold away.
    Column(
        Modifier.fillMaxWidth().hoverable(interaction)
            .clickable(enabled = connected, onClick = onClick)
            .padding(start = 12.dp, end = 6.dp, top = 12.dp, bottom = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(7.dp).background(
                    when {
                        connection == HostConnection.Connected -> MaterialTheme.colorScheme.tertiaryContainer
                        failed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.outline
                    },
                    CircleShape,
                ),
            )
            Text(
                host.saved.label,
                style = MaterialTheme.typography.labelLarge,
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
        StatusBar(host, view, state.terminals[focusedId])
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
private fun StatusBar(host: RexHost, view: SessionView?, terminal: TerminalSession?) {
    val grid = terminal?.grid?.collectAsState()?.value
    val exit = terminal?.exitCode?.collectAsState()?.value
    val parts = listOfNotNull(host.saved.label, view?.label, grid?.let { "${it.columns}×${it.rows}" }, exit?.let { "exited with $it" })
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 12.dp, vertical = 4.dp)) {
        HintText(parts.joinToString("  ·  "))
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
