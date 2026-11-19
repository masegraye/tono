package org.example.kotlintest.rex

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/** A file on this machine, or bytes already in memory, such as an image off the clipboard. */
actual class UploadContent private constructor(internal val path: Path?, internal val bytes: ByteArray?) {
    constructor(path: Path) : this(path, null)
    constructor(bytes: ByteArray) : this(null, bytes)

    /** The bytes, from the start. */
    fun open(): InputStream = bytes?.inputStream() ?: Files.newInputStream(path!!)
}

/** A file on this machine, as it is now. Throws [UploadFailed] for a folder, or anything else that isn't a file to read. */
fun uploadFile(path: Path): UploadFile {
    val name = path.fileName?.toString() ?: "file"
    if (Files.isDirectory(path)) throw UploadFailed("$name is a folder. Folders can't be uploaded.", name)
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) throw UploadFailed("$name can't be read", name)
    return UploadFile(name, Files.size(path), UploadContent(path))
}

fun uploadFile(name: String, bytes: ByteArray): UploadFile = UploadFile(name, bytes.size.toLong(), UploadContent(bytes))

internal actual suspend fun HttpClient.sendUpload(endpoint: RexEndpoint, ticket: String, file: UploadFile, onSent: (Long) -> Unit) {
    val socket = try {
        within(UPLOAD_ANSWER_MILLIS) { rexWebSocket(endpoint, "/api/data", UPLOAD_SUBPROTOCOL) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw UploadFailed("The upload connection couldn't be opened: ${failure.message ?: failure::class.simpleName}")
    }
    try {
        socket.upload(ticket, file, onSent)
    } catch (failure: UploadFailed) {
        throw failure
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw UploadFailed("${file.name} wasn't uploaded: ${failure.message ?: failure::class.simpleName}", file.name)
    } finally {
        // Finished or not, there is nothing more to say on it. Dropped rather than closed
        // politely when it failed part way: a normal close would be no different to the
        // server, which types nothing for a file that is short.
        socket.cancel()
    }
}

/** The exchange on an upload's connection, once it is open. */
internal suspend fun DefaultClientWebSocketSession.upload(ticket: String, file: UploadFile, onSent: (Long) -> Unit) {
    val ticketBytes = decodeBase64Url(ticket)
    if (ticketBytes.size != 16) throw UploadFailed("Rex issued a ticket of ${ticketBytes.size} bytes, where it is always 16")
    send(Frame.Binary(true, byteArrayOf(0x01) + ticketBytes))
    val answer = (within(UPLOAD_ANSWER_MILLIS) { incoming.receiveCatching().getOrNull() } as? Frame.Binary)?.readBytes()
        ?: throw UploadFailed("Rex didn't answer the upload's connection")
    if (answer.firstOrNull() == 0x82.toByte()) throw UploadFailed(uploadRejectFailure(answer.getOrNull(1)?.toInt()))
    if (answer.firstOrNull() != 0x81.toByte()) throw UploadFailed("Rex answered the upload's connection with something unexpected")

    var sent = 0L
    withContext(Dispatchers.IO) {
        file.content.open().use { input ->
            val buffer = ByteArray(UPLOAD_CHUNK_BYTES)
            while (sent < file.size) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), file.size - sent).toInt())
                if (count <= 0) break
                send(Frame.Binary(true, buffer.copyOf(count)))
                sent += count
                onSent(sent)
            }
        }
    }
    // The size was promised when the file was reserved, and the server holds to it.
    if (sent != file.size) throw UploadFailed("${file.name} changed while it was uploading", file.name)
    send(Frame.Binary(true, ByteArray(0)))
    val closed = try {
        within(UPLOAD_FINISH_MILLIS) {
            for (frame in incoming) { /* nothing is expected but the close */ }
            closeReason.await()
        }
    } catch (_: RexDisconnected) {
        throw UploadFailed("Rex didn't finish the upload")
    }
    if (closed?.code?.toInt() != 1000) throw UploadFailed(uploadCloseFailure(closed?.code?.toInt(), closed?.message))
}

private const val UPLOAD_ANSWER_MILLIS = 10_000L

/** How long the server has to close the connection once it has the whole file, as in Rex Rune. */
private const val UPLOAD_FINISH_MILLIS = 120_000L
