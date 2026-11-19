package org.example.kotlintest.rex

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A terminal's stream is opened once, and one the server refuses is asked for again
 * (backlog-044). A server lets a client have one stream of a terminal at a time and refuses
 * a second with reason 2, so a stream this client opened and lost track of shut it out of
 * that terminal until the app was started again.
 *
 * Against a stand-in server with real sockets, since what went wrong was in what was opened
 * and closed, and in several threads doing it at once.
 */
class TerminalStreamsTest {
    /**
     * One session with a tab for each of [blocks], each tab a single terminal. It keeps the
     * rule under test: one stream of a block at a time, and REJECT with reason 2 for another.
     * A stream is welcomed and then sent nothing, so its terminal stays "restoring".
     */
    private class StandInServer(private val blocks: List<String> = listOf("block:1", "block:2")) : AutoCloseable {
        val sessionId = "session:1"
        val windows = blocks.indices.map { "window:${it + 1}" }

        /** Reasons to refuse the next streams asked for with, whatever is open, one each. */
        val refuseNext = CopyOnWriteArrayList<Int>()

        /** The blocks that have a stream open now. */
        val open: Set<String> get() = streams.keys.toSet()

        /** Every answer given to a stream's opening, in order: the block, and the reason it was refused for, or null if it was welcomed. */
        val answers = CopyOnWriteArrayList<Pair<String, Int?>>()
        val refusals: List<Pair<String, Int?>> get() = answers.filter { it.second != null }

        private val streams = ConcurrentHashMap<String, Unit>()
        private val tickets = ConcurrentHashMap<String, String>()
        private val ticketNumbers = AtomicInteger()
        private val layout = Mutex()
        private var active = windows.first()
        private var revision = 1L

