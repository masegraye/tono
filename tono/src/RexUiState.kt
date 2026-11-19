package org.example.kotlintest

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import org.example.kotlintest.rex.HostConnection
import org.example.kotlintest.rex.HostState
import org.example.kotlintest.rex.NotificationSwitches
import org.example.kotlintest.rex.ProgramCopy
import org.example.kotlintest.rex.RexClient
import org.example.kotlintest.rex.RexHost
import org.example.kotlintest.rex.SessionView
import org.example.kotlintest.rex.TailnetServer
import org.example.kotlintest.rex.TerminalSession
import org.example.kotlintest.rex.WindowView
import org.example.kotlintest.rex.hasDefaultLabel
import org.example.kotlintest.rex.hasNews

/** What the command palette is listing. */
enum class PaletteMode {
    Commands, Sessions, Hosts,
    /** Choosing a server to add: the Rex servers found on the tailnet, or an address typed in. */
    AddHost,
    /** Choosing a host to stop connecting to. */
    RemoveHost,
}

/**
 * Something waiting for the user to agree to it.
 *
 * @param destructive whether agreeing ends or removes something that can't be brought back.
 *   Everything asked about so far does, which is why it is asked.
 */
class Confirmation(val title: String, val text: String, val confirmLabel: String, val destructive: Boolean = true, val onConfirm: () -> Unit)

/**
 * A tab whose name is being edited.
 *
 * @param givenName the name someone gave it, or empty if it has none
 * @param automaticName what it is called while it has none
 */
class TabRename(val host: RexHost, val sessionId: String, val windowId: String, val givenName: String, val automaticName: String)

/**
 * The state of the app's own interface, as opposed to what the servers report: which
 * overlays are open, the font size, and so on. Actions read and change it.
 */
@Stable
class RexUiState(val client: RexClient) {
    var palette: PaletteMode? by mutableStateOf(null)
    var sidebarVisible by mutableStateOf(true)

    /**
     * Whether the window is too narrow to show the sidebar and a terminal side by side, as a
     * phone held upright is. Then one of them fills it at a time: the sidebar is where you
     * choose a tab, and going to one puts it away.
     */
    var narrow by mutableStateOf(false)

    /**
     * Whether the sidebar lists only the tabs whose programs have something to say: working,
     * waiting for you, failed or done, a bell you haven't seen, or progress. For finding what
     * needs you among many tabs.
     */
    var activityOnly by mutableStateOf(false)
    var debugLogVisible by mutableStateOf(false)
    var fontSize by mutableIntStateOf(DEFAULT_FONT_SIZE)
    var confirmation: Confirmation? by mutableStateOf(null)
    var renaming: TabRename? by mutableStateOf(null)

    /** Whether a program in a tab out of view makes a sound when it wants you, fails, or finishes. Remembered between runs. */
    var soundsEnabled by mutableStateOf(client.workspace.sounds)
        private set

    /** Plays the sounds. Set once the app's window exists. */
    var sounds: Sounds? = null

    /** What to show a desktop notification for. Remembered between runs. */
    var notifications: NotificationSwitches
        get() = notificationSwitches
        set(switches) {
            notificationSwitches = switches
            client.workspace.notifications = switches
            client.workspace.save()
        }
    private var notificationSwitches by mutableStateOf(client.workspace.notifications)

    /** Shows the notifications. */
    internal val notifier = Notifier(this)

    /** Whether to ask if the browser may show notifications: there has been something to show, and it hasn't been asked. */
    var notificationQuestion by mutableStateOf(false)

    /** Where actions start work they don't wait for. Set once the app's window exists. */
    var scope: CoroutineScope? = null

    fun setSounds(enabled: Boolean) {
        soundsEnabled = enabled
        client.workspace.sounds = enabled
        client.workspace.save()
    }

    /** The Rex servers found on the tailnet, for the host picker. Null until they have been looked for. */
    var tailnetServers: List<TailnetServer>? by mutableStateOf(null)
    var showcase by mutableStateOf(false)

    /** True while Control is down. The sidebar shows each tab's shortcut digit for as long as it is. */
    var controlHeld by mutableStateOf(false)

    /** The hosts (by label) and sessions (by id) folded away in the sidebar. Remembered between runs. */
    var collapsedHosts: Set<String> by mutableStateOf(client.workspace.collapsedHosts)
        private set
    var collapsedSessions: Set<String> by mutableStateOf(client.workspace.collapsedSessions)
        private set

