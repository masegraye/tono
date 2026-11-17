package org.example.kotlintest.ghostty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val ESC = "\u001b"

class GhosttyTerminalTest {
    private fun terminal(columns: Int = 20, rows: Int = 4) = GhosttyTerminal.create(columns, rows)

    @Test
    fun `plain output lands on the screen`() = terminal().use { terminal ->
        terminal.write("hello\r\nworld")

        assertEquals("hello\nworld", terminal.text())
        assertEquals(20, terminal.columns)
        assertEquals(4, terminal.rows)
    }

    @Test
    fun `a frame has one row per screen row and the cursor`() = terminal().use { terminal ->
        GhosttyRenderer().use { renderer ->
            terminal.write("ab\r\ncd")

            val frame = renderer.render(terminal)

            assertEquals(20, frame.columns)
            assertEquals(4, frame.rows.size)
            assertEquals(listOf("ab", "cd", "", ""), frame.rows.map { it.text() })
            val cursor = assertNotNull(frame.cursor)
            assertEquals(2, cursor.column)
            assertEquals(1, cursor.row)
        }
    }

    @Test
    fun `styled text becomes separate runs`() = terminal().use { terminal ->
        GhosttyRenderer().use { renderer ->
            terminal.setColors(TerminalColors(foreground = 0xEEEEEE, background = 0x101010, ansi = ANSI))
            terminal.write("hi $ESC[1;31mred$ESC[0m $ESC[4;48;2;1;2;3mbox$ESC[0m")

            val frame = renderer.render(terminal)
            val runs = frame.rows[0].runs

            assertEquals(0x101010, frame.background)
            assertEquals(0xEEEEEE, frame.foreground)
            assertEquals(listOf("hi ", "red", " ", "box"), runs.map { it.text })
            assertEquals(listOf(0, 3, 6, 7), runs.map { it.column })

            assertEquals(0xEEEEEE, runs[0].foreground)
            assertNull(runs[0].background)

            assertEquals(0xAA0001, runs[1].foreground) // ANSI red, from the palette set above
            assertTrue(runs[1].bold)

            assertEquals(0x010203, runs[3].background)
            assertTrue(runs[3].underline)
        }
    }

    @Test
    fun `inverse swaps the colors`() = terminal().use { terminal ->
        GhosttyRenderer().use { renderer ->
            terminal.setColors(TerminalColors(foreground = 0xEEEEEE, background = 0x101010))
            terminal.write("$ESC[7mX")

            val run = renderer.render(terminal).rows[0].runs.single()

            assertEquals(0x101010, run.foreground)
            assertEquals(0xEEEEEE, run.background)
        }
    }

    @Test
    fun `a wide character is its own two-cell run`() = terminal().use { terminal ->
        GhosttyRenderer().use { renderer ->
            terminal.write("a日b")

            val runs = renderer.render(terminal).rows[0].runs

            assertEquals(listOf("a", "日", "b"), runs.map { it.text })
            assertEquals(listOf(0, 1, 3), runs.map { it.column })
            assertEquals(listOf(1, 2, 1), runs.map { it.cells })
        }
    }

    @Test
    fun `unchanged rows are reused between frames`() = terminal().use { terminal ->
        GhosttyRenderer().use { renderer ->
            terminal.write("top\r\nbottom")
            val first = renderer.render(terminal)

            terminal.write("!")
            val second = renderer.render(terminal)

            assertSame(first.rows[0], second.rows[0])
            assertNotSame(first.rows[1], second.rows[1])
            assertEquals("bottom!", second.rows[1].text())
        }
    }

    @Test
    fun `changing the colors rebuilds every row`() = terminal().use { terminal ->
        GhosttyRenderer().use { renderer ->
            terminal.write("text")
            val before = renderer.render(terminal)

            terminal.setColors(TerminalColors(foreground = 0x00FF00, background = 0x000000))
            renderer.invalidate()
            val after = renderer.render(terminal)

            assertNotSame(before.rows[0], after.rows[0])
            assertEquals(0x00FF00, after.rows[0].runs.single().foreground)
        }
    }

    @Test
    fun `scrolling the viewport shows older output and hides the cursor`() = terminal(rows = 3).use { terminal ->
        GhosttyRenderer().use { renderer ->
            terminal.write((1..6).joinToString("\r\n") { "line $it" })
            assertEquals(listOf("line 4", "line 5", "line 6"), renderer.render(terminal).rows.map { it.text() })

            terminal.scrollViewport(-2)
            val scrolled = renderer.render(terminal)
            assertEquals(listOf("line 2", "line 3", "line 4"), scrolled.rows.map { it.text() })
            assertNull(scrolled.cursor)

            terminal.scrollToBottom()
            assertEquals("line 6", renderer.render(terminal).rows.last().text())
        }
    }

    @Test
    fun `resizing changes the grid`() = terminal().use { terminal ->
        terminal.resize(columns = 10, rows = 2)

        assertEquals(10, terminal.columns)
        assertEquals(2, terminal.rows)
    }

    @Test
    fun `a terminal is restored from its snapshot and keeps going`() = terminal().use { original ->
        original.write("before $ESC[32mgreen")
        val snapshot = original.snapshot()
        // A Rex terminal stream is a snapshot followed directly by live output.
        val stream = snapshot + " after".toByteArray()

        val restored = GhosttyTerminal.restore(stream)

        restored.terminal.use { terminal ->
            assertEquals(snapshot.size, restored.snapshotBytes)
            assertEquals("before green", terminal.text())
            terminal.write(stream, restored.snapshotBytes)
            assertEquals("before green after", terminal.text())
        }
    }

    private companion object {
        val ANSI = listOf(
            0x000001, 0xAA0001, 0x00AA01, 0xAA5501, 0x0000AA, 0xAA00AA, 0x00AAAA, 0xAAAAAA,
            0x555555, 0xFF5555, 0x55FF55, 0xFFFF55, 0x5555FF, 0xFF55FF, 0x55FFFF, 0xFFFFFF,
        )
    }
}
