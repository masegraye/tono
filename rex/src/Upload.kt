package org.example.kotlintest.rex

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/*
 * A file dropped on a terminal, or an image pasted into one, goes to the machine the terminal
 * runs on, which may not be this one. The Rex server takes it: each file is reserved with the
 * terminal's `upload_begin`, which answers with a ticket for a data connection, and its bytes
 * go up that connection. The server keeps the files in a directory of its own, and once every
 * file of a drop has arrived it types their paths into the terminal itself, quoted for a
 * shell, as a paste. So nothing is typed from here, and nothing is typed at all unless every
 * file arrived: a drop that fails in any part leaves the prompt as it was.
 *
 * The rules are Rex Rune's (its docs/terminal.md, "Uploading files"). Nothing is retried.
 * What the server was seen to do, on API revision 12:
 *
 * - A file may be 100 MiB at most; a larger one is refused when it is reserved.
 * - A message on the data connection may be 64 KiB at most; a larger one ends the connection.
 * - Each connection is closed with status 1000 as soon as its own file is complete.
 * - A batch whose files aren't all reserved within about a minute is aborted, and a ticket
 *   lasts a minute. So a drop's files are all reserved first and all sent at once, rather
 *   than a few at a time.
 */

/** The first API revision with `upload_begin`. */
const val UPLOAD_MIN_API_REVISION = 11

/**
 * The most files one drop may hold. Each file has a connection of its own, all open at once
 * (see above), and a browser allows a page only so many.
 */
const val MAX_UPLOAD_FILES = 100

/** The most a terminal accepts in one file, as the server says when it refuses a larger one. */
const val MAX_UPLOAD_BYTES = 100L * 1024 * 1024

/** How much of a file goes in one message: the most the server takes. */
internal const val UPLOAD_CHUNK_BYTES = 64 * 1024

/** What an upload's data connection speaks. */
internal const val UPLOAD_SUBPROTOCOL = "rex.data.bytes.v1"

/** The name a pasted image is uploaded under, as in Rex Rune. */
const val PASTED_IMAGE_NAME = "clipboard.png"

/** Where a file's bytes are: a path or bytes in memory on the desktop, a `File` or `Blob` in a browser. */
expect class UploadContent

/** One file to upload: the name the server keeps it under (its last path component is used), and its exact size. */
class UploadFile(val name: String, val size: Long, val content: UploadContent)

/**
 * Sends one reserved file up the data connection its [ticket] is for: exactly its size in
 * bytes, in order, then an empty message, which ends the upload. Returns once the server has
 * closed the connection with status 1000, and throws [UploadFailed] for anything else.
 * [onSent] is told how many of the file's bytes have gone so far. Cancelling it drops the
 * connection, which leaves nothing on the server.
 */
internal expect suspend fun HttpClient.sendUpload(endpoint: RexEndpoint, ticket: String, file: UploadFile, onSent: (Long) -> Unit)

/** Why an upload didn't happen, in words for the person who dropped the files. */
class UploadFailed(message: String, private val fileName: String? = null) : Exception(message) {
    /** The same without the file's name, for the debug log, which is something people paste elsewhere. */
    val withoutName: String get() = fileName?.takeIf { it.isNotEmpty() }?.let { message?.replace(it, "a file") } ?: message.orEmpty()
}

/** An upload under way, or one that failed, for the pane it was meant for to show. */
data class Upload(
    val id: Long,
    val blockId: String,
    val fileCount: Int,
    val totalBytes: Long,
    val sentBytes: Long = 0,
    val startedAtMillis: Long = 0,
    /** Why it failed, once it has. Nothing was typed. */
    val failure: String? = null,
)

/**
 * Uploads [files] as one batch, whose paths the server types together in this order once all
 * of them have arrived. [begin] calls the terminal's `upload_begin` with the arguments it is
 * given, and [send] carries one file up its ticket's connection. Throws [UploadFailed] if any
 * part fails, in which case the files still on their way are dropped and nothing is typed.
 */
