package org.example.kotlintest.ghostty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GhosttySelectionTest {
    /** Cells ten pixels wide and twenty tall, so a position reads off easily. */
    private fun at(column: Int, row: Int, within: Float = 0.5f) =
        MouseInput(MouseAction.Motion, MouseButton.Left, x = (column + within) * 10, y = row * 20 + 10f, cellWidth = 10, cellHeight = 20)

    private fun selecting(columns: Int = 20, rows: Int = 4, body: Scene.() -> Unit) =
        GhosttyTerminal.create(columns, rows).use { terminal ->
            GhosttyRenderer().use { renderer -> GhosttySelection().use { selection -> Scene(terminal, renderer, selection).body() } }
        }

    private class Scene(val terminal: GhosttyTerminal, val renderer: GhosttyRenderer, val selection: GhosttySelection) {
        val text get() = selection.text(terminal)
        val shown get() = renderer.render(terminal).selection
    }

    @Test
    fun `nothing is selected to begin with`() = selecting {
        terminal.write("hello world")

        assertEquals("", text)
        assertEquals(emptyList(), shown)
    }

    @Test
    fun `dragging selects the cells passed over`() = selecting {
        terminal.write("hello world")

        selection.press(terminal, at(0, 0, within = 0.1f), timeMillis = 1_000)
        assertFalse(selection.drag(terminal, at(4, 0, within = 0.9f)))
        selection.release(terminal, at(4, 0, within = 0.9f))

        assertEquals("hello", text)
        assertEquals(listOf(SelectedCells(row = 0, firstColumn = 0, lastColumn = 4)), shown)
    }

    @Test
    fun `dragging over several rows takes the rows between whole`() = selecting {
        terminal.write("one\r\ntwo\r\nthree")

        selection.press(terminal, at(1, 0, within = 0.1f), timeMillis = 1_000)
        selection.drag(terminal, at(1, 2, within = 0.9f))

        assertEquals("ne\ntwo\nth", text)
        assertEquals(
            listOf(SelectedCells(0, 1, 19), SelectedCells(1, 0, 19), SelectedCells(2, 0, 1)),
            shown,
        )
    }

    @Test
    fun `a press on its own drops the selection`() = selecting {
        terminal.write("hello world")
        selection.press(terminal, at(0, 0, within = 0.1f), timeMillis = 1_000)
        selection.drag(terminal, at(4, 0, within = 0.9f))
        selection.release(terminal, at(4, 0))

        selection.press(terminal, at(8, 0), timeMillis = 5_000)
        selection.release(terminal, at(8, 0))

        assertEquals("", text)
        assertEquals(emptyList(), shown)
    }

    @Test
    fun `a second press selects the word and a third the line`() = selecting {
        terminal.write("hello brave world")

        selection.press(terminal, at(7, 0), timeMillis = 1_000)
        selection.release(terminal, at(7, 0))
        selection.press(terminal, at(7, 0), timeMillis = 1_200)
        selection.release(terminal, at(7, 0))
        assertEquals("brave", text)

        selection.press(terminal, at(7, 0), timeMillis = 1_400)
        selection.release(terminal, at(7, 0))
        assertEquals("hello brave world", text)
    }

    @Test
    fun `a second press that comes late, or elsewhere, is a first press`() = selecting {
        terminal.write("hello brave world")

        selection.press(terminal, at(7, 0), timeMillis = 1_000)
        selection.press(terminal, at(7, 0), timeMillis = 2_000)
        assertEquals("", text)

        selection.press(terminal, at(14, 0), timeMillis = 2_100)
        assertEquals("", text)
    }

    @Test
    fun `a line the terminal wrapped is copied as one line`() = selecting(columns = 10) {
        terminal.write("abcdefghijklmno\r\nnext")

        selection.selectAll(terminal)

        assertEquals("abcdefghijklmno\nnext", text)
    }

    @Test
    fun `select all takes the scrollback too`() = selecting {
        terminal.write((1..10).joinToString("\r\n") { "line $it" })

        selection.selectAll(terminal)

        assertEquals((1..10).joinToString("\n") { "line $it" }, text)
        assertEquals(4, shown.size)
    }

    @Test
    fun `clearing drops the selection`() = selecting {
        terminal.write("hello")
        selection.selectAll(terminal)

        selection.clear(terminal)

        assertEquals("", text)
        assertEquals(emptyList(), shown)
    }

    @Test
    fun `a selection stays on its text as output scrolls it`() = selecting {
        terminal.write("first\r\nsecond")
        selection.press(terminal, at(0, 1, within = 0.1f), timeMillis = 1_000)
        selection.drag(terminal, at(5, 1, within = 0.9f))
        selection.release(terminal, at(5, 1))
        assertEquals(listOf(SelectedCells(1, 0, 5)), shown)

        terminal.write("\r\nthird\r\nfourth\r\nfifth")

        assertEquals("second", text)
        assertEquals(listOf(SelectedCells(0, 0, 5)), shown)

        // And out of view with it, still there to copy.
        terminal.write("\r\nsixth\r\nseventh")
        assertEquals(emptyList(), shown)
        assertEquals("second", text)
    }

    @Test
    fun `a selection survives the terminal being resized`() = selecting {
        terminal.write("hello brave world")
        selection.press(terminal, at(6, 0, within = 0.1f), timeMillis = 1_000)
        selection.drag(terminal, at(10, 0, within = 0.9f))

        terminal.resize(8, 4)

        assertEquals("brave", text)
        assertEquals(listOf(SelectedCells(0, 6, 7), SelectedCells(1, 0, 2)), shown)
    }

    @Test
    fun `dragging past the bottom asks to scroll, and scrolling extends the selection`() = selecting {
        terminal.write((1..10).joinToString("\r\n") { "line $it" })
        terminal.scrollViewport(-6) // lines 1 to 4 in view
        selection.press(terminal, at(0, 0, within = 0.1f), timeMillis = 1_000)

        val below = MouseInput(MouseAction.Motion, MouseButton.Left, x = 39f, y = 95f, cellWidth = 10, cellHeight = 20)
        assertTrue(selection.drag(terminal, below))
        assertEquals("line 1\nline 2\nline 3\nline", text)

        assertTrue(selection.autoscroll(terminal, below))
        assertEquals("line 1\nline 2\nline 3\nline 4\nline", text)

        // Back inside, it stops asking.
        assertFalse(selection.drag(terminal, at(3, 2)))
    }

    @Test
    fun `a position outside the terminal counts as the nearest cell`() = selecting {
        terminal.write("hello\r\nworld")
        selection.press(terminal, at(2, 0, within = 0.1f), timeMillis = 1_000)

        selection.drag(terminal, MouseInput(MouseAction.Motion, MouseButton.Left, x = 500f, y = 30f, cellWidth = 10, cellHeight = 20))

        assertEquals("llo\nworld", text)
    }

    @Test
    fun `erasing the screen drops the selection`() = selecting {
        terminal.write("hello")
        selection.selectAll(terminal)

        terminal.write("\u001b[2J")

        assertEquals("", text)
    }
}
