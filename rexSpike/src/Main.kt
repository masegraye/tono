package org.example.kotlintest.rexspike

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.system.exitProcess

/**
 * Reads one terminal from the local Rex server and prints its screen.
 *
 * It only looks: it attaches to the block's session and opens its terminal stream, but
 * never types, resizes, or claims the block.
 *
 * Usage: rexSpike [block-id] [session-id]   (defaults: $REX_BLOCK and $REX_SESSION)
 */
fun main(args: Array<String>) = runBlocking {
    val blockId = args.getOrNull(0) ?: System.getenv("REX_BLOCK")
    val sessionId = args.getOrNull(1) ?: System.getenv("REX_SESSION")
    if (blockId == null || sessionId == null) {
        System.err.println("usage: rexSpike <block-id> <session-id>, or run inside a Rex terminal")
        exitProcess(2)
    }
    val socketPath = rexSocketPath()
    val client = rexHttpClient()

    // 1. The control connection.
    val control = RexControl.connect(client, socketPath)
    println("control   connected over $socketPath")
    println("          api revision ${control.welcome.int("api_revision")}, client ${control.welcome["client"]?.jsonObject?.string("id")}")

    val sessions = control.call("session.list").jsonObject["sessions"]?.jsonArray.orEmpty()
    println("sessions  " + sessions.joinToString { it.jsonObject.string("label") ?: it.jsonObject.string("session_id").orEmpty() })

    // 2. Attach to the session, then ask for a one-time ticket to the block's terminal stream.
    control.call("session.attach", buildJsonObject { put("session_id", sessionId) })
    val ticket = control.call(
        "data.connect",
        buildJsonObject {
            put("session_id", sessionId)
            put("block_id", blockId)
            put("data_ref_id", "terminal")
            put("subprotocol", RexTerminalStream.SUBPROTOCOL)
        },
    ).jsonObject.string("ticket") ?: error("data.connect returned no ticket")

    // 3. The data connection.
    val stream = RexTerminalStream.open(client, socketPath, ticket)
    println("stream    open, nonce ${stream.nonce}, resumable for ${stream.resumeWindowMillis} ms")

    // 4. Collect bytes until the whole snapshot has arrived, then restore a terminal from it.
    val received = java.io.ByteArrayOutputStream()
    var snapshotBytes: Int? = null
    var chunks = 0
    val receiveStarted = System.nanoTime()
    while (snapshotBytes == null) {
        received.write(stream.read(timeoutMillis = 5_000) ?: error("The snapshot stopped arriving after ${received.size()} bytes"))
        // Walking the records from the start is too slow to do for every chunk of a large
        // snapshot, so only look every so often and whenever the stream pauses.
        if (++chunks % 64 == 0 || received.size() < 1_000_000 || stream.peekIdle()) {
            snapshotBytes = snapshotLength(received.toByteArray(), received.size())
        }
    }
    val buffer = received.toByteArray()
    val receiveMillis = (System.nanoTime() - receiveStarted) / 1_000_000
    val decodeStarted = System.nanoTime()
    val (terminal, consumed) = GhosttyTerminal.restore(buffer)
    val decodeMillis = (System.nanoTime() - decodeStarted) / 1_000_000
    println("snapshot  $snapshotBytes bytes, format version ${snapshotVersion(buffer)}; libghostty-vt consumed $consumed")
    println("          received in $receiveMillis ms, decoded in $decodeMillis ms")
    check(consumed == snapshotBytes) { "The decoder and the record walk disagree on where the snapshot ends" }

    // 5. Everything after the snapshot is live output. Follow it briefly.
    var liveBytes = 0
    val serverMessages = mutableListOf<String>()
    fun feed(bytes: ByteArray, offset: Int) {
        val length = bytes.size - offset
        terminal.write(bytes, offset, length)
        liveBytes += length
        serverMessages += rexMessages(bytes, offset, length, stream.nonce).map { it.toString() }
    }
    feed(buffer, consumed)
    // A busy terminal never goes quiet, so follow for a fixed time rather than until silence.
    val followUntil = System.nanoTime() + FOLLOW_MILLIS * 1_000_000
    while (true) {
        val remaining = (followUntil - System.nanoTime()) / 1_000_000
        if (remaining <= 0) break
        feed(stream.read(timeoutMillis = remaining) ?: break, 0)
    }
    println("live      $liveBytes bytes in the $FOLLOW_MILLIS ms after the snapshot")
    serverMessages.forEach { println("          server message: ${it.take(140)}") }

    // 6. What the terminal looks like now.
    val lines = terminal.text().lines()
    println("terminal  ${terminal.columns}x${terminal.rows}, cursor at column ${terminal.cursorColumn}, row ${terminal.cursorRow}; formatted text is ${lines.size} lines")
    println("─".repeat(terminal.columns.coerceAtMost(100)))
    lines.takeLast(terminal.rows.coerceAtMost(24)).forEach(::println)
    println("─".repeat(terminal.columns.coerceAtMost(100)))

    terminal.close()
    stream.close()
    control.call("session.detach", buildJsonObject { put("session_id", sessionId) })
    control.close()
    client.close()
    println("done      ${control.eventsSeen} events arrived on the control connection meanwhile")
}

/** How long to follow live output after the snapshot. */
private const val FOLLOW_MILLIS = 2_000L
