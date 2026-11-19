package org.example.kotlintest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.example.kotlintest.rex.RexHost
import org.example.kotlintest.rex.Upload
import org.example.kotlintest.rex.UploadFile
import org.example.kotlintest.rex.nowMillis

/*
 * Files dropped on a pane, and files or an image pasted into one, go to the machine its
 * terminal runs on, and their paths are typed there (rex/src/Upload.kt). This is how a
 * screenshot gets into an agent running in a terminal on another machine.
 *
 * What is pasted follows Rex Rune: copied files are uploaded, otherwise text is pasted as it
 * always was, and only a clipboard with an image and no text uploads the image, as
 * clipboard.png. A page in a browser can't tell a copied file from a copied picture, so
 * there text comes first.
 */

/**
 * What was dropped or pasted, as far as it can be uploaded. One thing among it that can't be,
 * a folder say, refuses all of it: files dropped together are typed together or not at all.
 */
internal class FileOffer(val files: List<UploadFile>, val refused: String? = null)

/** What a paste finds on the clipboard. */
internal sealed interface ClipboardContent {
    class Text(val text: String) : ClipboardContent

    /** Copied files, or an image on its own. */
    class Files(val offer: FileOffer) : ClipboardContent
}

/**
 * What the clipboard holds for a paste, or null if that is nothing or it can't be read. A
 * browser may ask the user first, and refuses a page that isn't served securely.
 */
internal expect suspend fun clipboardContent(): ClipboardContent?

/**
 * Makes what this is put on somewhere files can be dropped from outside the app. [onHover]
 * says when dragged files come over it and leave it, and [onDrop] is given what was let go.
 */
@Composable
internal expect fun Modifier.fileDropTarget(onHover: (Boolean) -> Unit, onDrop: (FileOffer) -> Unit): Modifier

/**
 * Calls [onPaste] with files a browser pastes into the page other than by the app's own
 * paste, which reads the clipboard itself: its menu's Paste, for one. Nothing on the desktop.
 */
@Composable
internal expect fun WatchPastedFiles(onPaste: (FileOffer) -> Unit)

/** Uploads what was dropped on or pasted into a pane, or says in the pane why it can't be. */
internal fun RexHost.offer(blockId: String, offer: FileOffer) {
    when {
        offer.refused != null -> uploadRefused(blockId, offer.refused)
        else -> upload(blockId, offer.files)
    }
}

/** What a pane says of the files on their way to it: `Uploading 3 files · 42%`. */
internal fun uploadingWords(sending: List<Upload>): String {
    val files = sending.sumOf { it.fileCount }
    val fraction = uploadFraction(sending)
    return "Uploading " + (if (files == 1) "1 file" else "$files files") + (fraction?.let { " · ${(it * 100).toInt()}%" } ?: "")
}

/** How much of what is being sent has gone, from 0 to 1. Null when there is nothing to measure it by. */
internal fun uploadFraction(sending: List<Upload>): Float? {
    val total = sending.sumOf { it.totalBytes }
    if (total <= 0) return null
    return (sending.sumOf { it.sentBytes }.toDouble() / total).toFloat().coerceIn(0f, 1f)
}

/** What a pane says of an upload that failed. The prompt is as it was, and that is said too. */
internal fun failureWords(failure: String): String =
    "Couldn't upload. " + failure.trimEnd().let { if (it.endsWith('.') || it.endsWith('!') || it.endsWith('?')) it else "$it." } + " Nothing was typed."

/**
 * Over a pane: that files held over it would be uploaded to it, how far along files on their
 * way to it are, and why the last upload failed. Progress appears only once an upload has
 * taken a moment, which most don't.
 */
@Composable
internal fun BoxScope.PaneUploads(host: RexHost, blockId: String, dropping: Boolean) {
    val uploads by host.uploads.collectAsState()
    val mine = uploads.filter { it.blockId == blockId }
    val sending = mine.filter { it.failure == null }
    val failed = mine.lastOrNull { it.failure != null }
    val accent = MaterialTheme.colorScheme.primary

    if (dropping) {
        Box(
            Modifier.matchParentSize()
                .background(accent.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
                .border(2.dp, accent, RoundedCornerShape(6.dp))
                .testTag("drop-highlight"),
            contentAlignment = Alignment.Center,
        ) {
            Chip("Drop to upload to this terminal's machine", MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface)
        }
    }

    val oldest = sending.minOfOrNull { it.startedAtMillis }
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(oldest) {
        slow = false
        if (oldest != null) {
            delay((UPLOAD_QUIET_MILLIS - (nowMillis() - oldest)).coerceAtLeast(0))
            slow = true
        }
    }
    Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (slow && sending.isNotEmpty()) {
            Chip(
                uploadingWords(sending), MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface,
                Modifier.testTag("upload-progress"), bar = uploadFraction(sending), barColor = accent,
            )
        }
        if (failed != null) {
            LaunchedEffect(failed.id) {
                delay(FAILURE_SHOWN_MILLIS)
                host.dismissUpload(failed.id)
            }
            Chip(
                failureWords(failed.failure.orEmpty()), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer,
                Modifier.testTag("upload-failed").clickable { host.dismissUpload(failed.id) },
            )
        }
    }
}

/** A few words on a pane. [bar], from 0 to 1, draws how far along something is as a line under them. */
@Composable
private fun Chip(text: String, background: Color, color: Color, modifier: Modifier = Modifier, bar: Float? = null, barColor: Color = color) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .widthIn(max = 520.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(background.copy(alpha = 0.95f))
            .drawBehind {
                if (bar != null) {
                    val height = 2.dp.toPx()
                    drawRect(barColor, Offset(0f, size.height - height), Size(size.width * bar, height))
                }
            }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** An upload quicker than this shows nothing but the paths arriving. */
private const val UPLOAD_QUIET_MILLIS = 500L

/** How long a pane says why an upload failed, unless it is clicked away sooner. */
private const val FAILURE_SHOWN_MILLIS = 12_000L
