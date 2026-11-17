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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
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
                if (ui.palette != null) return@onPreviewKeyEvent false // the palette has the keyboard
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
    val states = hosts.map { host -> key(host) { host.state.collectAsState().value } }
    val tabs = linearTabs(hosts.map { it.saved.label }.zip(states), ui.collapsedHosts, ui.collapsedSessions)
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
                    DropdownMenuItem(text = { Text("UI Showcase") }, onClick = { menuOpen = false; commands.run("client.showcase.toggle", origin = "menu") })
                }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            hosts.forEachIndexed { hostIndex, host ->
                val state = states[hostIndex]
                val label = host.saved.label
                val hostCollapsed = label in ui.collapsedHosts
                HostHeader(
                    host = host,
                    state = state,
                    collapsed = hostCollapsed,
                    // Folded away, a heading still shows that something under it is busy.
                    activity = if (hostCollapsed) state.activity.values.maxOrNull() ?: ActivityState.Idle else ActivityState.Idle,
                    onClick = { ui.toggleHost(label) },
                    onRetry = host::retry,
                )
                if (hostCollapsed) return@forEachIndexed
                for (session in state.sessions) {
                    val layout = state.views[session.id]
                    val windows = layout?.windows.orEmpty()
                    val collapsed = session.id in ui.collapsedSessions
                    SessionHeader(
                        label = session.label,
                        collapsed = collapsed,
                        tabCount = windows.size,
                        activity = if (collapsed) windows.maxOfOrNull(state::activityOf) ?: ActivityState.Idle else ActivityState.Idle,
                        onClick = { ui.toggleSession(session.id) },
                    )
                    if (collapsed) continue
                    for (tab in windows) {
                        val stop = NavigationStop(label, session.id, tab.id)
                        TabRow(
                            label = tab.label,
                            digit = tabShortcutDigit(tabs.indexOf(stop), tabs.size),
                            digitAlpha = digitAlpha,
                            showing = host === selected && session.id == state.selectedSessionId && tab.id == layout?.activeWindow?.id,
                            activity = state.activityOf(tab),
                            onClick = { ui.go(stop) },
                        )
                    }
                }
            }
        }
    }
}

/** A host: a heading over its sessions. Clicking it folds them away. */
@Composable
private fun HostHeader(host: RexHost, state: HostState, collapsed: Boolean, activity: ActivityState, onClick: () -> Unit, onRetry: () -> Unit) {
    val connection = state.connection
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(7.dp).background(
                    when (connection) {
                        HostConnection.Connected -> MaterialTheme.colorScheme.tertiaryContainer
                        HostConnection.Connecting -> MaterialTheme.colorScheme.outline
                        is HostConnection.Disconnected -> MaterialTheme.colorScheme.error
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
            FoldMark(collapsed)
        }
        if (!collapsed) {
            when (connection) {
                HostConnection.Connected -> if (state.sessions.isEmpty()) HintText("No sessions", Modifier.padding(top = 4.dp))
                HostConnection.Connecting -> HintText("Connecting…", Modifier.padding(top = 4.dp))
                is HostConnection.Disconnected -> {
                    HintText(connection.reason, Modifier.padding(top = 4.dp))
                    if (!connection.permanent) TextButton(onClick = onRetry) { Text("Retry now") }
                }
            }
        }
    }
}

/** A session: a small, quiet heading over its tabs. Clicking it folds them away. */
@Composable
private fun SessionHeader(label: String, collapsed: Boolean, tabCount: Int, activity: ActivityState, onClick: () -> Unit) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 14.dp, end = 8.dp, top = 9.dp, bottom = 2.dp),
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
            Text(tabCount.toString(), style = MaterialTheme.typography.labelSmall, color = quiet, modifier = Modifier.padding(start = 6.dp))
            FoldMark(collapsed = true)
        }
    }
}

/** A tab: what you go to. */
@Composable
private fun TabRow(label: String, digit: Int?, digitAlpha: Float, showing: Boolean, activity: ActivityState, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (showing) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (showing) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
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

/** A small arrow at the end of a heading: pointing right when its contents are folded away. */
@Composable
private fun FoldMark(collapsed: Boolean) {
    Icon(
        if (collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
        contentDescription = if (collapsed) "Folded" else null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (collapsed) 0.7f else 0.25f),
        modifier = Modifier.padding(start = 2.dp).size(16.dp),
    )
}

@Composable
private fun HostContent(host: RexHost, ui: RexUiState, modifier: Modifier) {
    val state by host.state.collectAsState()
    val view = state.view
    val window = view?.activeWindow
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
                    onKey = { host.sendKey(pane.blockId, it) },
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
