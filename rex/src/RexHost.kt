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
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.ConcurrentHashMap

sealed interface HostConnection {
    data object Connecting : HostConnection
    data object Connected : HostConnection
    /** Not connected. The host keeps retrying unless [permanent]. */
    data class Disconnected(val reason: String, val permanent: Boolean = false) : HostConnection
}

/** Everything the app shows about one host. Replaced as a whole whenever something changes. */
data class HostState(
    val connection: HostConnection = HostConnection.Connecting,
    /** This app's identity on the server. Blocks it owns name it as their owner. */
    val clientId: String? = null,
    /** Identifies the machine and account the server runs as, whichever address reached it. */
    val serverHostId: String? = null,
    val apiRevision: Int? = null,
    val sessions: List<SessionSummary> = emptyList(),
    val selectedSessionId: String? = null,
    /** The selected session's layout. */
    val view: SessionView? = null,
    /** The live terminals of the selected session's active window, by block id. */
    val terminals: Map<String, TerminalSession> = emptyMap(),
)

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
    /** Called for the server's own events, such as `keymap_changed`, and with `connected` after each connect. */
    private val onServerEvent: (type: String) -> Unit = {},
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val mutableState = MutableStateFlow(HostState())
    val state: StateFlow<HostState> get() = mutableState

    /** Work for the control connection, done one at a time in the order it was asked for. */
    private val actions = Channel<suspend (RexControl) -> Unit>(Channel.UNLIMITED)
    private val retryNow = Channel<Unit>(Channel.CONFLATED)
    private val desiredGrids = ConcurrentHashMap<String, GridSize>()
    /** Blocks claimed on this connection, until the server's view says otherwise. */
    private val claimed = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var terminalColors: TerminalColors? = null
    @Volatile private var control: RexControl? = null
    private val endpoint: RexEndpoint? = runCatching { RexEndpoint.parse(saved.endpoint) }.getOrNull()

    init {
        scope.launch { run() }
    }

    // What the user can do. Each is queued and runs in order on the control connection.

    /** Shows another session. */
    fun selectSession(sessionId: String) = act("select session") { attach(it, sessionId) }

    /** Makes a window the session's active one. That is the session's own state, shared by every client on it. */
    fun focusWindow(windowId: String) = act("focus tab") { control ->
        control.call("session.focus_window", sessionPayload { put("window_id", windowId) })
    }

    /** Moves to the tab after or before the active one. */
    fun stepWindow(forward: Boolean) = act("step tab") { control ->
        control.call(if (forward) "session.focus_next_window" else "session.focus_previous_window", sessionPayload {})
    }

    /** Opens a tab with a shell in it, and shows it. */
    fun newTab() = act("new tab") { control ->
        val created = control.call("session.new_window", sessionPayload { put("layout", SHELL_LAYOUT) })
        log.add(saved.label, "created tab ${created.string("window_id")}")
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
        control.call("block.close", sessionPayload { put("block_id", blockId) })
    }

    /** Closes a tab and every pane in it. */
    fun closeWindow(windowId: String) = act("close tab") { control ->
        control.call("session.close_window", sessionPayload { put("window_id", windowId) })
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
        control.call("session.focus_direction", sessionPayload { put("direction", direction) })
    }

    /** Moves focus to the next or previous pane of the tab. */
    fun stepPane(forward: Boolean) = act("step pane") { control ->
        control.call(if (forward) "session.focus_next_pane" else "session.focus_previous_pane", sessionPayload {})
    }

    /** Runs one of the server's own actions, such as clearing a terminal, on a pane. */
    fun performServerAction(name: String, args: JsonObject?, blockId: String?) = act(name) { control ->
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
            current.terminals[blockId]?.scrollToBottom()
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
        terminal.scrollToBottom()
        val data = String(encodePaste(text, terminal.bracketedPaste), Charsets.UTF_8)
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
    private fun act(what: String?, action: suspend (RexControl) -> Unit) {
        actions.trySend { control ->
            try {
                action(control)
                if (what != null) log.add(saved.label, "→ $what")
            } catch (refused: RexError) {
                log.add(saved.label, "✗ ${what ?: "request"}: ${refused.message}", isError = true)
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
    }

    private suspend fun run() {
        val endpoint = endpoint ?: run {
            val reason = runCatching { RexEndpoint.parse(saved.endpoint) }.exceptionOrNull()?.message ?: "Invalid endpoint"
            log.add(saved.label, reason, isError = true)
            mutableState.update { it.copy(connection = HostConnection.Disconnected(reason, permanent = true)) }
            return
        }
        var reconnectToken: String? = null
        var attempt = 0
        while (true) {
            mutableState.update { it.copy(connection = HostConnection.Connecting) }
            log.add(saved.label, "connecting to ${saved.endpoint}")
            val reason = try {
                coroutineScope {
                    val connection = RexControl.connect(http, endpoint, this, info, reconnectToken)
                    reconnectToken = connection.reconnectToken
                    try {
                        serve(connection, endpoint)
                        attempt = 0
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
            mutableState.value.terminals.values.forEach { it.close() }
            mutableState.update { it.copy(connection = HostConnection.Disconnected(reason), view = null, terminals = emptyMap()) }
            val delayMillis = RETRY_DELAYS_MILLIS[minOf(attempt++, RETRY_DELAYS_MILLIS.lastIndex)]
            log.add(saved.label, "disconnected: $reason. Trying again in ${delayMillis / 1000.0}s", isError = true)
            withTimeoutOrNull(delayMillis) { retryNow.receive() }
        }
    }

    /** Sets a new connection up, then handles its events and this host's actions until it ends. */
    private suspend fun serve(connection: RexControl, endpoint: RexEndpoint) {
        // One request tells this client everything it needs to draw the host, and turns on
        // events for every session. Attaching to none here: the session to show is chosen next.
        val boot = withTimeout(BOOTSTRAP_TIMEOUT_MILLIS) {
            connection.call("session.bootstrap", buildJsonObject {
                put("event_scope", "session")
                put("attach_session_ids", JsonArray(emptyList()))
            })
        }
        val sessions = parseSessions(boot.obj("attach_all")?.get("sessions") as? JsonArray)
        mutableState.update {
            it.copy(
                connection = HostConnection.Connected,
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
            connection.closed.await()
            events.cancel()
            work.cancel()
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
    }

    /** Takes a new layout, and makes the open terminals match the blocks it shows. */
    private fun showView(connection: RexControl, view: SessionView) {
        val endpoint = endpoint ?: return
        val window = view.activeWindow
        val visible = window?.panes.orEmpty()
            .filter { it.isTerminal && (window?.zoomedBlockId == null || it.blockId == window.zoomedBlockId) }
        // The server's word on who owns a block replaces this connection's own bookkeeping.
        claimed.removeAll { blockId ->
            val owner = view.windows.firstNotNullOfOrNull { w -> w.panes.firstOrNull { it.blockId == blockId } }?.ownerClientId
            owner != connection.clientId
        }
        mutableState.update { current ->
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
            current.copy(view = view, terminals = terminals)
        }
    }

    private fun openTerminal(connection: RexControl, endpoint: RexEndpoint, sessionId: String, blockId: String) =
        TerminalSession(sessionId, blockId, scope, terminalColors, log = { log.add(saved.label, "${short(blockId)} $it") }) {
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
        log.add(saved.label, "event ${describe(event, current)}")
        when (event.type) {
            // Says only that the layout changed, so fetch it, unless this one is already known.
            "session_view_changed" -> if (event.sessionId == current.selectedSessionId) {
                val revision = event.payload.long("revision") ?: Long.MAX_VALUE
                if (revision > (current.view?.revision ?: -1)) {
                    val view = connection.call("session.view", buildJsonObject { put("session_id", event.sessionId) })
                    if (state.value.selectedSessionId == event.sessionId) showView(connection, parseSessionView(view))
                }
            }
            "session_created", "session_destroyed", "session_label_changed" -> {
                val sessions = parseSessions(connection.call("session.list")["sessions"] as? JsonArray)
                mutableState.update { it.copy(sessions = sessions) }
                if (sessions.none { it.id == state.value.selectedSessionId }) {
                    // The session being shown is gone. Show another rather than nothing.
                    state.value.terminals.values.forEach { it.close() }
                    mutableState.update { it.copy(selectedSessionId = null, view = null, terminals = emptyMap()) }
                    sessions.firstOrNull()?.let { attach(connection, it.id) }
                }
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
    private fun describe(failure: Exception): String = when (failure) {
        is java.nio.channels.UnresolvedAddressException -> "Host name not found"
        is kotlinx.coroutines.TimeoutCancellationException -> "The server didn't answer in time"
        is java.nio.file.NoSuchFileException -> "No server is listening at ${failure.file}"
        else -> failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
    }

    private companion object {
        const val BOOTSTRAP_TIMEOUT_MILLIS = 8_000L
        val RETRY_DELAYS_MILLIS = longArrayOf(500, 1_000, 2_000, 5_000, 10_000)
        val SHELL_LAYOUT = buildJsonObject { putJsonObject("block") { put("flavor", "$TERMINAL_CREATOR.shell") } }

        /** `block:01a1eb49-4eac-…` is too long to read in a log; its kind and last few characters identify it. */
        fun short(id: String): String = id.substringBefore(':') + ":…" + id.takeLast(6)
    }
}
