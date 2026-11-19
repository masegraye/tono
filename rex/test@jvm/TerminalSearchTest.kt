package org.example.kotlintest.rex

import kotlinx.coroutines.flow.MutableStateFlow
import org.example.kotlintest.ghostty.GhosttyRenderer
import org.example.kotlintest.ghostty.GhosttyTerminal
import org.example.kotlintest.ghostty.SearchHighlights
import org.example.kotlintest.ghostty.SelectedCells
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalSearchTest {
    private class Scene(val terminal: GhosttyTerminal, val renderer: GhosttyRenderer) {
        val state = MutableStateFlow<TerminalSearch?>(null)
        var clock = 0L
        /** Each look at the clock is a millisecond after the last, so that a refresh's time runs out. */
        val searching = TerminalSearching(state) { clock++ }

        /** What the search's owner does: says what is wanted, then has it looked for. */
        fun find(query: String) {
            state.value = TerminalSearch(query, complete = query.isEmpty())
            searching.find(terminal, query)
            searching.refresh()
        }

        fun catchUp() {
            var rounds = 0
            do {
                searching.refresh()
                check(++rounds < 100_000) { "the search never finished" }
            } while (searching.behind)
        }

        val found get() = state.value!!
        val screen get() = renderer.render(terminal).rows.map { it.text().trimEnd() }
    }

    private fun scene(columns: Int = 20, rows: Int = 4, body: Scene.() -> Unit) = GhosttyTerminal.create(columns, rows).use { terminal ->
        GhosttyRenderer().use { renderer ->
            val scene = Scene(terminal, renderer)
            try {
                scene.body()
            } finally {
                scene.searching.close()
            }
        }
    }

    @Test
    fun `looking for text selects the newest match, and counts from it`() = scene {
        terminal.write("fish a\r\nfish b\r\nfish c")

        find("fish")

        assertEquals("1 of 3", found.count)
        assertTrue(found.complete)
        assertEquals(listOf(SelectedCells(row = 2, firstColumn = 0, lastColumn = 3)), found.highlights.selected)
        assertEquals(setOf(SelectedCells(0, 0, 3), SelectedCells(1, 0, 3)), found.highlights.others.toSet())
    }

    @Test
    fun `next goes to older matches and round, previous the other way`() = scene {
        terminal.write("fish a\r\nfish b\r\nfish c")
        find("fish")

        fun step(forward: Boolean): String {
            searching.step(forward)
            searching.refresh()
            return found.count
        }
        assertEquals("2 of 3", step(forward = true))
        assertEquals("3 of 3", step(forward = true))
        assertEquals("1 of 3", step(forward = true))
        assertEquals("3 of 3", step(forward = false))
        assertEquals("2 of 3", step(forward = false))
    }

    @Test
    fun `nothing found says so, and nothing looked for says nothing`() = scene {
        terminal.write("fish")

        find("fowl")
        assertEquals("No matches", found.count)
        assertEquals(SearchHighlights.None, found.highlights)

        find("")
        assertEquals("", found.count)
    }

    @Test
    fun `the selected match is scrolled into view from the history`() = scene {
        terminal.write("needle here\r\n")
        repeat(30) { terminal.write("line $it\r\n") }

        find("needle")
        searching.refresh() // as the terminal's loop does after anything happens

        assertEquals("1 of 1", found.count)
        val row = screen.indexOf("needle here")
        assertTrue(row >= 0, "the match isn't on screen: $screen")
        assertEquals(listOf(SelectedCells(row, 0, 5)), found.highlights.selected)
    }

    @Test
    fun `output that keeps arriving is searched, and the selected match stays on its text`() = scene {
        terminal.write("fish a\r\n")
        find("fish")
        assertEquals("1 of 1", found.count)

        repeat(40) {
            terminal.write("fish $it\r\n")
            searching.refresh()
        }

        // Forty newer matches came in under the selected one, which is now the oldest.
        assertEquals("41 of 41", found.count)
    }

    @Test
    fun `results for text that is no longer wanted are dropped`() = scene {
        terminal.write("fish and chips")
        find("fish")

        // The owner has moved on to other text, and the terminal's thread hasn't heard yet.
        state.value = TerminalSearch("chips", complete = false)
        searching.refresh()

        assertEquals(TerminalSearch("chips", complete = false), found)

        // And once the search is closed, nothing is published at all.
        state.value = null
        searching.refresh()
        assertNull(state.value)
    }

    @Test
    fun `a history is searched a few milliseconds at a time, and all of it in the end`() = scene(columns = 80, rows = 24) {
        // About as much as a terminal made here keeps. One restored from a server keeps what the server kept.
        val line = "x".repeat(70)
        terminal.write("needle 1\r\n")
        repeat(800) { terminal.write("$line $it\r\n") }
        terminal.write("needle 2")
        state.value = TerminalSearch("needle", complete = false)
        searching.find(terminal, "needle")

        do {
            val before = clock
            searching.refresh()
            assertTrue(clock - before < 20, "one refresh looked at the clock ${clock - before} times")
        } while (searching.behind)

        assertEquals("1 of 2", found.count)
        assertTrue(found.complete)
    }

    @Test
    fun `closing ends the search, and it can be started again`() = scene {
        terminal.write("fish")
        find("fish")

        searching.find(terminal, null)
        state.value = null
        searching.refresh()
        assertFalse(searching.behind)

        find("fish")
        assertEquals("1 of 1", found.count)
    }

    @Test
    fun `how the count reads`() {
        assertEquals("", TerminalSearch("").count)
        assertEquals("Searching…", TerminalSearch("a", complete = false).count)
        assertEquals("No matches", TerminalSearch("a").count)
        assertEquals("3 of 17", TerminalSearch("a", total = 17, selected = 2).count)
        assertEquals("17 matches", TerminalSearch("a", total = 17).count)
        assertEquals("1 match", TerminalSearch("a", total = 1).count)
    }
}
