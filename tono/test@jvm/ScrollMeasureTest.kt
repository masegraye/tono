package org.example.kotlintest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import org.example.kotlintest.ghostty.GhosttyMouse
import org.example.kotlintest.ghostty.GhosttyRenderer
import org.example.kotlintest.ghostty.GhosttySelection
import org.example.kotlintest.ghostty.GhosttyTerminal
import org.example.kotlintest.ghostty.MouseAction
import org.example.kotlintest.ghostty.MouseButton
import org.example.kotlintest.ghostty.MouseInput
import org.example.kotlintest.ghostty.TerminalFrame
import org.example.kotlintest.rex.ScrollTelemetry
import org.example.kotlintest.rex.TerminalPointer
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ESC = "\u001b"

/**
 * Scrolling measured offscreen (backlog-053): a terminal full of text, wheel events handed
 * to [TerminalPointer] as the app hands them, and each frame that follows read and drawn
 * with [drawTerminal] into a picture, timed. Nothing waits on a window or a screen, so the
 * numbers are what the work costs and they repeat.
 *
 * As a test it runs a short version and checks that it works. For the numbers, run it with
 * `TONO_SCROLL_MEASURE=<label>` in the environment: the table goes to
 * `<project>/build/scroll-measure/<label>.md`.
 */
