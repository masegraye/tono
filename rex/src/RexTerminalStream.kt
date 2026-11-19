package org.example.kotlintest.rex

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The server answered a data connection's opening with a REJECT. [reason] is its code, as the
 * server's own description of the protocol lists them (GET /llms.txt, "Handshake Messages"):
 *
 * 1. the ticket is unknown, expired, already used or someone else's, or its session is gone;
 * 2. this client already has a connection to that block's stream, and may have only one;
 * 3. the stream isn't available, such as when its block has closed;
 * 4. opening it would pass a limit, so try again later;
 * 5. the handshake was malformed or late;
 * 6. the server failed;
 * 7. the ticket was issued for another subprotocol.
 */
class RexStreamRefused(val reason: Int?) : RuntimeException("The server refused the terminal stream (reason $reason${explained(reason)})") {
    /**
     * Whether asking again a little later, with a new ticket, may be answered differently.
     * A stream this client still has open (2) is one it is closing: a terminal left and come
     * back to at once is opened again while the server hasn't yet taken the old stream down.
     */
    val passes: Boolean get() = reason == ALREADY_OPEN || reason == LIMIT

    private companion object {
        const val ALREADY_OPEN = 2
        const val LIMIT = 4

        fun explained(reason: Int?) = when (reason) {
            1 -> ": the ticket wasn't accepted"
            ALREADY_OPEN -> ": this client already has it open"
            3 -> ": the terminal isn't available"
            LIMIT -> ": too many are open"
            6 -> ": the server failed"
            else -> ""
        }
    }
}

/**
 * A data connection: the bytes one terminal block produces. The stream opens with a Ghostty
 * snapshot of the terminal, then carries everything its program writes, with the server's
 * own messages mixed in where they are safe to apply (see [RexStreamScanner]).
 */
class RexTerminalStream private constructor(
    private val socket: DefaultClientWebSocketSession,
    /** Marks the server's own messages in the stream. */
    val nonce: ULong,
) {
    companion object {
        const val SUBPROTOCOL = "rex.data.terminal.v1"

        /** Opens the stream that [ticket] (from `data.connect`) was issued for. A ticket works once. */
        suspend fun open(client: HttpClient, endpoint: RexEndpoint, ticket: String, timeoutMillis: Long = 10_000): RexTerminalStream {
            val socket = within(timeoutMillis) { client.rexWebSocket(endpoint, "/api/data", SUBPROTOCOL) }
            return socket.orDrop {
                val ticketBytes = decodeBase64Url(ticket)
                check(ticketBytes.size == 16) { "A ticket is 16 bytes, got ${ticketBytes.size}" }
                socket.send(Frame.Binary(true, byteArrayOf(HELLO) + ticketBytes))

                val answer = (within(timeoutMillis) { socket.incoming.receive() } as? Frame.Binary)?.readBytes()
                    ?: error("The server didn't answer the data handshake")
                if (answer.isNotEmpty() && answer[0] == REJECT) throw RexStreamRefused(answer.getOrNull(1)?.toInt()?.and(0xFF))
                // WELCOME: 0x81, 16-byte reconnect token, 3 zero bytes, u32 resume window (ms), u64 nonce
                check(answer.size == 32 && answer[0] == WELCOME) { "Unexpected data handshake answer of ${answer.size} bytes" }
                RexTerminalStream(socket, nonce = littleEndian(answer, 24, 8).toULong())
            }
        }

        private const val HELLO: Byte = 0x01
        private const val WELCOME: Byte = 0x81.toByte()
        private const val REJECT: Byte = 0x82.toByte()
    }

    /** The stream's chunks, as binary frames. Where one ends and the next begins means nothing. */
    internal val frames: ReceiveChannel<Frame> get() = socket.incoming

    /** True when no further chunk is waiting right now. */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal val idle: Boolean get() = socket.incoming.isEmpty

    /** Why the server closed the stream, once [frames] has ended. */
    internal suspend fun closeReason(): CloseReason? = socket.closeReason.await()

    /** Closing with a normal status ends the stream on the server. */
    suspend fun close() = runCatching { socket.close(CloseReason(CloseReason.Codes.NORMAL, "")) }
}