        private val engine = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket("/api/control", protocol = "rex.control.v1") { control() }
                webSocket("/api/data", protocol = RexTerminalStream.SUBPROTOCOL) { data() }
            }
        }.start()

        val address: String = runBlocking { "http://127.0.0.1:${engine.engine.resolvedConnectors().first().port}" }

        private fun view() = buildJsonObject {
            put("revision", revision)
            put("session_id", sessionId)
            put("label", "stand-in")
            put("active_window_id", active)
            putJsonArray("windows") {
                for ((index, block) in blocks.withIndex()) add(buildJsonObject {
                    put("window_id", windows[index])
                    put("label", "Tab ${index + 1}")
                    put("focused_block_id", block)
                    putJsonArray("layers") {
                        add(buildJsonObject {
                            put("layer_id", "layer:${index + 1}")
                            put("kind", "tiled")
                            putJsonObject("layout") {
                                put("rect", buildJsonArray { listOf(0, 0, 1, 1).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                                put("block_id", block)
                            }
                            putJsonArray("blocks") {
                                add(buildJsonObject {
                                    put("block_id", block)
                                    put("label", "shell")
                                    put("creator_name", "com.superlogical.terminal")
                                    put("flavor", "com.superlogical.terminal.shell")
                                })
                            }
                        })
                    }
                })
            }
        }

        private suspend fun DefaultWebSocketServerSession.control() {
            incoming.receive() // the hello
            send(Frame.Text(buildJsonObject {
                put("type", "welcome")
                put("protocol", "rex.control.v1")
                put("api_revision", RexControl.API_REVISION)
                putJsonObject("client") {
                    put("id", "client:1")
                    put("reconnect_token", "token")
                }
            }.toString()))
            for (frame in incoming) {
                val request = Json.parseToJsonElement((frame as? Frame.Text ?: continue).readText()).jsonObject
                val payload = request["payload"] as? JsonObject
                var event: JsonObject? = null
                val result = layout.withLock {
                    when (request.string("method")) {
                        "session.bootstrap" -> buildJsonObject {
                            put("client_id", "client:1")
                            putJsonObject("attach_all") {
                                putJsonArray("sessions") { add(buildJsonObject { put("session_id", sessionId); put("label", "stand-in") }) }
                            }
                        }
                        "session.attach" -> buildJsonObject { put("view", view()) }
                        "session.view" -> view()
                        "session.focus_window" -> {
                            active = payload?.string("window_id") ?: active
                            revision++
                            // As the server does: the layout changed, come and read it.
                            event = buildJsonObject {
                                put("type", "event")
                                putJsonObject("payload") {
                                    put("type", "session_event")
                                    putJsonObject("payload") {
                                        put("session_id", sessionId)
                                        putJsonObject("event") {
                                            put("type", "session_view_changed")
                                            putJsonObject("payload") { put("revision", revision) }
                                        }
                                    }
                                }
                            }
                            EMPTY
                        }
                        "data.connect" -> {
                            val ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also { bytes ->
                                var number = ticketNumbers.incrementAndGet()
                                for (index in 0 until 4) { bytes[index] = number.toByte(); number = number shr 8 }
                            })
                            tickets[ticket] = payload?.string("block_id").orEmpty()
                            buildJsonObject { put("ticket", ticket); put("expires_in_ms", 60_000) }
                        }
                        else -> EMPTY
                    }
                }
                send(Frame.Text(buildJsonObject {
                    put("type", "response")
                    put("id", request.string("id"))
                    put("payload", result)
                }.toString()))
                event?.let { send(Frame.Text(it.toString())) }
            }
        }

        private suspend fun DefaultWebSocketServerSession.data() {
            val hello = (incoming.receive() as Frame.Binary).readBytes()
            val block = tickets.remove(Base64.getUrlEncoder().withoutPadding().encodeToString(hello.copyOfRange(1, 17)))
            val reason = when {
                block == null -> 1
                refuseNext.isNotEmpty() -> refuseNext.removeAt(0)
                streams.putIfAbsent(block, Unit) != null -> 2
                else -> null
            }
            answers += (block ?: "?") to reason
            if (reason != null) {
                send(Frame.Binary(true, byteArrayOf(0x82.toByte(), reason.toByte())))
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "data handshake rejected"))
                return
            }
            try {
                send(Frame.Binary(true, ByteArray(32).also { it[0] = 0x81.toByte(); it[24] = 7 }))
                for (frame in incoming) { /* until the client closes or drops it */ }
            } finally {
                streams.remove(block)
            }
        }

        override fun close() { engine.stop(0, 0) }
    }

    /** Runs [check] with a host connected to a new stand-in server, once its first terminal's stream is open. */
    private fun withHost(prepare: (StandInServer) -> Unit = {}, check: suspend (StandInServer, RexHost) -> Unit) = runBlocking {
        StandInServer().use { server ->
            prepare(server)
            val client = rexHttpClient()
            val scope = CoroutineScope(Dispatchers.Default + Job())
            try {
                val host = RexHost(SavedHost("stand-in", server.address), client, scope, TonoClient)
                withTimeout(5_000) { host.state.first { it.terminals.isNotEmpty() } }
                check(server, host)
                host.close()
            } finally {
                scope.cancel()
                client.close()
            }
        }
    }

    /** Waits until [condition] holds, and says whether it came to. */
    private suspend fun eventually(millis: Long = 5_000, condition: () -> Boolean): Boolean =
        withTimeoutOrNull(millis) { while (!condition()) delay(10) } != null

    @Test
    fun `switching tabs quickly opens each terminal once and leaves none open behind`() = withHost { server, host ->
        assertTrue(eventually { server.open == setOf("block:1") }, "the first tab's terminal is open: ${server.open}")
        // Each switch changes the layout on the server, whose news of it arrives while the next switch is under way.
        repeat(400) {
            host.showTab(server.sessionId, server.windows[(it + 1) % 2])
            delay(2)
        }
        assertTrue(eventually { host.moving.value == 0 }, "the switches were all carried out")
        // The stream of the tab left is closed, which takes a moment to arrive.
        val settled = eventually(3_000) { server.open == setOf("block:1") }
        assertEquals(emptyList(), server.refusals.distinct(), "no stream was asked for while this client still had one of the same terminal")
        assertTrue(settled, "only the terminal of the tab on screen has a stream: ${server.open}")
        assertEquals(setOf("block:1"), host.state.value.terminals.keys)

        // And the terminal of the other tab can still be opened: nothing lost has kept hold of it.
        host.showTab(server.sessionId, server.windows[1])
        assertTrue(eventually(3_000) { server.open == setOf("block:2") }, "the other tab's terminal opens: ${server.open}")
        assertEquals(emptyList(), server.refusals)
    }

    @Test
    fun `a stream refused because this client still has one is asked for again by itself`() = withHost(prepare = { it.refuseNext += listOf(2, 2, 2) }) { server, host ->
        assertTrue(eventually { server.open == setOf("block:1") }, "the terminal opened after being refused: ${server.answers}")
        assertEquals(listOf<Int?>(2, 2, 2, null), server.answers.map { it.second })
        val terminal = host.state.value.terminals.getValue("block:1")
        assertEquals(TerminalSession.Status.Connecting, terminal.status.value, "a few quick refusals are not shown")
    }

    @Test
    fun `a stream that goes on being refused says so and is still asked for`() = withHost(prepare = { server -> repeat(7) { server.refuseNext += 2 } }) { server, host ->
        val terminal = host.state.value.terminals.getValue("block:1")
        val said = withTimeout(5_000) { terminal.status.first { it is TerminalSession.Status.Retrying } }
        assertEquals("The server refused the terminal stream (reason 2: this client already has it open)", (said as TerminalSession.Status.Retrying).reason)
        assertTrue(eventually(8_000) { server.open == setOf("block:1") }, "the terminal opened in the end: ${server.answers}")
        // The welcome is still on its way to the client when the server counts the stream as open.
        assertTrue(eventually { terminal.status.value == TerminalSession.Status.Connecting }, "and it no longer says it is being refused: ${terminal.status.value}")
        assertTrue(host.state.value.terminals["block:1"] === terminal, "it is the same terminal, not one made by showing the tab again")
    }

    @Test
    fun `a terminal whose stream failed opens afresh when its tab is shown again`() = withHost(prepare = { it.refuseNext += 6 }) { server, host ->
        // "The server failed" is not asked about again by itself.
        val failed = host.state.value.terminals.getValue("block:1")
        val ended = withTimeout(5_000) { failed.status.first { it is TerminalSession.Status.Ended } }
        assertEquals("The server refused the terminal stream (reason 6: the server failed)", assertIs<TerminalSession.Status.Ended>(ended).reason)
        assertEquals(emptySet(), server.open)

        host.showTab(server.sessionId, server.windows[1])
        assertTrue(eventually { server.open == setOf("block:2") }, "the other tab opens: ${server.open}")
        host.showTab(server.sessionId, server.windows[0])
        assertTrue(eventually { server.open == setOf("block:1") }, "the tab that failed opens when it is shown again: ${server.answers}")
        assertTrue(host.state.value.terminals["block:1"] !== failed)
        assertEquals(listOf("block:1" to 6), server.refusals)
    }
}
