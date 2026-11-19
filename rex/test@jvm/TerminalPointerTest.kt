package org.example.kotlintest.rex

import org.example.kotlintest.ghostty.GhosttyMouse
import org.example.kotlintest.ghostty.GhosttyRenderer
import org.example.kotlintest.ghostty.GhosttySelection
import org.example.kotlintest.ghostty.GhosttyTerminal
import org.example.kotlintest.ghostty.MouseAction
import org.example.kotlintest.ghostty.MouseButton
import org.example.kotlintest.ghostty.MouseInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ESC = "\u001b"

class TerminalPointerTest {
    private class Scene(val terminal: GhosttyTerminal, val selection: GhosttySelection, val pointer: TerminalPointer) {
        var now = 1_000L
        val selected get() = selection.text(terminal)

        /** Cells are ten pixels wide and twenty tall. */
        fun pointer(action: MouseAction, button: MouseButton?, x: Float, y: Float, shift: Boolean = false, scrollRows: Int = 0, control: Boolean = false): String {
            now += 1_000 // far enough apart that no two presses make a double click
            return pointer.handle(terminal, MouseInput(action, button, x, y, cellWidth = 10, cellHeight = 20, shift = shift, control = control), scrollRows, now).decodeToString()
        }

        /** The addresses opened so far. */
        val opened = ArrayList<String>()

        fun drag(fromX: Float, toX: Float, y: Float = 10f, shift: Boolean = false): List<String> = listOf(
            pointer(MouseAction.Press, MouseButton.Left, fromX, y, shift),
            pointer(MouseAction.Motion, MouseButton.Left, toX, y, shift),
            pointer(MouseAction.Release, MouseButton.Left, toX, y, shift),
        )
    }

    private fun scene(output: String, body: Scene.() -> Unit) = GhosttyTerminal.create(20, 4).use { terminal ->
        GhosttyMouse().use { mouse ->
            GhosttySelection().use { selection ->
                GhosttyRenderer().use { renderer ->
                    terminal.write(output.encodeToByteArray())
                    lateinit var scene: Scene
                    val pointer = TerminalPointer(
                        mouse, selection,
                        linkAt = { at, column, row -> linkAt(DrawnScreen(renderer.render(at), at), column, row) },
                        open = { scene.opened += it },
                    )
                    scene = Scene(terminal, selection, pointer)
                    scene.body()
                }
            }
        }
    }

    /** What a program writes to be told of clicks, in the form most use. */
    private val tracking = "$ESC[?1000h$ESC[?1006h"

    @Test
    fun `dragging over a program that hasn't asked for the mouse selects, and sends it nothing`() = scene("hello world") {
        assertEquals(listOf("", "", ""), drag(fromX = 1f, toX = 49f))

        assertEquals("hello", selected)
    }

    @Test
    fun `a program that asked for the mouse is told, and nothing is selected`() = scene("hello world$tracking") {
        val sent = drag(fromX = 1f, toX = 49f)

        assertEquals("$ESC[<0;1;1M", sent.first())
        assertEquals("$ESC[<0;5;1m", sent.last())
        assertEquals("", selected)
    }

    @Test
    fun `with shift, dragging selects even there, and the program hears none of it`() = scene("hello world$tracking") {
        assertEquals(listOf("", "", ""), drag(fromX = 1f, toX = 49f, shift = true))

        assertEquals("hello", selected)
    }

    @Test
    fun `letting go of shift in the middle of a drag changes nothing`() = scene("hello world$tracking") {
        pointer(MouseAction.Press, MouseButton.Left, 1f, 10f, shift = true)
        assertEquals("", pointer(MouseAction.Motion, MouseButton.Left, 49f, 10f))
        assertEquals("", pointer(MouseAction.Release, MouseButton.Left, 49f, 10f))

        assertEquals("hello", selected)
        // The next click is the program's again.
        assertEquals("$ESC[<0;1;1M", pointer(MouseAction.Press, MouseButton.Left, 1f, 10f))
    }

    @Test
    fun `a click drops what was selected`() = scene("hello world") {
        drag(fromX = 1f, toX = 49f)

        pointer(MouseAction.Press, MouseButton.Left, 85f, 10f)
        pointer(MouseAction.Release, MouseButton.Left, 85f, 10f)

        assertEquals("", selected)
    }

    @Test
    fun `the other buttons leave the selection alone`() = scene("hello world") {
        drag(fromX = 1f, toX = 49f)

        pointer(MouseAction.Press, MouseButton.Right, 85f, 10f)
        pointer(MouseAction.Release, MouseButton.Right, 85f, 10f)

        assertEquals("hello", selected)
    }

    @Test
    fun `a selection is dragged past the terminal's edge`() = scene("hello world") {
        pointer(MouseAction.Press, MouseButton.Left, 61f, 10f)
        pointer(MouseAction.Motion, MouseButton.Left, 900f, 10f)
        pointer(MouseAction.Release, MouseButton.Left, 900f, 10f)

        assertEquals("world", selected)
    }

    @Test
    fun `a press outside the terminal does nothing`() = scene("hello world$tracking") {
        assertEquals("", pointer(MouseAction.Press, MouseButton.Left, 900f, 10f))
        assertEquals("", pointer(MouseAction.Motion, MouseButton.Left, 1f, 10f))

        assertEquals("", selected)
    }

    @Test
    fun `the wheel scrolls the history while selecting, and the selection stays on its text`() =
        scene((1..10).joinToString("\r\n") { "line $it" }) {
            pointer(MouseAction.Press, MouseButton.Left, 1f, 70f) // the last line
            pointer(MouseAction.Motion, MouseButton.Left, 39f, 70f)

            assertEquals("", pointer(MouseAction.Press, MouseButton.WheelUp, 39f, 70f, scrollRows = -3))
            assertEquals("line", selected)

            pointer(MouseAction.Motion, MouseButton.Left, 39f, 10f) // now over line 4
            assertEquals(" 4\nline 5\nline 6\nline 7\nline 8\nline 9", selected)
        }

