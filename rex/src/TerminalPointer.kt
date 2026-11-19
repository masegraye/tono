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
 * Where the middle button is asked to paste, a middle click on a program that hasn't asked
 * for the mouse pastes, and so does one with Shift held on a program that has. The program
 * then hears nothing of that click. A click is the button going down and coming up in one
 * place; anything else with the middle button pastes nothing.
 *
 * Used on the terminal's thread, like the terminal.
 *
 * @param linkAt the link at a cell of the terminal's view, if there is one
 * @param open called with the address of a link that was clicked with Control held
 * @param selected called when the left button comes up after selecting, whether or not anything is selected then
 * @param paste called for a middle click that asks for a paste
 */
internal class TerminalPointer(
    private val mouse: GhosttyMouse,
    private val selection: GhosttySelection,
    private val linkAt: (terminal: GhosttyTerminal, column: Int, row: Int) -> TerminalLink? = { _, _, _ -> null },
    private val open: (url: String) -> Unit = {},
    private val selected: () -> Unit = {},
    private val paste: () -> Unit = {},
) {
    /** Whether the left button is held after a press that began selecting, so that what the pointer does next is selecting too. */
    private var selecting = false

    /** Whether the left button is held after a press that opened a link, so that the rest of that click is nobody's. */
    private var opening = false

    /** Whether the middle button is held after a press that the program wasn't told of, since it may be a paste. */
    private var middleHeld = false

    /** Where that press was, while the button hasn't moved from it. A release there pastes. */
    private var pasteAt: MouseInput? = null

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
     * @param middlePastes whether a middle click pastes, where the program doesn't want it
     */
    fun handle(terminal: GhosttyTerminal, input: MouseInput, scrollRows: Int, timeMillis: Long, middlePastes: Boolean = false): ByteArray {
        val left = input.button == MouseButton.Left
        val middle = input.button == MouseButton.Middle
        if (middleHeld) {
            // The program never heard of the press, so it isn't told of the drag or the release either.
            if (middle && input.action == MouseAction.Motion) {
                if (pasteAt?.isNear(input) == false) pasteAt = null
                return NOTHING
            }
            val at = pasteAt
            middleHeld = false
            pasteAt = null
            if (middle && input.action == MouseAction.Release) {
                if (at?.isNear(input) == true) paste()
                return NOTHING
            }
        }
        if (middle && input.action == MouseAction.Press && middlePastes) {
            // Never in the middle of selecting: the left button is down, and this is a slip.
            if (selecting) return NOTHING
            if (isInside(terminal, input)) {
                val report = if (input.shift) NOTHING else mouse.encode(terminal, input)
                if (report.isNotEmpty()) return report
                middleHeld = true
                pasteAt = input
            }
            return NOTHING
        }
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
            if (released) {
                selected()
                return NOTHING
            }
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

    /** Whether the pointer is where it was: within a few pixels, as a hand moves while it clicks. */
    private fun MouseInput.isNear(other: MouseInput): Boolean {
        val dx = other.x - x
        val dy = other.y - y
        return dx * dx + dy * dy <= CLICK_SLACK_PIXELS * CLICK_SLACK_PIXELS
    }

    private companion object {
        /** How far the pointer may move between a button going down and coming up and still have clicked. Rex Rune's. */
        const val CLICK_SLACK_PIXELS = 3f
        val NOTHING = ByteArray(0)
    }
}