    fun toggleHost(label: String) = setCollapsed(hosts = if (label in collapsedHosts) collapsedHosts - label else collapsedHosts + label)

    fun toggleSession(sessionId: String) = setSessionCollapsed(sessionId, sessionId !in collapsedSessions)

    fun setSessionCollapsed(sessionId: String, collapsed: Boolean) =
        setCollapsed(sessions = if (collapsed) collapsedSessions + sessionId else collapsedSessions - sessionId)

    /** Folds away every session but one. */
    fun collapseSessionsExcept(sessionId: String) =
        setCollapsed(sessions = client.hosts.value.flatMap { host -> host.state.value.sessions.map { it.id } }.toSet() - sessionId)

    private fun setCollapsed(hosts: Set<String> = collapsedHosts, sessions: Set<String> = collapsedSessions) {
        collapsedHosts = hosts
        collapsedSessions = sessions
        client.workspace.collapsedHosts = hosts
        client.workspace.collapsedSessions = sessions
        client.workspace.save()
    }

    /** Changes whenever the keyboard should go back to the focused terminal, such as when an overlay closes. */
    var focusRequests by mutableIntStateOf(0)
        private set

    /** Whether something is open over the terminals and has the keyboard: the palette, or a dialog. */
    val overlayOpen: Boolean get() = palette != null || renaming != null || confirmation != null

    /**
     * Whether text a program copied should go on the clipboard: see [takesCopy].
     *
     * @param windowFocused whether the app's window has the keyboard
     */
    fun takes(copy: ProgramCopy, windowFocused: Boolean): Boolean = takesCopy(
        copy,
        windowFocused = windowFocused,
        // In a narrow window the sidebar is shown instead of the terminals, so it is over them too.
        overlayOpen = overlayOpen || (narrow && sidebarVisible),
        shownHost = host?.saved?.label,
        state = hostState,
    )

    /** Hands the keyboard back to the focused terminal, unless something is open over it. */
    fun returnFocusToTerminal() {
        if (overlayOpen) return
        focusRequests++
        if (narrow) sidebarVisible = false
    }

    fun closePalette() {
        palette = null
        returnFocusToTerminal()
    }

    // Where the user is, read at the moment an action runs.

    val host: RexHost?
        get() = client.hosts.value.let { hosts -> hosts.firstOrNull { it.saved.label == client.selectedHost.value } ?: hosts.firstOrNull() }
    val hostState: HostState? get() = host?.state?.value
    val view: SessionView? get() = hostState?.view
    val window: WindowView? get() = hostState?.window

    /** The pane that has the keyboard. Focus is the session's own state, so this is the server's answer. */
    val focusedBlockId: String? get() = window?.let(::focusedBlockOf)
    val focusedTerminal: TerminalSession? get() = focusedBlockId?.let { hostState?.terminals?.get(it) }

    /** Asks for a new name for a tab, which need not be the one showing. */
    fun renameTab(host: RexHost, sessionId: String, tab: WindowView) {
        val state = host.state.value
        // What it is called without a name, whether or not it has one now.
        val automatic = state.nameOf(tab.copy(label = "")).let { name -> listOfNotNull(name.text, name.directory).joinToString("  ") }
        renaming = TabRename(host, sessionId, tab.id, givenName = if (tab.hasDefaultLabel()) "" else tab.label, automaticName = automatic)
    }

    /** Opens a tab in a session, which need not be the one showing, and goes to it. */
    fun newTab(host: RexHost, sessionId: String) {
        client.log.add("app", "new tab in ${host.state.value.sessions.firstOrNull { it.id == sessionId }?.label ?: sessionId} (sidebar)")
        if (host.saved.label in collapsedHosts || sessionId in collapsedSessions) {
            setCollapsed(collapsedHosts - host.saved.label, collapsedSessions - sessionId)
        }
        client.selectHost(host.saved.label)
        host.newTab(sessionId)
        returnFocusToTerminal()
    }

    /** Creates a session on a host, which need not be the one showing, and goes to it. */
    fun newSession(host: RexHost) {
        client.log.add("app", "new session on ${host.saved.label} (sidebar)")
        if (host.saved.label in collapsedHosts) setCollapsed(hosts = collapsedHosts - host.saved.label)
        client.selectHost(host.saved.label)
        host.newSession()
        returnFocusToTerminal()
    }

