package org.example.kotlintest.rex

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
import kotlin.test.assertFailsWith
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * A server that takes connections and answers none doesn't keep the attempts made on it: each
 * is closed when its time runs out. These use real sockets, since what leaked was a socket.
 */
class SilentConnectionTest {
    /**
     * Accepts every connection and then says nothing, or only as much as [upgrades] allows:
     * the answer that makes the connection a WebSocket, and nothing after it.
     */
    private class SilentServer(private val upgrades: Boolean = false) : AutoCloseable {
        private val listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val accepted = AtomicInteger()
        private val closed = AtomicInteger()

        val endpoint = RexEndpoint.parse("http://127.0.0.1:${listener.localPort}")

        /** How many connections were ever made. */
        val connections: Int get() = accepted.get()

        /** How many of them the client still holds open. */
        val open: Int get() = accepted.get() - closed.get()

        init {
            thread(isDaemon = true) {
                while (true) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    sockets += socket
                    accepted.incrementAndGet()
                    thread(isDaemon = true) {
                        runCatching { serve(socket) }
                        closed.incrementAndGet()
                    }
                }
            }
        }

        /** Returns when the client has closed its end. */
        private fun serve(socket: Socket) {
            val input = socket.getInputStream()
            if (upgrades) {
                val request = StringBuilder()
                while (!request.endsWith("\r\n\r\n")) request.append(input.read().takeIf { it >= 0 }?.toChar() ?: return)
                fun header(name: String) = request.lineSequence().first { it.startsWith("$name:", ignoreCase = true) }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((header("Sec-WebSocket-Key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
                )
                socket.getOutputStream().apply {
                    write(
                        ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                            "Sec-WebSocket-Accept: $accept\r\nSec-WebSocket-Protocol: ${header("Sec-WebSocket-Protocol")}\r\n\r\n").toByteArray(),
                    )
                    flush()
                }
            }
            while (input.read() >= 0) { /* until the client closes */ }
        }

        /** Waits for [open] to become [expected], for as long as closing a socket may take, and returns what it is then. */
        fun openOnceSettled(expected: Int, millis: Long = 3_000): Int {
            val deadline = System.nanoTime() + millis * 1_000_000
            while (open != expected && System.nanoTime() < deadline) Thread.sleep(10)
            return open
        }

        override fun close() {
            listener.close()
            sockets.forEach { runCatching { it.close() } }
        }
    }

    private val attempts = 3

    @Test
    fun anAttemptTheServerNeverAnsweredIsClosedWhenItsTimeRunsOut() = runBlocking {
        SilentServer().use { server ->
            val client = rexHttpClient()
            try {
                repeat(attempts) {
                    val failure = assertFailsWith<RexDisconnected> {
                        RexControl.connect(client, server.endpoint, this, TonoClient, timeoutMillis = 300)
                    }
                    assertEquals("The server didn't answer in time", failure.message)
                }
                assertEquals(attempts, server.connections, "Each attempt reached the server")
                assertEquals(0, server.openOnceSettled(0), "None of them is left open")
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun anAttemptTheServerNeverWelcomedIsClosedWhenItsTimeRunsOut() = runBlocking {
        SilentServer(upgrades = true).use { server ->
            val client = rexHttpClient()
            try {
                repeat(attempts) {
                    val failure = assertFailsWith<RexDisconnected> {
                        RexControl.connect(client, server.endpoint, this, TonoClient, timeoutMillis = 300)
                    }
                    assertEquals("The server didn't answer in time", failure.message)
                }
                assertEquals(attempts, server.connections)
                assertEquals(0, server.openOnceSettled(0), "None of them is left open")
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun aTerminalStreamTheServerNeverAnsweredIsClosedToo() = runBlocking {
        val ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16))
        for (upgrades in listOf(false, true)) {
            SilentServer(upgrades).use { server ->
                val client = rexHttpClient()
                try {
                    repeat(attempts) {
                        assertFailsWith<RexDisconnected> { RexTerminalStream.open(client, server.endpoint, ticket, timeoutMillis = 300) }
                    }
                    assertEquals(attempts, server.connections)
                    assertEquals(0, server.openOnceSettled(0), "None of them is left open, upgraded: $upgrades")
                } finally {
                    client.close()
                }
            }
        }
    }

    @Test
    fun anAttemptThatIsCancelledIsClosed() = runBlocking {
        SilentServer().use { server ->
            val client = rexHttpClient()
            try {
                // As when a host is removed, or the app closes, while it is still being tried.
                val trying = launch { RexControl.connect(client, server.endpoint, this, TonoClient) }
                while (server.connections == 0) delay(10)
                assertEquals(1, server.open)
                trying.cancelAndJoin()
                assertEquals(0, server.openOnceSettled(0))
            } finally {
                client.close()
            }
        }
    }
}
