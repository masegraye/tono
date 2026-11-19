package org.example.kotlintest.ghostty

import kotlin.test.Test
import kotlin.test.assertEquals

/** Jumping between shell prompts, by the marks a shell with its integration on sends (OSC 133). */
class GhosttyPromptsTest {
    private class Scene(val terminal: GhosttyTerminal, val renderer: GhosttyRenderer) {
        /** The rows in view, without the blanks at their ends. */
        val screen get() = renderer.render(terminal).rows.map { it.text().trimEnd() }

        /** What a shell sends for one command: a marked prompt, the command typed at it, and five lines of output. */
        fun command(number: Int, marked: Boolean = true) {
            terminal.write(prompt(marked) + "cmd$number\r\n" + if (marked) "$OSC;C$BEL" else "")
            for (line in 1..5) terminal.write("out$number.$line\r\n")
            if (marked) terminal.write("$OSC;D;0$BEL")
        }

        /** A prompt waiting for a command. */
        fun prompt(marked: Boolean = true) = if (marked) "$OSC;A$BEL$ $OSC;B$BEL" else "$ "

        fun jump(count: Int): List<String> {
            terminal.scrollByPrompts(count)
            return screen
        }
    }

    private fun scene(body: Scene.() -> Unit) = GhosttyTerminal.create(20, 4).use { terminal ->
        GhosttyRenderer().use { renderer -> Scene(terminal, renderer).body() }
    }

    /** Four commands and the prompt after them, in a view four rows tall. */
    private fun Scene.fourCommands(marked: Boolean = true) {
        for (number in 1..4) command(number, marked)
        terminal.write(prompt(marked))
    }

    private val live = listOf("out4.3", "out4.4", "out4.5", "$")
    private fun at(number: Int) = listOf("$ cmd$number", "out$number.1", "out$number.2", "out$number.3")

    @Test
    fun `going up lands on each older prompt in turn, and stops at the oldest`() = scene {
        fourCommands()
        assertEquals(live, screen)

        assertEquals(at(4), jump(-1))
        assertEquals(at(3), jump(-1))
        assertEquals(at(2), jump(-1))
        assertEquals(at(1), jump(-1))
        assertEquals(at(1), jump(-1), "nothing older")
    }

    @Test
    fun `going down lands on each newer prompt in turn, and ends at the live screen`() = scene {
        fourCommands()
        repeat(4) { jump(-1) }

        assertEquals(at(2), jump(1))
        assertEquals(at(3), jump(1))
        assertEquals(at(4), jump(1))
        // The newest prompt is in the live screen, which is as far down as a view goes.
        assertEquals(live, jump(1))
        assertEquals(live, jump(1), "nothing newer")
    }

    @Test
    fun `from the middle of a command's output, up is that command's prompt and down the next one`() = scene {
        fourCommands()
        terminal.scrollViewport(-8)
        assertEquals(listOf("out3.1", "out3.2", "out3.3", "out3.4"), screen)

        assertEquals(at(3), jump(-1))
        terminal.scrollViewport(1)
        assertEquals(at(4), jump(1))
    }

    @Test
    fun `without marks nothing moves`() = scene {
        fourCommands(marked = false)

        assertEquals(live, jump(-1))
        terminal.scrollViewport(-8)
        val middle = screen
        assertEquals(middle, jump(-1))
        assertEquals(middle, jump(1))
    }

    @Test
    fun `only the marked prompts are stopped at`() = scene {
        command(1)
        command(2, marked = false)
        command(3)
        terminal.write(prompt())

        assertEquals(at(3), jump(-1))
        assertEquals(at(1), jump(-1))
    }

    @Test
    fun `a terminal restored from a snapshot, as a server sends it, keeps its marks`() = scene {
        fourCommands()

        GhosttyTerminal.restore(terminal.snapshot()).terminal.use { restored ->
            GhosttyRenderer().use { renderer ->
                restored.scrollByPrompts(-2)
                assertEquals(at(3), renderer.render(restored).rows.map { it.text().trimEnd() })
            }
        }
    }

    private companion object {
        const val OSC = "\u001b]133"
        const val BEL = "\u0007"
    }
}
