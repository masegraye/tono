package org.example.kotlintest.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.close
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import io.ktor.server.websocket.WebSockets as ServerWebSockets

/** The relay doesn't keep a connection to a server for a page that has stopped waiting for it. */
class TonoRelayTest {
    /** Accepts every connection and never says anything on it. */
    private class SilentServer : AutoCloseable {
        private val listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val accepted = AtomicInteger()
        private val closed = AtomicInteger()

        val address = "http://127.0.0.1:${listener.localPort}"
        val connections: Int get() = accepted.get()

        /** How many connections the other end still holds open. */
        val open: Int get() = accepted.get() - closed.get()

        init {
            thread(isDaemon = true) {
                while (true) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    sockets += socket
                    accepted.incrementAndGet()
                    thread(isDaemon = true) {
                        runCatching { while (socket.getInputStream().read() >= 0) { /* until the other end closes */ } }
                        closed.incrementAndGet()
                    }
                }
            }
        }

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

    /** Runs [block] with a relay whose own machine's Rex server is [rex], and a page's way of reaching it. */
    private fun withRelay(rex: SilentServer, block: suspend (page: HttpClient, relay: String) -> Unit) {
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { tonoRelay(localEndpoint = rex.address) }.start()
        val page = HttpClient(CIO) { install(WebSockets) }
        try {
            runBlocking {
                val port = server.engine.resolvedConnectors().first().port
                block(page, "127.0.0.1:$port")
            }
        } finally {
            page.close()
            server.stop(100, 500)
        }
    }

    private suspend fun HttpClient.relayed(relay: String) = webSocketSession("ws://$relay/api/tono/relay/api/control?endpoint=unix:///@local") {
        header(HttpHeaders.Origin, "http://$relay")
        header(HttpHeaders.SecWebSocketProtocol, "rex.control.v1")
    }

    @Test
    fun `a page that gives up on a silent server takes the relay's connection with it`() = SilentServer().use { rex ->
        withRelay(rex) { page, relay ->
            repeat(3) {
                val socket = page.relayed(relay)
                while (rex.connections <= it) delay(10)
                // As the page does when the server's ten seconds run out.
                socket.close(CloseReason(CloseReason.Codes.NORMAL, ""))
            }
            assertEquals(3, rex.connections)
            assertEquals(0, rex.openOnceSettled(0), "None of the relay's connections is left open")
        }
    }

    @Test
    fun `a page that goes away without a word does too`() = SilentServer().use { rex ->
        withRelay(rex) { page, relay ->
            val socket = page.relayed(relay)
            while (rex.connections == 0) delay(10)
            socket.cancel()
            assertEquals(0, rex.openOnceSettled(0))
        }
    }

    @Test
    fun `what a page says before the server has answered still reaches it`() {
        val rex = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            install(ServerWebSockets)
            routing {
                webSocket("/api/control", "rex.control.v1") {
                    // Says back what it hears.
                    for (frame in incoming) if (frame is Frame.Text) send(Frame.Text("heard " + frame.readText()))
                }
            }
        }.start()
        val relay = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            tonoRelay(localEndpoint = runBlocking { "http://127.0.0.1:${rex.engine.resolvedConnectors().first().port}" })
        }.start()
        val page = HttpClient(CIO) { install(WebSockets) }
        try {
            runBlocking {
                val socket = page.relayed("127.0.0.1:${relay.engine.resolvedConnectors().first().port}")
                socket.send(Frame.Text("hello"))
                assertEquals("heard hello", withTimeout(5_000) { (socket.incoming.receive() as Frame.Text).readText() })
                socket.send(Frame.Text("again"))
                assertEquals("heard again", withTimeout(5_000) { (socket.incoming.receive() as Frame.Text).readText() })
                socket.close(CloseReason(CloseReason.Codes.NORMAL, ""))
            }
        } finally {
            page.close()
            relay.stop(100, 500)
            rex.stop(100, 500)
        }
    }
}
