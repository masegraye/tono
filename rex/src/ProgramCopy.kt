package org.example.kotlintest.rex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * A program in a terminal copies by writing OSC 52: Neovim yanking to the clipboard, tmux, an
 * agent's copy button. The server may be another machine, so it can't put the text anywhere
 * useful itself; it tells every client with a `clipboard_written` event, and the client the
 * user is at puts it on that machine's clipboard.
 *
 * The rules are Rex Rune's. Only the terminal in use may change the clipboard, or a program
 * in a tab nobody is looking at could replace what the user is about to paste. The other
 * direction doesn't exist at all: a program's request to read the clipboard is never
 * answered, and nothing here sends the clipboard anywhere.
 */

/**
 * Text a program copied, on its way to this machine's clipboard.
 *
 * Not a data class: what is copied is often a secret, and a `toString` that printed it
 * would sooner or later put one in a log.
 */
class ProgramCopy(
    /** The label of the host the terminal is on. */
    val host: String,
    val sessionId: String,
    val blockId: String,
    val text: String,
)

/** The most text a program may copy at once, in bytes of UTF-8: 8 MiB, as in Rex Rune. */
const val MAX_COPY_BYTES = 8 * 1024 * 1024

/** A copy that isn't applied. The message says why, for the debug log, and never holds the text. */
internal class CopyRefused(message: String) : Exception(message)

/**
 * The text of a `clipboard_written` event, given the event's own fields. A small copy carries
 * its text. A larger one carries an id, and its text is fetched a part at a time with [read],
 * which calls the terminal's `clipboard_read` with the arguments it is given.
 *
 * Null when the event says nothing was copied.
 */
internal suspend fun copiedText(fields: JsonObject?, read: suspend (args: JsonObject) -> JsonObject): String? {
    val declared = fields?.long("bytes") ?: 0
    if (declared > MAX_COPY_BYTES) throw tooLarge()
    fields?.string("text")?.takeIf { it.isNotEmpty() }?.let { text ->
        if (text.encodeToByteArray().size > MAX_COPY_BYTES) throw tooLarge()
        return text
    }
    if (declared <= 0) return null
    // Without an id the server would hand over its newest copy, which need not be this one.
    val id = fields?.long("id")?.takeIf { it > 0 } ?: throw CopyRefused("the server sent neither the text nor an id to read it by")

    val text = StringBuilder()
    var offset = 0L
    var total = -1L
    while (true) {
        val part = read(buildJsonObject {
            put("id", id)
            put("offset", offset)
            put("max_bytes", READ_BYTES)
        })
        // Every part names the copy it came from. A program can copy again while this one is
        // being read, and half of one text joined to half of another is worse than nothing.
        if (part.long("id") != id) throw CopyRefused("the server answered with another copy")
        val size = part.long("total") ?: throw CopyRefused("the server didn't say how long the text is")
        if (size > MAX_COPY_BYTES) throw tooLarge()
        if (total >= 0 && size != total) throw CopyRefused("its length changed while it was being read")
        total = size
        val data = part.string("data") ?: throw CopyRefused("the server sent a part with no text")
        val atEnd = (part["eof"] as? JsonPrimitive)?.booleanOrNull == true
        if (data.isEmpty() && !atEnd) throw CopyRefused("the server stopped sending before the end")
        // The server counts in bytes of UTF-8, not in characters.
        offset += data.encodeToByteArray().size
        if (offset > total) throw CopyRefused("the server sent more text than it said there was")
        text.append(data)
        if (atEnd) {
            if (offset != total) throw CopyRefused("the text ended before all of it had arrived")
            return text.toString()
        }
    }
}

private fun tooLarge() = CopyRefused("it is over the ${MAX_COPY_BYTES / (1024 * 1024)} MiB a program may copy")

/**
 * How much of a large copy to ask for at a time. The server's own default is 48 KiB, which
 * makes the largest copy allowed some 170 requests; at this size it is about 30, which
 * matters to a server reached over a network. The server gives at most 960 KiB.
 */
private const val READ_BYTES = 256 * 1024

/**
 * What one host does with the copies its programs make: decides which count, reads their
 * text, and hands on the ones that still count once it is read.
 *
 * Only the newest copy counts. One that arrives while an older one is still being read takes
 * its place, and the older one is given up, so a large copy that is slow to arrive can never
 * land after the one that followed it.
 *
 * @param inUse whether a terminal is the one the user is at, by session and block. Asked when
 *   a copy arrives, and again when its text has been read, which may be a while later.
 * @param log says what happened to a copy in the debug log. Never the text.
 * @param deliver takes the text of a copy that counts
 */
internal class ProgramCopies(
    private val inUse: (sessionId: String, blockId: String) -> Boolean,
    private val log: (message: String, isError: Boolean) -> Unit,
    private val deliver: (sessionId: String, blockId: String, text: String) -> Unit,
) {
    private class Written(val sessionId: String, val blockId: String, val fields: JsonObject?)

    private val waiting = Channel<Written>(Channel.CONFLATED)

    /**
     * Takes a `clipboard_written` event from a terminal. A copy from any terminal but the one
     * in use is dropped here, before anything is read, and so never displaces one that counts.
     */
    fun written(sessionId: String, blockId: String, fields: JsonObject?) {
        if (inUse(sessionId, blockId)) waiting.trySend(Written(sessionId, blockId, fields))
        else log("ignored a copy from a terminal that isn't the one in use", false)
    }

    /**
     * Reads and hands on the copies that arrive, until cancelled. [read] calls the
     * `clipboard_read` of a terminal on the connection the copies come from.
     */
    suspend fun run(read: suspend (sessionId: String, blockId: String, args: JsonObject) -> JsonObject) {
        waiting.receiveAsFlow().collectLatest { copy ->
            val text = try {
                copiedText(copy.fields) { args -> read(copy.sessionId, copy.blockId, args) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Refused here, or by the server: it keeps a copy for about a minute.
                log("didn't take a program's copy: ${failure.message}", true)
                null
            }
            if (text == null) return@collectLatest
            if (inUse(copy.sessionId, copy.blockId)) deliver(copy.sessionId, copy.blockId, text)
            else log("ignored a copy: its terminal stopped being the one in use while it was read", false)
        }
    }

    /** Forgets a copy that is waiting to be read, as when the connection it came on is gone. */
    fun forget() {
        waiting.tryReceive()
    }
}
