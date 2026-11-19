package org.example.kotlintest.rex

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.kotlintest.ghostty.TerminalColors
import org.example.kotlintest.ghostty.encodePaste
import kotlin.concurrent.Volatile

sealed interface HostConnection {
    data object Connecting : HostConnection
    data object Connected : HostConnection
    /** Not connected. The host keeps retrying unless [permanent]. */
    data class Disconnected(val reason: String, val permanent: Boolean = false) : HostConnection
}

/**
 * How far along reaching a host is, for showing next to it. [HostConnection] alone can't say:
 * it is `Connecting` during the first attempt and during every one after, and `Disconnected`
 * only in the pauses between them.
 */
enum class HostReach {
    Connected,
    /** The first attempt hasn't ended: nothing is known about the host yet. */
    Connecting,
    /** It was connected, the connection ended, and it is being tried again every few seconds. */
    Reconnecting,
    /** It has never answered, or its address can't be read. Tried again every few seconds unless the address is the trouble. */
    Down,
}

/** Everything the app shows about one host. Replaced as a whole whenever something changes. */
data class HostState(
    val connection: HostConnection = HostConnection.Connecting,
    /**
     * Whether the last attempt to reach the host failed or its connection ended. It stays
     * set while the host is tried again, and is cleared only by connecting.
     */
    val failed: Boolean = false,
    /** Whether the host has been connected at some time since the app started. */
    val reached: Boolean = false,
    /** This app's identity on the server. Blocks it owns name it as their owner. */
    val clientId: String? = null,
    /** Identifies the machine and account the server runs as, whichever address reached it. */
    val serverHostId: String? = null,
    val apiRevision: Int? = null,
    val sessions: List<SessionSummary> = emptyList(),
    val selectedSessionId: String? = null,
    /**
     * The layout of every session, by session id, so that all their tabs can be listed.
     * One is missing only until it has been read.
     */
    val views: Map<String, SessionView> = emptyMap(),
    /** The live terminals of the selected session's active window, by block id. */
    val terminals: Map<String, TerminalSession> = emptyMap(),
    /**
     * What the program in each terminal says it is doing, by block id, for every session.
     * A terminal whose program has reported nothing, or is idle, has no entry.
     */
    val activity: Map<String, ActivityState> = emptyMap(),
    /**
     * The program in the foreground of each terminal, by block id, for every session: the
     * shell at its prompt, or the command it is running. Missing until the server has said.
     */
    val programs: Map<String, ForegroundProgram> = emptyMap(),
    /**
     * The tab this app shows in each session, by session id. Which tab a client is looking
     * at is the client's own business: two clients on one session see the same terminals
     * and each other's typing, but each chooses its own tab. The session's active window on
     * the server is only where this starts, and where it falls back when the tab closes.
     */
    val shownWindows: Map<String, String> = emptyMap(),
    /** The progress the program in each terminal last reported, by block id, for every session. See [progressOf]. */
    val progress: Map<String, TerminalProgress> = emptyMap(),
    /** The ids of the tabs in which a bell rang while they were out of sight, not looked at since. See [hasBell]. */
    val bells: Set<String> = emptySet(),
) {
    /** How far along reaching the host is. A pause between two tries doesn't change it. */
    val reach: HostReach
        get() = when {
            connection == HostConnection.Connected -> HostReach.Connected
            !failed && connection == HostConnection.Connecting -> HostReach.Connecting
            reached && (connection as? HostConnection.Disconnected)?.permanent != true -> HostReach.Reconnecting
            else -> HostReach.Down
        }

    /** The selected session's layout. */
    val view: SessionView? get() = views[selectedSessionId]

    /** The tab this app shows in a session: the one it last went to there, else the session's active one. */
    fun shownWindow(sessionId: String?): WindowView? {
        val view = views[sessionId] ?: return null
        return view.windows.firstOrNull { it.id == shownWindows[sessionId] } ?: view.activeWindow
    }

    /** The tab on screen: the selected session's shown one. */
    val window: WindowView? get() = shownWindow(selectedSessionId)

    /**
     * What to call a tab: the name someone gave it, or until someone does, the program
     * running in its focused pane and the directory that program is in. The server's
     * placeholder shows only while the program isn't known.
     */
    fun nameOf(window: WindowView): TabName {
        if (!window.hasDefaultLabel()) return TabName(window.label)
        val program = programs[window.focusedBlockId ?: window.panes.firstOrNull()?.blockId] ?: return TabName(window.label)
        return TabName(program.name, program.directory)
    }

    /**
     * Whether a terminal is the one in use on this host: the pane that has the keyboard, in
     * the tab on screen. Whether the host itself is the one showing is not this state's to say.
     */
    fun isInUse(sessionId: String, blockId: String): Boolean = sessionId == selectedSessionId && window?.keyboardBlockId == blockId

    /** The most pressing thing any pane of a tab is doing. */
    fun activityOf(window: WindowView): ActivityState =
        window.panes.maxOfOrNull { activity[it.blockId] ?: ActivityState.Idle } ?: ActivityState.Idle
}

/**
 * What a program says it is doing. Programs report this themselves (with OSC 7501), so a
 * terminal running something that doesn't report is simply idle. In order of how much it
 * wants attention, least first.
 */
enum class ActivityState {
    Idle,
    /** Finished. */
    Done,
    Working,
    Error,
    /** Waiting for the user: a question, a permission, a password. */
    Blocked;

    companion object {
        /** Reads a state as the server spells it. One this client doesn't know counts as idle. */
        fun parse(text: String?): ActivityState = entries.firstOrNull { it.name.equals(text, ignoreCase = true) } ?: Idle
    }
}

/** Which side of a pane a new one goes on. */
enum class SplitDirection(internal val axis: String, internal val side: String) {
    Left("horizontal", "before"),
    Right("horizontal", "after"),
    Up("vertical", "before"),
    Down("vertical", "after"),
}

/**
 * One Rex server, and the app's connection to it.
 *
 * It connects as soon as it is created and keeps the connection up: on any failure it
 * waits and tries again, carrying on as the same client where the server still remembers
 * it. Each time it connects it reads everything afresh in one `session.bootstrap`, selects
 * the session it had (or [rememberedSession], or the first listed), and opens the
 * terminals that session shows. It never creates or destroys anything unless asked to.
 */
