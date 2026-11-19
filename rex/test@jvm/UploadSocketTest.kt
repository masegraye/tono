package org.example.kotlintest.rex

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A file's bytes on an upload's connection, against a server that speaks as Rex was seen to:
 * a HELLO with the ticket is answered with a WELCOME or a REJECT, the file follows in
 * messages of 64 KiB at most, an empty message ends it, and the server's close says how it went.
 */
class UploadSocketTest {
    /** What the server does once it has a HELLO. */
    private enum class Manner { Accept, Reject, BreakRule, Silent }

    /** One upload connection as the server saw it. */
    private class Seen {
        @Volatile var protocol: String? = null
        @Volatile var path: String? = null
        @Volatile var hello: ByteArray? = null
        val messages = CopyOnWriteArrayList<ByteArray>()
        /** Whether the empty message that ends an upload arrived. */
        @Volatile var ended = false
        @Volatile var gone = false
        val bytes: ByteArray get() = ByteArrayOutputStream().also { out -> messages.forEach(out::write) }.toByteArray()
    }

    /** Just enough of a WebSocket server: the handshake, the client's masked frames, and a close. */
    private class Server(private val manner: Manner) : AutoCloseable {
        private val listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val endpoint = RexEndpoint.parse("http://127.0.0.1:${listener.localPort}")
        val seen = Seen()

        init {
            thread(isDaemon = true) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: return@thread
                runCatching { serve(socket) }
                seen.gone = true
                runCatching { socket.close() }
            }
        }