    @Test
    fun `held past the bottom edge it scrolls until the pointer comes back or the button comes up`() =
        scene((1..10).joinToString("\r\n") { "line $it" }) {
            terminal.scrollViewport(-6) // lines 1 to 4 in view
            pointer(MouseAction.Press, MouseButton.Left, 1f, 10f)
            pointer(MouseAction.Motion, MouseButton.Left, 39f, 95f)
            assertNotNull(pointer.scrollingAt)

            pointer.scroll(terminal)
            pointer.scroll(terminal)
            assertTrue(selected.endsWith("line 5\nline"), selected)

            pointer(MouseAction.Release, MouseButton.Left, 39f, 95f)
            assertNull(pointer.scrollingAt)
            pointer.scroll(terminal)
            assertTrue(selected.endsWith("line 5\nline"), selected)
        }

    @Test
    fun `a button that came up unseen ends the drag`() = scene("hello world") {
        pointer(MouseAction.Press, MouseButton.Left, 1f, 10f)
        pointer(MouseAction.Motion, MouseButton.Left, 49f, 10f)

        pointer(MouseAction.Motion, null, 150f, 10f)

        assertEquals("hello", selected)
    }

    // --- Links ---

    @Test
    fun `control and a click on an address opens it, and selects nothing`() = scene("see https://a.example") {
        assertEquals("", pointer(MouseAction.Press, MouseButton.Left, 45f, 10f, control = true))
        assertEquals("", pointer(MouseAction.Motion, MouseButton.Left, 95f, 10f, control = true))
        assertEquals("", pointer(MouseAction.Release, MouseButton.Left, 95f, 10f, control = true))

        assertEquals(listOf("https://a.example"), opened)
        assertEquals("", selected)
    }

    @Test
    fun `a click on an address without control opens nothing`() = scene("see https://a.example") {
        pointer(MouseAction.Press, MouseButton.Left, 45f, 10f)
        pointer(MouseAction.Release, MouseButton.Left, 45f, 10f)

        assertEquals(emptyList(), opened)
    }

    @Test
    fun `control and a click opens a link the program made, where the program said`() = scene(marked("https://real.example/", "https://shown.example")) {
        pointer(MouseAction.Press, MouseButton.Left, 45f, 10f, control = true)

        assertEquals(listOf("https://real.example/"), opened)
    }

    @Test
    fun `a link to a file is not opened, and the click is an ordinary one`() = scene(marked("file:///etc/passwd", "hello") + " world") {
        pointer(MouseAction.Press, MouseButton.Left, 1f, 10f, control = true)
        pointer(MouseAction.Motion, MouseButton.Left, 49f, 10f, control = true)
        pointer(MouseAction.Release, MouseButton.Left, 49f, 10f, control = true)

        assertEquals(emptyList(), opened)
        assertEquals("hello", selected)
    }

    @Test
    fun `a link is opened over a program that asked for the mouse, which hears nothing of that click`() = scene("see https://a.example$tracking") {
        assertEquals("", pointer(MouseAction.Press, MouseButton.Left, 45f, 10f, control = true))
        assertEquals("", pointer(MouseAction.Release, MouseButton.Left, 45f, 10f, control = true))

        assertEquals(listOf("https://a.example"), opened)
        // The next click is the program's again.
        assertEquals("$ESC[<0;5;1M", pointer(MouseAction.Press, MouseButton.Left, 45f, 10f))
        assertEquals("$ESC[<0;5;1m", pointer(MouseAction.Release, MouseButton.Left, 45f, 10f))
    }

    @Test
    fun `control and a click away from any link still goes to a program that asked for the mouse`() = scene("see https://a.example$tracking") {
        assertEquals("$ESC[<16;1;1M", pointer(MouseAction.Press, MouseButton.Left, 1f, 10f, control = true))
        assertEquals("$ESC[<16;1;1m", pointer(MouseAction.Release, MouseButton.Left, 1f, 10f, control = true))

        assertEquals(emptyList(), opened)
    }

    @Test
    fun `with shift as well, the click selects instead of opening`() = scene("see https://a.example$tracking") {
        pointer(MouseAction.Press, MouseButton.Left, 41f, 10f, shift = true, control = true)
        pointer(MouseAction.Motion, MouseButton.Left, 89f, 10f, shift = true, control = true)
        pointer(MouseAction.Release, MouseButton.Left, 89f, 10f, shift = true, control = true)

        assertEquals(emptyList(), opened)
        assertEquals("https", selected)
    }

    @Test
    fun `the other buttons open nothing`() = scene("see https://a.example") {
        pointer(MouseAction.Press, MouseButton.Right, 45f, 10f, control = true)
        pointer(MouseAction.Press, MouseButton.Middle, 45f, 10f, control = true)

        assertEquals(emptyList(), opened)
    }

    @Test
    fun `the link under the pointer is found, and none outside the terminal`() = scene("see https://a.example") {
        fun at(x: Float, y: Float) = MouseInput(MouseAction.Motion, null, x, y, cellWidth = 10, cellHeight = 20)

        assertEquals("https://a.example", pointer.linkUnder(terminal, at(45f, 10f))?.url)
        assertNull(pointer.linkUnder(terminal, at(5f, 10f)))
        assertNull(pointer.linkUnder(terminal, at(-5f, 10f)))
        assertNull(pointer.linkUnder(terminal, at(45f, 90f)))
    }
}
