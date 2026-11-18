package org.example.kotlintest.rex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A `clipboard_written` event's fields for a copy small enough to carry its text. */
private fun small(text: String) = buildJsonObject {
    put("text", text)
    put("id", 1)
    put("bytes", text.toByteArray().size)
}

/** The fields for a copy too large for the event: an id to read it by, and its length. */
private fun large(id: Long, bytes: Int) = buildJsonObject {
    put("id", id)
    put("bytes", bytes)
}

private fun part(id: Long, data: String, total: Int, eof: Boolean) = buildJsonObject {
    put("id", id)
    put("data", data)
    put("total", total)
    put("eof", eof)
}

/**
 * Answers `clipboard_read` as the server does: from the byte offset asked for, a few
 * characters at a time, never ending partway through one.
 */
private class CopyOnServer(val id: Long, val text: String, val charactersPerPart: Int = 3) {
    val requests = ArrayList<JsonObject>()
    val bytes: Int get() = text.toByteArray().size

    fun read(args: JsonObject): JsonObject {
        requests += args
        val all = text.toByteArray()
        val rest = String(all, args.long("offset")!!.toInt(), all.size - args.long("offset")!!.toInt())
        val data = rest.take(charactersPerPart)
        return part(id, data, all.size, eof = data.length == rest.length)
    }
}

class CopiedTextTest {
    private val neverRead: suspend (JsonObject) -> JsonObject = { error("a copy that carries its text is not read from the server") }

    @Test
    fun `a small copy is the text in the event`() = runBlocking {
        assertEquals("copied by a program", copiedText(small("copied by a program"), neverRead))
    }

    @Test
    fun `an event that says nothing was copied is no copy`() = runBlocking {
        assertNull(copiedText(null, neverRead))
        assertNull(copiedText(buildJsonObject { put("id", 4) }, neverRead))
    }

    @Test
    fun `a large copy is read in parts, each from the same copy, counting in bytes`() = runBlocking {
        // Two and three bytes to a character, so that bytes and characters come apart.
        val server = CopyOnServer(id = 7, text = "größer 殿 than the event")
        assertEquals(server.text, copiedText(large(7, server.bytes), server::read))
        assertTrue(server.requests.size > 1, "it took ${server.requests.size} requests")
        assertTrue(server.requests.all { it.long("id") == 7L }, "every part is asked of copy 7")
        assertEquals(listOf(0L, 4L, 8L, 13L), server.requests.take(4).map { it.long("offset") })
    }

    @Test
    fun `text over 8 MiB is refused, however it is announced`() {
        runBlocking {
            // By the event, before anything is read.
            assertFailsWith<CopyRefused> { copiedText(large(1, MAX_COPY_BYTES + 1), neverRead) }
            // By the text itself, when the event carries it.
            val text = "x".repeat(MAX_COPY_BYTES + 1)
            assertFailsWith<CopyRefused> { copiedText(buildJsonObject { put("text", text) }, neverRead) }
            // By the server's answer, when the event understated it.
            assertFailsWith<CopyRefused> { copiedText(large(1, 10)) { part(1, "x", MAX_COPY_BYTES + 1, eof = false) } }
            // By the parts adding up to more than was said.
            assertFailsWith<CopyRefused> { copiedText(large(1, 2)) { part(1, "xxx", 2, eof = false) } }
        }
    }

    @Test
    fun `text of exactly 8 MiB is taken`() = runBlocking {
        val text = "x".repeat(MAX_COPY_BYTES)
        assertEquals(MAX_COPY_BYTES, copiedText(buildJsonObject { put("text", text) }, neverRead)?.length)
    }

    @Test
    fun `a read that goes wrong is refused rather than half applied`() {
        runBlocking {
            // Another copy answers partway through.
            var asked = 0
            assertFailsWith<CopyRefused> { copiedText(large(1, 4)) { if (asked++ == 0) part(1, "ab", 4, false) else part(2, "cd", 4, true) } }
            // The length changes between parts.
            asked = 0
            assertFailsWith<CopyRefused> { copiedText(large(1, 4)) { if (asked++ == 0) part(1, "ab", 4, false) else part(1, "cd", 5, false) } }
            // Nothing more comes, and it isn't the end.
            assertFailsWith<CopyRefused> { copiedText(large(1, 4)) { part(1, "", 4, false) } }
            // It ends short of its length.
            assertFailsWith<CopyRefused> { copiedText(large(1, 4)) { part(1, "ab", 4, true) } }
            // There is text to read and no id to read it by.
            assertFailsWith<CopyRefused> { copiedText(buildJsonObject { put("bytes", 4) }, neverRead) }
        }
    }
}

class TerminalInUseTest {
    private fun pane(id: String) = PaneView(id, PaneRect.Full, label = null, ownerClientId = null, isTerminal = true, floating = false)

    private fun tab(id: String, focused: String?, vararg panes: String, zoomed: String? = null) =
        WindowView(id, id, focusedBlockId = focused, zoomedBlockId = zoomed, panes = panes.map(::pane))

    private fun session(id: String, active: String, vararg tabs: WindowView) = id to SessionView(id, id, revision = 1, activeWindowId = active, windows = tabs.toList())

    private val state = HostState(
        selectedSessionId = "work",
        views = mapOf(
            session("work", "editor", tab("editor", "left", "left", "right"), tab("logs", "tail", "tail")),
            session("notes", "todo", tab("todo", "list", "list")),
        ),
    )

    @Test
    fun `the focused pane of the tab on screen is the one in use`() {
        assertTrue(state.isInUse("work", "left"))
    }