    /** The hosts in the order the sidebar lists them. */
    fun orderedHosts(): List<RexHost> =
        sidebarOrder(client.hosts.value.map { it to it.state.value }, client.homeLabel) { it.saved.label }.map { it.first }

    private fun hostStates(): List<Pair<String, HostState>> = orderedHosts().map { it.saved.label to it.state.value }

    /**
     * The tabs the sidebar is listing, in its order: every tab of every session on every host,
     * less the ones folded away. A tab's place here is its number.
     */
    fun tabs(): List<NavigationStop> = linearTabs(hostStates(), collapsedHosts, collapsedSessions, activityOnly)

    /** Every tab there is, folded away or not. */
    fun allTabs(): List<NavigationStop> = linearTabs(hostStates())

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
            else -> (current + if (forward) 1 else -1).mod(all.size)
        }
        go(all[next])
    }

    /** Shows a session, or one of its tabs. Showing a tab makes it its session's active tab, for every client on that session. */
    fun go(stop: NavigationStop) {
        val target = client.hosts.value.firstOrNull { it.saved.label == stop.host } ?: return
        // Going somewhere that is folded away unfolds it, so the sidebar shows where you are.
        if (stop.host in collapsedHosts || stop.sessionId in collapsedSessions) {
            setCollapsed(collapsedHosts - stop.host, collapsedSessions - stop.sessionId)
        }
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
internal fun focusedBlockOf(window: WindowView): String? = window.keyboardBlockId

/**
 * Whether text a program copied should go on this machine's clipboard. Only the terminal the
 * user is at may change it, or a program somewhere out of sight could replace what they are
 * about to paste. So the copy has to come from the pane with the keyboard, in the tab on
 * screen, on the host being shown, while the app's window has the keyboard and nothing is
 * open over the terminals. These are Rex Rune's rules.
 *
 * The host checks where the copy came from when it arrives. This is asked at the last
 * moment, since reading a large copy takes time and the user may have moved on.
 *
 * @param overlayOpen whether something is open over the terminals: the palette, or a dialog
 * @param shownHost the label of the host being shown
 * @param state that host's state
 */
internal fun takesCopy(copy: ProgramCopy, windowFocused: Boolean, overlayOpen: Boolean, shownHost: String?, state: HostState?): Boolean =
    windowFocused && !overlayOpen && copy.host == shownHost && state?.isInUse(copy.sessionId, copy.blockId) == true

/** A place the user can go: a session, or one of its tabs. */
data class NavigationStop(val host: String, val sessionId: String, val windowId: String? = null)

/**
 * The tabs of every session on every connected host, in the order the sidebar lists them:
 * host by host, session by session, tab by tab. A tab's place in this list is its number,
 * which is what Control and a digit go to. Tabs folded away with their host or session are
 * not listed, so folding what you aren't using brings the rest within reach of the digits.
 *
 * @param hosts each host's label and state, in sidebar order
 */
/**
 * The order hosts are listed in: this machine's server first, then the hosts that are
 * connected, then the ones that aren't, each group by name. A host that can't be reached
 * sinks to the bottom, where it takes no room from the ones in use.
 */
internal fun <T> sidebarOrder(hosts: List<Pair<T, HostState>>, home: String?, label: (T) -> String): List<Pair<T, HostState>> =
    hosts.sortedWith(
        compareBy<Pair<T, HostState>>(
            { label(it.first) != home },
            { it.second.connection != HostConnection.Connected },
            { label(it.first).lowercase() },
            { label(it.first) },
        ),
    )

internal fun linearTabs(
    hosts: List<Pair<String, HostState>>,
    collapsedHosts: Set<String> = emptySet(),
    collapsedSessions: Set<String> = emptySet(),
    /** List only the tabs whose programs report something, wherever they are; folding is set aside. */
    activityOnly: Boolean = false,
): List<NavigationStop> =
    hosts.filter { (label, state) -> state.connection == HostConnection.Connected && (activityOnly || label !in collapsedHosts) }.flatMap { (label, state) ->
        state.sessions.filter { activityOnly || it.id !in collapsedSessions }.flatMap { session ->
            state.views[session.id]?.windows.orEmpty()
                .filter { !activityOnly || state.hasNews(it) }
                .map { NavigationStop(label, session.id, it.id) }
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
