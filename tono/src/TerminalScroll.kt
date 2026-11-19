package org.example.kotlintest

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import org.example.kotlintest.ghostty.TerminalFrame
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * One turn of the wheel, or one move of two fingers on a touchpad, as rows of the terminal.
 *
 * @param rows how far, down counting up: three for a notch of a wheel, a fraction for a touchpad
 * @param precise whether it is one of the many small moves a touchpad sends, which add up, and not a notch
 */
internal class WheelTurn(val rows: Float, val precise: Boolean)

/**
 * A wheel event from the desktop's toolkit, as rows. There a notch of a wheel is 1, and so
 * is each step a touchpad's scrolling is turned into where the toolkit is only given steps,
 * as it is under X11 and XWayland. A toolkit that is given the touchpad's own moves (macOS)
 * says them as fractions of that.
 */
internal fun notchedWheelTurn(delta: Float): WheelTurn = WheelTurn(delta * ROWS_PER_NOTCH, precise = delta != delta.toInt().toFloat())

/**
 * A wheel event from a browser, as rows. A browser says how far in pixels, unless it says
 * lines or pages: a notch of a wheel is a hundred pixels or so, and a touchpad sends a few
 * pixels at a time. Pixels become the rows they cover, so text moves as far as a page would.
 *
 * @param delta what the event said, in the unit [mode] names: 0 is pixels as the page counts them, 1 lines, 2 pages
 * @param cellHeight the height of a row, in the screen's own pixels, of which there are [density] to one of the page's
 */
internal fun browserWheelTurn(delta: Float, mode: Int, cellHeight: Float, density: Float, rows: Int): WheelTurn = when (mode) {
    1 -> WheelTurn(delta, precise = false)
    2 -> WheelTurn(delta * (rows - 1).coerceAtLeast(1), precise = false)
    // Anything under half a notch is a touchpad, or a wheel that turns without notches.
    else -> WheelTurn(if (cellHeight > 0f) delta * density / cellHeight else 0f, precise = abs(delta) < BROWSER_NOTCH_PIXELS / 2)
}

/** How far a notch of a wheel scrolls where the toolkit counts in notches. Ghostty's three, and what a terminal on Linux usually does. */
internal const val ROWS_PER_NOTCH = 3f
private const val BROWSER_NOTCH_PIXELS = 100f

/**
 * What the wheel does to one terminal's view, between the events and the picture.
 *
 * First, how many rows to ask the terminal for. A notch is whole rows at once. A touchpad's
 * small moves add up until they come to a row, and what is left over is kept for the next:
 * rounding each one up to a row, as was done before, made a slow drag of two fingers race
 * through the history.
 *
 * Second, with [smooth] on, how far from where it rests to draw the terminal, so that it
 * glides to where the wheel sent it and doesn't jump. A frame says how far the wheel has
 * moved the view ([TerminalFrame.scrolled]). When a frame comes that has moved, it is drawn
 * that far back, which is exactly where the picture was, and from there the distance is let
 * out a little every frame of the window ([advance]). The rows that show at the edge on the
 * way are the ones the frame carries from past its view ([TerminalFrame.above] and
 * [TerminalFrame.below]); with none of those, at an end of the history or in a full-screen
 * program, nothing is drawn away from its place.
 *
 * Used on the thread that draws.
 */
internal class TerminalScroll {
    /** Whether to glide. Off, the view moves by whole rows at once, as terminals do. */
    var smooth = false
        set(value) {
            field = value
            if (!value) ahead = 0f
        }

    /** What a touchpad has scrolled that hasn't come to a whole row yet. */
    private var remainder = 0f

    /**
     * How many rows further down the history than the frame says the picture is drawn, which
     * is where it was before the wheel turned up: so it is drawn that many rows higher. Negative
     * on the way down. Zero at rest.
     */
    var ahead by mutableFloatStateOf(0f)
        private set

    private var frameScrolled = 0L
    private var seen = false

    /** How many whole rows to ask the terminal to scroll for [turn], which may be none. */
    fun wheel(turn: WheelTurn): Int {
        if (!turn.precise) {
            remainder = 0f
            // A notch always does something, however small the toolkit says it was.
            return turn.rows.roundToInt().let { if (it == 0) (if (turn.rows > 0f) 1 else if (turn.rows < 0f) -1 else 0) else it }.coerceIn(-MOST_ROWS, MOST_ROWS)
        }
        // Turning back doesn't first have to undo what was left of the way there.
        if (remainder != 0f && (remainder > 0f) != (turn.rows > 0f)) remainder = 0f
        remainder += turn.rows
        val whole = remainder.toInt().coerceIn(-MOST_ROWS, MOST_ROWS)
        remainder -= whole
        return whole
    }

    /** A frame is about to be drawn in place of the one before. */
    fun frame(frame: TerminalFrame) {
        val moved = frame.scrolled - frameScrolled
        frameScrolled = frame.scrolled
        if (!seen) {
            seen = true
            return
        }
        if (!smooth) return
        // Drawn where the picture was, and no further away than there are rows to show for it.
        if (moved != 0L) ahead -= moved.coerceIn(-MOST_ROWS.toLong(), MOST_ROWS.toLong()).toFloat()
        ahead = ahead.coerceIn(-frame.above.size.toFloat(), frame.below.size.toFloat())
    }

    /** Whether the picture is on its way somewhere, so that [advance] has something to do. */
    val moving: Boolean get() = ahead != 0f

    /**
     * Lets out some of the distance, [millis] after the last time. Each frame covers the same
     * share of what is left, so it starts at once, slows as it arrives, and a wheel that
     * keeps turning keeps it moving evenly. [rowHeight] is a row's height in pixels: within
     * a pixel of rest, it rests.
     */
    fun advance(millis: Float, rowHeight: Float) {
        val left = ahead * exp(-millis.coerceIn(0f, 100f) / GLIDE_MILLIS)
        ahead = if (abs(left) * rowHeight < REST_PIXELS) 0f else left
    }

    /** How far down to draw [frame], in whole pixels, for rows [rowHeight] pixels high. */
    fun offset(frame: TerminalFrame, rowHeight: Float): Float =
        if (ahead == 0f) 0f else (-ahead.coerceIn(-frame.above.size.toFloat(), frame.below.size.toFloat()) * rowHeight).roundToInt().toFloat()

    companion object {
        /** How many rows past each edge of the view a terminal is asked for while [smooth] is on: two notches' worth. */
        const val OVERSCAN_ROWS = 6

        /** How long the glide takes to cover all but a third of what is left. Most of a notch is covered in a tenth of a second. */
        private const val GLIDE_MILLIS = 45f

        /** How near its place the picture is taken to have arrived, in pixels. */
        private const val REST_PIXELS = 1f

        /** The most rows one event scrolls, whatever it says. */
        private const val MOST_ROWS = 50
    }
}
