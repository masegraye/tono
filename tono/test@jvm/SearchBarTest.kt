package org.example.kotlintest

import org.example.kotlintest.ghostty.SelectedCells
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchBarTest {
    /** A view of 80 by 24 cells, ten pixels wide and twenty tall, with a bar 300 by 30 in a corner. */
    private fun atBottom(now: Boolean, vararg selected: SelectedCells, topInset: Int = 0) = searchBarAtBottom(
        now, selected.toList(), cellWidth = 10f, cellHeight = 20f, viewWidth = 800, viewHeight = 480, barWidth = 300, barHeight = 30, topInset = topInset,
    )

    @Test
    fun `the bar is at the top while the selected match is clear of it`() {
        assertFalse(atBottom(false))
        assertFalse(atBottom(false, SelectedCells(row = 10, firstColumn = 60, lastColumn = 65)))
        // In the top rows, but left of the bar.
        assertFalse(atBottom(false, SelectedCells(row = 0, firstColumn = 10, lastColumn = 15)))
    }

    @Test
    fun `it moves to the bottom when the selected match is under it`() {
        assertTrue(atBottom(false, SelectedCells(row = 0, firstColumn = 60, lastColumn = 65)))
        // Reaching under its left edge is enough.
        assertTrue(atBottom(false, SelectedCells(row = 1, firstColumn = 45, lastColumn = 50)))
        // A match over two rows, of which the second is under it.
        assertTrue(atBottom(false, SelectedCells(row = 0, firstColumn = 2, lastColumn = 9), SelectedCells(row = 1, firstColumn = 70, lastColumn = 79)))
    }

    @Test
    fun `it stays at the bottom until the selected match is under it there`() {
        assertTrue(atBottom(true, SelectedCells(row = 10, firstColumn = 60, lastColumn = 65)))
        assertTrue(atBottom(true))
        assertFalse(atBottom(true, SelectedCells(row = 23, firstColumn = 60, lastColumn = 65)))
    }

    @Test
    fun `with a match under it in both corners it stays where it is`() {
        val both = arrayOf(SelectedCells(row = 0, firstColumn = 60, lastColumn = 65), SelectedCells(row = 23, firstColumn = 60, lastColumn = 65))
        assertFalse(atBottom(false, *both))
        assertTrue(atBottom(true, *both))
    }

    @Test
    fun `kept down from the top, it covers the rows it is over`() {
        // From 40 to 70 pixels down: rows 2 and 3, and not row 0.
        assertFalse(atBottom(false, SelectedCells(row = 0, firstColumn = 60, lastColumn = 65), topInset = 40))
        assertTrue(atBottom(false, SelectedCells(row = 3, firstColumn = 60, lastColumn = 65), topInset = 40))
    }
}
