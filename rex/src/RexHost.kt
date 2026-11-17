package org.example.kotlintest.rex

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.kotlintest.ghostty.TerminalColors
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

/**
 * One Rex server, and the app's connection to it.
 *
 * It connects as soon as it is created and keeps the connection up: on any failure it
 * waits and tries again, carrying on as the same client where the server still remembers
 * it. Each time it connects it reads everything afresh in one `session.bootstrap`, selects
 * the session it had (or [rememberedSession], or the first listed), and opens the
 * terminals that session shows. It never creates or destroys a session by itself.
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
    private val onSessionSelected: (sessionId: String) -> Unit = {},
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

    init {
        scope.launch { run() }
    }

    /** Shows another session. Nothing is created: the session must be in [HostState.sessions]. */
    fun selectSession(sessionId: String) = act { attach(it, sessionId) }

    /** Makes a window the session's active one. This is the session's own state, shared by every client on it. */
    fun focusWindow(windowId: String) = act { control ->
        val sessionId = state.value.selectedSessionId ?: return@act
        control.call("session.focus_window", buildJsonObject {
            put("session_id", sessionId)
            put("window_id", windowId)
        })
    }

    /**
     * Types a key into a block.
     *
     * Looking at a terminal is passive, but pressing a key in one claims it: the block then
     * takes its size from this client, as it would from any Rex client being typed into.
     */
    fun sendKey(blockId: String, key: RexKey) = act { control ->
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

    /**
     * Says how large a block's terminal would be to fill the space this app gives it.
     * It takes effect only for blocks this client owns.
     */
    fun setDesiredGrid(blockId: String, grid: GridSize) {
        if (desiredGrids.put(blockId, grid) == grid) return
        act { control ->
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

    fun close() = scope.cancel()

    private fun act(action: suspend (RexControl) -> Unit) { actions.trySend(action) }

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
                put("block_ids", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(blockId)) })
                put("mode", "take")
            })
        }.isSuccess
        if (!taken) return
        claimed += blockId
        desiredGrids[blockId]?.let { resize(control, sessionId, blockId, it) }
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
        }
    }

    private suspend fun run() {
        val endpoint = try {
            RexEndpoint.parse(saved.endpoint)
        } catch (invalid: IllegalArgumentException) {
            mutableState.update { it.copy(connection = HostConnection.Disconnected(invalid.message ?: "Invalid endpoint", permanent = true)) }
            return
        }
        var reconnectToken: String? = null
        var attempt = 0
        while (true) {
            mutableState.update { it.copy(connection = HostConnection.Connecting) }
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
            withTimeoutOrNull(RETRY_DELAYS_MILLIS[minOf(attempt++, RETRY_DELAYS_MILLIS.lastIndex)]) { retryNow.receive() }
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
        control = connection
        chooseSession(sessions)?.let { attach(connection, it, endpoint) }

        coroutineScope {
            val events = launch {
                for (event in connection.events) runCatching { handleEvent(connection, event, endpoint) }
            }
            val work = launch {
                for (action in actions) {
                    try {
                        action(connection)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // One refused request shouldn't take the connection down with it.
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

    private suspend fun attach(connection: RexControl, sessionId: String, endpoint: RexEndpoint = RexEndpoint.parse(saved.endpoint)) {
        val previous = state.value.selectedSessionId
        if (previous != null && previous != sessionId) {
            runCatching { connection.call("session.detach", buildJsonObject { put("session_id", previous) }) }
        }
        val response = connection.call("session.attach", buildJsonObject { put("session_id", sessionId) })
        val view = parseSessionView(response.obj("view") ?: response)
        mutableState.update { it.copy(selectedSessionId = sessionId) }
        showView(connection, view, endpoint)
        onSessionSelected(sessionId)
    }

    /** Takes a new layout, and makes the open terminals match the blocks it shows. */
    private fun showView(connection: RexControl, view: SessionView, endpoint: RexEndpoint) {
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
        TerminalSession(sessionId, blockId, scope, terminalColors) {
            val ticket = connection.call("data.connect", buildJsonObject {
                put("session_id", sessionId)
                put("block_id", blockId)
                put("data_ref_id", "terminal")
                put("subprotocol", RexTerminalStream.SUBPROTOCOL)
            }).string("ticket") ?: error("The server issued no ticket for the terminal")
            RexTerminalStream.open(http, endpoint, ticket)
        }

    private suspend fun handleEvent(connection: RexControl, event: RexEvent, endpoint: RexEndpoint) {
        val current = state.value
        when (event.type) {
            // Says only that the layout changed, so fetch it, unless this one is already known.
            "session_view_changed" -> if (event.sessionId == current.selectedSessionId) {
                val revision = event.payload.long("revision") ?: Long.MAX_VALUE
                if (revision > (current.view?.revision ?: -1)) {
                    val view = connection.call("session.view", buildJsonObject { put("session_id", event.sessionId) })
                    if (state.value.selectedSessionId == event.sessionId) showView(connection, parseSessionView(view), endpoint)
                }
            }
            "session_created", "session_destroyed", "session_label_changed" -> {
                val sessions = parseSessions(connection.call("session.list")["sessions"] as? JsonArray)
                mutableState.update { it.copy(sessions = sessions) }
                if (sessions.none { it.id == state.value.selectedSessionId }) {
                    // The session being shown is gone. Show another rather than nothing.
                    state.value.terminals.values.forEach { it.close() }
                    mutableState.update { it.copy(selectedSessionId = null, view = null, terminals = emptyMap()) }
                    sessions.firstOrNull()?.let { attach(connection, it.id, endpoint) }
                }
            }
        }
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
    }
}
