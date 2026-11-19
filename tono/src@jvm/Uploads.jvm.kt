package org.example.kotlintest

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.kotlintest.rex.PASTED_IMAGE_NAME
import org.example.kotlintest.rex.UploadFailed
import org.example.kotlintest.rex.uploadFile
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * The files something holds, a drop or the clipboard, ready to upload. Null if it holds none.
 * A folder among them, or anything else that isn't a file to read, refuses them all.
 */
internal fun fileOffer(transferable: Transferable): FileOffer? {
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return null
    val files = (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)?.filterIsInstance<File>().orEmpty()
    if (files.isEmpty()) return null
    return try {
        FileOffer(files.map { uploadFile(it.toPath()) })
    } catch (refused: UploadFailed) {
        FileOffer(emptyList(), refused.message)
    }
}

/**
 * What a paste makes of the clipboard's contents, in Rex Rune's order: copied files are
 * uploaded, else text is pasted, else an image is uploaded as clipboard.png. Null if it has
 * none of them.
 */
internal fun clipboardContent(transferable: Transferable?): ClipboardContent? {
    if (transferable == null) return null
    runCatching { fileOffer(transferable) }.getOrNull()?.let { return ClipboardContent.Files(it) }
    if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
        val text = runCatching { transferable.getTransferData(DataFlavor.stringFlavor) as? String }.getOrNull()
        if (!text.isNullOrEmpty()) return ClipboardContent.Text(text)
    }
    if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
        val image = runCatching { transferable.getTransferData(DataFlavor.imageFlavor) as? Image }.getOrNull()
        val png = image?.let(::png) ?: return null
        return ClipboardContent.Files(FileOffer(listOf(uploadFile(PASTED_IMAGE_NAME, png))))
    }
    return null
}

/** An image as a PNG file's bytes. Null if it has no size, as one that never loaded hasn't. */
internal fun png(image: Image): ByteArray? {
    val buffered = image as? BufferedImage ?: run {
        val width = image.getWidth(null)
        val height = image.getHeight(null)
        if (width <= 0 || height <= 0) return null
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { copy ->
            val graphics = copy.createGraphics()
            graphics.drawImage(image, 0, 0, null)
            graphics.dispose()
        }
    }
    val out = ByteArrayOutputStream()
    return if (ImageIO.write(buffered, "png", out)) out.toByteArray() else null
}

// Off the interface's thread: a screenshot takes a moment to be made a PNG of.
internal actual suspend fun clipboardContent(): ClipboardContent? = withContext(Dispatchers.IO) {
    runCatching { clipboardContent(Toolkit.getDefaultToolkit().systemClipboard.getContents(null)) }.getOrNull()
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun Modifier.fileDropTarget(onHover: (Boolean) -> Unit, onDrop: (FileOffer) -> Unit): Modifier {
    val hover by rememberUpdatedState(onHover)
    val drop by rememberUpdatedState(onDrop)
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = hover(true)
            override fun onExited(event: DragAndDropEvent) = hover(false)
            override fun onEnded(event: DragAndDropEvent) = hover(false)

            override fun onDrop(event: DragAndDropEvent): Boolean {
                hover(false)
                val offer = runCatching { fileOffer(event.awtTransferable) }.getOrNull() ?: return false
                drop(offer)
                return true
            }
        }
    }
    // Only files: text dragged from another program is not a paste.
    return dragAndDropTarget(
        shouldStartDragAndDrop = { runCatching { it.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) }.getOrDefault(false) },
        target = target,
    )
}

@Composable
internal actual fun WatchPastedFiles(onPaste: (FileOffer) -> Unit) {}