/**
 * Separates the server's own messages from a terminal's output.
 *
 * A message is an APC escape sequence, `ESC _ rex1;<nonce>;<json> ESC \`. Its position
 * matters (a resize applies to the output after it, not before), so [feed] reports output
 * and messages in stream order. A look-alike with another nonce was printed by a program
 * and is passed through as output.
 */
internal class RexStreamScanner(nonce: ULong) {
    private val nonceText = nonce.toString()
    private var carry = ByteArray(0)

    fun feed(
        chunk: ByteArray,
        offset: Int,
        onOutput: (bytes: ByteArray, offset: Int, length: Int) -> Unit,
        onMessage: (JsonObject) -> Unit,
    ) {
        val data: ByteArray
        var position: Int
        if (carry.isEmpty()) {
            data = chunk
            position = offset
        } else {
            data = carry + chunk.copyOfRange(offset, chunk.size)
            position = 0
            carry = ByteArray(0)
        }
        while (position < data.size) {
            val start = indexOf(data, MARKER, position)
            if (start < 0) {
                // The chunk may end partway through a marker; hold that much back.
                val held = partialMarkerAtEnd(data, position)
                if (data.size - held > position) onOutput(data, position, data.size - held - position)
                carry = data.copyOfRange(data.size - held, data.size)
                return
            }
            if (start > position) onOutput(data, position, start - position)
            val end = indexOf(data, TERMINATOR, start + MARKER.size)
            if (end < 0) {
                if (data.size - start > MAX_MESSAGE_BYTES) {
                    onOutput(data, start, data.size - start) // not a message after all
                } else {
                    carry = data.copyOfRange(start, data.size) // the rest of it is still on its way
                }
                return
            }
            val body = data.decodeToString(start + MARKER.size, end)
            val message = body.substringAfter(';', "").takeIf { body.substringBefore(';') == nonceText }
                ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            if (message != null) onMessage(message) else onOutput(data, start, end + TERMINATOR.size - start)
            position = end + TERMINATOR.size
        }
    }

    private fun partialMarkerAtEnd(data: ByteArray, from: Int): Int {
        for (length in minOf(MARKER.size - 1, data.size - from) downTo 1) {
            if ((0 until length).all { data[data.size - length + it] == MARKER[it] }) return length
        }
        return 0
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int): Int {
        outer@ for (index in from..data.size - pattern.size) {
            for (offset in pattern.indices) if (data[index + offset] != pattern[offset]) continue@outer
            return index
        }
        return -1
    }

    private companion object {
        val MARKER = "\u001b_rex1;".encodeToByteArray()
        val TERMINATOR = "\u001b\\".encodeToByteArray()
        const val MAX_MESSAGE_BYTES = 1 shl 20
    }
}

/**
 * The length of the complete Ghostty snapshot at the start of [buffer], or null while more
 * bytes are needed. A snapshot is a 10-byte envelope (`GHOSTSNP` and a version) followed by
 * records of `tag: u16, length: u32, crc: u32, payload`, ending with a FINISH record.
 */
internal fun snapshotLength(buffer: ByteArray, size: Int): Int? {
    if (size < SNAPSHOT_ENVELOPE_BYTES) return null
    require(buffer.decodeToString(0, 8) == "GHOSTSNP") { "The stream doesn't start with a Ghostty snapshot" }
    var position = SNAPSHOT_ENVELOPE_BYTES
    while (size - position >= RECORD_HEADER_BYTES) {
        val tag = littleEndian(buffer, position, 2).toInt()
        val end = position + RECORD_HEADER_BYTES + littleEndian(buffer, position + 2, 4)
        if (end > size) return null
        position = end.toInt()
        if (tag == RECORD_TAG_FINISH) return position
    }
    return null
}

private const val SNAPSHOT_ENVELOPE_BYTES = 10
private const val RECORD_HEADER_BYTES = 10
private const val RECORD_TAG_FINISH = 6

internal fun littleEndian(bytes: ByteArray, offset: Int, count: Int): Long {
    var value = 0L
    for (index in count - 1 downTo 0) value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
    return value
}
