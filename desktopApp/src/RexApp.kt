package org.example.kotlintest

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlintest.rex.HostConnection
import org.example.kotlintest.rex.HostState
import org.example.kotlintest.rex.PaneRect
import org.example.kotlintest.rex.PaneView
import org.example.kotlintest.rex.RexHost
import org.example.kotlintest.rex.SessionView
import org.example.kotlintest.rex.TerminalSession
import org.example.kotlintest.rex.WindowView
import java.awt.event.KeyEvent

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
                            onGo = ui::go,
                            onSelectHost = { ui.client.selectHost(it.saved.label) },
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

@Composable
private fun Sidebar(
    hosts: List<RexHost>,
    selected: RexHost?,
    onGo: (NavigationStop) -> Unit,
    onSelectHost: (RexHost) -> Unit,
    commands: RexCommands,
    paletteShortcut: String?,
) {
    // Every host's state, read here so that tabs can be numbered across all of them.
    val states = hosts.map { host -> key(host) { host.state.collectAsState().value } }
    val tabs = linearTabs(hosts.map { it.saved.label }.zip(states))

    Column(
        Modifier.width(248.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
                HostHeader(host, state, onClick = { onSelectHost(host) }, onRetry = host::retry)
                for (session in state.sessions) {
                    val showing = host === selected && session.id == state.selectedSessionId
                    SessionRow(session.label, showing, onClick = { onGo(NavigationStop(label, session.id)) })
                    val layout = state.views[session.id]
                    for (tab in layout?.windows.orEmpty()) {
                        val stop = NavigationStop(label, session.id, tab.id)
                        val active = tab.id == layout?.activeWindow?.id
                        TabRow(
                            label = tab.label,
                            digit = tabShortcutDigit(tabs.indexOf(stop), tabs.size),
                            // The tab on screen, as opposed to the tab a session not on screen would show.
                            showing = showing && active,
                            active = active,
                            onClick = { onGo(stop) },
                        )
                    }
                }
            }
        }
    }
}

/** A session's name. Its tabs are listed beneath it. */
@Composable
private fun SessionRow(label: String, showing: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (showing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 28.dp, end = 12.dp, top = 8.dp, bottom = 3.dp),
    )
}

/** A tab, with the digit that goes to it when held with Control. */
@Composable
private fun TabRow(label: String, digit: Int?, showing: Boolean, active: Boolean, onClick: () -> Unit) {
    val color = when {
        showing -> MaterialTheme.colorScheme.onSecondaryContainer
        active -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (showing) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
    ) {
        Text(
            digit?.toString().orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.55f),
            textAlign = TextAlign.End,
            modifier = Modifier.width(20.dp).padding(end = 8.dp),
        )
        Text(label, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        // Marks the tab a session will show, for sessions that aren't the one on screen.
        if (active && !showing) Box(Modifier.size(5.dp).background(color.copy(alpha = 0.5f), CircleShape))
    }
}

@Composable
private fun HostHeader(host: RexHost, state: HostState, onClick: () -> Unit, onRetry: () -> Unit) {
    val connection = state.connection
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(8.dp).background(
                    when (connection) {
                        HostConnection.Connected -> MaterialTheme.colorScheme.tertiaryContainer
                        HostConnection.Connecting -> MaterialTheme.colorScheme.outline
                        is HostConnection.Disconnected -> MaterialTheme.colorScheme.error
                    },
                    CircleShape,
                ),
            )
            Text(host.saved.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when (connection) {
            HostConnection.Connected -> if (state.sessions.isEmpty()) HintText("No sessions", Modifier.padding(start = 16.dp))
            HostConnection.Connecting -> HintText("Connecting…", Modifier.padding(start = 16.dp))
            is HostConnection.Disconnected -> {
                HintText(connection.reason, Modifier.padding(start = 16.dp))
                if (!connection.permanent) TextButton(onClick = onRetry) { Text("Retry now") }
            }
        }
    }
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
