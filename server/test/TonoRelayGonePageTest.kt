package org.example.kotlintest.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The relay notices a page that has vanished without closing anything, and lets go of the
 * server for it, while a page that is only idle keeps its connection.
 */
class TonoRelayGonePageTest {
    /**
     * Stands in for a Rex server: makes the WebSocket, and notes how each connection ends,
     * `closed <code>` if the other end said goodbye and `dropped` if it just went.
     *
     * @param flood whether to send without pause for as long as the connection takes it
     */
    private class FakeRex(private val flood: Boolean = false) : AutoCloseable {
        private val listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val accepted = AtomicInteger()

        val address = "http://127.0.0.1:${listener.localPort}"
        val connections: Int get() = accepted.get()
        val endings = CopyOnWriteArrayList<String>()
        val texts = CopyOnWriteArrayList<String>()

        init {
            thread(isDaemon = true) {
                while (true) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    sockets += socket
                    thread(isDaemon = true) { endings += runCatching { serve(socket) }.getOrDefault("dropped") }
                }
            }
        }

        private fun serve(socket: Socket): String {
            val input = DataInputStream(socket.getInputStream().buffered())
            val request = buildString { while (!endsWith("\r\n\r\n")) append(input.readByte().toInt().toChar()) }
            fun header(name: String) = request.lineSequence().first { it.startsWith("$name:", ignoreCase = true) }.substringAfter(':').trim()
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((header("Sec-WebSocket-Key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
            )
            val output = socket.getOutputStream()
            output.write(
                ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\nSec-WebSocket-Protocol: ${header("Sec-WebSocket-Protocol")}\r\n\r\n").toByteArray(),
            )
            output.flush()
            accepted.incrementAndGet()
            if (flood) thread(isDaemon = true) {
                // One binary message of 32 KB, over and over.
                val message = byteArrayOf(0x82.toByte(), 126, 0x80.toByte(), 0) + ByteArray(0x8000) { 'x'.code.toByte() }
                runCatching { while (true) output.write(message) }
            }
            try {
                while (true) {
                    val opcode = input.readUnsignedByte() and 0x0f
                    var length = (input.readUnsignedByte() and 0x7f).toLong()
                    if (length == 126L) length = input.readUnsignedShort().toLong() else if (length == 127L) length = input.readLong()
                    // What a client sends is masked.
                    val mask = ByteArray(4).also(input::readFully)
                    val payload = ByteArray(length.toInt()).also(input::readFully)
                    for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                    when (opcode) {
                        1 -> texts += payload.decodeToString()
                        8 -> return "closed " + (((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff))
                    }
                }
            } catch (_: EOFException) {
                return "dropped"
            } finally {
                socket.close()
            }
        }

        /** How the only connection ended, or null if it is still open after [millis]. */
        fun ending(millis: Long): String? {
            val deadline = System.nanoTime() + millis * 1_000_000
            while (endings.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
            return endings.firstOrNull()
        }

        override fun close() {
            listener.close()
            sockets.forEach { runCatching { it.close() } }
        }
    }

    /**
     * A page that becomes a WebSocket and is then gone, as a sleeping phone is: nothing is
     * read, no question is answered, and the connection is never closed.
     */
    private fun vanishedPage(relay: String): Socket {
        val socket = Socket("127.0.0.1", relay.substringAfter(':').toInt())
        socket.getOutputStream().write(
            ("GET /api/tono/relay/api/control?endpoint=unix:///@local HTTP/1.1\r\nHost: $relay\r\nOrigin: http://$relay\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Protocol: rex.control.v1\r\n\r\n").toByteArray(),
        )
        socket.getOutputStream().flush()
        val input = socket.getInputStream()
        val answer = buildString { while (!endsWith("\r\n\r\n")) append(input.read().toChar()) }
        assertEquals("HTTP/1.1 101 Switching Protocols", answer.lineSequence().first())
        return socket
    }

    /** Runs [block] with a relay that asks its pages every 300 ms and gives them 700 ms to answer. */
    private fun withRelay(rex: FakeRex, block: suspend (relay: String) -> Unit) {
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            tonoRelay(localEndpoint = rex.address, pageAskMillis = 300, pagePatienceMillis = 700)
        }.start()
        try {
            runBlocking { block("127.0.0.1:${server.engine.resolvedConnectors().first().port}") }
        } finally {
            server.stop(100, 500)
        }
    }

    @Test
    fun `a page that vanishes takes the relay's connection to the server with it`() = FakeRex().use { rex ->
        withRelay(rex) { relay ->
            vanishedPage(relay).use {
                while (rex.connections == 0) delay(10)
                // Dropped and not closed: to the server this is a client that was lost, which
                // it keeps a place for, not one that left.
                assertEquals("dropped", rex.ending(5_000))
            }
        }
    }

    @Test
    fun `so does one that vanishes while the server has a lot to say`() = FakeRex(flood = true).use { rex ->
        withRelay(rex) { relay ->
            vanishedPage(relay).use {
                while (rex.connections == 0) delay(10)
                assertEquals("dropped", rex.ending(5_000))
            }
        }
    }

    @Test
    fun `a page that is there and has nothing to say stays connected`() = FakeRex().use { rex ->
        withRelay(rex) { relay ->
            val page = HttpClient(CIO) { install(WebSockets) }
            try {
                val socket = page.webSocketSession("ws://$relay/api/tono/relay/api/control?endpoint=unix:///@local") {
                    header(HttpHeaders.Origin, "http://$relay")
                    header(HttpHeaders.SecWebSocketProtocol, "rex.control.v1")
                }
                // Three times as long as a page that is gone is given.
                assertNull(rex.ending(3_000), "The relay kept the server's connection")
                socket.send(Frame.Text("still here"))
                while (rex.texts.isEmpty()) delay(10)
                assertEquals(listOf("still here"), rex.texts.toList())
                socket.close(CloseReason(CloseReason.Codes.NORMAL, ""))
                assertEquals("closed 1000", rex.ending(3_000), "A page that says goodbye has it passed on")
            } finally {
                page.close()
            }
        }
    }

    @Test
    fun `a page whose connection breaks is a client that was lost too`() = FakeRex().use { rex ->
        withRelay(rex) { relay ->
            val page = vanishedPage(relay)
            while (rex.connections == 0) delay(10)
            // Closing with no time to linger resets the connection.
            page.setSoLinger(true, 0)
            page.close()
            assertEquals("dropped", rex.ending(5_000))
        }
    }

    @Test
    fun `and so is one that ends its connection without saying goodbye`() = FakeRex().use { rex ->
        withRelay(rex) { relay ->
            val page = vanishedPage(relay)
            while (rex.connections == 0) delay(10)
            page.close()
            assertEquals("dropped", rex.ending(5_000))
        }
    }
}
