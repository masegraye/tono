package org.example.kotlintest.rex

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import kotlinx.coroutines.CoroutineDispatcher

/*
 * What the client needs from wherever it runs. On the desktop these are sockets, threads and
 * files. In a browser there is one thread, storage is the browser's own, and a connection
 * can only be made through the server the page came from.
 */

/** One HTTP client serves every connection the app makes. */
expect fun rexHttpClient(): HttpClient

/** Opens a WebSocket to a Rex server's [path], speaking [subprotocol]. */
internal expect suspend fun HttpClient.rexWebSocket(endpoint: RexEndpoint, path: String, subprotocol: String): DefaultClientWebSocketSession

/** Where terminals do their work. libghostty-vt isn't thread-safe, so it is one thread at most. */
internal expect val terminalDispatcher: CoroutineDispatcher

/** A map and a set that may be used from several threads at once, where there are several. */
internal expect fun <K, V> concurrentMap(): MutableMap<K, V>
internal expect fun <T> concurrentSet(): MutableSet<T>

/** Runs [block] with no other thread inside a block locked on the same [lock]. */
internal expect inline fun <T> locked(lock: Any, block: () -> T): T

/** Hands out 1, 2, 3 and so on, each once, whoever asks. */
internal expect class Counter() {
    fun next(): Long
}

/** Milliseconds since the start of 1970. */
expect fun nowMillis(): Long

/** A reason a connection couldn't be made, for the failures only this platform can name. Null for the rest. */
internal expect fun connectFailureMessage(failure: Exception): String?

/** The address of the Rex server on the machine the app is for: the first host a new workspace has. */
expect fun defaultLocalEndpoint(): String

/** The workspace kept where this platform keeps things, when the app isn't told to use another. */
expect fun defaultWorkspace(): Workspace

/**
 * The Rex servers on the tailnet, those that are up first. Empty when they can't be found
 * out: discovery is a convenience, and an address can always be typed.
 */
expect suspend fun tailnetRexServers(): List<TailnetServer>

/** Bytes as a string of one character each, for input that is bytes and has to travel as text. */
internal fun ByteArray.latin1(): String = CharArray(size) { (this[it].toInt() and 0xFF).toChar() }.concatToString()

/** Bytes gathered a piece at a time. */
internal class ByteCollector {
    private var buffer = ByteArray(8192)
    var size = 0
        private set

    fun write(bytes: ByteArray) {
        if (size + bytes.size > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + bytes.size))
        bytes.copyInto(buffer, size)
        size += bytes.size
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}

/** Reads base64 in the URL alphabet, with or without padding at the end. */
internal fun decodeBase64Url(text: String): ByteArray {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val digits = text.trimEnd('=')
    val out = ByteArray(digits.length * 6 / 8)
    var bits = 0
    var held = 0
    var at = 0
    for (char in digits) {
        val value = alphabet.indexOf(char)
        require(value >= 0) { "Not base64: $char" }
        held = (held shl 6) or value
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out[at++] = (held shr bits).toByte()
        }
    }
    return out
}
