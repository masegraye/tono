package org.example.kotlintest.rex

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * What is typed into a terminal other than keys (a mouse report, a screen keyboard's text)
 * is sent without waiting for the server's answer to the one before, in a way the server
 * keeps in order (backlog-054). Waiting made each wait a round trip: the reports of a wheel
 * turned fast over a program on another machine left one at a time, long after the wheel
 * had stopped.
 *
 * Against a stand-in server that answers typing only when the test says so.
 */
class TypingOrderTest {
    private class StandInServer(private val refuseActions: Boolean = false) : AutoCloseable {
        /** What was typed, as it arrived: the method it came by, and the text. */
        val typed = CopyOnWriteArrayList<Pair<String, String>>()

        /** Until this is done, typing gets no answer. */
        val answerTyping = CompletableDeferred<Unit>()

        private val engine = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket("/api/control", protocol = "rex.control.v1") { control() }
                // No terminal's stream is needed to type: the block isn't available.
                webSocket("/api/data", protocol = RexTerminalStream.SUBPROTOCOL) {
                    incoming.receive()
                    send(Frame.Binary(true, byteArrayOf(0x82.toByte(), 3)))
                }
            }
        }.start()

        val address: String = runBlocking { "http://127.0.0.1:${engine.engine.resolvedConnectors().first().port}" }

        private fun view() = buildJsonObject {
            put("revision", 1)
            put("session_id", "session:1")
            put("label", "stand-in")
            put("active_window_id", "window:1")
            putJsonArray("windows") {
                add(buildJsonObject {
                    put("window_id", "window:1")
                    put("label", "Tab 1")
                    put("focused_block_id", "block:1")
                    putJsonArray("layers") {
                        add(buildJsonObject {
                            put("layer_id", "layer:1")
                            put("kind", "tiled")
                            putJsonObject("layout") {
                                put("rect", buildJsonArray { listOf(0, 0, 1, 1).forEach { add(JsonPrimitive(it)) } })
                                put("block_id", "block:1")
                            }
                            putJsonArray("blocks") {
                                add(buildJsonObject {
                                    put("block_id", "block:1")
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
                val id = request.string("id")
                suspend fun answer(result: JsonObject) = send(Frame.Text(buildJsonObject { put("type", "response"); put("id", id); put("payload", result) }.toString()))
                when (val method = request.string("method")) {
                    "session.bootstrap" -> answer(buildJsonObject {
                        put("client_id", "client:1")
                        putJsonObject("attach_all") {
                            putJsonArray("sessions") { add(buildJsonObject { put("session_id", "session:1"); put("label", "stand-in") }) }
                        }
                    })
                    "session.attach" -> answer(buildJsonObject { put("view", view()) })
                    "session.view" -> answer(view())
                    "client.action" -> {
                        if (refuseActions) {
                            send(Frame.Text(buildJsonObject {
                                put("type", "response")
                                put("id", id)
                                putJsonObject("error") { put("code", "not_found"); put("message", "no such action"); put("status", 404) }
                            }.toString()))
                        } else {
                            typed += "client.action ${payload?.string("action")}" to (payload?.obj("args")?.string("data") ?: "?")
                            // Answered when the test lets it be, and the requests after it are read meanwhile.
                            launch { answerTyping.await(); answer(EMPTY) }
                        }
                    }
                    "com.superlogical.terminal.write" -> {
                        typed += method to (payload?.obj("args")?.string("data") ?: "?")
                        launch { answerTyping.await(); answer(EMPTY) }
                    }
                    else -> answer(EMPTY)
                }
            }
        }

        override fun close() { engine.stop(0, 0) }
    }

    private fun withHost(server: StandInServer, check: suspend (RexHost) -> Unit) = runBlocking {
        server.use {
            val client = rexHttpClient()
            val scope = CoroutineScope(Dispatchers.Default + Job())
            try {
                val host = RexHost(SavedHost("stand-in", server.address), client, scope, TonoClient)
                withTimeout(5_000) { host.state.first { it.view != null } }
                check(host)
                host.close()
            } finally {
                scope.cancel()
                client.close()
            }
        }
    }

    private suspend fun eventually(millis: Long = 5_000, condition: () -> Boolean): Boolean =
        withTimeoutOrNull(millis) { while (!condition()) delay(10) } != null

    @Test
    fun `what is typed is all sent, in order, while the server has answered none of it`() {
        val server = StandInServer()
        withHost(server) { host ->
            val texts = (1..30).map { "\u001b[<65;11;${it}M" }
            for (text in texts) host.typeText("block:1", text)
            assertTrue(eventually { server.typed.size == texts.size }, "all thirty arrived with none answered: ${server.typed.size} did")
            assertEquals(texts, server.typed.map { it.second })
            // As an action, which the server runs one at a time in the order it arrives, among the keys.
            assertEquals(setOf("client.action com.superlogical.terminal.write"), server.typed.map { it.first }.toSet())
            server.answerTyping.complete(Unit)
        }
    }

    @Test
    fun `a server that doesn't take typing as an action is sent it the way it always was, one at a time`() {
        val server = StandInServer(refuseActions = true)
        withHost(server) { host ->
            for (text in listOf("one", "two", "three")) host.typeText("block:1", text)
            assertTrue(eventually { server.typed.isNotEmpty() }, "what it refused was sent again the old way")
            delay(200)
            assertEquals(listOf("com.superlogical.terminal.write" to "one"), server.typed.toList(), "and the one after it waits for the answer")
            server.answerTyping.complete(Unit)
            assertTrue(eventually { server.typed.size == 3 }, "nothing was lost: ${server.typed}")
            assertEquals(listOf("one", "two", "three"), server.typed.map { it.second })
            host.typeText("block:1", "four")
            assertTrue(eventually { server.typed.size == 4 })
            assertEquals("com.superlogical.terminal.write" to "four", server.typed.last())
        }
    }
}
