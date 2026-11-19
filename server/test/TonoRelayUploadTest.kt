package org.example.kotlintest.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import io.ktor.server.websocket.WebSockets as ServerWebSockets

/**
 * A file on its way to a terminal's machine goes through the relay like everything else the
 * page sends: its bytes reach the Rex server, the server's close reaches the page, and an
 * upload whose page is gone doesn't stay open at the server.
 */
class TonoRelayUploadTest {
    /** Takes uploads as a Rex server does, as far as the relay can tell: counts the bytes, and closes when an empty message ends them. */
    private class Rex : AutoCloseable {
        val received = AtomicLong()
        val open = AtomicInteger()
        val ended = AtomicInteger()
        private val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            install(ServerWebSockets)
            routing {
                webSocket("/api/data", "rex.data.bytes.v1") {
                    open.incrementAndGet()
                    try {
                        for (frame in incoming) {
                            val bytes = (frame as? Frame.Binary)?.readBytes() ?: continue
                            if (bytes.isEmpty()) {
                                close(CloseReason(CloseReason.Codes.NORMAL, ""))
                                break
                            }
                            received.addAndGet(bytes.size.toLong())
                        }
                    } finally {
                        open.decrementAndGet()
                        ended.incrementAndGet()
                    }
                }
            }
        }.start()
        val address: String = runBlocking { "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}" }

        override fun close() {
            server.stop(100, 500)
        }
    }

    private fun withRelay(rex: Rex, askMillis: Long = 20_000, patienceMillis: Long = 40_000, block: suspend (page: HttpClient, relay: String) -> Unit) {
        val relay = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            tonoRelay(localEndpoint = rex.address, pageAskMillis = askMillis, pagePatienceMillis = patienceMillis)
        }.start()
        val page = HttpClient(CIO) { install(WebSockets) }
        try {
            runBlocking { block(page, "127.0.0.1:${relay.engine.resolvedConnectors().first().port}") }
        } finally {
            page.close()
            relay.stop(100, 500)
        }
    }

    private suspend fun HttpClient.uploading(relay: String) = webSocketSession("ws://$relay/api/tono/relay/api/data?endpoint=unix:///@local") {
        header(HttpHeaders.Origin, "http://$relay")
        header(HttpHeaders.SecWebSocketProtocol, "rex.data.bytes.v1")
    }

    private suspend fun settled(what: () -> Boolean) = withTimeout(5_000) { while (!what()) delay(10) }

    @Test
    fun `a file's bytes reach the server, and its close reaches the page`() = Rex().use { rex ->
        withRelay(rex) { page, relay ->
            val socket = page.uploading(relay)
            repeat(5) { socket.send(Frame.Binary(true, ByteArray(65536))) }
            socket.send(Frame.Binary(true, ByteArray(0)))
            val closed = withTimeout(5_000) { socket.closeReason.await() }
            assertEquals(1000, closed?.code?.toInt(), "The page hears that the file arrived")
            assertEquals(5 * 65536L, rex.received.get())
            settled { rex.open.get() == 0 }
        }
    }

    @Test
    fun `an upload whose page breaks off part way ends at the server too`() = Rex().use { rex ->
        withRelay(rex) { page, relay ->
            val socket = page.uploading(relay)
            socket.send(Frame.Binary(true, ByteArray(65536)))
            settled { rex.received.get() == 65536L }
            socket.cancel() // the tab closed, or the app dropped an upload that failed
            settled { rex.ended.get() == 1 }
            assertEquals(0, rex.open.get(), "Nothing is left waiting for the rest of the file")
        }
    }

    @Test
    fun `and so does one whose page said goodbye part way`() = Rex().use { rex ->
        withRelay(rex) { page, relay ->
            val socket = page.uploading(relay)
            socket.send(Frame.Binary(true, ByteArray(100)))
            settled { rex.received.get() == 100L }
            socket.close(CloseReason(CloseReason.Codes.NORMAL, ""))
            settled { rex.ended.get() == 1 }
            assertEquals(0, rex.open.get())
        }
    }
}