        private fun serve(socket: Socket) {
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = socket.getOutputStream()
            val request = generateSequence { readLine(input).takeIf { it.isNotEmpty() } }.toList()
            fun header(name: String) = request.firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()
            seen.path = request.first().split(' ')[1]
            seen.protocol = header("Sec-WebSocket-Protocol")
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((header("Sec-WebSocket-Key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
            )
            output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\nSec-WebSocket-Protocol: ${seen.protocol}\r\n\r\n".toByteArray())
            output.flush()
            fun send(opcode: Int, payload: ByteArray) {
                output.write(byteArrayOf((0x80 or opcode).toByte(), payload.size.toByte()) + payload)
                output.flush()
            }
            fun close(code: Int, reason: String) = send(0x8, byteArrayOf((code shr 8).toByte(), code.toByte()) + reason.toByteArray())
            while (true) {
                val (opcode, payload) = try { readFrame(input) } catch (_: EOFException) { return }
                when {
                    opcode == 0x8 -> return
                    opcode != 0x2 -> {}
                    seen.hello == null -> {
                        seen.hello = payload
                        when (manner) {
                            Manner.Reject -> { send(0x2, byteArrayOf(0x82.toByte(), 0x01)); close(1008, "data handshake rejected"); return }
                            Manner.Silent -> {}
                            else -> send(0x2, ByteArray(32).also { it[0] = 0x81.toByte() })
                        }
                    }
                    payload.isEmpty() -> {
                        seen.ended = true
                        close(1000, "")
                    }
                    else -> {
                        seen.messages += payload
                        if (manner == Manner.BreakRule) { close(4006, "data stream broke a rule"); return }
                    }
                }
            }
        }

        private fun readLine(input: DataInputStream): String {
            val line = StringBuilder()
            while (true) {
                val char = input.readUnsignedByte().toChar()
                if (char == '\n') return line.toString().trimEnd('\r')
                line.append(char)
            }
        }

        private fun readFrame(input: DataInputStream): Pair<Int, ByteArray> {
            val opcode = input.readUnsignedByte() and 0x0F
            val second = input.readUnsignedByte()
            val length = when (val short = second and 0x7F) {
                126 -> input.readUnsignedShort().toLong()
                127 -> input.readLong()
                else -> short.toLong()
            }
            val mask = ByteArray(4).also { if (second and 0x80 != 0) input.readFully(it) }
            val payload = ByteArray(length.toInt()).also(input::readFully)
            for (index in payload.indices) payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
            return opcode to payload
        }

        override fun close() = listener.close()
    }

    private val http = rexHttpClient()
    private val scratch = Files.createTempDirectory("tono-upload-test")

    @AfterTest
    fun tidy() {
        http.close()
        scratch.toFile().deleteRecursively()
    }

    /** Sixteen bytes, as a ticket is, in the form the server issues it. */
    private val ticketBytes = ByteArray(16) { (it * 17 + 3).toByte() }
    private val ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(ticketBytes)

    private fun send(server: Server, file: UploadFile, onSent: (Long) -> Unit = {}) = runBlocking {
        withTimeout(20_000) { http.sendUpload(server.endpoint, ticket, file, onSent) }
    }

    @Test
    fun aFileGoesUpInPiecesAndEndsWithAnEmptyMessage() = Server(Manner.Accept).use { server ->
        val bytes = ByteArray(200_000) { (it * 31).toByte() }
        val path = Files.write(scratch.resolve("a b.bin"), bytes)
        val progress = mutableListOf<Long>()
        send(server, uploadFile(path), progress::add)

        assertEquals("/api/data", server.seen.path)
        assertEquals("rex.data.bytes.v1", server.seen.protocol)
        assertContentEquals(byteArrayOf(0x01) + ticketBytes, server.seen.hello)
        assertContentEquals(bytes, server.seen.bytes)
        assertTrue(server.seen.ended)
        assertEquals(listOf(65536, 65536, 65536, 3392), server.seen.messages.map { it.size }, "No message is over the 64 KiB the server takes")
        assertEquals(listOf(65536L, 131072L, 196608L, 200000L), progress)
    }

    @Test
    fun bytesInMemoryGoTheSameWay() = Server(Manner.Accept).use { server ->
        val png = ByteArray(1000) { it.toByte() }
        send(server, uploadFile(PASTED_IMAGE_NAME, png))
        assertContentEquals(png, server.seen.bytes)
        assertTrue(server.seen.ended)
    }

    @Test
    fun anEmptyFileIsOnlyTheEnd() = Server(Manner.Accept).use { server ->
        send(server, uploadFile("empty.txt", ByteArray(0)))
        assertTrue(server.seen.messages.isEmpty())
        assertTrue(server.seen.ended)
    }

    @Test
    fun aRefusedTicketIsAFailure() = Server(Manner.Reject).use { server ->
        val failure = assertFailsWith<UploadFailed> { send(server, uploadFile("a.txt", ByteArray(10))) }
        assertTrue("expired" in failure.message!!, failure.message)
        assertTrue(server.seen.messages.isEmpty(), "Nothing of the file was sent")
    }

    @Test
    fun aCloseThatIsNotSuccessIsAFailureWithTheServersReason() = Server(Manner.BreakRule).use { server ->
        val failure = assertFailsWith<UploadFailed> { send(server, uploadFile("a.txt", ByteArray(10))) }
        assertEquals("Rex refused the upload: data stream broke a rule (4006)", failure.message)
    }

    /** The size was promised when the file was reserved. Short of it, the upload is never ended, so nothing is typed. */
    @Test
    fun aFileThatShrankIsNeverEnded() = Server(Manner.Accept).use { server ->
        val path = Files.write(scratch.resolve("log.txt"), ByteArray(100))
        val file = uploadFile(path)
        Files.write(path, ByteArray(40))
        val failure = assertFailsWith<UploadFailed> { send(server, file) }
        assertEquals("log.txt changed while it was uploading", failure.message)
        waitFor { server.seen.gone }
        assertEquals(false, server.seen.ended)
    }

    /** One that grew is sent as long as it was promised to be, and no longer. */
    @Test
    fun aFileThatGrewIsSentAtTheSizeItWasReservedWith() = Server(Manner.Accept).use { server ->
        val path = Files.write(scratch.resolve("log.txt"), ByteArray(100) { 1 })
        val file = uploadFile(path)
        Files.write(path, ByteArray(500) { 1 })
        send(server, file)
        assertEquals(100, server.seen.bytes.size)
        assertTrue(server.seen.ended)
    }

    @Test
    fun aServerThatIsNotThereIsAFailure() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val failure = assertFailsWith<UploadFailed> {
            runBlocking { http.sendUpload(RexEndpoint.parse("http://127.0.0.1:$port"), ticket, uploadFile("a", ByteArray(1))) {} }
        }
        assertTrue(failure.message!!.startsWith("The upload connection couldn't be opened"), failure.message)
    }

    @Test
    fun aTicketOfTheWrongLengthIsNotSent() = Server(Manner.Accept).use { server ->
        val failure = assertFailsWith<UploadFailed> {
            runBlocking { http.sendUpload(server.endpoint, "AAAA", uploadFile("a", ByteArray(1))) {} }
        }
        assertTrue("16" in failure.message!!, failure.message)
        assertEquals(null, server.seen.hello)
    }

    @Test
    fun foldersAndMissingFilesAreNotFilesToUpload() {
        val folder = Files.createDirectory(scratch.resolve("photos"))
        assertEquals("photos is a folder. Folders can't be uploaded.", assertFailsWith<UploadFailed> { uploadFile(folder) }.message)
        assertEquals("gone.txt can't be read", assertFailsWith<UploadFailed> { uploadFile(scratch.resolve("gone.txt")) }.message)
        val real = uploadFile(Files.write(scratch.resolve("it's here.txt"), ByteArray(12)))
        assertEquals("it's here.txt", real.name)
        assertEquals(12, real.size)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(condition())
    }
}
