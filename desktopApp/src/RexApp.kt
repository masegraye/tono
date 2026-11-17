package org.example.kotlintest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.kotlintest.rex.HostConnection
import org.example.kotlintest.rex.HostState
import org.example.kotlintest.rex.PaneView
import org.example.kotlintest.rex.RexClient
import org.example.kotlintest.rex.RexHost
import org.example.kotlintest.rex.SessionView
import org.example.kotlintest.rex.TerminalSession
import org.example.kotlintest.rex.WindowView

/**
 * The Rex client: every configured server and its sessions down the side, and the selected
 * session's tabs and terminals in the rest of the window.
 */
@Composable
fun RexApp(client: RexClient, onOpenShowcase: () -> Unit) {
    val hosts by client.hosts.collectAsState()
    val selectedLabel by client.selectedHost.collectAsState()
    val selected = hosts.firstOrNull { it.saved.label == selectedLabel } ?: hosts.firstOrNull()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxSize()) {
            Sidebar(
                hosts = hosts,
                selected = selected,
                onSelectSession = { host, sessionId ->
                    client.selectHost(host.saved.label)
                    host.selectSession(sessionId)
                },
                onSelectHost = { client.selectHost(it.saved.label) },
                onOpenShowcase = onOpenShowcase,
            )
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (selected != null) HostContent(selected, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Sidebar(
    hosts: List<RexHost>,
    selected: RexHost?,
    onSelectSession: (RexHost, String) -> Unit,
    onSelectHost: (RexHost) -> Unit,
    onOpenShowcase: () -> Unit,
) {
    Column(
        Modifier.width(232.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Rex", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("UI showcase") }, onClick = { menuOpen = false; onOpenShowcase() })
                }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            for (host in hosts) {
                val state by host.state.collectAsState()
                HostHeader(host, state, onClick = { onSelectHost(host) }, onRetry = host::retry)
                for (session in state.sessions) {
                    val showing = host === selected && session.id == state.selectedSessionId
                    Text(
                        session.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (showing) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 1.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(if (showing) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .clickable { onSelectSession(host, session.id) }
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }
            }
        }
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
private fun HostContent(host: RexHost, modifier: Modifier) {
    val state by host.state.collectAsState()
    val view = state.view
    val window = view?.activeWindow
    // Which pane has the keyboard is this client's own business; it starts where the session's focus is.
    var focusedBlock by remember(view?.sessionId, window?.id) { mutableStateOf(window?.focusedBlockId) }
    val panes = window?.panes.orEmpty().filter { window?.zoomedBlockId == null || it.blockId == window.zoomedBlockId }
    val focused = panes.firstOrNull { it.blockId == focusedBlock } ?: panes.firstOrNull()

    Column(modifier.fillMaxSize()) {
        if (view != null) TabStrip(view, onSelect = host::focusWindow)
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
                        val rect = if (window?.zoomedBlockId != null) org.example.kotlintest.rex.PaneRect.Full else pane.rect
                        Box(
                            Modifier
                                .offset(x = width * rect.x, y = height * rect.y)
                                .size(width * rect.width, height * rect.height)
                                .padding(3.dp),
                        ) {
                            Pane(
                                pane = pane,
                                terminal = state.terminals[pane.blockId],
                                focused = pane.blockId == focused?.blockId,
                                onFocus = { focusedBlock = pane.blockId },
                                host = host,
                            )
                        }
                    }
                }
            }
        }
        StatusBar(host, state, view, focused, state.terminals[focused?.blockId])
    }
}

@Composable
private fun TabStrip(view: SessionView, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (window in view.windows) Tab(window, active = window.id == view.activeWindow?.id, onClick = { onSelect(window.id) })
    }
}

@Composable
private fun Tab(window: WindowView, active: Boolean, onClick: () -> Unit) {
    Text(
        window.label,
        style = MaterialTheme.typography.labelLarge,
        color = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun Pane(pane: PaneView, terminal: TerminalSession?, focused: Boolean, onFocus: () -> Unit, host: RexHost) {
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
private fun StatusBar(host: RexHost, state: HostState, view: SessionView?, pane: PaneView?, terminal: TerminalSession?) {
    val grid = terminal?.grid?.collectAsState()?.value
    val exit = terminal?.exitCode?.collectAsState()?.value
    val parts = listOfNotNull(
        host.saved.label,
        view?.label,
        grid?.let { "${it.columns}×${it.rows}" },
        exit?.let { "exited with $it" },
        // Looking is passive. Typing claims the terminal, as with any Rex client.
        pane?.takeIf { it.isTerminal }?.let {
            when (it.ownerClientId) {
                state.clientId -> "this client controls the terminal's size"
                null -> "no client controls this terminal's size yet"
                else -> "sized by another client until you type here"
            }
        },
    )
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 12.dp, vertical = 4.dp)) {
        HintText(parts.joinToString("  ·  "))
        Spacer(Modifier.height(1.dp))
    }
}

@Composable
private fun Notice(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
