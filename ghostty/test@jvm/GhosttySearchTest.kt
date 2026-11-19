package org.example.kotlintest.ghostty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GhosttySearchTest {
    private fun searching(columns: Int = 20, rows: Int = 4, body: Scene.() -> Unit) =
        GhosttyTerminal.create(columns, rows).use { terminal ->
            GhosttyRenderer().use { renderer -> GhosttySearch(terminal).use { search -> Scene(terminal, renderer, search).body() } }
        }

    private class Scene(val terminal: GhosttyTerminal, val renderer: GhosttyRenderer, val search: GhosttySearch) {
        /** Looks until everything has been looked through. */
        fun catchUp() {
            var rounds = 0
            while (!search.advance()) check(++rounds < 10_000) { "the search never finished" }
        }

        fun find(text: String) {
            search.setNeedle(text)
            catchUp()
        }

        /** The rows on screen, without the blanks at their ends. */
        val screen get() = renderer.render(terminal).rows.map { it.text().trimEnd() }
    }

    @Test
    fun `nothing is found before anything is looked for`() = searching {
        terminal.write("hello world")
        catchUp()

        assertEquals(0, search.total)
        assertNull(search.selected)
        assertEquals(SearchHighlights.None, search.highlights())
        assertFalse(search.selectNext())
    }

    @Test
    fun `every match on screen is counted and marked, and none is selected yet`() = searching {
        terminal.write("one fish\r\ntwo fish\r\nred")

        find("fish")

        assertEquals(2, search.total)
        assertNull(search.selected)
        assertEquals(
            setOf(SelectedCells(row = 0, firstColumn = 4, lastColumn = 7), SelectedCells(row = 1, firstColumn = 4, lastColumn = 7)),
            search.highlights().others.toSet(),
        )
        assertEquals(emptyList(), search.highlights().selected)
    }

    @Test
    fun `letters match in either case`() = searching {
        terminal.write("Error error ERROR errand")

        find("eRRor")

        assertEquals(3, search.total)
    }

    @Test
    fun `next starts at the newest match and goes back through the older ones, then round again`() = searching {
        terminal.write("fish a\r\nfish b\r\nfish c")
        find("fish")

        assertTrue(search.selectNext())
        assertEquals(0, search.selected)
        assertEquals(listOf(SelectedCells(row = 2, firstColumn = 0, lastColumn = 3)), search.highlights().selected)
        // The other two are still marked, and the selected one isn't marked twice.
        assertEquals(setOf(SelectedCells(0, 0, 3), SelectedCells(1, 0, 3)), search.highlights().others.toSet())

        assertTrue(search.selectNext())
        assertEquals(1, search.selected)
        assertEquals(listOf(SelectedCells(row = 1, firstColumn = 0, lastColumn = 3)), search.highlights().selected)

        assertTrue(search.selectNext())
        assertEquals(2, search.selected)
        assertTrue(search.selectNext())
        assertEquals(0, search.selected)
    }

    @Test
    fun `previous goes towards newer matches, and round from the newest to the oldest`() = searching {
        terminal.write("fish a\r\nfish b\r\nfish c")
        find("fish")
        search.selectNext()

        assertTrue(search.selectPrevious())
        assertEquals(2, search.selected)
        assertEquals(listOf(SelectedCells(row = 0, firstColumn = 0, lastColumn = 3)), search.highlights().selected)
        assertTrue(search.selectPrevious())
        assertEquals(1, search.selected)
    }

    @Test
    fun `a match in the history is counted, and selecting it scrolls it into view`() = searching {
        terminal.write("needle here\r\n")
        repeat(30) { terminal.write("line $it\r\n") }
        find("needle")

        assertEquals(1, search.total)
        assertEquals(emptyList(), search.highlights().others)

        assertTrue(search.selectNext())
        catchUp()

        val row = screen.indexOf("needle here")
        assertTrue(row >= 0, "the match isn't on screen: $screen")
        assertEquals(listOf(SelectedCells(row = row, firstColumn = 0, lastColumn = 5)), search.highlights().selected)
    }

    @Test
    fun `output that arrives while searching is searched too, and the selected match stays the same text`() = searching {
        terminal.write("fish a\r\n")
        find("fish")
        search.selectNext()
        assertEquals(1, search.total)
        assertEquals(0, search.selected)

        terminal.write("fish b\r\nfish c")
        catchUp()

        assertEquals(3, search.total)
        // Two newer matches came in under it.
        assertEquals(2, search.selected)
        assertEquals(listOf(SelectedCells(row = 0, firstColumn = 0, lastColumn = 3)), search.highlights().selected)
    }

    @Test
    fun `the marks follow the view as it is scrolled`() = searching {
        terminal.write("fish\r\n")
        repeat(3) { terminal.write("line $it\r\n") }
        find("fish")
        assertEquals(emptyList(), search.highlights().others) // it has just scrolled off the top

        terminal.scrollViewport(-1)
        catchUp()

        assertEquals(listOf(SelectedCells(row = 0, firstColumn = 0, lastColumn = 3)), search.highlights().others)
    }

    @Test
    fun `a match the terminal wrapped is marked in both rows`() = searching(columns = 10) {
        terminal.write("123456 needle")

        find("needle")

        assertEquals(1, search.total)
        assertEquals(listOf(SelectedCells(row = 0, firstColumn = 7, lastColumn = 9), SelectedCells(row = 1, firstColumn = 0, lastColumn = 2)), search.highlights().others)
    }

    @Test
    fun `changing what is looked for starts over`() = searching {
        terminal.write("fish and chips")
        find("fish")
        search.selectNext()

        find("chips")

        assertEquals(1, search.total)
        assertNull(search.selected)
        assertEquals(listOf(SelectedCells(row = 0, firstColumn = 9, lastColumn = 13)), search.highlights().others)

        find("")
        assertEquals(0, search.total)
        assertEquals(SearchHighlights.None, search.highlights())
    }

    @Test
    fun `text beyond the English alphabet is found as it is written`() = searching {
        terminal.write("café Café")

        find("café")

        assertEquals(2, search.total)
        find("É")
        assertEquals(0, search.total)
    }

    @Test
    fun `more matches than first made room for are all marked`() = searching(columns = 80, rows = 24) {
        repeat(24) { row -> terminal.write("a a a a a a a a" + if (row < 23) "\r\n" else "") }

        find("a")

        assertEquals(192, search.total)
        assertEquals(192, search.highlights().others.size)
    }

    @Test
    fun `the cells between two places`() {
        assertEquals(listOf(SelectedCells(1, 2, 5)), cellsBetween(2, 1, 5, 1, columns = 10, rows = 4))
        assertEquals(listOf(SelectedCells(1, 8, 9), SelectedCells(2, 0, 9), SelectedCells(3, 0, 1)), cellsBetween(8, 1, 1, 3, columns = 10, rows = 4))
        // Begun above the view, or ending below it.
        assertEquals(listOf(SelectedCells(0, 0, 3)), cellsBetween(null, null, 3, 0, columns = 10, rows = 4))
        assertEquals(listOf(SelectedCells(3, 7, 9)), cellsBetween(7, 3, null, null, columns = 10, rows = 4))
        assertEquals(emptyList(), cellsBetween(null, null, null, null, columns = 10, rows = 4))
    }
}