class RexHost(
    val saved: SavedHost,
    private val http: HttpClient,
    parentScope: CoroutineScope,
    private val info: RexClientInfo,
    /** The session selected on this host last time, by id or label. */
    private val rememberedSession: String? = null,
    /** True for a host that came from the home server's host list rather than this app's saved hosts. */
    val discovered: Boolean = false,
    private val log: RexLog = RexLog(),
    private val onSessionSelected: (sessionId: String) -> Unit = {},
    /** The tab that was showing in each session last time, by session id. */
    rememberedTabs: Map<String, String> = emptyMap(),
    /** Called when the tab shown in a session changes, with null when there is no longer one to remember. */
    private val onTabShown: (sessionId: String, windowId: String?) -> Unit = { _, _ -> },
    /**
     * Called with what happens in a tab nobody is looking at: a program there starts waiting
     * for the user, fails or finishes, or asks to tell the user something. See [TabNews].
     */
    private val onNews: (TabNews) -> Unit = {},
    /** Called for the server's own events, such as `keymap_changed`, and with `connected` after each connect. */
    private val onServerEvent: (type: String) -> Unit = {},
    /** Called with text a program copied in the terminal in use here, for this machine's clipboard. */
    private val onProgramCopy: (ProgramCopy) -> Unit = {},
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val mutableState = MutableStateFlow(HostState(shownWindows = rememberedTabs))
    val state: StateFlow<HostState> get() = mutableState

    /**
     * How many requests to show another session or tab are waiting or under way. Until they
     * are done, the tab on screen may be one that is only being passed through: asked for
     * twice in a row, the second tab shows at once, then the first as its session is
     * attached, then the second again.
     */
    val moving: StateFlow<Int> get() = mutableMoving
    private val mutableMoving = MutableStateFlow(0)

    /** Work for the control connection, done one at a time in the order it was asked for. */
    private val actions = Channel<suspend (RexControl) -> Unit>(Channel.UNLIMITED)
    private val retryNow = Channel<Unit>(Channel.CONFLATED)
    private val desiredGrids = concurrentMap<String, GridSize>()
    /** Blocks claimed on this connection, until the server's view says otherwise. */
    private val claimed = concurrentSet<String>()
    /** Where each line between panes was last asked to go, until that has been sent. */
    private val pendingResizes = concurrentMap<String, Float>()

    /** Whether this host's terminals are what the user is looking at: it is the host showing, in a window that has focus. */
    @Volatile private var inFront = false
    /** For each session, when a terminal this client owned last closed in it. */
    private val ownClosures = concurrentMap<String, Long>()
    /** Terminals a claim is already on its way for, so a moving pointer asks once. */
    private val claiming = concurrentSet<String>()
    /** The terminals claimed for being in front, since this host last came to the front. */
    private val claimedInFront = concurrentSet<String>()
    /** Each terminal's status records, by block id then record id: a program can report several things at once. */
    private val statusRecords = concurrentMap<String, MutableMap<String, ActivityState>>()
    /** Blocks whose status has been asked for on this connection. Later changes arrive as events. */
    private val statusRead = concurrentSet<String>()
    /**
     * What programs copy. Only the terminal in use may change the clipboard: the focused pane
     * of the tab on screen, on the host showing, while the app's window has the keyboard.
     */
    private val copies = ProgramCopies(
        inUse = { sessionId, blockId -> inFront && state.value.isInUse(sessionId, blockId) },
        log = { message, isError -> log.add(saved.label, message, isError) },
        deliver = { sessionId, blockId, text -> onProgramCopy(ProgramCopy(saved.label, sessionId, blockId, text)) },
    )
    @Volatile private var terminalColors: TerminalColors? = null
    @Volatile private var control: RexControl? = null
    private val endpoint: RexEndpoint? = runCatching { RexEndpoint.parse(saved.endpoint) }.getOrNull()

    init {
        scope.launch { run() }
    }

    // What the user can do. Each is queued and runs in order on the control connection.

    /** Shows another session. */
    fun selectSession(sessionId: String) = act("select session", moves = true) { attach(it, sessionId) }

    /**
     * Shows a tab of any session, selecting the session if it isn't the one showing.
     *
     * The tab shown is this app's own choice and changes here at once. The session is also
     * told that this is now its active window, as Rex.app tells it, so that a command which
     * names no window acts where someone last looked. Other clients keep the tab they chose.
     */
    fun showTab(sessionId: String, windowId: String) {
        setShown(sessionId, windowId)
        act("show tab", moves = true) { control ->
            // Going to a tab claims its terminals afresh, as in Rex.app: whatever another
            // client did with them since this one last had them, the tab is in use here now.
            state.value.views[sessionId]?.windows?.firstOrNull { it.id == windowId }?.panes?.forEach { claimedInFront.remove(it.blockId) }
            if (state.value.selectedSessionId != sessionId) attach(control, sessionId)
            else state.value.views[sessionId]?.let { showView(control, it) } // open the tab's terminals
            makeActive(control, sessionId)
        }
    }

    /**
     * Says whether this host's terminals are in front of the user: this is the host showing,
     * and the app's window has focus.
     *
     * A terminal takes its size from one client at a time, its owner, and the client someone
     * is using is the one whose size should count. So, as Rex's own apps do, this app claims
     * the terminals on screen when it comes to the front: once each, and again the next time
     * it comes forward. If another client takes one back meanwhile, that is left alone until
     * then, or until something is typed here, so two clients never fight over a terminal.
     */
    fun setInFront(value: Boolean) {
        if (inFront == value) return
        inFront = value
        look()
        if (value) {
            claimedInFront.clear()
            act(null) { claimInFront(it) }
        }
    }

    /**
     * Says the pointer is over a terminal. That claims it, as in Rex.app: the client under
     * your hand is the one in use, and a program that follows the mouse needs the terminal
     * to be the size it is drawn at here for the pointer to land where it looks.
     *
     * Called for every movement, so it does nothing unless the terminal is someone else's.
     */
    fun pointerOver(blockId: String) {
        if (owns(blockId) || !claiming.add(blockId)) return
        act(null) { control ->
            try {
                state.value.selectedSessionId?.let { claim(control, it, blockId) }
            } finally {
                claiming.remove(blockId)
            }
        }
    }

    /** Claims the terminals on screen that haven't been claimed since this host came to the front. */
    private suspend fun claimInFront(control: RexControl) {
        if (!inFront) return
        val current = state.value
        val sessionId = current.selectedSessionId ?: return
        val window = current.window ?: return
        for (pane in window.panes) {
            if (!pane.isTerminal || (window.zoomedBlockId != null && pane.blockId != window.zoomedBlockId)) continue
            if (claimedInFront.add(pane.blockId)) claim(control, sessionId, pane.blockId)
        }
    }

    private fun setShown(sessionId: String, windowId: String?) {
        if (state.value.shownWindows[sessionId] == windowId) return
        mutableState.update { it.copy(shownWindows = if (windowId == null) it.shownWindows - sessionId else it.shownWindows + (sessionId to windowId)) }
        onTabShown(sessionId, windowId)
        look()
    }

    /** Takes the tab on screen as seen, if it is in front of the user: a bell that rang in it is no longer news. */
    private fun look() = mutableState.update { it.looked(inFront) }

    /**
     * Makes the tab this app shows the session's active window, if it isn't. Actions the
     * server carries out relative to the active window, such as moving focus to the pane on
     * the left, need this first: another client may have gone to a different tab since.
     */
    private suspend fun makeActive(control: RexControl, sessionId: String? = state.value.selectedSessionId) {
        val current = state.value
        val shown = current.shownWindow(sessionId)?.id ?: return
        if (current.views[sessionId]?.activeWindowId == shown) return
        control.call("session.focus_window", buildJsonObject {
            put("session_id", sessionId)
            put("window_id", shown)
        })
    }

    /**
     * Opens a tab with a shell in it, and shows it.
     *
     * @param sessionId the session to open it in; the one showing if left out
     */
    fun newTab(sessionId: String? = null) = act("new tab") { control ->
        val target = sessionId ?: state.value.selectedSessionId ?: return@act
        // The new tab is shown, so its session has to be the one showing.
        if (state.value.selectedSessionId != target) attach(control, target)
        val created = control.call("session.new_window", buildJsonObject {
            put("session_id", target)
            put("layout", SHELL_LAYOUT)
        })
        log.add(saved.label, "created tab ${created.string("window_id")}")
        created.string("window_id")?.let { setShown(target, it) } // a tab made here is shown here
        claimCreated(control, created["block_ids"] as? JsonArray)
    }

    /** Creates a session with a shell in it, and shows it. */
    fun newSession() = act("new session") { control ->
        val created = control.call("session.create", buildJsonObject {
            put("initial_windows", buildJsonArray { add(buildJsonObject { put("layout", SHELL_LAYOUT) }) })
        })
        val sessionId = created.string("session_id") ?: return@act
        log.add(saved.label, "created session $sessionId")
        attach(control, sessionId)
        val blocks = ((created["initial_windows"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("block_ids") as? JsonArray
        claimCreated(control, blocks)
    }

    /** Opens a shell beside a pane. */
    fun split(blockId: String, direction: SplitDirection) = act("split") { control ->
        val created = control.call("session.new_split", sessionPayload {
            put("anchor_id", blockId)
            put("direction", direction.axis)
            put("side", direction.side)
            put("layout", SHELL_LAYOUT)
            put("focus", true)
        })
        claimCreated(control, created["block_ids"] as? JsonArray)
    }

    /** Closes a pane and ends the program in it. */
    fun closeBlock(blockId: String) = act("close pane") { control ->
        val view = state.value.view
        // The only pane of the only tab is the last of the session, and the session goes with it.
        if (view != null && view.windows.size == 1 && view.windows.single().panes.let { it.size == 1 && it.single().blockId == blockId }) {
            destroy(control, view.sessionId, "its last pane was closed")
        } else {
            control.call("block.close", sessionPayload { put("block_id", blockId) })
        }
    }

    /**
     * Names a tab, in any session. The name is the window's label on the server, so every
     * client shows it. An empty name clears it, and the tab goes back to being called by
     * the program running in it.
     */
    fun renameTab(sessionId: String, windowId: String, name: String) = act("rename tab") { control ->
        control.call("session.set_window_label", buildJsonObject {
            put("session_id", sessionId)
            put("window_id", windowId)
            put("label", name.trim())
        })
    }

    /**
     * Moves a tab to just before another in its session, or to the end. The order of a
     * session's tabs is the session's own, so it changes for every client.
     */
    fun moveTab(sessionId: String, windowId: String, beforeWindowId: String?) = act("move tab") { control ->
        control.call("session.move_window", buildJsonObject {
            put("session_id", sessionId)
            put("window_id", windowId)
            beforeWindowId?.let { put("before_window_id", it) }
        })
    }

    /** Moves a session to just before another in the server's list, or to the end. That order too is shared. */
    fun moveSession(sessionId: String, beforeSessionId: String?) = act("move session") { control ->
        control.call("session.move", buildJsonObject {
            put("session_id", sessionId)
            beforeSessionId?.let { put("before_session_id", it) }
        })
    }

    /** Closes a tab and every pane in it. */
    fun closeWindow(windowId: String) = act("close tab") { control ->
        val view = state.value.view
        if (view != null && view.windows.singleOrNull()?.id == windowId) {
            destroy(control, view.sessionId, "its last tab was closed")
        } else {
            control.call("session.close_window", sessionPayload { put("window_id", windowId) })
        }
    }

    /**
     * Closes every tab of a session but one. [windowIds] are the others as they were when the
     * user agreed to it; what has changed since is left alone, as [tabsToClose] says.
     */
    fun closeOtherTabs(sessionId: String, keepWindowId: String, windowIds: List<String>) = act("close other tabs") { control ->
        val open = state.value.views[sessionId]?.windows?.map { it.id }.orEmpty()
        for (windowId in tabsToClose(open, keepWindowId, windowIds)) {
            control.call("session.close_window", buildJsonObject { put("session_id", sessionId); put("window_id", windowId) })
        }
    }

    /**
     * Names a session. The name is the session's label on the server, so every client shows
     * it. An empty name gives it back the one the server chose when it was made.
     */
    fun renameSession(sessionId: String, name: String) = act("rename session") { control ->
        control.call("session.set_label", buildJsonObject {
            put("session_id", sessionId)
            put("label", name.trim())
        })
    }

    /** Closes a session and everything in it. */
    fun closeSession(sessionId: String) = act("close session") { control -> destroy(control, sessionId, "it was closed here") }

    /**
     * Whether closing a tab would close its session, because it is the session's last. A
     * session with no tabs is no use to anyone, so, as in Rex.app, it isn't left behind.
     */
    fun isLastTab(windowId: String): Boolean = state.value.view?.windows?.singleOrNull()?.id == windowId

    private suspend fun destroy(control: RexControl, sessionId: String, why: String) {
        val label = state.value.sessions.firstOrNull { it.id == sessionId }?.label ?: short(sessionId)
        control.call("session.destroy", buildJsonObject { put("session_id", sessionId) })
        log.add(saved.label, "closed session $label: $why")
    }

    /** Zooms a pane to fill its tab, or restores the layout if it already does. */
    fun toggleZoom(blockId: String) = act("zoom") { control ->
        control.call("session.zoom_block", sessionPayload { put("block_id", blockId) })
    }

    /** Moves the session's focus to a pane. */
    fun focusBlock(blockId: String) = act("focus pane") { control ->
        control.call("session.focus_block", sessionPayload { put("block_id", blockId) })
    }

    /** Moves focus to the nearest pane in a direction: `left`, `right`, `up` or `down`. */
    fun focusDirection(direction: String) = act("focus $direction") { control ->
        makeActive(control)
        control.call("session.focus_direction", sessionPayload { put("direction", direction) })
    }

    /** Moves focus to the next or previous pane of the tab. */
    fun stepPane(forward: Boolean) = act("step pane") { control ->
        makeActive(control)
        control.call(if (forward) "session.focus_next_pane" else "session.focus_previous_pane", sessionPayload {})
    }

    // Rearranging a tab's panes. The layout is the session's own, so these change it for every client.

    /**
     * Moves the line between two panes, to [ratio] of the space it divides. A drag asks many
     * times in a row, faster than the server answers, so only the newest place asked for
     * each line is sent.
     */
    fun resizePane(divider: PaneDivider, ratio: Float) {
        val sessionId = state.value.selectedSessionId ?: return
        val key = "$sessionId ${divider.key} ${divider.axis}"
        val waiting = pendingResizes.put(key, ratio) != null
        if (waiting) return // the request already queued for this line will send the newer ratio
        act(null) { control ->
            val newest = pendingResizes.remove(key) ?: return@act
            control.call("session.resize_pane", buildJsonObject {
                put("session_id", sessionId)
                put("direction", divider.axis)
                put("before_block_id", divider.beforeBlockId)
                put("after_block_id", divider.afterBlockId)
                put("ratio", newest)
            })
        }
    }

    /** Moves one border of a pane a step toward a side; see [resizedBy]. */
    fun resizePane(blockId: String, side: PaneSide, percent: Double = RESIZE_STEP_PERCENT) {
        // Stepped from the ratio the server last reported, so a held key never runs ahead of it.
        val divider = state.value.window?.resizedBy(blockId, side, percent) ?: return
        resizePane(divider, divider.ratio)
    }

    /** Evens out the tab's panes; see [balanced]. */
    fun balancePanes() {
        val moves = state.value.window?.balanced().orEmpty()
        if (moves.isNotEmpty()) log.add(saved.label, "balancing ${moves.size} split${if (moves.size == 1) "" else "s"}")
        for (divider in moves) resizePane(divider, divider.ratio)
    }

    /** Trades a pane's place with the pane lying against one side of it. Focus stays with the pane. */
    fun swapPane(blockId: String, side: PaneSide) {
        val other = state.value.window?.neighbourOf(blockId, side) ?: return
        act("move pane ${side.name.lowercase()}") { control ->
            control.call("session.swap_blocks", sessionPayload {
                put("block_id", blockId)
                put("other_block_id", other)
            })
        }
    }

    /**
     * Moves a pane into another tab of its session, to the right of the pane that has the
     * keyboard there, and shows that tab. The program in it carries on. If it was the only
     * pane of its tab, the server closes that tab.
     */
    fun movePaneToTab(blockId: String, windowId: String) = act("move pane to tab") { control ->
        val sessionId = state.value.selectedSessionId ?: return@act
        val target = state.value.views[sessionId]?.windows?.firstOrNull { it.id == windowId } ?: return@act
        if (target.panes.any { it.blockId == blockId }) return@act // it is there already
        // Beside a tiled pane: one that floats has no place in the layout to split.
        val anchor = target.panes.filter { !it.floating }.let { tiled -> tiled.firstOrNull { it.blockId == target.focusedBlockId } ?: tiled.firstOrNull() } ?: return@act
        control.call("session.move_block", buildJsonObject {
            put("session_id", sessionId)
            put("block_id", blockId)
            put("anchor_block_id", anchor.blockId)
        })
        setShown(sessionId, windowId) // the pane is what the user is working in, so follow it
    }

    /**
     * Moves a pane into a new tab of its own, and shows it.
     *
     * The server can't make a window around a block that already exists: a new window always
     * starts a program. So one is opened with a shell in it, the pane is moved in beside the
     * shell, and the shell is closed, which leaves the pane with the tab to itself. The shell
     * lives for a moment and nothing is typed into it.
     */
    fun movePaneToNewTab(blockId: String) = act("move pane to new tab") { control ->
        val sessionId = state.value.selectedSessionId ?: return@act
        val created = control.call("session.new_window", buildJsonObject {
            put("session_id", sessionId)
            put("layout", SHELL_LAYOUT)
            put("focus", false)
        })
        val windowId = created.string("window_id") ?: return@act
        val placeholder = ((created["block_ids"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.content
        try {
            control.call("session.move_block", buildJsonObject {
                put("session_id", sessionId)
                put("block_id", blockId)
                put("anchor_block_id", placeholder)
            })
        } catch (refused: RexError) {
            // Leave nothing behind: the tab was only ever for the pane.
            runCatching { control.call("session.close_window", buildJsonObject { put("session_id", sessionId); put("window_id", windowId) }) }
            throw refused
        }
        setShown(sessionId, windowId)
        control.call("block.close", buildJsonObject { put("session_id", sessionId); put("block_id", placeholder) })
    }

    /**
     * Names a pane. The name is the block's label on the server, so every client can show
     * it. An empty name clears it.
     */
    fun renamePane(blockId: String, name: String) = act("rename pane") { control ->
        control.call("session.set_block_label", sessionPayload {
            put("block_id", blockId)
            put("label", name.trim())
        })
    }

    /** Runs one of the server's own actions, such as clearing a terminal, on a pane. */
    fun performServerAction(name: String, args: JsonObject?, blockId: String?) = act(name) { control ->
        makeActive(control)
        control.call("client.action", buildJsonObject {
            put("action", name)
            args?.let { put("args", it) }
            put("origin", "palette")
            state.value.selectedSessionId?.let { put("session_id", it) }
            if (blockId != null) put("block_id", blockId)
        })
    }

    /**
     * Types a key into a block.
     *
     * Looking at a terminal is passive, but pressing a key in one claims it: the block then
     * takes its size from this client, as it would from any Rex client being typed into.
     */
    fun sendKey(blockId: String, key: RexKey) = act(null) { control ->
        val current = state.value
        val sessionId = current.selectedSessionId ?: return@act
        if (key.action != RexKey.Action.Release) {
            claim(control, sessionId, blockId)
            current.terminals[blockId]?.typedInto()
        }
        // Not awaited, so typing never waits on the network. Keys still arrive in order.
        control.request("client.key", buildJsonObject {
            put("session_id", sessionId)
            put("block_id", blockId)
            put("key", key.toJson())
        })
    }

    /** Pastes text into a block, bracketed if its program asked for that. */
    fun paste(blockId: String, text: String) = act("paste") { control ->
        if (text.isEmpty()) return@act
        val current = state.value
        val sessionId = current.selectedSessionId ?: return@act
        val terminal = current.terminals[blockId] ?: return@act
        claim(control, sessionId, blockId)
        terminal.typedInto()
        val data = encodePaste(text, terminal.bracketedPaste).decodeToString()
        control.callBlock("$TERMINAL_CREATOR.write", sessionId, blockId, buildJsonObject { put("data", data) })
    }

    /**
     * Types text into a block as it is, for text that didn't come from keys: what a keyboard
     * on a screen put together, which may be a whole word, or a character no key has.
     */
    fun typeText(blockId: String, text: String) = act(null) { control ->
        if (text.isEmpty()) return@act
        val current = state.value
        val sessionId = current.selectedSessionId ?: return@act
        claim(control, sessionId, blockId)
        current.terminals[blockId]?.typedInto()
        control.callBlock("$TERMINAL_CREATOR.write", sessionId, blockId, buildJsonObject { put("data", text) })
    }

    /** Sends a terminal's program input that isn't a key press, such as the report of a mouse click. */
    private fun write(sessionId: String, blockId: String, data: String, claims: Boolean) = act(null) { control ->
        if (claims) claim(control, sessionId, blockId)
        control.callBlock("$TERMINAL_CREATOR.write", sessionId, blockId, buildJsonObject { put("data", data) })
    }

    /**
     * Says how large a block's terminal would be to fill the space this app gives it.
     * It takes effect only for blocks this client owns.
     */
    fun setDesiredGrid(blockId: String, grid: GridSize) {
        if (desiredGrids.put(blockId, grid) == grid) return
        act(null) { control ->
            val sessionId = state.value.selectedSessionId ?: return@act
            if (owns(blockId) && desiredGrids[blockId] == grid) resize(control, sessionId, blockId, grid)
        }
    }

    /** Sets the colors every terminal on this host is drawn with. */
    fun setTerminalColors(colors: TerminalColors) {
        terminalColors = colors
        state.value.terminals.values.forEach { it.setColors(colors) }
    }

    /** Tries to connect now, instead of waiting out the rest of a retry delay. */
    fun retry() { retryNow.trySend(Unit) }

    /** Calls a method on the server. Throws [RexDisconnected] when there is no connection. */
    suspend fun call(method: String, payload: JsonObject = EMPTY): JsonObject =
        (control ?: throw RexDisconnected("Not connected to ${saved.label}")).call(method, payload)

    /** Declares this client's actions and default keys, and returns what each key does once the server's configuration is applied. */
    suspend fun keymap(actions: List<RexAction>, defaults: List<KeyBinding>): Keymap =
        parseKeymap(call("client.keymap", keymapRequest(actions, defaults)))

    fun close() = scope.cancel()

    /** Queues work for the connection. [what] names it in the log; null keeps it out (typing, resizing). */
    private fun act(what: String?, moves: Boolean = false, action: suspend (RexControl) -> Unit) {
        if (moves) mutableMoving.update { it + 1 }
        actions.trySend { control ->
            try {
                action(control)
                if (what != null) log.add(saved.label, "→ $what")
            } catch (refused: RexError) {
                log.add(saved.label, "✗ ${what ?: "request"}: ${refused.message}", isError = true)
            } finally {
                if (moves) mutableMoving.update { it - 1 }
            }
        }
    }

    private inline fun sessionPayload(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        state.value.selectedSessionId?.let { put("session_id", it) }
        build()
    }

    private fun owns(blockId: String): Boolean {
        val current = state.value
        val owner = current.view?.windows?.firstNotNullOfOrNull { window -> window.panes.firstOrNull { it.blockId == blockId } }?.ownerClientId
        return blockId in claimed || (owner != null && owner == current.clientId)
    }

    private suspend fun claim(control: RexControl, sessionId: String, blockId: String) {
        if (owns(blockId)) return
        val taken = runCatching {
            control.call("session.own_block", buildJsonObject {
                put("session_id", sessionId)
                put("block_ids", buildJsonArray { add(JsonPrimitive(blockId)) })
                put("mode", "take")
            })
        }.isSuccess
        if (!taken) return
        claimed += blockId
        log.add(saved.label, "claimed ${short(blockId)}: it now takes its size from this client")
        // The size is sent now if the terminal has been laid out here, and otherwise as soon
        // as it has (see setDesiredGrid).
        desiredGrids[blockId]?.let { resize(control, sessionId, blockId, it) }
    }

    /** Blocks this client just created are its own from the start, so they fill their panes. */
    private suspend fun claimCreated(control: RexControl, blockIds: JsonArray?) {
        val sessionId = state.value.selectedSessionId ?: return
        for (id in blockIds.orEmpty()) (id as? JsonPrimitive)?.content?.let { claim(control, sessionId, it) }
    }

    private suspend fun resize(control: RexControl, sessionId: String, blockId: String, grid: GridSize) {
        runCatching {
            control.callBlock("$TERMINAL_CREATOR.resize", sessionId, blockId, buildJsonObject {
                put("columns", grid.columns)
                put("rows", grid.rows)
                if (grid.cellWidthPx > 0 && grid.cellHeightPx > 0) {
                    put("cell_width_px", grid.cellWidthPx)
                    put("cell_height_px", grid.cellHeightPx)
                }
            })
        }.onSuccess { log.add(saved.label, "asked for ${short(blockId)} to be ${grid.columns}×${grid.rows}") }
            .onFailure { log.add(saved.label, "couldn't size ${short(blockId)}: ${it.message}", isError = true) }
    }

    private suspend fun run() {
        val endpoint = endpoint ?: run {
            val reason = runCatching { RexEndpoint.parse(saved.endpoint) }.exceptionOrNull()?.message ?: "Invalid endpoint"
            log.add(saved.label, reason, isError = true)
            mutableState.update { it.copy(connection = HostConnection.Disconnected(reason, permanent = true), failed = true) }
            return
        }
        var reconnectToken: String? = null
        var attempt = 0
        // Why the last attempt failed, if it did. A host that is down is tried again for as
        // long as the app runs, and saying so each time would bury everything else in the log.
        var lastFailure: String? = null
        while (true) {
            mutableState.update { it.copy(connection = HostConnection.Connecting) }
            if (lastFailure == null) log.add(saved.label, "connecting to ${saved.endpoint}")
            val reason = try {
                coroutineScope {
                    val connection = RexControl.connect(http, endpoint, this, info, reconnectToken)
                    reconnectToken = connection.reconnectToken
                    try {
                        serve(connection, endpoint)
                        attempt = 0
                        lastFailure = null
                    } catch (failure: Exception) {
                        // Setup may be half done on the server. Closing properly ends this
                        // client there, so the next attempt must start as a new one.
                        reconnectToken = null
                        throw failure
                    } finally {
                        control = null
                        withContext(NonCancellable) { connection.close() }
                    }
                    connection.closed.await()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                describe(failure)
            }
            // Everything read on that connection is stale now; it is all read again on the next.
            claimed.clear()
            claimedInFront.clear()
            statusRecords.clear()
            statusRead.clear()
            copies.forget()
            mutableState.value.terminals.values.forEach { it.close() }
            mutableState.update { it.copy(connection = HostConnection.Disconnected(reason), failed = true, views = emptyMap(), terminals = emptyMap(), activity = emptyMap(), programs = emptyMap(), progress = emptyMap(), bells = emptySet()) }
            val delayMillis = RETRY_DELAYS_MILLIS[minOf(attempt++, RETRY_DELAYS_MILLIS.lastIndex)]
            if (reason != lastFailure) log.add(saved.label, "not connected: $reason. Trying again every few seconds until it answers", isError = true)
            lastFailure = reason
            withTimeoutOrNull(delayMillis) { retryNow.receive() }
        }
    }

    /** Sets a new connection up, then handles its events and this host's actions until it ends. */
    private suspend fun serve(connection: RexControl, endpoint: RexEndpoint) {
        // One request tells this client everything it needs to draw the host, and turns on
        // events for every session, including what the programs in their terminals report.
        // Attaching to none here: the session to show is chosen next.
        val boot = within(BOOTSTRAP_TIMEOUT_MILLIS) {
            connection.call("session.bootstrap", buildJsonObject {
                put("event_scope", "all")
                put("attach_session_ids", JsonArray(emptyList()))
            })
        }
        val sessions = parseSessions(boot.obj("attach_all")?.get("sessions") as? JsonArray)
        mutableState.update {
            it.copy(
                connection = HostConnection.Connected,
                failed = false,
                reached = true,
                clientId = connection.clientId ?: boot.string("client_id"),
                serverHostId = boot.obj("server_status")?.string("host_id"),
                apiRevision = connection.apiRevision,
                sessions = sessions,
            )
        }
        log.add(saved.label, "connected: API ${connection.apiRevision}, ${sessions.size} session${if (sessions.size == 1) "" else "s"}, as ${connection.clientId}")
        control = connection
        onServerEvent("connected")
        chooseSession(sessions)?.let { attach(connection, it) }

        coroutineScope {
            // The other sessions' layouts, so their tabs can be listed beside the selected one's.
            launch { for (session in sessions) readView(connection, session.id) }
            val events = launch {
                for (event in connection.events) runCatching { handleEvent(connection, event) }
            }
            val work = launch {
                for (action in actions) {
                    try {
                        action(connection)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // One failed request shouldn't take the connection down with it.
                    }
                }
            }
            // Beside the events rather than among them: a large copy is read in many parts,
            // and the events that follow it shouldn't wait for that.
            val copying = launch {
                copies.run { sessionId, blockId, args -> connection.callBlock("$TERMINAL_CREATOR.clipboard_read", sessionId, blockId, args) }
            }
            connection.closed.await()
            events.cancel()
            work.cancel()
            copying.cancel()
        }
    }

    /** The session to show: the one already selected, else the remembered one, else the first. */
    private fun chooseSession(sessions: List<SessionSummary>): String? {
        val wanted = state.value.selectedSessionId ?: rememberedSession
        val matches = sessions.filter { it.id == wanted || it.label == wanted }
        // An ambiguous label never picks one of its matches arbitrarily.
        return matches.singleOrNull()?.id ?: sessions.firstOrNull()?.id
    }

    private suspend fun attach(connection: RexControl, sessionId: String) {
        val previous = state.value.selectedSessionId
        if (previous != null && previous != sessionId) {
            runCatching { connection.call("session.detach", buildJsonObject { put("session_id", previous) }) }
        }
        val response = connection.call("session.attach", buildJsonObject { put("session_id", sessionId) })
        val view = parseSessionView(response.obj("view") ?: response)
        mutableState.update { it.copy(selectedSessionId = sessionId) }
        log.add(saved.label, "attached to ${view.label}: ${view.windows.size} tab${if (view.windows.size == 1) "" else "s"}, revision ${view.revision}")
        showView(connection, view)
        onSessionSelected(sessionId)
        readStatuses(connection, view)
    }

    /** Reads a session's layout, unless it is already known. Any session can be read, attached or not. */
    private suspend fun readView(connection: RexControl, sessionId: String) {
        if (sessionId in state.value.views) return
        runCatching { connection.call("session.view", buildJsonObject { put("session_id", sessionId) }) }
            .onSuccess {
                val view = parseSessionView(it)
                showView(connection, view)
                readStatuses(connection, view)
            }
            .onFailure { log.add(saved.label, "couldn't read the layout of ${short(sessionId)}: ${it.message}", isError = true) }
    }

    /**
     * Asks what the programs in a session's terminals have reported, for terminals not asked
     * about yet. After that, changes arrive as events.
     */
    private suspend fun readStatuses(connection: RexControl, view: SessionView) {
        for (pane in view.windows.flatMap { it.panes }) {
            if (!pane.isTerminal || !statusRead.add(pane.blockId)) continue
            val records = runCatching {
                connection.callBlock("$TERMINAL_CREATOR.program_status", view.sessionId, pane.blockId)["records"] as? JsonArray
            }.getOrNull().orEmpty()
            for (record in records.mapNotNull { it as? JsonObject }) {
                setStatus(pane.blockId, record.string("id").orEmpty(), ActivityState.parse(record.string("state")), isNews = false)
            }
            setProgram(pane.blockId, runCatching { connection.callBlock("$TERMINAL_CREATOR.process", view.sessionId, pane.blockId) }.getOrNull())
        }
    }

    /**
     * Records the program in a terminal's foreground, from a `process` reply or a
     * `process_changed` event. One that names no program keeps the last one known.
     */
    private fun setProgram(blockId: String, process: JsonObject?) {
        val known = state.value.programs[blockId]
        val program = foregroundProgram(process, known) ?: return
        if (program != known) mutableState.update { it.copy(programs = it.programs + (blockId to program)) }
    }

    /** Records one status report, and updates what the block as a whole is doing. Null removes the record. */
    private fun setStatus(blockId: String, recordId: String, state: ActivityState?, isNews: Boolean = true) {
        val records = statusRecords.getOrPut(blockId) { concurrentMap() }
        if (state == null || state == ActivityState.Idle) records.remove(recordId) else records[recordId] = state
        publishActivity(blockId, isNews)
    }

    /**
     * @param isNews false for what is only being read back, as when connecting: a program that
     *   has been waiting for an hour is not something that just happened
     */
    private fun publishActivity(blockId: String, isNews: Boolean = true) {
        val now = statusRecords[blockId]?.values?.maxOrNull() ?: ActivityState.Idle
        val before = state.value.activity[blockId] ?: ActivityState.Idle
        if (now == before) return
        mutableState.update { it.copy(activity = if (now == ActivityState.Idle) it.activity - blockId else it.activity + (blockId to now)) }
        log.add(saved.label, "${short(blockId)} is ${now.name.lowercase()}")

        // What is worth telling someone who isn't looking: a program now waiting for them,
        // one that failed, or one that finished, which includes a working program that
        // simply stopped saying so.
        val news = when {
            now == ActivityState.Blocked || now == ActivityState.Error || now == ActivityState.Done -> now
            before == ActivityState.Working && now == ActivityState.Idle -> ActivityState.Done
            else -> null
        }
        if (isNews && news != null && !isBeingWatched(blockId)) onNews(TabNews.Activity(state.value.placeOf(saved.label, blockId), news))
    }

    /** Whether a terminal is in the tab on screen while the app's window has focus. */
    private fun isBeingWatched(blockId: String): Boolean = inFront && state.value.window?.panes?.any { it.blockId == blockId } == true

    /** Takes a session's new layout. For the selected session, also makes the open terminals match the blocks it shows. */
    private suspend fun showView(connection: RexControl, view: SessionView) {
        val endpoint = endpoint ?: return
        mutableState.update { current ->
            val views = current.views + (view.sessionId to view)
            if (view.sessionId != current.selectedSessionId) return@update current.copy(views = views)

            // The tab this app chose in the session. If it is gone (closed, or never chosen),
            // the session's active one takes its place, once: from then on that is the choice,
            // and another client going elsewhere doesn't move this one.
            val window = view.windows.firstOrNull { it.id == current.shownWindows[view.sessionId] } ?: view.activeWindow
            val visible = window?.panes.orEmpty()
                .filter { it.isTerminal && (window?.zoomedBlockId == null || it.blockId == window.zoomedBlockId) }
            // The server's word on who owns a block replaces this connection's own bookkeeping.
            claimed.removeAll { blockId ->
                val owner = view.windows.firstNotNullOfOrNull { w -> w.panes.firstOrNull { it.blockId == blockId } }?.ownerClientId
                owner != connection.clientId
            }
            val terminals = HashMap<String, TerminalSession>()
            for (pane in visible) {
                val existing = current.terminals[pane.blockId]
                val reusable = existing != null && existing.sessionId == view.sessionId &&
                    existing.status.value !is TerminalSession.Status.Ended
                terminals[pane.blockId] = if (existing != null && reusable) existing else {
                    existing?.close()
                    openTerminal(connection, endpoint, view.sessionId, pane.blockId)
                }
            }
            current.terminals.filterKeys { it !in terminals }.values.forEach { it.close() }
            val shown = if (window != null) current.shownWindows + (view.sessionId to window.id) else current.shownWindows - view.sessionId
            current.copy(views = views, terminals = terminals, shownWindows = shown)
        }
        look() // the tab on screen may have changed, and a tab with a bell may be gone
        if (view.sessionId == state.value.selectedSessionId) {
            onTabShown(view.sessionId, state.value.shownWindows[view.sessionId])
            claimInFront(connection) // a tab just gone to, or a pane just opened, is on screen now
        }
    }

    private fun openTerminal(connection: RexControl, endpoint: RexEndpoint, sessionId: String, blockId: String) =
        TerminalSession(
            sessionId, blockId, scope, terminalColors,
            log = { log.add(saved.label, "${short(blockId)} $it") },
            onInput = { data, claims -> write(sessionId, blockId, data, claims) },
        ) {
            val ticket = connection.call("data.connect", buildJsonObject {
                put("session_id", sessionId)
                put("block_id", blockId)
                put("data_ref_id", "terminal")
                put("subprotocol", RexTerminalStream.SUBPROTOCOL)
            }).string("ticket") ?: error("The server issued no ticket for the terminal")
            RexTerminalStream.open(http, endpoint, ticket)
        }

    private suspend fun handleEvent(connection: RexControl, event: RexEvent) {
        val current = state.value
        // Some events arrive in bursts and say little: a block's title changing, or every
        // command-line call joining and leaving. Their effects are logged instead.
        val quiet = event.type in QUIET_EVENTS || (event.type == "block_event" && event.payload.string("name") in QUIET_BLOCK_EVENTS)
        if (!quiet) log.add(saved.label, "event ${describe(event, current)}")
        when (event.type) {
            "block_event" -> event.payload.string("block_id")?.let { blockId ->
                val fields = event.payload.obj("payload")
                when (event.payload.string("name")) {
                    "program_status_changed" -> setStatus(blockId, fields?.string("id").orEmpty(), ActivityState.parse(fields?.obj("record")?.string("state")))
                    "process_changed" -> setProgram(blockId, fields)
                    "program_status_removed" -> (fields?.get("ids") as? JsonArray).orEmpty().forEach { id -> setStatus(blockId, (id as? JsonPrimitive)?.content.orEmpty(), null) }
                    "clipboard_written" -> event.sessionId?.let { sessionId -> copies.written(sessionId, blockId, fields) }
                    "bell" -> event.sessionId?.let { sessionId -> mutableState.update { it.rang(sessionId, blockId, inFront) } }
                    "progress_report" -> mutableState.update { it.reported(blockId, fields) }
                    "desktop_notification" -> if (!isBeingWatched(blockId)) TabNews.message(state.value.placeOf(saved.label, blockId), fields)?.let(onNews)
                }
            }
            "block_closed" -> event.payload.string("block_id")?.let { blockId ->
                // A terminal this client had, such as a shell someone just typed `exit` into
                // here. If that turns out to have been the session's last, the session goes too.
                if (owns(blockId)) event.sessionId?.let { sessionId ->
                    // Usually the layout follows this event; if it came first, the session is already known to be empty.
                    if (state.value.views[sessionId]?.windows?.isEmpty() == true) destroy(connection, sessionId, "its last tab closed")
                    else ownClosures[sessionId] = nowMillis()
                }
                claimed.remove(blockId)
                statusRecords.remove(blockId)
                publishActivity(blockId, isNews = false)
                mutableState.update { it.copy(programs = it.programs - blockId, progress = it.progress - blockId) }
            }
            // Says only that a session's layout changed, so fetch it, unless this one is already known.
            "session_view_changed" -> event.sessionId?.let { sessionId ->
                val revision = event.payload.long("revision") ?: Long.MAX_VALUE
                if (revision > (current.views[sessionId]?.revision ?: -1)) {
                    val view = parseSessionView(connection.call("session.view", buildJsonObject { put("session_id", sessionId) }))
                    showView(connection, view)
                    readStatuses(connection, view) // for terminals that are new
                    // Its last tab just closed, by something done in this client (see block_closed).
                    val closedHere = ownClosures.remove(sessionId)?.let { nowMillis() - it < OWN_CLOSURE_MILLIS } == true
                    if (view.windows.isEmpty() && closedHere) destroy(connection, sessionId, "its last tab closed")
                }
            }
            "session_created", "session_destroyed", "session_label_changed", "session_moved" -> {
                val before = current.sessions
                val sessions = parseSessions(connection.call("session.list")["sessions"] as? JsonArray)
                // Forget the layouts of sessions that are gone, and read those of new ones.
                val gone = state.value.shownWindows.keys.filter { id -> sessions.none { it.id == id } }
                mutableState.update { state ->
                    state.copy(sessions = sessions, views = state.views.filterKeys { id -> sessions.any { it.id == id } }, shownWindows = state.shownWindows - gone.toSet())
                }
                gone.forEach { onTabShown(it, null) }
                if (sessions.none { it.id == state.value.selectedSessionId }) {
                    // The session being shown is gone. Show its neighbour rather than nothing:
                    // the one that came after it in the list, or the one before if it was last.
                    val was = before.indexOfFirst { it.id == current.selectedSessionId }
                    val neighbour = neighbourOf(before.map { it.id }, was, sessions.map { it.id })
                    state.value.terminals.values.forEach { it.close() }
                    mutableState.update { it.copy(selectedSessionId = null, terminals = emptyMap()) }
                    (neighbour ?: sessions.firstOrNull()?.id)?.let { attach(connection, it) }
                }
                for (session in sessions) readView(connection, session.id)
            }
            "keymap_changed", "hosts_changed" -> onServerEvent(event.type)
            // Another client asked this one to perform an action. That isn't offered yet, and
            // the caller is waiting, so say so rather than leave it to time out.
            "client_perform_requested" -> event.payload.string("call_id")?.let { callId ->
                connection.request("client.reply", buildJsonObject {
                    put("call_id", callId)
                    putJsonObject("error") {
                        put("code", "refused")
                        put("message", "This client doesn't perform actions for other clients yet")
                    }
                })
            }
        }
    }

    /** An event in a line: its name, the session it is about, and the fields worth seeing. */
    private fun describe(event: RexEvent, current: HostState): String {
        val session = event.sessionId?.let { id -> current.sessions.firstOrNull { it.id == id }?.label ?: short(id) }
        val detail = event.payload.entries
            .filter { (_, value) -> value is JsonPrimitive }
            .joinToString(" ") { (key, value) -> "$key=${(value as JsonPrimitive).content.let { if (it.contains(':')) short(it) else it }}" }
        return listOfNotNull(event.type, session?.let { "[$it]" }, detail.takeIf { it.isNotEmpty() }).joinToString(" ")
    }

    /** Why a connection attempt failed, in words fit to show next to the host. */
    private fun describe(failure: Exception): String =
        connectFailureMessage(failure) ?: failure.message?.takeIf { it.isNotBlank() } ?: failure::class.simpleName ?: "It failed"

    private companion object {
        const val BOOTSTRAP_TIMEOUT_MILLIS = 8_000L
        /** How soon a session must be found empty after this client's terminal closed in it, to be closed for that. */
        const val OWN_CLOSURE_MILLIS = 5_000L
        val RETRY_DELAYS_MILLIS = longArrayOf(500, 1_000, 2_000, 5_000, 10_000)
        val QUIET_EVENTS = setOf("client_joined", "client_left", "client_connected", "client_disconnected", "client_changed")
        val QUIET_BLOCK_EVENTS = setOf("program_status_changed", "progress_report", "title_changed", "process_changed", "pwd_changed")
        val SHELL_LAYOUT = buildJsonObject { putJsonObject("block") { put("flavor", "$TERMINAL_CREATOR.shell") } }

        /** `block:01a1eb49-4eac-…` is too long to read in a log; its kind and last few characters identify it. */
        fun short(id: String): String = id.substringBefore(':') + ":…" + id.takeLast(6)
    }
}

/**
 * What to show when the thing at [index] of [before] is gone: the nearest of what came after
 * it that is still in [remaining], or failing that the nearest of what came before.
 */
internal fun neighbourOf(before: List<String>, index: Int, remaining: List<String>): String? {
    if (index !in before.indices) return null
    val left = remaining.toSet()
    return before.drop(index + 1).firstOrNull { it in left } ?: before.take(index).lastOrNull { it in left }
}

