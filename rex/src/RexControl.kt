package org.example.kotlintest.rex

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The server answered a request with an error. Branch on [code]; [message] is for people. */
class RexError(val code: String, message: String, val status: Int) : RuntimeException("$code: $message")

/** The control connection ended while a request was waiting, or before it could be sent. */
class RexDisconnected(message: String) : RuntimeException(message)

/** Something the server reported without being asked. */
class RexEvent(
    val type: String,
    val payload: JsonObject,
    /** The session it happened in, for a session's own events. */
    val sessionId: String?,
)

/** How this program introduces itself to servers. */
class RexClientInfo(val name: String, val version: String, val label: String? = null) {
    /** The same program, told apart from others like it by [label], such as the device it is on. */
    fun labelled(label: String?) = RexClientInfo(name, version, label?.let(::fitted))

    private companion object {
        /** A server takes a label of up to 64 bytes. */
        const val MAX_LABEL_BYTES = 64

        fun fitted(label: String): String {
            var end = label.length
            while (end > 0 && label.substring(0, end).encodeToByteArray().size > MAX_LABEL_BYTES) end--
            return label.substring(0, end)
        }
    }
}

/** This app, as it introduces itself. */
val TonoClient = RexClientInfo(name = "Tono", version = "0.1.0")

/**
 * The control connection to one server: a WebSocket carrying one JSON object per message.
 * Requests are matched to responses by id, so any number can be in flight at once.
 */
class RexControl private constructor(
    private val socket: DefaultClientWebSocketSession,
    val welcome: JsonObject,
) {
    companion object {
        /** The API revisions this client understands. The server picks the newest one both sides speak. */
        const val API_REVISION = 12
        const val API_MIN_REVISION = 10

        /**
         * Opens the connection and exchanges hello and welcome.
         *
         * @param reconnectToken the token from the last welcome, to carry on as the same client
         *   (keeping its attachments and block ownership) after a dropped connection
         */
        suspend fun connect(
            client: HttpClient,
            endpoint: RexEndpoint,
            scope: CoroutineScope,
            info: RexClientInfo,
            reconnectToken: String? = null,
        ): RexControl {
            val socket = within(CONNECT_TIMEOUT_MILLIS) { client.rexWebSocket(endpoint, "/api/control", "rex.control.v1") }
            val hello = buildJsonObject {
                put("type", "hello")
                put("api_revision", API_REVISION)
                put("api_min_revision", API_MIN_REVISION)
                reconnectToken?.let { put("reconnect_token", it) }
                putJsonObject("client") {
                    put("kind", "app")
                    put("platform", clientPlatform)
                    put("name", info.name)
                    put("version", info.version)
                    info.label?.let { put("label", it) }
                }
            }
            socket.send(Frame.Text(hello.toString()))
            val first = within(CONNECT_TIMEOUT_MILLIS) { socket.incoming.receive() } as? Frame.Text
                ?: throw RexDisconnected("The server didn't send a welcome")
            val welcome = Json.parseToJsonElement(first.readText()).jsonObject
            if (welcome.string("type") != "welcome" || welcome.string("protocol") != "rex.control.v1") {
                throw RexDisconnected("Unexpected first message from the server")
            }
            return RexControl(socket, welcome).also { it.start(scope) }
        }

        private const val CONNECT_TIMEOUT_MILLIS = 10_000L

        /**
         * How long a server may say nothing before it is asked whether it is there, and how
         * long it then has to say anything at all. A machine that goes to sleep, or drops off
         * the network, closes nothing: its connection looks open for as long as nobody writes
         * to it, and for many minutes after. Together these are about what Rex Rune allows a
         * silent server (30 seconds), and far less than the server's own nine minutes.
         */
        const val QUIET_MILLIS = 10_000L
        const val PATIENCE_MILLIS = 10_000L
    }

    /** Counts what has arrived, so that the watch below can tell a quiet server from a silent one. */
    @Volatile
    private var heard = 0L

    private fun start(scope: CoroutineScope) {
        val reading = scope.launch { readLoop() }
        val watching = scope.launch {
            awaitSilence(
                heard = { heard },
                // Anything it sends back will do, a refusal included. The answer is dropped.
                ask = { try { request("server.status") } catch (_: RexDisconnected) {} },
            )
            val reason = "No answer from the server for ${(QUIET_MILLIS + PATIENCE_MILLIS) / 1000} seconds"
            reading.cancel(CancellationException(reason))
            // The other end isn't there to finish a polite close with.
            socket.cancel(CancellationException(reason))
        }
        reading.invokeOnCompletion { watching.cancel() }
    }

    private val identity: JsonObject? = welcome["client"] as? JsonObject
    val clientId: String? = identity?.string("id")
    /** Never log this. */
    val reconnectToken: String? = identity?.string("reconnect_token")
    val apiRevision: Int = welcome["api_revision"]?.jsonPrimitive?.int ?: 0

    private val nextId = Counter()
    private val pending = concurrentMap<String, CompletableDeferred<JsonObject>>()
    private val eventChannel = Channel<RexEvent>(Channel.UNLIMITED)
    private val ended = CompletableDeferred<String>()

    /** Events, in the order the server sent them. Closed when the connection ends. */
    val events: ReceiveChannel<RexEvent> get() = eventChannel

    /** Completes with the reason once the connection has ended. */
    val closed: Deferred<String> get() = ended

    /** Calls a method and waits for its result. Throws [RexError] if the server refuses it. */
    suspend fun call(method: String, payload: JsonObject = EMPTY): JsonObject = request(method, payload).await()

    /**
     * Sends a request now and returns its eventual result. Requests are sent in the order
     * this is called, which is what keeps typed keys in order.
     */
    suspend fun request(method: String, payload: JsonObject = EMPTY): Deferred<JsonObject> {
        val id = nextId.next().toString()
        val result = CompletableDeferred<JsonObject>()
        pending[id] = result
        val message = buildJsonObject {
            put("type", "request")
            put("id", id)
            put("method", method)
            put("payload", payload)
        }
        try {
            socket.send(Frame.Text(message.toString()))
        } catch (failure: Exception) {
            pending.remove(id)
            throw RexDisconnected("Not connected: ${failure.message}")
        }
        if (ended.isCompleted) pending.remove(id)?.completeExceptionally(RexDisconnected("Connection closed"))
        return result
    }

    /** Calls a method that a block provides, such as `com.superlogical.terminal.resize`. */
    suspend fun callBlock(method: String, sessionId: String, blockId: String, args: JsonObject = EMPTY): JsonObject =
        call(method, buildJsonObject {
            put("session_id", sessionId)
            put("block_id", blockId)
            put("args", args)
        })

    private suspend fun readLoop() {
        var reason = "Connection closed"
        try {
            for (frame in socket.incoming) {
                heard++
                // Skip anything unknown: a newer server may send message types this client predates.
                val message = (frame as? Frame.Text)?.let { runCatching { Json.parseToJsonElement(it.readText()).jsonObject }.getOrNull() } ?: continue
                when (message.string("type")) {
                    "response" -> pending.remove(message.string("id"))?.let { waiting ->
                        val error = message["error"] as? JsonObject
                        if (error != null) {
                            waiting.completeExceptionally(
                                RexError(error.string("code") ?: "internal", error.string("message").orEmpty(), error["status"]?.jsonPrimitive?.int ?: 500),
                            )
                        } else {
                            waiting.complete(message["payload"] as? JsonObject ?: EMPTY)
                        }
                    }
                    "event" -> (message["payload"] as? JsonObject)?.let(::toEvent)?.let { eventChannel.trySend(it) }
                }
            }
            socket.closeReason.await()?.let { reason = it.message.ifEmpty { "Connection closed (${it.code})" } }
        } catch (failure: Exception) {
            reason = failure.message ?: failure.toString()
        } finally {
            ended.complete(reason)
            eventChannel.close()
            pending.values.forEach { it.completeExceptionally(RexDisconnected(reason)) }
            pending.clear()
        }
    }

    /** A session's events arrive wrapped in a `session_event` that names the session. */
    private fun toEvent(outer: JsonObject): RexEvent? {
        val type = outer.string("type") ?: return null
        val payload = outer["payload"] as? JsonObject ?: EMPTY
        if (type != "session_event") return RexEvent(type, payload, sessionId = null)
        val inner = payload["event"] as? JsonObject ?: return null
        return RexEvent(inner.string("type") ?: return null, inner["payload"] as? JsonObject ?: EMPTY, payload.string("session_id"))
    }

    /** Closing with a close frame tells the server this client is gone for good. */
    suspend fun close() = runCatching { socket.close(CloseReason(CloseReason.Codes.NORMAL, "")) }
}