class ScrollMeasureTest {
    private val label: String? = System.getenv("TONO_SCROLL_MEASURE")?.takeIf { it.isNotEmpty() }
    private val outputDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "project.yaml").isFile }
        .resolve("build/scroll-measure")
        .apply { mkdirs() }

    private val font: FontFamily = runCatching {
        val process = ProcessBuilder("fc-match", "-f", "%{file}", "monospace").start()
        val file = File(process.inputStream.bufferedReader().use { it.readText() }.trim())
        process.waitFor()
        FontFamily(Font("monospace", file.readBytes()))
    }.getOrDefault(FontFamily.Monospace)

    /** A window's worth of terminal: its size in pixels and the screen's scale. */
    private class Screen(val name: String, val width: Int, val height: Int, val density: Float)

    private val large = Screen("3400×2000 at 1.6 (a 4K screen, as Omarchy scales it)", 3400, 2000, 1.6f)
    private val small = Screen("1400×900 at 1", 1400, 900, 1f)

    /** One terminal drawn offscreen, with the pieces the app uses between the wheel and the picture. */
    private inner class Bench(screen: Screen, val smooth: Boolean = false) : AutoCloseable {
        private var frame by mutableStateOf<TerminalFrame?>(null)
        lateinit var cell: CellSize
        lateinit var layouts: RowLayouts
        private val scene = ImageComposeScene(screen.width, screen.height, Density(screen.density)) {
            val style = remember { TextStyle(fontFamily = font, fontSize = 13.sp) }
            val measurer = rememberTextMeasurer(cacheSize = 0)
            val measured = remember { measureCell(measurer, style, Density(screen.density)).also { cell = it } }
            val rowLayouts = remember { RowLayouts(measured).also { layouts = it } }
            val pictures = remember { Pictures() }
            Canvas(Modifier.fillMaxSize()) {
                frame?.let { drawTerminal(it, measured, rowLayouts, measurer, style, pictures) }
            }
        }
        private var clock = 0L

        init {
            draw() // so that the cell is measured
        }

        val columns = (screen.width / cell.width).toInt()
        val rows = (screen.height / cell.height).toInt()
        val terminal = GhosttyTerminal.create(columns, rows)
        val renderer = GhosttyRenderer()
        private val mouse = GhosttyMouse()
        private val selection = GhosttySelection()
        private val pointer = TerminalPointer(mouse, selection)
        var time = 1_000L

        /** A turn of the wheel in the middle of the terminal. Returns what the program is sent for it. */
        fun wheel(rows: Int): ByteArray {
            time += 16
            val input = MouseInput(
                MouseAction.Press, if (rows < 0) MouseButton.WheelUp else MouseButton.WheelDown,
                x = 400f, y = 300f, cellWidth = cell.width.toInt().coerceAtLeast(1), cellHeight = cell.height.toInt().coerceAtLeast(1),
            )
            return pointer.handle(terminal, input, rows, time)
        }

        fun read(): TerminalFrame = renderer.render(terminal).also { frame = it }

        /** Composes, lays out, draws and rasterizes one frame of the window. */
        fun draw() {
            clock += 16_000_000
            scene.render(clock).close()
        }

        override fun close() {
            scene.close()
            renderer.close()
            mouse.close()
            selection.close()
            terminal.close()
        }
    }

    private class Step(val handleMicros: Long, val readMicros: Long, val drawMicros: Long, val layoutMicros: Long, val laidOut: Int, val rows: Int, val runs: Int, val moved: Long)

    private fun micros() = System.nanoTime() / 1000

    /** One wheel event and the frame after it: handled, read, drawn. [program] plays the terminal's program, given what it was sent. */
    private fun Bench.step(rows: Int, program: (ByteArray) -> Unit = {}): Step {
        val top = terminal.viewportTop
        val start = micros()
        val report = wheel(rows)
        if (report.isNotEmpty()) program(report)
        val handled = micros()
        val frame = read()
        val read = micros()
        draw()
        val drawn = micros()
        return Step(handled - start, read - handled, drawn - read, layouts.layoutMicros, layouts.laidOut, frame.rows.size, frame.rows.sumOf { it.runs.size }, terminal.viewportTop - top)
    }

    private fun plain(lines: Int) = buildString {
        for (i in 1..lines) append("%05d the quick brown fox jumps over the lazy dog; pack my box with five dozen liquor jugs %d\r\n".format(i, i * 7))
    }

    /** A log in SGR colors, a dozen or two runs to a row. */
    private fun colored(lines: Int, columns: Int) = buildString {
        val random = Random(7)
        val levels = listOf("INFO " to "32", "WARN " to "33;1", "ERROR" to "31;1", "DEBUG" to "2;36")
        val words = "connect retry flush cache index shard commit rollback queue worker timeout socket parse render frame scroll layout glyph atlas".split(" ")
        for (i in 1..lines) {
            val (level, sgr) = levels.random(random)
            val line = StringBuilder("$ESC[90m2026-11-19T10:%02d:%02d$ESC[0m $ESC[${sgr}m$level$ESC[0m $ESC[35m[${words.random(random)}]$ESC[0m".format(i / 60 % 60, i % 60))
            var width = 40
            while (width < columns - 24) {
                val word = words.random(random)
                val roll = random.nextDouble()
                line.append(' ')
                line.append(
                    when {
                        roll < 0.25 -> "$ESC[38;5;${random.nextInt(17, 230)}m$word$ESC[0m"
                        roll < 0.35 -> "$ESC[4m$word$ESC[0m"
                        roll < 0.45 -> "$ESC[1;3m$word$ESC[0m"
                        roll < 0.5 -> "$ESC[48;2;${random.nextInt(20, 90)};${random.nextInt(20, 90)};${random.nextInt(20, 90)}m $word $ESC[0m"
                        else -> "$word=${random.nextInt(0, 9999)}"
                    },
                )
                width += word.length + 6
            }
            append(line).append("\r\n")
        }
    }

    /**
     * What a coding agent leaves in a terminal: boxes drawn with lines, text on colored
     * backgrounds, symbols from outside the font's plain range, bold and dim. Many short runs.
     */
    private fun agent(blocks: Int, columns: Int) = buildString {
        val random = Random(11)
        val words = "refactor the renderer so that rows which only moved keep their layout and nothing is measured twice while the wheel turns".split(" ")
        fun sentence(length: Int) = buildString { while (this.length < length) append(words.random(random)).append(' ') }.take(length)
        val width = columns - 4
        repeat(blocks) { block ->
            append("$ESC[1;38;5;141m● $ESC[0m$ESC[1m${sentence(60)}$ESC[0m\r\n")
            append("$ESC[38;5;240m╭${"─".repeat(width)}╮$ESC[0m\r\n")
            repeat(6) { line ->
                val added = line % 2 == 0
                val background = if (added) "48;2;20;60;30" else "48;2;70;25;25"
                append("$ESC[38;5;240m│$ESC[0m$ESC[$background;38;5;250m %4d ${if (added) "+" else "-"} ".format(block * 10 + line))
                append("$ESC[38;5;81mval$ESC[38;5;250m ${sentence(18)}$ESC[38;5;215m=$ESC[38;5;250m ${sentence(30)}$ESC[38;5;114m\"${sentence(16)}\"$ESC[38;5;250m".padEnd(width + 60))
                append("$ESC[0m$ESC[${columns}G$ESC[38;5;240m│$ESC[0m\r\n")
            }
            append("$ESC[38;5;240m╰${"─".repeat(width)}╯$ESC[0m\r\n")
            append("  $ESC[2m⎿  $ESC[0m$ESC[32m✓$ESC[0m ${sentence(40)} $ESC[2m(${random.nextInt(1, 900)} ms)$ESC[0m → $ESC[4;38;5;75m${sentence(24)}$ESC[0m\r\n\r\n")
        }
    }

    /** A full-screen program that tracks the mouse and draws its whole screen again for every wheel report, three lines on. */
    private class Program(val bench: Bench) {
        private var top = 10_000
        private fun line(i: Int) = "$ESC[38;5;${17 + i % 200}m%05d$ESC[0m $ESC[1mitem$ESC[0m ${"lorem ipsum dolor sit amet ".repeat(3)}$ESC[32m${i * 13}$ESC[0m".format(i)

        fun start() {
            bench.terminal.write("$ESC[?1049h$ESC[?1000h$ESC[?1006h$ESC[?25l")
            paint()
        }

        fun wheel(report: ByteArray) {
            // SGR reports: button 64 is the wheel turned up, 65 down.
            val button = report.decodeToString().substringAfter("<").substringBefore(";").toIntOrNull() ?: return
            top += if (button == 64) -3 else 3
            paint()
        }

        private fun paint() = bench.terminal.write(buildString {
            append("$ESC[?2026h$ESC[H")
            for (row in 0 until bench.rows - 1) append(line(top + row)).append("$ESC[K\r\n")
            append("$ESC[7m top=$top $ESC[0m$ESC[K$ESC[?2026l")
        })
    }

    private class Result(val name: String, val steps: List<Step>)

    private fun measure(screen: Screen, wheelSteps: Int): List<Result> {
        val results = ArrayList<Result>()
        fun scrollBack(name: String, content: (Bench) -> String) = Bench(screen).use { bench ->
            bench.terminal.write(content(bench))
            bench.read(); bench.draw()
            // Up and back down once unmeasured, so that what is timed is not the first use of anything.
            repeat(wheelSteps) { bench.step(-3) }
            repeat(wheelSteps) { bench.step(3) }
            results += Result("$name, wheel up", List(wheelSteps) { bench.step(-3) })
            results += Result("$name, wheel back down", List(wheelSteps) { bench.step(3) })
            for (result in results.takeLast(2)) assertTrue(result.steps.all { it.moved == 3L || it.moved == -3L }, "${result.name}: every step moves the view three rows")
        }
        scrollBack("plain text") { plain(10_000) }
        scrollBack("colored log") { colored(4_000, it.columns) }
        scrollBack("agent's output") { agent(400, it.columns) }
        Bench(screen).use { bench ->
            val program = Program(bench)
            program.start()
            bench.read(); bench.draw()
            repeat(wheelSteps) { bench.step(-3, program::wheel) }
            results += Result("full-screen program with the mouse, wheel up", List(wheelSteps) { bench.step(-3, program::wheel) })
        }
        Bench(screen).use { bench ->
            // Scrolled back while the program at the bottom goes on writing, as a spinner does.
            bench.terminal.write(agent(400, bench.columns))
            bench.read(); bench.draw()
            repeat(wheelSteps) { bench.step(-3) }
            repeat(wheelSteps) { bench.step(3) }
            val spinner = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
            fun spin(i: Int) = bench.terminal.write("\r$ESC[38;5;141m${spinner[i % spinner.length]}$ESC[0m Working… $ESC[2m(${i}s)$ESC[0m$ESC[K")
            results += Result("agent's output, wheel up while a spinner turns", List(wheelSteps) { spin(it); bench.step(-3) })
            assertTrue(results.last().steps.all { it.moved == -3L }, "every step moves the view three rows while the spinner turns")
        }
        return results
    }

    private fun List<Long>.percentile(percent: Int): Long = sorted()[(size - 1) * percent / 100]
    private fun ms(micros: Long) = "%.1f".format(micros / 1000.0)

    private fun table(screen: Screen, results: List<Result>) = buildString {
        append("### ${screen.name}\n\n")
        append("| Scrolling | Rows × runs | Rows laid out per step | Read frame, ms (median / p95) | Lay out text, ms (median / p95) | Whole frame, ms (median / p95 / max) | Frames over 16.7 ms |\n")
        append("|---|---|---|---|---|---|---|\n")
        for (result in results) {
            val steps = result.steps
            val whole = steps.map { it.handleMicros + it.readMicros + it.drawMicros }
            append("| ${result.name} | ${steps.last().rows} × ${steps.sumOf { it.runs } / steps.size} | ${"%.1f".format(steps.sumOf { it.laidOut }.toDouble() / steps.size)}")
            append(" | ${ms(steps.map { it.readMicros }.percentile(50))} / ${ms(steps.map { it.readMicros }.percentile(95))}")
            append(" | ${ms(steps.map { it.layoutMicros }.percentile(50))} / ${ms(steps.map { it.layoutMicros }.percentile(95))}")
            append(" | ${ms(whole.percentile(50))} / ${ms(whole.percentile(95))} / ${ms(whole.percentile(100))}")
            append(" | ${whole.count { it > 16_700 }} of ${whole.size} |\n")
        }
        append("\n")
    }

    @Test
    fun `scrolling is measured from the wheel to the picture`() {
        val lines = ArrayList<String>()
        ScrollTelemetry.start { lines += it }
        try {
            if (label == null) {
                val results = measure(small, wheelSteps = 6)
                assertEquals(8, results.size)
                for (result in results) assertTrue(result.steps.all { it.rows > 0 && it.drawMicros > 0 }, result.name)
                return
            }
            val text = buildString {
                append("# Scrolling measured offscreen: $label\n\n")
                append("Each step is one wheel event of three rows and the frame after it: handled by `TerminalPointer`, read by `GhosttyRenderer`, and composed, laid out, drawn and rasterized on the CPU by `drawTerminal` in an `ImageComposeScene`. 50 steps a line, after as many unmeasured.\n\n")
                for (screen in listOf(large, small)) append(table(screen, measure(screen, wheelSteps = 50)))
            }
            File(outputDir, "$label.md").writeText(text)
            println(text)
        } finally {
            ScrollTelemetry.stop()
        }
    }
}
