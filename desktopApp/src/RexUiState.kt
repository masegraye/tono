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

    /** Moves to the next or previous tab, carrying on into the other sessions and hosts, in sidebar order. */
    fun step(forward: Boolean, sessionsOnly: Boolean = false) {
        val hosts = client.hosts.value.map { it.saved.label to it.state.value }
        val here = host?.saved?.label
        val all = navigationStops(hosts, here, sessionsOnly)
        if (all.isEmpty()) return
        val current = all.indexOfFirst {
            it.host == here && it.sessionId == hostState?.selectedSessionId && (it.windowId == null || it.windowId == window?.id)
        }
        val next = when {
            current < 0 -> if (forward) 0 else all.lastIndex
            else -> Math.floorMod(current + if (forward) 1 else -1, all.size)
        }
        go(all[next])
    }

    fun go(stop: NavigationStop) {
        val target = client.hosts.value.firstOrNull { it.saved.label == stop.host } ?: return
        client.selectHost(stop.host)
        if (target.state.value.selectedSessionId != stop.sessionId) target.selectSession(stop.sessionId)
        stop.windowId?.let(target::focusWindow)
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

/** A place the user can go: a session, or one tab of the session they are in. */
data class NavigationStop(val host: String, val sessionId: String, val windowId: String? = null)

/**
 * Every place to go, in sidebar order, for moving through all of them with one key.
 *
 * Only the session being looked at is stepped through tab by tab. Every other session is a
 * single stop, entered at whichever tab it already shows: a session's active tab is shared
 * by everyone attached to it, so passing through must not change it.
 *
 * @param hosts each host's label and state, in sidebar order
 * @param currentHost the label of the host being looked at
 */
internal fun navigationStops(hosts: List<Pair<String, HostState>>, currentHost: String?, sessionsOnly: Boolean = false): List<NavigationStop> =
    hosts.filter { (_, state) -> state.connection == HostConnection.Connected }.flatMap { (label, state) ->
        state.sessions.flatMap { session ->
            val tabs = state.view?.windows?.takeIf { !sessionsOnly && label == currentHost && session.id == state.selectedSessionId && it.isNotEmpty() }
            tabs?.map { NavigationStop(label, session.id, it.id) } ?: listOf(NavigationStop(label, session.id))
        }
    }
