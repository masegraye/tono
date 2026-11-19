package org.example.kotlintest

import kotlinx.coroutines.launch
import org.example.kotlintest.rex.RexHost
import org.example.kotlintest.rex.SelectionSwitches
import org.example.kotlintest.rex.TerminalSession

/*
 * What selecting text with the pointer copies, and what a middle click pastes. Two switches,
 * Rex Rune's, both off until turned on:
 *
 * - Copy on Selection: a selection the pointer has finished goes to the clipboard, as if
 *   Control+Shift+C had been pressed. Rex for Mac has this one too.
 * - Primary Selection: it becomes the desktop's primary selection instead, which is what
 *   other programs on Linux paste with the middle button, and a middle click here pastes
 *   whatever the primary selection holds. The clipboard is left alone.
 *
 * A browser has no primary selection, and a page can neither set nor read one. There the
 * clipboard stands in for it: with Primary Selection on, selecting copies to the clipboard
 * and a middle click pastes the clipboard, as Control+Shift+V does.
 *
 * Only a selection made with the pointer is copied, when its button comes up. Select All
 * and a search's matches are not.
 */

/** What there is to be the primary selection where the app runs. */
internal enum class PrimarySelection {
    /** The desktop has one, apart from its clipboard: X11 and Wayland do. */
    Desktop,

    /** There is none to reach, and the clipboard stands in for it: a browser. */
    Clipboard,

    /** There is none, and the switch does nothing: a desktop other than Linux's. */
    None,
}

internal expect fun primarySelection(): PrimarySelection

/**
 * Makes text the primary selection. Returns null once it is, and otherwise why it isn't,
 * for the debug log. Never throws. Where the clipboard stands in, this is [setClipboardText].
 */
internal expect suspend fun setPrimarySelectionText(text: String): String?

/**
 * What the primary selection holds for a paste, or null if that is nothing or it can't be
 * read. Where the clipboard stands in, this is [clipboardContent].
 */
internal expect suspend fun primarySelectionContent(): ClipboardContent?

/** Where a finished selection goes. */
internal data class SelectionTargets(val clipboard: Boolean, val primary: Boolean) {
    val any: Boolean get() = clipboard || primary
}

/** Where the switches send a selection, given what there is for a primary selection. */
internal fun selectionTargets(switches: SelectionSwitches, primary: PrimarySelection): SelectionTargets = SelectionTargets(
    clipboard = switches.copyOnSelection || (switches.primarySelection && primary == PrimarySelection.Clipboard),
    primary = switches.primarySelection && primary == PrimarySelection.Desktop,
)

/** Whether a middle click pastes. */
internal fun middlePastes(switches: SelectionSwitches, primary: PrimarySelection): Boolean =
    switches.primarySelection && primary != PrimarySelection.None

/** Whether a middle click in a terminal pastes, as things stand. */
internal val RexUiState.middlePastes: Boolean get() = middlePastes(selection, primarySelection())

/**
 * The pointer has finished selecting in [terminal]: copies what is selected to wherever the
 * switches say. Nothing selected, as after a click, changes nothing.
 */
internal fun RexUiState.selectionFinished(terminal: TerminalSession) {
    val targets = selectionTargets(selection, primarySelection())
    if (!targets.any || overlayOpen) return
    scope?.launch {
        val text = terminal.selectedText()
        if (text.isEmpty()) return@launch
        if (targets.primary) report("primary selection", text, setPrimarySelectionText(text))
        if (targets.clipboard) report("clipboard", text, setClipboardText(text))
    }
}

private fun RexUiState.report(where: String, text: String, refusal: String?) {
    if (refusal == null) client.log.add("clipboard", "selecting put ${text.length} characters on the $where")
    else client.log.add("clipboard", "couldn't put the selection on the $where: $refusal", isError = true)
}

/**
 * A middle click asked for a paste into a pane: pastes what the primary selection holds,
 * as Paste into Pane does the clipboard, uploads and all.
 */
internal fun RexUiState.pastePrimarySelection(host: RexHost, blockId: String) {
    if (!middlePastes || overlayOpen) return
    scope?.launch {
        when (val content = primarySelectionContent()) {
            is ClipboardContent.Text -> host.paste(blockId, content.text)
            is ClipboardContent.Files -> host.offer(blockId, content.offer)
            null -> client.log.add("clipboard", "a middle click found nothing to paste")
        }
    }
}
