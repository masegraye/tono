package org.example.kotlintest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.StringSelection

/**
 * The window toolkit's primary selection: X11's PRIMARY, which is also what the toolkit
 * uses under Wayland, where it runs through XWayland and the compositor carries PRIMARY
 * across to Wayland's own. Null where there is none, as on a Mac or Windows.
 */
private val systemSelection: Clipboard? by lazy { runCatching { Toolkit.getDefaultToolkit().systemSelection }.getOrNull() }

internal actual fun primarySelection(): PrimarySelection = if (systemSelection != null) PrimarySelection.Desktop else PrimarySelection.None

internal actual suspend fun setPrimarySelectionText(text: String): String? {
    val selection = systemSelection ?: return "this desktop has no primary selection"
    return runCatching { selection.setContents(StringSelection(text), null) }
        .exceptionOrNull()?.let { it.message ?: it::class.simpleName ?: "the primary selection refused" }
}

// Off the interface's thread: the program that owns the selection is asked for it, and may take its time.
internal actual suspend fun primarySelectionContent(): ClipboardContent? = withContext(Dispatchers.IO) {
    runCatching { clipboardContent(systemSelection?.getContents(null)) }.getOrNull()
}
