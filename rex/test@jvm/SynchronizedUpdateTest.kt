package org.example.kotlintest.rex

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.example.kotlintest.ghostty.GhosttyTerminal

private const val ESC = "\u001b"

/**
 * A program that says it is in the middle of drawing (a synchronized update, mode 2026) is
 * not shown half done: the last finished frame stays up until it says it is through
 * (backlog-019, backlog-054). A full-screen program that redraws for every turn of the
 * wheel otherwise shows its screen erased, or half of the new one, whenever its output
 * arrives in more than one piece.
 *
 * Against a stand-in for a terminal's stream, with a real socket.
 */
class SynchronizedUpdateTest {
    /** A stream that opens with the snapshot of an empty terminal, and then carries what [output] is sent. */
    private class StandInStream : AutoCloseable {
        val output = Channel<String>(Channel.UNLIMITED)

        private val engine = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket("/api/data", protocol = RexTerminalStream.SUBPROTOCOL) {
                    incoming.receive() // the hello and its ticket
                    send(Frame.Binary(true, ByteArray(32).also { it[0] = 0x81.toByte(); it[24] = 7 }))
                    send(Frame.Binary(true, GhosttyTerminal.create(20, 4).use { it.snapshot() }))
                    for (text in output) send(Frame.Binary(true, text.encodeToByteArray()))
                }
            }
        }.start()

        val address: String = runBlocking { "http://127.0.0.1:${engine.engine.resolvedConnectors().first().port}" }

        override fun close() { engine.stop(0, 0) }
    }

    private fun withTerminal(check: suspend (StandInStream, TerminalSession) -> Unit) = runBlocking {
        StandInStream().use { server ->
            val client = rexHttpClient()
            val scope = CoroutineScope(Dispatchers.Default + Job())
            try {
                val ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16))
                val terminal = TerminalSession("session:1", "block:1", scope, colors = null) {
                    RexTerminalStream.open(client, RexEndpoint.parse(server.address), ticket)
                }
                withTimeout(5_000) { terminal.frame.first { it != null } }
                check(server, terminal)
                terminal.close()
            } finally {
                scope.cancel()
                client.close()
            }
        }
    }

    private fun TerminalSession.firstRow(): String = frame.value?.rows?.firstOrNull()?.text()?.trimEnd().orEmpty()

    private suspend fun eventually(millis: Long = 3_000, condition: () -> Boolean): Boolean =
        withTimeoutOrNull(millis) { while (!condition()) delay(5) } != null

    @Test
    fun `a screen being redrawn is shown when the program says it is through, and not before`() = withTerminal { server, terminal ->
        server.output.send("one")
        assertTrue(eventually { terminal.firstRow() == "one" }, "what is written outside an update shows at once: '${terminal.firstRow()}'")

        // The program erases its screen and draws half of the next, in one piece of output, and the rest in another.
        server.output.send("$ESC[?2026h$ESC[2J$ESC[Htw")
        delay(150)
        assertEquals("one", terminal.firstRow(), "the frame before the update stays up while it is under way")
        // What is asked of the terminal meanwhile doesn't show the half-drawn screen either.
        terminal.scroll(0)
        delay(50)
        assertEquals("one", terminal.firstRow())
        server.output.send("o$ESC[?2026l")
        assertTrue(eventually { terminal.firstRow() == "two" }, "and the new screen shows whole when the update ends: '${terminal.firstRow()}'")
    }

    @Test
    fun `a program that never says it is through is shown anyway after a second`() = withTerminal { server, terminal ->
        server.output.send("$ESC[?2026hstuck")
        delay(300)
        assertEquals("", terminal.firstRow(), "held at first")
        assertTrue(eventually(2_000) { terminal.firstRow() == "stuck" }, "then shown: '${terminal.firstRow()}'")
        // And goes on being shown as it draws, though it still says it is in the middle of an update.
        server.output.send(" still")
        assertTrue(eventually(500) { terminal.firstRow() == "stuck still" }, "'${terminal.firstRow()}'")
    }
}
