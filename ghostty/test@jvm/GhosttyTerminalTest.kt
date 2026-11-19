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
            assertEquals(TerminalUnderline.Single, runs[3].underline)
        }
    }

    @Test
    fun `each kind of underline, its color and an overline are read`() = terminal().use { terminal ->
        GhosttyRenderer().use { renderer ->
            terminal.setColors(TerminalColors(foreground = 0xEEEEEE, background = 0x101010, ansi = ANSI))
            // One letter for each of SGR 4:1 to 4:5, then colors for the line (58, as RGB and from the palette, and 59 to drop it), then 53 and 55.
            terminal.write("$ESC[4:1ma$ESC[4:2mb$ESC[4:3mc$ESC[4:4md$ESC[4:5me$ESC[4:0mf")
            terminal.write("$ESC[4:3;58:2::10:20:30mg$ESC[58:5:1mh$ESC[59mi$ESC[0;53mj$ESC[9mk$ESC[55ml")

            val runs = renderer.render(terminal).rows[0].runs

            assertEquals(listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l"), runs.map { it.text })
            assertEquals(
                listOf(TerminalUnderline.Single, TerminalUnderline.Double, TerminalUnderline.Curly, TerminalUnderline.Dotted, TerminalUnderline.Dashed, TerminalUnderline.None),
                runs.take(6).map { it.underline },
            )
            assertTrue(runs.take(6).all { it.underlineColor == null && !it.overline })
            assertEquals(listOf(TerminalUnderline.Curly, TerminalUnderline.Curly, TerminalUnderline.Curly, TerminalUnderline.None), runs.subList(6, 10).map { it.underline })
            assertEquals(listOf(0x0A141E, 0xAA0001, null, null), runs.subList(6, 10).map { it.underlineColor }) // the second is ANSI red, from the palette set above
            assertEquals(listOf(false, false, false, true, true, false), runs.subList(6, 12).map { it.overline })
            assertEquals(listOf(false, true, true), runs.subList(9, 12).map { it.strikethrough })
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
    fun `rows that draw the same are equal, wherever they are and whenever they were read`() = terminal(rows = 3).use { terminal ->
        GhosttyRenderer().use { renderer ->
            terminal.write((1..6).joinToString("\r\n") { "$ESC[3${it}mline $it$ESC[0m" })
            val before = renderer.render(terminal)

            // The library reads every row again after the view moves, so these are new objects.
            terminal.scrollViewport(-1)
            val after = renderer.render(terminal)

            assertEquals(listOf("line 3", "line 4", "line 5"), after.rows.map { it.text() })
            assertNotSame(before.rows[0], after.rows[1])
            assertEquals(before.rows[0], after.rows[1])
            assertEquals(before.rows[0].hashCode(), after.rows[1].hashCode())
            assertEquals(before.rows[1], after.rows[2])
            // The same text in another color is another row.
            assertTrue(before.rows[0] != before.rows[1])
            assertTrue(after.rows[0] != before.rows[0])
        }
    }

    @Test
    fun `the view says where it is in the history, and stops at both ends`() = terminal(rows = 3).use { terminal ->
        terminal.write((1..6).joinToString("\r\n") { "line $it" })
        val bottom = terminal.viewportTop
        assertEquals(3, bottom)

        terminal.scrollViewport(-2)
        assertEquals(bottom - 2, terminal.viewportTop)
        // Asked for more than there is.
        terminal.scrollViewport(-5)
        assertEquals(0, terminal.viewportTop)
        terminal.scrollViewport(50)
        assertEquals(bottom, terminal.viewportTop)
    }

    @Test
    fun `asked for rows past the view's edges, a frame has the ones there are`() = terminal(rows = 3).use { terminal ->
        GhosttyRenderer().use { renderer ->
            terminal.write((1..9).joinToString("\r\n") { "line $it" })
            assertTrue(renderer.render(terminal).let { it.above.isEmpty() && it.below.isEmpty() })

            renderer.overscan = 2
            val live = renderer.render(terminal)
            assertEquals(listOf("line 7", "line 8", "line 9"), live.rows.map { it.text() })
            assertEquals(listOf("line 5", "line 6"), live.above.map { it.text() })
            // There is nothing below the live screen.
            assertEquals(emptyList(), live.below)
            assertEquals(2, assertNotNull(live.cursor).row)

            terminal.scrollViewport(-5)
            val back = renderer.render(terminal)
            assertEquals(listOf("line 2", "line 3", "line 4"), back.rows.map { it.text() })
            assertEquals(listOf("line 1"), back.above.map { it.text() })
            assertEquals(listOf("line 5", "line 6"), back.below.map { it.text() })

            renderer.overscan = 0
            assertTrue(renderer.render(terminal).let { it.above.isEmpty() && it.below.isEmpty() && it.rows.size == 3 })
        }
    }

    @Test
    fun `a selection in the rows past the view's edges is placed by the view's rows`() = terminal(rows = 3).use { terminal ->
        GhosttyRenderer().use { renderer ->
            GhosttySelection().use { selection ->
                terminal.write((1..9).joinToString("\r\n") { "line $it" })
                selection.selectAll(terminal)
                terminal.scrollViewport(-3)
                renderer.overscan = 1

                val frame = renderer.render(terminal)

                assertEquals(listOf("line 4", "line 5", "line 6"), frame.rows.map { it.text() })
                assertEquals(listOf(-1, 0, 1, 2, 3), frame.selection.map { it.row })
            }
        }
    }

    @Test
    fun `a program can ask for pastes to be bracketed`() = terminal().use { terminal ->
        assertEquals(false, terminal.bracketedPaste)
        terminal.write("$ESC[?2004h")
        assertEquals(true, terminal.bracketedPaste)
        terminal.write("$ESC[?2004l")
        assertEquals(false, terminal.bracketedPaste)
    }

    @Test
    fun `pasted text is made safe for the terminal`() {
        // Bracketed: the program is told where the paste starts and ends, and the text is kept.
        assertEquals("$ESC[200~ls\nrm$ESC[201~", String(encodePaste("ls\nrm", bracketed = true)))
        // Not bracketed: a newline becomes Return, as if typed.
        assertEquals("ls\rrm", String(encodePaste("ls\nrm", bracketed = false)))
        // An escape character in the text can't end the paste early or start a sequence.
        assertEquals("$ESC[200~a b$ESC[201~", String(encodePaste("a${ESC}b", bracketed = true)))
        assertEquals("", String(encodePaste("", bracketed = false)))
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

    private fun at(column: Int, row: Int, action: MouseAction, button: MouseButton?, shift: Boolean = false, down: Boolean = action == MouseAction.Press) =
        MouseInput(action, button, x = column * 8f + 3f, y = row * 16f + 5f, cellWidth = 8, cellHeight = 16, shift = shift, anyButtonDown = down)

    private fun GhosttyMouse.report(terminal: GhosttyTerminal, input: MouseInput) = String(encode(terminal, input)).replace("\u001b", "ESC")

    @Test
    fun `a program that has not asked for the mouse is sent nothing`() = terminal().use { terminal ->
        GhosttyMouse().use { mouse ->
            assertEquals("", mouse.report(terminal, at(3, 1, MouseAction.Press, MouseButton.Left)))
            assertEquals("", mouse.report(terminal, at(3, 1, MouseAction.Press, MouseButton.WheelUp)))
        }
    }

    @Test
    fun `clicks, the wheel and drags are reported by cell once a program asks`() = terminal().use { terminal ->
        GhosttyMouse().use { mouse ->
            // What Neovim sends: report presses, releases and drags, in the SGR form.
            terminal.write("\u001b[?1002h\u001b[?1006h")
            assertEquals("ESC[<0;4;2M", mouse.report(terminal, at(3, 1, MouseAction.Press, MouseButton.Left)))
            assertEquals("ESC[<32;6;2M", mouse.report(terminal, at(5, 1, MouseAction.Motion, MouseButton.Left, down = true)))
            assertEquals("", mouse.report(terminal, at(5, 1, MouseAction.Motion, MouseButton.Left, down = true)), "still in the same cell")
            assertEquals("ESC[<0;6;2m", mouse.report(terminal, at(5, 1, MouseAction.Release, MouseButton.Left)))
            assertEquals("ESC[<64;1;1M", mouse.report(terminal, at(0, 0, MouseAction.Press, MouseButton.WheelUp)))
            assertEquals("ESC[<65;1;1M", mouse.report(terminal, at(0, 0, MouseAction.Press, MouseButton.WheelDown)))
            // Just moving, with no button down, is only wanted in the any-motion mode.
            assertEquals("", mouse.report(terminal, at(9, 2, MouseAction.Motion, null)))
            terminal.write("\u001b[?1003h")
            assertEquals("ESC[<35;11;3M", mouse.report(terminal, at(10, 2, MouseAction.Motion, null)))
            // And when the program stops asking, nothing more is sent.
            terminal.write("\u001b[?1003l\u001b[?1002l")
            assertEquals("", mouse.report(terminal, at(3, 1, MouseAction.Press, MouseButton.Left)))
        }
    }

    private fun base64(bytes: ByteArray) = java.util.Base64.getEncoder().encodeToString(bytes)

    /** A Kitty graphics command that sends pixels and shows them where the cursor is. */
    private fun showImage(format: Int, width: Int, height: Int, pixels: ByteArray) =
        "\u001b_Ga=T,f=$format,s=$width,v=$height;${base64(pixels)}\u001b\\"

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `an image a program sends is on screen where the cursor was, with its pixels`() = terminal(columns = 20, rows = 6).use { terminal ->
        GhosttyRenderer().use { renderer ->
            terminal.resize(20, 6, cellWidthPx = 10, cellHeightPx = 20)
            assertEquals(emptyList(), renderer.render(terminal).images)

            // Two pixels by two: red, green, blue, and half-transparent white.
            val pixels = bytes(255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255, 255, 255, 255, 128)
            terminal.write("ab\r\nc")
            terminal.write(showImage(format = 32, width = 2, height = 2, pixels))

            val frame = renderer.render(terminal)
            val placed = frame.images.single()
            assertEquals(1 to 1, placed.column to placed.row, "after the c on the second row")
            assertEquals(2 to 2, placed.image.width to placed.image.height)
            assertEquals(pixels.toList(), placed.image.rgba.toList())
            assertEquals(listOf(0, 0, 2, 2), listOf(placed.sourceX, placed.sourceY, placed.sourceWidth, placed.sourceHeight))
            assertEquals(10 to 20, frame.cellWidthPx to frame.cellHeightPx)

            // Nothing changed: the same image object comes back, so what was made from it can be kept.
            assertEquals(true, renderer.render(terminal).images.single().image === placed.image)
        }
    }

    @Test
    fun `pixels without alpha are opaque`() = terminal().use { terminal ->
        GhosttyRenderer().use { renderer ->
            terminal.resize(20, 4, cellWidthPx = 10, cellHeightPx = 20) // an image is sized in cells, so a cell needs a size
            terminal.write(showImage(format = 24, width = 1, height = 2, bytes(10, 20, 30, 40, 50, 60)))
            assertEquals(bytes(10, 20, 30, 255, 40, 50, 60, 255).toList(), renderer.render(terminal).images.single().image.rgba.toList())
        }
    }

    @Test
    fun `a PNG is decoded on its way in`() = terminal().use { terminal ->
        GhosttyRenderer().use { renderer ->
            val picture = java.awt.image.BufferedImage(3, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            picture.setRGB(0, 0, 0xFF112233.toInt())
            picture.setRGB(2, 1, 0x80AABBCC.toInt())
            val png = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(picture, "png", it) }.toByteArray()
            terminal.resize(20, 4, cellWidthPx = 10, cellHeightPx = 20)
            terminal.write("\u001b_Ga=T,f=100;${base64(png)}\u001b\\")

            val image = renderer.render(terminal).images.single().image
            assertEquals(3 to 2, image.width to image.height)
            assertEquals(bytes(0x11, 0x22, 0x33, 0xFF).toList(), image.rgba.take(4))
            assertEquals(bytes(0xAA, 0xBB, 0xCC, 0x80).toList(), image.rgba.takeLast(4))
        }
    }

    @Test
    fun `an image comes back with the terminal it was in`() = terminal().use { terminal ->
        terminal.resize(20, 4, cellWidthPx = 10, cellHeightPx = 20)
        terminal.write(showImage(format = 32, width = 1, height = 1, bytes(1, 2, 3, 4)))
        GhosttyTerminal.restore(terminal.snapshot()).terminal.use { restored ->
            GhosttyRenderer().use { renderer ->
                assertEquals(bytes(1, 2, 3, 4).toList(), renderer.render(restored).images.single().image.rgba.toList())
            }
        }
    }

    @Test
    fun `a program cannot make the terminal read a file`() = terminal().use { terminal ->
        GhosttyRenderer().use { renderer ->
            val file = java.nio.file.Files.createTempFile("tono-image", ".rgba")
            try {
                java.nio.file.Files.write(file, bytes(9, 9, 9, 255))
                terminal.resize(20, 4, cellWidthPx = 10, cellHeightPx = 20)
                // t=f names a file for the terminal to read the pixels from.
                terminal.write("\u001b_Ga=T,t=f,f=32,s=1,v=1;${base64(file.toString().toByteArray())}\u001b\\")
                assertEquals(emptyList(), renderer.render(terminal).images)
            } finally {
                java.nio.file.Files.deleteIfExists(file)
            }
        }
    }
}
