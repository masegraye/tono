package org.example.kotlintest.rex

import org.example.kotlintest.ghostty.GhosttyMouse
import org.example.kotlintest.ghostty.GhosttySelection
import org.example.kotlintest.ghostty.GhosttyTerminal
import org.example.kotlintest.ghostty.MouseAction
import org.example.kotlintest.ghostty.MouseButton
import org.example.kotlintest.ghostty.MouseInput

/**
 * Decides what the pointer does in one terminal. A program that asked for the mouse is told
 * of it. Over one that hasn't, the left button selects text and the wheel scrolls through
 * the history. With Shift held, the left button selects whatever the program asked, which
 * is the way to copy out of Neovim or tmux. With Control held, a click on a link opens it,
 * whether or not the program asked for the mouse, and a click anywhere else is what it
 * would have been without.
 *
 * Used on the terminal's thread, like the terminal.
 *
 * @param linkAt the link at a cell of the terminal's view, if there is one
 * @param open called with the address of a link that was clicked with Control held
 */
internal class TerminalPointer(
    private val mouse: GhosttyMouse,
    private val selection: GhosttySelection,
    private val linkAt: (terminal: GhosttyTerminal, column: Int, row: Int) -> TerminalLink? = { _, _, _ -> null },
    private val open: (url: String) -> Unit = {},
) {
    /** Whether the left button is held after a press that began selecting, so that what the pointer does next is selecting too. */
    private var selecting = false

    /** Whether the left button is held after a press that opened a link, so that the rest of that click is nobody's. */
    private var opening = false

    /**
     * Where the pointer is held past the top or bottom edge while selecting, or null. While
     * it is set, [scroll] is to be called every so often.
     */
    var scrollingAt: MouseInput? = null
        private set

    /**
     * Takes one event, and returns what to send the terminal's program for it, which is
     * nothing when the event selected or scrolled instead, or meant nothing.
     *
     * The position may be outside the terminal, as it is when the pane is larger than a
     * terminal sized by another client. The program is told only of what happens inside;
     * a selection is dragged past the edges.
     *
     * @param scrollRows for a turn of the wheel, how far to scroll if the program doesn't want it
     * @param timeMillis when, which tells a double click from two clicks
     */
    fun handle(terminal: GhosttyTerminal, input: MouseInput, scrollRows: Int, timeMillis: Long): ByteArray {
        val left = input.button == MouseButton.Left
        if (selecting && input.action == MouseAction.Motion && left) {
            scrollingAt = input.takeIf { selection.drag(terminal, input) }
            return NOTHING
        }
        val released = input.action == MouseAction.Release && left
        // The button came up, or did without this hearing of it: the pointer moves with it up, or it goes down again.
        if (selecting && (released || (input.action == MouseAction.Motion && !left) || (input.action == MouseAction.Press && left))) {
            if (released) selection.release(terminal, input)
            selecting = false
            scrollingAt = null
            if (released) return NOTHING
        }
        if (opening) {
            // The program never heard of the press, so it isn't told of the drag or the release either.
            if (left && input.action != MouseAction.Press) {
                if (released) opening = false
                return NOTHING
            }
            opening = false
        }
        if (left && input.action == MouseAction.Press && input.control && !input.shift) {
            linkUnder(terminal, input)?.let { link ->
                opening = true
                open(link.url)
                return NOTHING
            }
        }
        val inside = isInside(terminal, input)
        val forProgram = inside && !(left && input.shift && input.action == MouseAction.Press)
        val report = if (forProgram) mouse.encode(terminal, input) else NOTHING
        when {
            report.isNotEmpty() -> return report
            scrollRows != 0 -> terminal.scrollViewport(scrollRows)
            inside && left && input.action == MouseAction.Press -> {
                selection.press(terminal, input, timeMillis)
                selecting = true
            }
        }
        return NOTHING
    }

    /** The link under the pointer, or null: there is none there, or the pointer is outside the terminal. */
    fun linkUnder(terminal: GhosttyTerminal, at: MouseInput): TerminalLink? {
        if (at.cellWidth <= 0 || at.cellHeight <= 0 || !isInside(terminal, at)) return null
        return linkAt(terminal, (at.x / at.cellWidth).toInt(), (at.y / at.cellHeight).toInt())
    }

    private fun isInside(terminal: GhosttyTerminal, input: MouseInput): Boolean =
        input.x >= 0f && input.y >= 0f && input.x < terminal.columns * input.cellWidth && input.y < terminal.rows * input.cellHeight

    /** Scrolls a row towards the edge the pointer is held past, taking the selection with it. */
    fun scroll(terminal: GhosttyTerminal) {
        val at = scrollingAt ?: return
        if (!selection.autoscroll(terminal, at)) scrollingAt = null
    }

    private companion object {
        val NOTHING = ByteArray(0)
    }
}
