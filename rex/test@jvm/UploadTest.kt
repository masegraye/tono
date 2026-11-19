package org.example.kotlintest.rex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun file(name: String, size: Int = name.length) = uploadFile(name, ByteArray(size))

/** The server's side of a batch, as far as [uploadBatch] sees it: what was reserved, and what was sent on which ticket. */
private class Uploads {
    val begun = mutableListOf<JsonObject>()
    val sent = mutableListOf<String>()
    /** Everything in the order it happened. */
    val order = mutableListOf<String>()
    var refuse: (JsonObject) -> RexError? = { null }

    suspend fun begin(args: JsonObject): JsonObject {
        refuse(args)?.let { throw it }
        begun += args
        order += "begin ${args.string("name")}"
        return buildJsonObject { put("ticket", "ticket-${args.string("name")}"); put("expires_in_ms", 60000) }
    }

    suspend fun send(ticket: String, file: UploadFile, onSent: (Long) -> Unit) {
        order += "send ${file.name}"
        assertEquals("ticket-${file.name}", ticket)
        onSent(file.size)
        sent += file.name
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class UploadTest {
    @Test
    fun oneFileIsReservedOnItsOwn() = runTest {
        val server = Uploads()
        uploadBatch(listOf(file("shot.png", 7)), apiRevision = 12, server::begin, server::send)
        val args = server.begun.single()
        assertEquals("shot.png", args.string("name"))
        assertEquals(7, args.long("size"))
        assertNull(args["batch"])
        assertNull(args["index"])
        assertNull(args["count"])
        assertTrue(Regex("upload:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(args.string("ref")!!), args.string("ref"))
        assertEquals(listOf("shot.png"), server.sent)
    }

    @Test
    fun severalFilesAreOneBatchInTheOrderGiven() = runTest {
        val server = Uploads()
        uploadBatch(listOf(file("a"), file("b"), file("c")), apiRevision = 11, server::begin, server::send)
        assertEquals(listOf("a", "b", "c"), server.begun.map { it.string("name") })
        assertEquals(listOf(0L, 1L, 2L), server.begun.map { it.long("index") })
        assertEquals(setOf(3L), server.begun.map { it.long("count") }.toSet())
        val batch = server.begun.map { it.string("batch") }.toSet().single()!!
        assertTrue(Regex("[0-9a-f-]{36}").matches(batch))
        assertEquals(3, server.begun.map { it.string("ref") }.toSet().size, "Each file has a reference of its own")
        assertFalse(server.begun.any { it.string("ref") == "upload:$batch" })
    }

    /** A batch left half reserved is aborted by the server after a minute, so no file waits for another to be sent. */
    @Test
    fun everyFileIsReservedBeforeAnyIsSent() = runTest {
        val server = Uploads()
        uploadBatch(listOf(file("a"), file("b")), apiRevision = 12, server::begin, server::send)
        assertEquals(listOf("begin a", "begin b"), server.order.take(2))
        assertEquals(setOf("send a", "send b"), server.order.drop(2).toSet())
    }

    @Test
    fun filesAreSentTogetherAndNotOneAfterAnother() = runTest {
        val started = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        val server = Uploads()
        uploadBatch(listOf(file("a"), file("b"), file("c")), apiRevision = 12, server::begin, send = { _, file, _ ->
            started += file.name
            if (started.size == 3) release.complete(Unit)
            release.await() // would never come if the files went in turn
        })
        assertEquals(3, started.size)
    }

    @Test
    fun anOlderServerIsAskedNothing() = runTest {
        val server = Uploads()
        val failure = assertFailsWith<UploadFailed> { uploadBatch(listOf(file("a")), apiRevision = 10, server::begin, server::send) }
        assertTrue("too old" in failure.message!!, failure.message)
        assertTrue(server.order.isEmpty())
    }

    @Test
    fun aFileOverTheLimitStopsTheWholeDropBeforeAnythingIsReserved() = runTest {
        val server = Uploads()
        val big = UploadFile("film.mkv", MAX_UPLOAD_BYTES + 1, UploadContent(ByteArray(0)))
        val failure = assertFailsWith<UploadFailed> { uploadBatch(listOf(file("a"), big), apiRevision = 12, server::begin, server::send) }
        assertEquals("film.mkv is 100 MB. A terminal accepts files up to 100 MB.", failure.message)
        assertEquals("a file is 100 MB. A terminal accepts files up to 100 MB.", failure.withoutName)
        assertTrue(server.order.isEmpty())
        // Exactly the limit goes.
        uploadBatch(listOf(UploadFile("just.bin", MAX_UPLOAD_BYTES, UploadContent(ByteArray(0)))), apiRevision = 12, server::begin, server::send)
        assertEquals(listOf("just.bin"), server.sent)
    }

    @Test
    fun tooManyFilesAtOnceAreRefused() = runTest {
        val server = Uploads()
        val failure = assertFailsWith<UploadFailed> {
            uploadBatch(List(MAX_UPLOAD_FILES + 1) { file("f$it") }, apiRevision = 12, server::begin, server::send)
        }
        assertEquals("101 files are too many to upload at once. The most is 100.", failure.message)
        assertTrue(server.order.isEmpty())
    }

    @Test
    fun aFileTheServerRefusesMeansNothingIsSent() = runTest {
        val server = Uploads()
        server.refuse = { args -> if (args.string("name") == "b") RexError("bad_request", "upload name \"..\" is not a file name", 400) else null }
        val failure = assertFailsWith<UploadFailed> { uploadBatch(listOf(file("a"), file("b"), file("c")), apiRevision = 12, server::begin, server::send) }
        assertEquals("b: upload name \"..\" is not a file name", failure.message)
        assertEquals(listOf("begin a"), server.order, "The first was reserved, and is left to expire; none was sent")
    }

    @Test
    fun aServerThatNeverAnswersIsGivenUpOn() = runTest {
        val failure = assertFailsWith<UploadFailed> {
            uploadBatch(listOf(file("a")), apiRevision = 12, begin = { awaitCancellation() }, send = { _, _, _ -> error("not reached") })
        }
        assertEquals("The server didn't answer in time", failure.message)
    }

    @Test
    fun oneFileFailingStopsTheOthers() = runTest {
        val server = Uploads()
        var cancelled = 0
        var finished = 0
        val failure = assertFailsWith<UploadFailed> {
            uploadBatch(listOf(file("a"), file("b"), file("c")), apiRevision = 12, server::begin, send = { _, file, _ ->
                if (file.name == "b") {
                    delay(100)
                    throw UploadFailed("Rex refused the upload: data stream broke a rule (4006)")
                }
                try {
                    delay(60_000)
                    finished++
                } finally {
                    cancelled++
                }
            })
        }
        assertEquals("Rex refused the upload: data stream broke a rule (4006)", failure.message)
        assertEquals(2, cancelled)
        assertEquals(0, finished)
    }

    @Test
    fun aFailureThatIsNotAnUploadsOwnIsStillOne() = runTest {
        val server = Uploads()
        val failure = assertFailsWith<UploadFailed> {
            uploadBatch(listOf(file("a")), apiRevision = 12, server::begin, send = { _, _, _ -> throw IllegalStateException("socket fell over") })
        }
        assertEquals("socket fell over", failure.message)
    }

    @Test
    fun progressIsTheSumOverTheFiles() = runTest {
        val server = Uploads()
        val progress = mutableListOf<Long>()
        uploadBatch(listOf(file("a", 100), file("b", 50)), apiRevision = 12, server::begin, send = { _, file, onSent ->
            onSent(file.size / 2)
            delay(if (file.name == "a") 10 else 20)
            onSent(file.size)
        }, onProgress = { progress += it })
        assertEquals(listOf(50L, 75L, 125L, 150L), progress)
    }

    @Test
    fun nothingToUploadAsksNothing() = runTest {
        val server = Uploads()
        uploadBatch(emptyList(), apiRevision = 1, server::begin, server::send)
        assertTrue(server.order.isEmpty())
    }

    @Test
    fun sizesAreSaidInWords() {
        assertEquals("0 bytes", byteCount(0))
        assertEquals("1 byte", byteCount(1))
        assertEquals("1023 bytes", byteCount(1023))
        assertEquals("1 KB", byteCount(1024))
        assertEquals("1.5 KB", byteCount(1536))
        assertEquals("2.4 MB", byteCount(2_500_000))
        assertEquals("100 MB", byteCount(MAX_UPLOAD_BYTES))
        assertEquals("1.5 GB", byteCount(3L * 512 * 1024 * 1024))
    }

    @Test
    fun whyAnUploadsConnectionClosed() {
        assertEquals("Rex refused the upload: data stream broke a rule (4006)", uploadCloseFailure(4006, "data stream broke a rule"))
        assertEquals("Rex refused the upload (1011)", uploadCloseFailure(1011, ""))
        assertEquals("The upload connection ended early", uploadCloseFailure(null, null))
        assertEquals("The terminal has closed", uploadRejectFailure(3))
        assertTrue("expired" in uploadRejectFailure(1))
        assertTrue("reason 6" in uploadRejectFailure(6))
    }
}
