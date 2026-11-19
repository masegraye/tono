@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.kotlintest

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.await
import org.example.kotlintest.rex.PASTED_IMAGE_NAME
import org.example.kotlintest.rex.UploadFile
import org.example.kotlintest.rex.nowMillis
import org.example.kotlintest.rex.uploadFile
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.Promise

// --- Pasting ---

/**
 * Reads the clipboard for a paste: `{ text, image }`, where text comes first and an image is
 * a Blob. Reading anything but text is newer and some browsers lack it or refuse it, so
 * reading text alone is tried after it.
 */
private fun readClipboard(): Promise<JsAny?> = js(
    """(async () => {
        try {
            if (navigator.clipboard && navigator.clipboard.read) {
                const items = await navigator.clipboard.read();
                for (const item of items) {
                    if (!item.types.includes('text/plain')) continue;
                    const text = await (await item.getType('text/plain')).text();
                    if (text) return { text, image: null };
                }
                for (const item of items) {
                    const type = item.types.find((name) => name.startsWith('image/'));
                    if (type) return { text: null, image: await item.getType(type) };
                }
                return null;
            }
        } catch (error) {}
        try {
            const text = await navigator.clipboard.readText();
            return text ? { text, image: null } : null;
        } catch (error) {
            return null;
        }
    })()""",
)

private fun textOf(read: JsAny): String? = js("read.text")
private fun imageOf(read: JsAny): JsAny? = js("read.image")
private fun typeOf(blob: JsAny): String = js("blob.type || ''")

/** A picture off the clipboard has no name, or a browser's stand-in for one. It is clipboard.png, or whatever kind of picture it is. */
private fun pastedImage(blob: JsAny): UploadFile {
    val kind = typeOf(blob).removePrefix("image/").substringBefore(';').substringBefore('+').filter { it.isLetterOrDigit() }
    return uploadFile(blob, if (kind.isEmpty() || kind == "png") PASTED_IMAGE_NAME else PASTED_IMAGE_NAME.substringBefore('.') + "." + kind)
}

/** When the app last read the clipboard for a paste of its own. */
private var lastOwnPasteMillis = 0L

internal actual suspend fun clipboardContent(): ClipboardContent? {
    lastOwnPasteMillis = nowMillis()
    val read = runCatching { readClipboard().await<JsAny?>() }.getOrNull() ?: return null
    textOf(read)?.takeIf { it.isNotEmpty() }?.let { return ClipboardContent.Text(it) }
    return imageOf(read)?.let { ClipboardContent.Files(FileOffer(listOf(pastedImage(it)))) }
}

/**
 * Hears the page's paste events. One with text is left alone, as it always was: text comes
 * first. One with files and no text is taken, and [onFiles] given the files; a picture
 * copied as such arrives as a file too.
 */
private fun watchPastes(onFiles: (JsArray<JsAny>) -> Unit): JsAny = js(
    """(() => {
        const paste = (event) => {
            const data = event.clipboardData;
            if (!data || !data.files || data.files.length === 0) return;
            // A text field of the app's own, or of the page's, has the paste.
            const target = event.composedPath ? event.composedPath()[0] : event.target;
            if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) return;
            if (data.getData('text/plain')) return;
            event.preventDefault();
            onFiles(Array.from(data.files));
        };
        addEventListener('paste', paste);
        return () => removeEventListener('paste', paste);
    })()""",
)

private fun stopWatching(watching: JsAny): Unit = js("watching()")
private fun lengthOf(array: JsArray<JsAny>): Int = js("array.length")
private fun itemOf(array: JsArray<JsAny>, index: Int): JsAny = js("array[index]")
private fun nameOf(file: JsAny): String = js("file.name || ''")

private fun files(array: JsArray<JsAny>): List<JsAny> = List(lengthOf(array)) { itemOf(array, it) }

@Composable
internal actual fun WatchPastedFiles(onPaste: (FileOffer) -> Unit) {
    val report by rememberUpdatedState(onPaste)
    DisposableEffect(Unit) {
        val watching = watchPastes { pasted ->
            // The app's own paste has just read the clipboard, and this is the same paste again.
            if (nowMillis() - lastOwnPasteMillis < OWN_PASTE_MILLIS) return@watchPastes
            val files = files(pasted)
            // A picture copied as a picture is given the name image.png by the browser.
            val picture = files.singleOrNull()?.takeIf { nameOf(it) == "image.png" && typeOf(it) == "image/png" }
            report(FileOffer(if (picture != null) listOf(pastedImage(picture)) else files.map { uploadFile(it) }))
        }
        onDispose { stopWatching(watching) }
    }
}

