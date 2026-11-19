package org.example.kotlintest.rex

import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.measureTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/**
 * A host that is refused is down as soon as the refusal arrives, is tried again, and never
 * says "connecting" again. The web app's relay refuses an address it won't connect to by
 * taking the socket and closing it at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefusedHostTest {
    // How a host's state moves from one attempt to the next.

    @Test
    fun aHostThatFailedIsNeverConnectingAgain() {
        var state = HostState().trying()
        assertEquals(HostReach.Connecting, state.reach, "the first attempt")
        state = state.lost("refused")
        assertEquals(HostReach.Down, state.reach)
        repeat(5) {
            state = state.trying()
            assertEquals(HostReach.Down, state.reach, "a later attempt")
            state = state.lost("refused")
            assertEquals(HostReach.Down, state.reach)
        }
    }

    @Test
    fun aHostThatWasReachedIsReconnectingUntilItIsBack() {
        var state = HostState().trying().copy(connection = HostConnection.Connected, failed = false, reached = true)
        state = state.lost("the server went away")
        assertEquals(HostReach.Reconnecting, state.reach)
        repeat(3) {
            state = state.trying()
            assertEquals(HostReach.Reconnecting, state.reach)
            state = state.lost("refused")
            assertEquals(HostReach.Reconnecting, state.reach)
        }
        assertEquals(HostReach.Connected, state.trying().copy(connection = HostConnection.Connected, failed = false, reached = true).reach)
    }

    @Test
    fun losingAHostKeepsWhereTheUserWasAndDropsWhatWasRead() {
        val before = HostState(
            connection = HostConnection.Connected, reached = true, selectedSessionId = "session:1",
            shownWindows = mapOf("session:1" to "window:2"), bells = setOf("window:3"),
        )
        val after = before.lost("gone")
        assertEquals(HostConnection.Disconnected("gone"), after.connection)
        assertTrue(after.failed)
        assertTrue(after.reached)
        assertEquals("session:1", after.selectedSessionId)
        assertEquals(mapOf("session:1" to "window:2"), after.shownWindows)
        assertTrue(after.bells.isEmpty())
    }

    // A socket closed under the first exchange is a failure, not a cancellation.

    @Test
    fun aSocketThatCancelsTheWriteIsAFailure() = runTest {
        // What a browser's socket does when it is written to after the other end closed it.
        val failure = assertFailsWith<RexDisconnected> {
            closedIsFailure(why = { "Not a Rex server this relay will connect to" }) { throw CancellationException("Channel was cancelled") }
        }
        assertEquals("Not a Rex server this relay will connect to", failure.message)
        assertEquals("Channel was cancelled", assertFailsWith<RexDisconnected> { closedIsFailure { throw CancellationException("Channel was cancelled") } }.message)
    }

    @Test
    fun aSocketThatEndsTheReadIsAFailure() = runTest {
        val closed = Channel<Int>().apply { close() }
        assertEquals("refused", assertFailsWith<RexDisconnected> { closedIsFailure(why = { "refused" }) { closed.receive() } }.message)
        assertEquals("The server closed the connection", assertFailsWith<RexDisconnected> { closedIsFailure { closed.receive() } }.message)
    }

    @Test
    fun beingCancelledOneselfIsStillACancellation() = runTest {
        var failure: Throwable? = null
        val work = launch {
            try {
                closedIsFailure { awaitCancellation() }
            } catch (caught: Throwable) {
                failure = caught
                throw caught
            }
        }
        runCurrent()
        work.cancel()
        work.join()
        assertTrue(failure is CancellationException, "got $failure")
    }

    @Test
    fun otherFailuresAndResultsPassThrough() = runTest {
        assertEquals(7, closedIsFailure { 7 })
        assertEquals("no", assertFailsWith<IllegalStateException> { closedIsFailure { error("no") } }.message)
    }

    // With real sockets.

    /** Takes every connection as a WebSocket and closes it at once with 1008 and [reason], as the relay does. */
    private class RefusingServer(private val reason: String = "Not a Rex server this relay will connect to") : AutoCloseable {
        private val listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val accepted = AtomicInteger()

        val address = "http://127.0.0.1:${listener.localPort}"
        val connections: Int get() = accepted.get()

        init {
            thread(isDaemon = true) {
                while (true) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    accepted.incrementAndGet()
                    thread(isDaemon = true) {
                        runCatching {
                            socket.use {
                                val input = it.getInputStream()
                                val request = StringBuilder()
                                while (!request.endsWith("\r\n\r\n")) request.append(input.read().takeIf { byte -> byte >= 0 }?.toChar() ?: return@use)
                                fun header(name: String) = request.lineSequence().first { line -> line.startsWith("$name:", ignoreCase = true) }.substringAfter(':').trim()
                                val accept = Base64.getEncoder().encodeToString(
                                    MessageDigest.getInstance("SHA-1").digest((header("Sec-WebSocket-Key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
                                )
                                val words = reason.toByteArray()
                                it.getOutputStream().apply {
                                    write(
                                        ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                                            "Sec-WebSocket-Accept: $accept\r\nSec-WebSocket-Protocol: ${header("Sec-WebSocket-Protocol")}\r\n\r\n").toByteArray(),
                                    )
                                    // A close frame: 1008, "policy violation", and why.
                                    write(byteArrayOf(0x88.toByte(), (2 + words.size).toByte(), (1008 shr 8).toByte(), (1008 and 0xff).toByte()) + words)
                                    flush()
                                }
                                while (input.read() >= 0) { /* until the client closes */ }
                            }
                        }
                    }
                }
            }
        }

        override fun close() = listener.close()
    }

    @Test
    fun aRefusalIsReportedAtOnceWithItsReason() = runBlocking {
        RefusingServer().use { server ->
            val client = rexHttpClient()
            try {
                val took = measureTime {
                    val failure = assertFailsWith<RexDisconnected> { RexControl.connect(client, RexEndpoint.parse(server.address), this, TonoClient) }
                    assertEquals("Not a Rex server this relay will connect to", failure.message)
                }
                assertTrue(took.inWholeMilliseconds < 3_000, "It took $took, where waiting for a welcome takes ten seconds")
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun aRefusedHostGoesDownAtOnceAndIsTriedAgainWithoutSayingConnecting() = runBlocking {
        RefusingServer().use { server ->
            val client = rexHttpClient()
            val scope = CoroutineScope(Dispatchers.Default + Job())
            try {
                val host = RexHost(SavedHost("refused", server.address), client, scope, TonoClient)
                val down = withTimeout(3_000) { host.state.first { it.reach == HostReach.Down } }
                assertEquals("Not a Rex server this relay will connect to", (down.connection as? HostConnection.Disconnected)?.reason ?: "still trying")

                // Every state from here on, through later attempts and attempts asked for by hand.
                val seen = mutableListOf<HostState>()
                val watching = launch(Dispatchers.Default) { host.state.collect { synchronized(seen) { seen += it } } }
                val before = server.connections
                repeat(6) {
                    delay(150)
                    host.retry()
                }
                delay(300)
                watching.cancel()
                assertTrue(server.connections > before, "It is tried again: ${server.connections} connections, $before before")
                val reaches = synchronized(seen) { seen.map { it.reach } }
                assertEquals(setOf(HostReach.Down), reaches.toSet(), "It stays down while it is tried again")
                assertTrue(synchronized(seen) { seen.any { it.connection == HostConnection.Connecting } }, "though attempts were under way")
                assertFalse(host.state.value.reached)
                host.close()
            } finally {
                scope.cancel()
                client.close()
            }
        }
    }
}
