package org.example.kotlintest

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.example.kotlintest.rex.HostConnection
import org.example.kotlintest.rex.HostState
import org.example.kotlintest.rex.RexClient
import org.example.kotlintest.rex.RexHost
import org.example.kotlintest.rex.SessionView
import org.example.kotlintest.rex.TerminalSession
import org.example.kotlintest.rex.WindowView

/** What the command palette is listing. */
enum class PaletteMode { Commands, Sessions, Hosts }

/** Something destructive, waiting for the user to agree to it. */
class Confirmation(val title: String, val text: String, val confirmLabel: String, val onConfirm: () -> Unit)

/**
 * The state of the app's own interface, as opposed to what the servers report: which
 * overlays are open, the font size, and so on. Actions read and change it.
 */
@Stable
class RexUiState(val client: RexClient) {
    var palette: PaletteMode? by mutableStateOf(null)
    var sidebarVisible by mutableStateOf(true)
    var debugLogVisible by mutableStateOf(false)
    var fontSize by mutableIntStateOf(DEFAULT_FONT_SIZE)
    var confirmation: Confirmation? by mutableStateOf(null)
    var showcase by mutableStateOf(false)

    /** Changes whenever the keyboard should go back to the focused terminal, such as when an overlay closes. */
    var focusRequests by mutableIntStateOf(0)
        private set

    fun returnFocusToTerminal() { focusRequests++ }

    fun closePalette() {
        palette = null
        returnFocusToTerminal()
    }

    // Where the user is, read at the moment an action runs.

    val host: RexHost?
        get() = client.hosts.value.let { hosts -> hosts.firstOrNull { it.saved.label == client.selectedHost.value } ?: hosts.firstOrNull() }
    val hostState: HostState? get() = host?.state?.value
    val view: SessionView? get() = hostState?.view
    val window: WindowView? get() = view?.activeWindow

    /** The pane that has the keyboard. Focus is the session's own state, so this is the server's answer. */
    val focusedBlockId: String? get() = window?.let(::focusedBlockOf)
    val focusedTerminal: TerminalSession? get() = focusedBlockId?.let { hostState?.terminals?.get(it) }

    private fun hostStates(): List<Pair<String, HostState>> = client.hosts.value.map { it.saved.label to it.state.value }

    /** Every tab of every session on every host, in the order the sidebar lists them. */
    fun tabs(): List<NavigationStop> = linearTabs(hostStates())

    /** Goes to a tab by its place in [tabs], counting from 1. As in Rex.app, -1 is the last tab. */
    fun goToTab(index: Int) {
        val all = tabs()
        (if (index == -1) all.lastOrNull() else all.getOrNull(index - 1))?.let(::go)
    }

    /** Moves to the tab after or before the one showing, through every session and host, wrapping around. */
    fun stepTab(forward: Boolean) = step(tabs(), forward) { it.windowId == window?.id }

    /** Moves to the session after or before the one showing, wrapping around. */
    fun stepSession(forward: Boolean) = step(linearSessions(hostStates()), forward) { true }

    private fun step(all: List<NavigationStop>, forward: Boolean, isHere: (NavigationStop) -> Boolean) {
        if (all.isEmpty()) return
        val here = host?.saved?.label
        val current = all.indexOfFirst { it.host == here && it.sessionId == hostState?.selectedSessionId && isHere(it) }
        val next = when {
            current < 0 -> if (forward) 0 else all.lastIndex
            else -> Math.floorMod(current + if (forward) 1 else -1, all.size)
        }
        go(all[next])
    }

    /** Shows a session, or one of its tabs. Showing a tab makes it its session's active tab, for every client on that session. */
    fun go(stop: NavigationStop) {
        val target = client.hosts.value.firstOrNull { it.saved.label == stop.host } ?: return
        client.selectHost(stop.host)
        if (stop.windowId != null) target.showTab(stop.sessionId, stop.windowId) else target.selectSession(stop.sessionId)
        returnFocusToTerminal()
    }

    companion object {
        const val DEFAULT_FONT_SIZE = 13
        val FONT_SIZES = 8..32
    }
}

/** The pane that has the keyboard in a tab: the zoomed one, else the session's focused one, else the first. */
internal fun focusedBlockOf(window: WindowView): String? =
    window.zoomedBlockId ?: window.focusedBlockId?.takeIf { id -> window.panes.any { it.blockId == id } } ?: window.panes.firstOrNull()?.blockId

/** A place the user can go: a session, or one of its tabs. */
data class NavigationStop(val host: String, val sessionId: String, val windowId: String? = null)

/**
 * Every tab of every session on every connected host, in the order the sidebar lists them:
 * host by host, session by session, tab by tab. A tab's place in this list is its number,
 * which is what Control and a digit go to.
 *
 * @param hosts each host's label and state, in sidebar order
 */
internal fun linearTabs(hosts: List<Pair<String, HostState>>): List<NavigationStop> =
    hosts.filter { (_, state) -> state.connection == HostConnection.Connected }.flatMap { (label, state) ->
        state.sessions.flatMap { session ->
            state.views[session.id]?.windows.orEmpty().map { NavigationStop(label, session.id, it.id) }
        }
    }

/** Every session on every connected host, in sidebar order. */
internal fun linearSessions(hosts: List<Pair<String, HostState>>): List<NavigationStop> =
    hosts.filter { (_, state) -> state.connection == HostConnection.Connected }
        .flatMap { (label, state) -> state.sessions.map { NavigationStop(label, it.id) } }

/**
 * The digit to show beside the tab at [index] of [count]: 1 to 8 for the first eight, and 9
 * for the last tab when there are more than eight, since Control+9 always goes to the last.
 */
internal fun tabShortcutDigit(index: Int, count: Int): Int? = when {
    index < 8 -> index + 1
    index == count - 1 -> 9
    else -> null
}