private const val OWN_PASTE_MILLIS = 1_000L

// --- Dropping ---

/** Somewhere on the page files can be dropped: where it is, and whom to tell. */
private class DropTarget {
    var bounds: Rect = Rect.Zero
    var onHover: (Boolean) -> Unit = {}
    var onDrop: (FileOffer) -> Unit = {}
}

/**
 * The page hears of dragged files as a whole, at a point; the app has one canvas. So each
 * place files can be dropped says where it is, and the point is looked up among them.
 */
private val dropTargets = mutableListOf<DropTarget>()
private var hovered: DropTarget? = null
private var watchingDrops: JsAny? = null

private fun targetAt(x: Double, y: Double): DropTarget? = dropTargets.lastOrNull { it.bounds.contains(Offset(x.toFloat(), y.toFloat())) }

private fun hover(target: DropTarget?) {
    if (hovered === target) return
    hovered?.onHover?.invoke(false)
    hovered = target
    target?.onHover?.invoke(true)
}

/**
 * Hears files dragged over the page and dropped on it. Positions are in the canvas's pixels.
 * [onOver] answers whether files can be dropped at a point. A browser's own answer to a
 * dropped file is to leave the page and show the file, so every drop of files is taken,
 * wherever it lands. Folders are counted rather than passed on: they can't be uploaded.
 */
private fun watchDrops(onOver: (Double, Double) -> Boolean, onLeave: () -> Unit, onDrop: (Double, Double, JsArray<JsAny>, Int) -> Unit): JsAny = js(
    """(() => {
        const hasFiles = (event) => event.dataTransfer && Array.from(event.dataTransfer.types || []).includes('Files');
        const scale = () => window.devicePixelRatio || 1;
        const over = (event) => {
            if (!hasFiles(event)) return;
            event.preventDefault();
            const here = onOver(event.clientX * scale(), event.clientY * scale());
            event.dataTransfer.dropEffect = here ? 'copy' : 'none';
        };
        const leave = (event) => { if (!event.relatedTarget) onLeave(); };
        const drop = (event) => {
            if (!hasFiles(event)) return;
            event.preventDefault();
            const files = [];
            let folders = 0;
            const items = event.dataTransfer.items;
            if (items && items.length) {
                for (const item of items) {
                    if (item.kind !== 'file') continue;
                    const entry = item.webkitGetAsEntry ? item.webkitGetAsEntry() : null;
                    if (entry && entry.isDirectory) { folders++; continue; }
                    const file = item.getAsFile();
                    if (file) files.push(file);
                }
            } else {
                files.push(...event.dataTransfer.files);
            }
            onDrop(event.clientX * scale(), event.clientY * scale(), files, folders);
        };
        addEventListener('dragenter', over);
        addEventListener('dragover', over);
        addEventListener('dragleave', leave);
        addEventListener('drop', drop);
        return () => {
            removeEventListener('dragenter', over);
            removeEventListener('dragover', over);
            removeEventListener('dragleave', leave);
            removeEventListener('drop', drop);
        };
    })()""",
)

private fun register(target: DropTarget) {
    dropTargets += target
    if (watchingDrops != null) return
    watchingDrops = watchDrops(
        onOver = { x, y -> targetAt(x, y).also(::hover) != null },
        onLeave = { hover(null) },
        onDrop = { x, y, dropped, folders ->
            val target = targetAt(x, y)
            hover(null)
            val files = files(dropped)
            if (target != null && (files.isNotEmpty() || folders > 0)) {
                target.onDrop(
                    if (folders > 0) FileOffer(emptyList(), "Folders can't be uploaded.")
                    else FileOffer(files.map { uploadFile(it) }),
                )
            }
        },
    )
}

private fun unregister(target: DropTarget) {
    if (hovered === target) hover(null)
    dropTargets -= target
    if (dropTargets.isEmpty()) {
        watchingDrops?.let(::stopWatching)
        watchingDrops = null
    }
}

@Composable
internal actual fun Modifier.fileDropTarget(onHover: (Boolean) -> Unit, onDrop: (FileOffer) -> Unit): Modifier {
    val target = remember { DropTarget() }
    target.onHover = onHover
    target.onDrop = onDrop
    DisposableEffect(target) {
        register(target)
        onDispose { unregister(target) }
    }
    return onGloballyPositioned { target.bounds = it.boundsInWindow() }
}
