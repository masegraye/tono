package org.example.kotlintest

/** A page can't reach the desktop's primary selection, in any browser. */
internal actual fun primarySelection(): PrimarySelection = PrimarySelection.Clipboard

internal actual suspend fun setPrimarySelectionText(text: String): String? = setClipboardText(text)

internal actual suspend fun primarySelectionContent(): ClipboardContent? = clipboardContent()
