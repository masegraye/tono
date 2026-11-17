package org.example.kotlintest.rexspike

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.ClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.unixSocket
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

// The protocol is described by the server itself: GET /llms.txt on its socket.

/** Where the local Rex server listens: `REX_SERVER`, or the default per-user socket. */
fun rexSocketPath(): String =
    System.getenv("REX_SERVER")?.removePrefix("unix://")
        ?: "${System.getenv("XDG_RUNTIME_DIR") ?: "/run/user/1000"}/rex/server.sock"

fun rexHttpClient(): HttpClient = HttpClient(CIO) { install(WebSockets) }

private suspend fun HttpClient.rexWebSocket(socketPath: String, path: String, subprotocol: String): ClientWebSocketSession =
    webSocketSession {
        // Over a Unix socket the host name is ignored.
        url("ws://localhost$path")
        unixSocket(socketPath)
        header(HttpHeaders.SecWebSocketProtocol, subprotocol)
    }

class RexError(val code: String, message: String) : RuntimeException("$code: $message")

/** The control connection: one JSON object per message, requests matched to responses by id. */
class RexControl private constructor(private val socket: ClientWebSocketSession, val welcome: JsonObject) {

    companion object {
        suspend fun connect(client: HttpClient, socketPath: String): RexControl {
            val socket = client.rexWebSocket(socketPath, "/api/control", "rex.control.v1")
            val hello = buildJsonObject {
                put("type", "hello")
                put("api_revision", 12)
                put("api_min_revision", 10)
                putJsonObject("client") {
                    put("kind", "other")
                    put("platform", "linux")
                    put("name", "kotlin-test spike")
                    put("version", "0.0.1")
                }
            }
            socket.send(Frame.Text(hello.toString()))
            val welcome = Json.parseToJsonElement((socket.incoming.receive() as Frame.Text).readText()).jsonObject
            check(welcome["type"]?.jsonPrimitive?.content == "welcome") { "Expected a welcome, got $welcome" }
            return RexControl(socket, welcome)
        }
    }

    private var nextId = 1

    /** Events that arrived while waiting for responses. */
    var eventsSeen = 0
        private set

    /** Calls a method and waits for its response. One call at a time. */
    suspend fun call(method: String, payload: JsonObject = JsonObject(emptyMap())): JsonElement {
        val id = (nextId++).toString()
        val request = buildJsonObject {
            put("type", "request")
            put("id", id)
            put("method", method)
            put("payload", payload)
        }
        socket.send(Frame.Text(request.toString()))
        while (true) {
            val frame = socket.incoming.receive() as? Frame.Text ?: continue
            val message = Json.parseToJsonElement(frame.readText()).jsonObject
            when (message["type"]?.jsonPrimitive?.content) {
                "event" -> eventsSeen++
                "response" -> if (message["id"]?.jsonPrimitive?.content == id) {
                    message["error"]?.jsonObject?.let { error ->
                        throw RexError(error.getValue("code").jsonPrimitive.content, error.getValue("message").jsonPrimitive.content)
                    }
                    return message["payload"] ?: JsonObject(emptyMap())
                }
            }
        }
    }

    suspend fun close() = socket.close(CloseReason(CloseReason.Codes.NORMAL, ""))
}

/**
 * A data connection: the bytes one terminal block produces. The stream starts with a Ghostty
 * snapshot of the terminal, then carries everything its program writes.
 */
class RexTerminalStream private constructor(
    private val socket: ClientWebSocketSession,
    /** Marks the server's own messages in the stream; see [rexMessages]. */
    val nonce: ULong,
    val resumeWindowMillis: Long,
) {
    companion object {
        /** Opens the stream that [ticket] (from `data.connect`) was issued for. */
        suspend fun open(client: HttpClient, socketPath: String, ticket: String): RexTerminalStream {
            val socket = client.rexWebSocket(socketPath, "/api/data", SUBPROTOCOL)
            val ticketBytes = Base64.getUrlDecoder().decode(ticket)
            check(ticketBytes.size == 16) { "A ticket is 16 bytes, got ${ticketBytes.size}" }
            socket.send(Frame.Binary(true, byteArrayOf(HELLO) + ticketBytes))

            val answer = (socket.incoming.receive() as Frame.Binary).readBytes()
            if (answer[0] == REJECT) error("The server rejected the data connection, reason ${answer[1]}")
            // WELCOME: 0x81, 16-byte reconnect token, 3 zero bytes, u32 resume window (ms), u64 nonce
            check(answer[0] == WELCOME && answer.size == 32) { "Unexpected handshake answer of ${answer.size} bytes" }
            return RexTerminalStream(
                socket,
                nonce = littleEndian(answer, 24, 8).toULong(),
                resumeWindowMillis = littleEndian(answer, 20, 4),
            )
        }

        const val SUBPROTOCOL = "rex.data.terminal.v1"
        private const val HELLO: Byte = 0x01
        private const val WELCOME: Byte = 0x81.toByte()
        private const val REJECT: Byte = 0x82.toByte()
    }

    /** The next chunk of the stream, or null if none arrives within [timeoutMillis]. */
    suspend fun read(timeoutMillis: Long): ByteArray? = withTimeoutOrNull(timeoutMillis) {
        var chunk: ByteArray? = null
        while (chunk == null) chunk = (socket.incoming.receive() as? Frame.Binary)?.readBytes()
        chunk
    }

    /** True when no further chunk is waiting right now. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun peekIdle(): Boolean = socket.incoming.isEmpty

    /** Closing with a normal status ends the stream on the server. */
    suspend fun close() = socket.close(CloseReason(CloseReason.Codes.NORMAL, ""))
}

/**
 * The server's own messages in a terminal stream: `ESC _ rex1;<nonce>;<json> ESC \`.
 * One whose nonce doesn't match was printed by a program and is not the server's.
 */
fun rexMessages(stream: ByteArray, offset: Int, length: Int, nonce: ULong): List<JsonObject> {
    val text = String(stream, offset, length, Charsets.ISO_8859_1)
    return Regex("\u001b_rex1;(\\d+);(.*?)\u001b\\\\").findAll(text)
        .filter { it.groupValues[1] == nonce.toString() }
        .mapNotNull { match ->
            val json = String(match.groupValues[2].toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
            runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
        }
        .toList()
}

internal fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content
internal fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.int
