package org.example.kotlintest

import org.example.kotlintest.ghostty.TerminalFrame
import org.example.kotlintest.ghostty.TerminalRow
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the wheel asks of a terminal, and how far from its place the terminal is drawn while it glides there. */
class TerminalScrollTest {
    private val row = TerminalRow(emptyList())

    private fun frame(scrolled: Long, above: Int = 6, below: Int = 6) =
        TerminalFrame(80, List(24) { row }, 0, 0xFFFFFF, cursor = null, above = List(above) { row }, below = List(below) { row }, scrolled = scrolled)

    @Test
    fun `a notch of the desktop's wheel is three rows, either way`() {
        val scroll = TerminalScroll()
        assertEquals(3, scroll.wheel(notchedWheelTurn(1f)))
        assertEquals(-3, scroll.wheel(notchedWheelTurn(-1f)))
        // Two notches the toolkit put in one event.
        assertEquals(-6, scroll.wheel(notchedWheelTurn(-2f)))
    }

    @Test
    fun `a touchpad's small moves add up to rows, and what is left over is kept`() {
        val scroll = TerminalScroll()
        // A tenth of a notch each, which is three tenths of a row: a row every fourth event, and nine in thirty.
        val asked = List(30) { scroll.wheel(notchedWheelTurn(0.1f)) }
        assertEquals(9, asked.sum(), "$asked")
        assertTrue(asked.all { it == 0 || it == 1 }, "$asked")
        assertEquals(listOf(0, 0, 0, 1), asked.take(4))
    }

    @Test
    fun `turning back starts from nothing`() {
        val scroll = TerminalScroll()
        assertEquals(0, scroll.wheel(WheelTurn(0.9f, precise = true)))
        // Not held back by the nine tenths of a row the other way.
        assertEquals(0, scroll.wheel(WheelTurn(-0.6f, precise = true)))
        assertEquals(-1, scroll.wheel(WheelTurn(-0.6f, precise = true)))
    }

    @Test
    fun `a browser's pixels are the rows they cover`() {
        // Rows 20 of the page's pixels high, on a screen with two of its own to each.
        fun turn(pixels: Float) = browserWheelTurn(pixels, mode = 0, cellHeight = 40f, density = 2f, rows = 30)
        val notch = turn(100f)
        assertEquals(5f, notch.rows)
        assertFalse(notch.precise)
        val touchpad = turn(-4f)
        assertEquals(-0.2f, touchpad.rows)
        assertTrue(touchpad.precise)

        // A hundred and twenty moves of four pixels are 480 pixels, which is 24 rows: not the 1440 it once was.
        val scroll = TerminalScroll()
        assertEquals(-24, List(120) { scroll.wheel(turn(-4f)) }.sum())
        assertEquals(5, scroll.wheel(notch))
    }

    @Test
    fun `a browser that counts in lines or pages is taken at its word`() {
        assertEquals(3f, browserWheelTurn(3f, mode = 1, cellHeight = 40f, density = 2f, rows = 30).rows)
        assertEquals(-29f, browserWheelTurn(-1f, mode = 2, cellHeight = 40f, density = 2f, rows = 30).rows)
    }

    @Test
    fun `without smooth scrolling nothing is ever drawn away from its place`() {
        val scroll = TerminalScroll()
        scroll.frame(frame(0))
        scroll.frame(frame(-3))
        assertFalse(scroll.moving)
        assertEquals(0f, scroll.offset(frame(-3), 20f))
    }

    @Test
    fun `a frame that scrolled is first drawn where the one before was, and glides from there to its place`() {
        val scroll = TerminalScroll().apply { smooth = true }
        scroll.frame(frame(0))
        assertFalse(scroll.moving)

        // The wheel turned up a notch: the view is three rows higher in the history, so its rows are three lower on screen.
        val next = frame(-3)
        scroll.frame(next)
        assertTrue(scroll.moving)
        // Drawn three rows higher, which is where they were.
        assertEquals(-60f, scroll.offset(next, 20f))

        val offsets = ArrayList<Float>()
        repeat(60) {
            scroll.advance(16f, 20f)
            offsets += scroll.offset(next, 20f)
        }
        assertTrue(offsets.zipWithNext().all { (a, b) -> b >= a }, "it only ever comes closer: $offsets")
        assertTrue(offsets.all { it == it.toInt().toFloat() }, "on whole pixels, so that text stays sharp: $offsets")
        assertTrue(abs(offsets[6]) < 10f, "most of the way in a tenth of a second: $offsets")
        assertEquals(0f, offsets.last())
        assertFalse(scroll.moving)
    }

    @Test
    fun `a wheel that keeps turning keeps the picture moving, never further away than there are rows for`() {
        val scroll = TerminalScroll().apply { smooth = true }
        scroll.frame(frame(0))
        var scrolled = 0L
        repeat(20) {
            scrolled += 3
            val next = frame(scrolled)
            scroll.frame(next)
            assertTrue(scroll.ahead in -6f..0f, "${scroll.ahead}")
            assertTrue(scroll.offset(next, 20f) in 0f..120f)
            scroll.advance(16f, 20f)
        }
        // Many notches in one frame, as a wheel flicked hard sends them: as far as the rows past the edge go, and no further.
        val far = frame(scrolled + 90)
        scroll.frame(far)
        assertEquals(120f, scroll.offset(far, 20f))
    }

    @Test
    fun `at an end of the history, and in a full-screen program, there is nothing to glide over`() {
        val scroll = TerminalScroll().apply { smooth = true }
        scroll.frame(frame(0))
        // Scrolled down to the live screen, which has nothing below it.
        val live = frame(3, below = 0)
        scroll.frame(live)
        assertEquals(60f, scroll.offset(live, 20f))
        // And up from it, with nothing kept above: a full-screen program's screen.
        val screen = frame(0, above = 0, below = 0)
        scroll.frame(screen)
        assertEquals(0f, scroll.offset(screen, 20f))
        assertFalse(scroll.moving)
    }

    @Test
    fun `the first frame is where things start, whatever it says was scrolled before`() {
        val scroll = TerminalScroll().apply { smooth = true }
        scroll.frame(frame(-40))
        assertFalse(scroll.moving)
    }

    @Test
    fun `turning smooth scrolling off stops a glide where it rests`() {
        val scroll = TerminalScroll().apply { smooth = true }
        scroll.frame(frame(0))
        scroll.frame(frame(3))
        scroll.smooth = false
        assertFalse(scroll.moving)
    }
}