/**
 * Runs [block], giving up after [millis] with a [RexDisconnected].
 *
 * `withTimeout` alone reports running out of time as a cancellation, and everything here that
 * tries again after a failure passes cancellations on, as it must when the app is closing. So
 * a server that accepted a connection and then said nothing ended the trying for good, with
 * the host left "connecting" and never marked as down. A timeout is a failure like any other.
 */
internal suspend fun <T> within(millis: Long, block: suspend CoroutineScope.() -> T): T =
    try {
        withTimeout(millis, block)
    } catch (_: TimeoutCancellationException) {
        throw RexDisconnected("The server didn't answer in time")
    }

/**
 * Returns once the other end has gone silent: nothing arrived for [quietMillis], it was then
 * asked something, and still nothing arrived for [patienceMillis]. Never returns while it
 * keeps talking.
 *
 * Time is counted in steps of waiting, not read off a clock, so a machine that was itself
 * asleep for an hour counts that as one step and gives the server its full time to answer.
 *
 * @param heard how much has arrived so far; any change means the other end is there
 * @param ask sends something the other end will answer
 */
internal suspend fun awaitSilence(
    heard: () -> Long,
    ask: suspend () -> Unit,
    quietMillis: Long = RexControl.QUIET_MILLIS,
    patienceMillis: Long = RexControl.PATIENCE_MILLIS,
    stepMillis: Long = 1_000L,
) {
    var seen = heard()
    var quiet = 0L
    var asked = false
    while (quiet < quietMillis + patienceMillis) {
        delay(stepMillis)
        val now = heard()
        if (now != seen) {
            seen = now
            quiet = 0
            asked = false
            continue
        }
        quiet += stepMillis
        if (!asked && quiet >= quietMillis) {
            asked = true
            ask()
        }
    }
}

internal val EMPTY = JsonObject(emptyMap())

internal fun JsonObject.string(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.long(key: String): Long? = runCatching { this[key]?.jsonPrimitive?.long }.getOrNull()
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