@OptIn(ExperimentalUuidApi::class)
internal suspend fun uploadBatch(
    files: List<UploadFile>,
    apiRevision: Int,
    begin: suspend (args: JsonObject) -> JsonObject,
    send: suspend (ticket: String, file: UploadFile, onSent: (Long) -> Unit) -> Unit,
    onProgress: (sentBytes: Long) -> Unit = {},
    newId: () -> String = { Uuid.random().toString() },
) {
    if (files.isEmpty()) return
    if (apiRevision < UPLOAD_MIN_API_REVISION) throw UploadFailed("This host's Rex server is too old to receive files. Update Rex there.")
    if (files.size > MAX_UPLOAD_FILES) throw UploadFailed("${files.size} files are too many to upload at once. The most is $MAX_UPLOAD_FILES.")
    // Asked of every file before any is reserved, so that one that can't go costs nothing.
    files.firstOrNull { it.size > MAX_UPLOAD_BYTES }?.let {
        throw UploadFailed("${it.name} is ${byteCount(it.size)}. A terminal accepts files up to ${byteCount(MAX_UPLOAD_BYTES)}.", it.name)
    }
    val batch = if (files.size > 1) newId() else null
    val tickets = files.mapIndexed { index, file ->
        val reply = try {
            within(BEGIN_TIMEOUT_MILLIS) {
                begin(buildJsonObject {
                    put("ref", "upload:" + newId())
                    put("name", file.name)
                    put("size", file.size)
                    if (batch != null) {
                        put("batch", batch)
                        put("index", index)
                        put("count", files.size)
                    }
                })
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: RexError) {
            // "too large: the file is … bytes, more than the … a terminal accepts" and the like.
            throw UploadFailed("${file.name}: ${refused.message?.substringAfter(": ") ?: refused.code}", file.name)
        } catch (failure: Exception) {
            throw UploadFailed(failure.message ?: "Rex didn't answer")
        }
        reply.string("ticket") ?: throw UploadFailed("Rex issued no ticket for ${file.name}", file.name)
    }
    val sent = LongArray(files.size)
    // The first failure cancels the rest: a batch missing a file types nothing, so what the
    // others would still send is wasted.
    try {
        coroutineScope {
            files.indices.map { index ->
                async {
                    send(tickets[index], files[index]) { bytes ->
                        // Reported from one thread in a browser, and from several on the desktop,
                        // where a figure a moment out of date is no harm.
                        sent[index] = bytes
                        onProgress(sent.sum())
                    }
                }
            }.awaitAll()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: UploadFailed) {
        throw failure
    } catch (failure: Exception) {
        throw UploadFailed(failure.message ?: "The upload connection failed")
    }
}

private const val BEGIN_TIMEOUT_MILLIS = 8_000L

/** A size in words: `512 bytes`, `1.5 MB`. In units of 1024, which is how the server's limit is counted. */
fun byteCount(bytes: Long): String = when {
    bytes < 1024 -> "$bytes byte${if (bytes == 1L) "" else "s"}"
    bytes < 1024 * 1024 -> oneDecimal(bytes / 1024.0) + " KB"
    bytes < 1024L * 1024 * 1024 -> oneDecimal(bytes / (1024.0 * 1024)) + " MB"
    else -> oneDecimal(bytes / (1024.0 * 1024 * 1024)) + " GB"
}

private fun oneDecimal(value: Double): String {
    val tenths = kotlin.math.round(value * 10).toLong()
    return if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
}

/** Why the server closed an upload's connection with something other than success. */
internal fun uploadCloseFailure(code: Int?, reason: String?): String = when {
    code == null -> "The upload connection ended early"
    !reason.isNullOrEmpty() -> "Rex refused the upload: $reason ($code)"
    else -> "Rex refused the upload ($code)"
}

/** Why the server refused an upload's ticket, from the reason in its REJECT. */
internal fun uploadRejectFailure(reason: Int?): String = when (reason) {
    1 -> "Rex no longer knows this upload: its ticket expired or its session is gone"
    3 -> "The terminal has closed"
    else -> "Rex refused the upload's connection (reason $reason)"
}