    @Test
    fun `another pane of the same tab is not`() {
        assertFalse(state.isInUse("work", "right"))
    }

    @Test
    fun `the focused pane of a tab that isn't on screen is not`() {
        assertFalse(state.isInUse("work", "tail"))
    }

    @Test
    fun `nor is the focused pane of a session that isn't showing`() {
        assertFalse(state.isInUse("notes", "list"))
        // Not even under the name of the session that is.
        assertFalse(state.isInUse("work", "list"))
    }

    @Test
    fun `the tab on screen is the one this app chose, not the session's active one`() {
        val elsewhere = state.copy(shownWindows = mapOf("work" to "logs"))
        assertTrue(elsewhere.isInUse("work", "tail"))
        assertFalse(elsewhere.isInUse("work", "left"))
    }

    @Test
    fun `a zoomed pane is the one in use, whichever has the session's focus`() {
        val zoomed = state.copy(views = mapOf(session("work", "editor", tab("editor", "left", "left", "right", zoomed = "right"))))
        assertTrue(zoomed.isInUse("work", "right"))
        assertFalse(zoomed.isInUse("work", "left"))
    }

    @Test
    fun `nothing is in use before a session is showing`() {
        assertFalse(HostState().isInUse("work", "left"))
    }
}

class ProgramCopiesTest {
    private val delivered = ArrayList<String>()
    private val logged = ArrayList<String>()
    private var inUse = setOf("work" to "left")
    private val copies = ProgramCopies(
        inUse = { sessionId, blockId -> (sessionId to blockId) in inUse },
        log = { message, _ -> logged += message },
        deliver = { _, _, text -> delivered += text },
    )

    /** Runs [body] beside the copies being read, on one thread, so that [settle] lets everything waiting run. */
    private fun reading(read: suspend (args: JsonObject) -> JsonObject, body: suspend CoroutineScope.() -> Unit) = runBlocking {
        val running = launch { copies.run { _, _, args -> read(args) } }
        withTimeout(10_000) { body() }
        running.cancel()
    }

    private suspend fun settle() = repeat(100) { yield() }

    @Test
    fun `a copy from the terminal in use is handed on`() = reading({ error("not read") }) {
        copies.written("work", "left", small("copied by a program"))
        settle()
        assertEquals(listOf("copied by a program"), delivered)
    }

    @Test
    fun `a copy from any other terminal is dropped without being read`() = reading({ error("not read") }) {
        copies.written("work", "right", small("from the other pane"))
        copies.written("work", "tail", large(3, 1_000_000))
        copies.written("notes", "list", small("from another session"))
        settle()
        assertEquals(emptyList(), delivered)
    }

    @Test
    fun `a copy that isn't in use doesn't displace one that is`() = reading({ error("not read") }) {
        copies.written("work", "left", small("wanted"))
        copies.written("work", "right", small("from the other pane"))
        settle()
        assertEquals(listOf("wanted"), delivered)
    }

    @Test
    fun `of copies that arrive together, only the newest is handed on`() = reading({ error("not read") }) {
        copies.written("work", "left", small("first"))
        copies.written("work", "left", small("second"))
        copies.written("work", "left", small("third"))
        settle()
        assertEquals(listOf("third"), delivered)
    }

    @Test
    fun `a newer copy takes the place of one still being read`() {
        val asked = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<JsonObject>()
        reading({ asked.complete(Unit); answer.await() }) {
            copies.written("work", "left", large(1, 5))
            asked.await()
            copies.written("work", "left", small("newer"))
            settle()
            // The older copy's text arrives only now, after the one that followed it.
            answer.complete(part(1, "older", 5, eof = true))
            settle()
            assertEquals(listOf("newer"), delivered)
        }
    }

    @Test
    fun `a copy is dropped if its terminal stops being in use while it is read`() {
        val asked = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<JsonObject>()
        reading({ asked.complete(Unit); answer.await() }) {
            copies.written("work", "left", large(1, 5))
            asked.await()
            inUse = setOf("work" to "right") // focus moved to the other pane
            answer.complete(part(1, "stale", 5, eof = true))
            settle()
            assertEquals(emptyList(), delivered)
        }
    }

    @Test
    fun `a copy that is too large is dropped, and the next one still arrives`() = reading({ error("not read") }) {
        copies.written("work", "left", large(1, MAX_COPY_BYTES + 1))
        settle()
        assertEquals(emptyList(), delivered)
        assertTrue(logged.any { "8 MiB" in it }, "the debug log says why: $logged")
        copies.written("work", "left", small("after"))
        settle()
        assertEquals(listOf("after"), delivered)
    }

    @Test
    fun `a copy the server no longer has is dropped, and the next one still arrives`() =
        reading({ throw RexError("not_found", "no such copy", 404) }) {
            copies.written("work", "left", large(1, 5))
            settle()
            copies.written("work", "left", small("after"))
            settle()
            assertEquals(listOf("after"), delivered)
        }

    @Test
    fun `the debug log never holds what was copied`() = reading({ error("not read") }) {
        copies.written("work", "left", small("hunter2"))
        copies.written("work", "right", small("hunter2"))
        settle()
        assertFalse(logged.any { "hunter2" in it }, "$logged")
    }

    @Test
    fun `a copy waiting when the connection goes is forgotten`() {
        copies.written("work", "left", small("from the old connection"))
        copies.forget()
        reading({ error("not read") }) {
            settle()
            assertEquals(emptyList(), delivered)
        }
    }
}
