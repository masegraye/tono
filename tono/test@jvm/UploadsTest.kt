package org.example.kotlintest

import org.example.kotlintest.rex.Upload
import java.awt.Color
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A clipboard, or something dragged, holding what it is given and nothing else. */
private class Holding(private val contents: Map<DataFlavor, Any>) : Transferable {
    constructor(vararg contents: Pair<DataFlavor, Any>) : this(contents.toMap())

    override fun getTransferDataFlavors(): Array<DataFlavor> = contents.keys.toTypedArray()
    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in contents
    override fun getTransferData(flavor: DataFlavor): Any = contents[flavor] ?: throw UnsupportedFlavorException(flavor)
}

private fun picture(): BufferedImage = BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB).also {
    it.setRGB(0, 0, Color.RED.rgb)
    it.setRGB(2, 1, Color.BLUE.rgb)
}

/** What is pasted and dropped: which of the things on a clipboard wins, and what is refused. */
class UploadsTest {
    private val scratch = Files.createTempDirectory("tono-uploads-test")

    @AfterTest
    fun tidy() {
        scratch.toFile().deleteRecursively()
    }

    private fun file(name: String, size: Int = 5): File = Files.write(scratch.resolve(name), ByteArray(size)).toFile()

    @Test
    fun anImageOnItsOwnIsUploadedAsClipboardPng() {
        val content = assertIs<ClipboardContent.Files>(clipboardContent(Holding(DataFlavor.imageFlavor to picture())))
        val image = content.offer.files.single()
        assertNull(content.offer.refused)
        assertEquals("clipboard.png", image.name)
        // It is a PNG of the same picture.
        val bytes = image.content.open().readBytes()
        assertEquals(image.size, bytes.size.toLong())
        val read = ImageIO.read(bytes.inputStream())
        assertEquals(3, read.width)
        assertEquals(2, read.height)
        assertEquals(Color.RED.rgb, read.getRGB(0, 0))
        assertEquals(Color.BLUE.rgb, read.getRGB(2, 1))
    }

    @Test
    fun textWinsOverAnImage() {
        val content = clipboardContent(Holding(DataFlavor.imageFlavor to picture(), DataFlavor.stringFlavor to "ls -la"))
        assertEquals("ls -la", assertIs<ClipboardContent.Text>(content).text)
    }

    /** A spreadsheet's cells, say, are on the clipboard as text and as a picture of them. Empty text is no text. */
    @Test
    fun anImageWithEmptyTextIsStillAnImage() {
        val content = clipboardContent(Holding(DataFlavor.imageFlavor to picture(), DataFlavor.stringFlavor to ""))
        assertEquals("clipboard.png", assertIs<ClipboardContent.Files>(content).offer.files.single().name)
    }

    /** A file manager puts a copied file's path on the clipboard as text too. The file is what was copied. */
    @Test
    fun copiedFilesWinOverTheirPathsAsText() {
        val files = listOf(file("b.txt"), file("a b.png", 9))
        val content = clipboardContent(Holding(DataFlavor.javaFileListFlavor to files, DataFlavor.stringFlavor to files.joinToString("\n")))
        val offer = assertIs<ClipboardContent.Files>(content).offer
        assertEquals(listOf("b.txt", "a b.png"), offer.files.map { it.name }, "In the order they were copied")
        assertEquals(listOf(5L, 9L), offer.files.map { it.size })
    }

    @Test
    fun textAloneIsText() {
        assertEquals("hello", assertIs<ClipboardContent.Text>(clipboardContent(Holding(DataFlavor.stringFlavor to "hello"))).text)
    }

    @Test
    fun anEmptyClipboardIsNothing() {
        assertNull(clipboardContent(null))
        assertNull(clipboardContent(Holding()))
        assertNull(clipboardContent(Holding(DataFlavor.stringFlavor to "")))
        assertNull(clipboardContent(Holding(DataFlavor.javaFileListFlavor to emptyList<File>())))
    }

    @Test
    fun aFolderAmongDroppedFilesRefusesThemAll() {
        val folder = Files.createDirectory(scratch.resolve("photos")).toFile()
        val offer = fileOffer(Holding(DataFlavor.javaFileListFlavor to listOf(file("a.txt"), folder)))!!
        assertEquals("photos is a folder. Folders can't be uploaded.", offer.refused)
        assertTrue(offer.files.isEmpty())
    }

    @Test
    fun aFileThatIsGoneRefusesTheDrop() {
        val offer = fileOffer(Holding(DataFlavor.javaFileListFlavor to listOf(File(scratch.toFile(), "gone.txt"))))!!
        assertEquals("gone.txt can't be read", offer.refused)
    }

    @Test
    fun somethingDraggedThatIsNotFilesIsNotAnOffer() {
        assertNull(fileOffer(Holding(DataFlavor.stringFlavor to "some text")))
    }

    private fun sending(files: Int, sent: Long, total: Long) = Upload(1, "block:1", files, total, sent)

    @Test
    fun progressIsSaidInFilesAndPercent() {
        assertEquals("Uploading 1 file · 0%", uploadingWords(listOf(sending(1, 0, 1000))))
        assertEquals("Uploading 3 files · 42%", uploadingWords(listOf(sending(3, 425, 1000))))
        assertEquals("Uploading 5 files · 50%", uploadingWords(listOf(sending(3, 100, 1000), sending(2, 900, 1000))), "Two drops on one pane are counted together")
        assertEquals("Uploading 1 file · 99%", uploadingWords(listOf(sending(1, 999, 1000))), "Never a hundred before it is done")
        assertEquals("Uploading 1 file", uploadingWords(listOf(sending(1, 0, 0))), "An empty file has no part done")
        assertNull(uploadFraction(emptyList()))
        assertEquals(0.5f, uploadFraction(listOf(sending(1, 50, 100))))
    }

    @Test
    fun aFailureSaysThatNothingWasTyped() {
        assertEquals("Couldn't upload. Folders can't be uploaded. Nothing was typed.", failureWords("Folders can't be uploaded."))
        assertEquals("Couldn't upload. Rex refused the upload (1011). Nothing was typed.", failureWords("Rex refused the upload (1011)"))
    }
}
