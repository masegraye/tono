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
 * is the way to copy out of Neovim or tmux.
 *
 * Used on the terminal's thread, like the terminal.
 */
internal class TerminalPointer(private val mouse: GhosttyMouse, private val selection: GhosttySelection) {
    /** Whether the left button is held after a press that began selecting, so that what the pointer does next is selecting too. */
    private var selecting = false

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
        val inside = input.x >= 0f && input.y >= 0f && input.x < terminal.columns * input.cellWidth && input.y < terminal.rows * input.cellHeight
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

    /** Scrolls a row towards the edge the pointer is held past, taking the selection with it. */
    fun scroll(terminal: GhosttyTerminal) {
        val at = scrollingAt ?: return
        if (!selection.autoscroll(terminal, at)) scrollingAt = null
    }

    private companion object {
        val NOTHING = ByteArray(0)
    }
}
