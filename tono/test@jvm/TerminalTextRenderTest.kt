package org.example.kotlintest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import org.example.kotlintest.ghostty.SearchHighlights
import org.example.kotlintest.ghostty.SelectedCells
import org.example.kotlintest.ghostty.TerminalFrame
import org.example.kotlintest.ghostty.TerminalRow
import org.example.kotlintest.ghostty.TerminalRun
import org.jetbrains.skia.Bitmap
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A terminal's text as the desktop draws it, offscreen, to see that a glyph far along a row
 * is in the cell it belongs to: text is drawn a run at a time and what is marked on it
 * (a selection, a search's matches) a cell at a time, so if the two disagree about how wide
 * a cell is, the marks miss their text, by more the further right they are. The pictures
 * are left in `<project>/build/terminal-text-renders/` to be looked at.
 */
class TerminalTextRenderTest {
    private val outputDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "project.yaml").isFile }
        .resolve("build/terminal-text-renders")
        .apply { mkdirs() }

    private val columns = 130

    /**
     * The desktop's monospace font, from its file, which is how the web app gets it. The
     * name "monospace" alone isn't used: here it gives a font whose space is narrower than
     * its letters, which the last test is about.
     */
    private val font: FontFamily = runCatching {
        val process = ProcessBuilder("fc-match", "-f", "%{file}", "monospace").start()
        val file = File(process.inputStream.bufferedReader().use { it.readText() }.trim())
        process.waitFor()
        FontFamily(Font("monospace", file.readBytes()))
    }.getOrDefault(FontFamily.Monospace)
    private val background = 0x101418
    private val foreground = 0xFFFFFF
    private val matchColor = Color(0xFF006030) // dark, so that it isn't taken for text

    /** What was drawn, and the cells it was drawn in. */
    private class Drawn(val width: Int, val height: Int, private val pixels: IntArray, val cell: CellSize, val pieces: List<Int>) {
        fun color(x: Int, y: Int): Int = pixels[y * width + x] and 0xFFFFFF

        private fun light(x: Int, y: Int): Int {
            val pixel = color(x, y)
            return maxOf((pixel shr 16) and 0xFF, (pixel shr 8) and 0xFF, pixel and 0xFF)
        }

        /** How much ink each pixel column of a row holds, text being far lighter than anything behind it. */
        private fun ink(row: Int): DoubleArray {
            val top = (row * cell.height).toInt()
            return DoubleArray(width) { x -> (top until top + cell.height.toInt()).sumOf { y -> (light(x, y) - 140).coerceAtLeast(0) }.toDouble() }
        }

        /** Where the ink in the cells from [first] to [last] of a row is centered, in pixels. */
        fun inkCenter(row: Int, first: Int, last: Int = first): Double {
            val ink = ink(row)
            // Half a cell either side as well, which is where a glyph that has drifted would be found.
            val from = ((first - 0.5f) * cell.width).toInt().coerceAtLeast(0)
            val to = ceil((last + 1.5f) * cell.width).toInt().coerceAtMost(width)
            val total = (from until to).sumOf { ink[it] }
            assertTrue(total > 0, "there is ink around columns $first..$last of row $row")
            return (from until to).sumOf { ink[it] * (it + 0.5) } / total
        }

        /** The pixel columns of a row's top line that aren't the terminal's background: what is marked there. */
        fun marked(row: Int, background: Int): IntRange {
            val y = (row * cell.height).toInt() + 1
            val marked = (0 until width).filter { x -> color(x, y) != background }
            return marked.first()..marked.last()
        }
    }

    private fun draw(
        name: String?,
        rows: List<TerminalRow>,
        family: FontFamily = font,
        fontSize: Int = 13,
        density: Float = 1f,
        selection: List<SelectedCells> = emptyList(),
        matches: SearchHighlights? = null,
    ): Drawn {
        val frame = TerminalFrame(columns, rows, background, foreground, cursor = null, selection = selection)
        var cell: CellSize? = null
        var pieces = emptyList<Int>()
        // Wide enough for the widest cells drawn here, and then some.
        val scene = ImageComposeScene(width = (columns * fontSize * density * 1.0f).toInt(), height = (rows.size * fontSize * density * 2.5f).toInt(), density = Density(density)) {
            val style = remember { TextStyle(fontFamily = family, fontSize = fontSize.sp) }
            val measurer = rememberTextMeasurer(cacheSize = 0)
            val measured = remember { measureCell(measurer, style) }
            val layouts = remember { RowLayouts(measured) }
            val pictures = remember { Pictures() }
            Canvas(Modifier.fillMaxSize()) {
                drawTerminal(frame, measured, layouts, measurer, style, pictures, matches = matches, matchColor = matchColor)
                cell = measured
                pieces = rows.map { layouts.of(it, measurer, style).size }
            }
        }
        try {
            val image = scene.render(0)
            if (name != null) File(outputDir, "$name.png").writeBytes(image.encodeToData()!!.bytes)
            // Copied out while the image is still held: a bitmap made from it shares its pixels.
            val bitmap = Bitmap.makeFromImage(image)
            val pixels = IntArray(image.width * image.height) { bitmap.getColor(it % image.width, it / image.width) }
            return Drawn(image.width, image.height, pixels, cell!!, pieces).also { image.close() }
        } finally {
            scene.close()
        }
    }

    private fun run(column: Int, text: String, cells: Int = text.length, bold: Boolean = false, italic: Boolean = false, underline: Boolean = false) =
        TerminalRun(column, cells, text, foreground, background = null, bold = bold, italic = italic, underline = underline)

    /** One run the width of the terminal, with a mark in its first cell and one in [markAt], and nothing between them to get in the way of finding them. */
    private fun marks(markAt: Int, bold: Boolean = false, italic: Boolean = false) =
        TerminalRow(listOf(run(0, "|" + " ".repeat(markAt - 1) + "|" + " ".repeat(columns - markAt - 1), bold = bold, italic = italic)))

    private val last = columns - 2

    /** How far the mark in column [column] is from where its cell says it should be, going by the mark in column 0. */
    private fun Drawn.drift(row: Int, column: Int): Double = inkCenter(row, column) - inkCenter(row, 0) - column * cell.width

    @Test
    fun `a glyph at the far end of a long run is in its own cell, at any size`() {
        for (density in listOf(1f, 1.25f, 1.5f, 2f)) for (size in 10..16) {
            val drawn = draw(if (size == 13) "plain-at-$density" else null, listOf(marks(last)), fontSize = size, density = density)
            val drift = drawn.drift(0, last)
            assertTrue(abs(drift) <= 0.5, "at ${size}sp and density $density, column $last is drawn ${"%.2f".format(drift)} pixels from its cell (cells are ${drawn.cell.width} wide)")
            assertEquals(listOf(1), drawn.pieces, "and the run is laid out once, as a whole")
        }
    }

    @Test
    fun `bold and italic runs keep to their cells too`() {
        val rows = listOf(marks(last, bold = true), marks(last, italic = true), marks(last, bold = true, italic = true))
        val drawn = draw("bold-and-italic", rows)
        for (row in rows.indices) {
            val drift = drawn.drift(row, last)
            assertTrue(abs(drift) <= 0.5, "row $row: column $last is drawn ${"%.2f".format(drift)} pixels from its cell")
        }
    }

    @Test
    fun `a selection and a search match at the right edge cover their text`() {
        // A word that ends at the edge, in a full row of text, as in the report of the bug.
        val word = "cornerword"
        val at = columns - word.length
        val text = "the quick brown fox jumps over the lazy dog. ".repeat(4).take(at - 1) + " " + word
        val row = TerminalRow(listOf(run(0, text)))
        // The same row without what comes before the word, so that the word's ink can be told
        // apart, and the word again where nothing can have moved it.
        val alone = TerminalRow(listOf(run(0, " ".repeat(at) + word)))
        val atTheStart = TerminalRow(listOf(run(0, word)))
        val rows = listOf(row, alone, atTheStart)
        val cells = SelectedCells(0, at, columns - 1)
        // Marked as well, so that its ink is weighed the same. The selected match is the one
        // not measured: the line round it is drawn over the edges of its text.
        val reference = SelectedCells(2, 0, word.length - 1)
        val selected = draw("selection-at-the-edge", rows, selection = listOf(cells, cells.copy(row = 1), reference))
        val matched = draw("match-at-the-edge", rows, matches = SearchHighlights(others = listOf(cells.copy(row = 1), reference), selected = listOf(cells)))
        for ((what, drawn) in listOf("selection" to selected, "match" to matched)) {
            val width = drawn.cell.width
            val mark = drawn.marked(1, background)
            assertTrue(abs(mark.first - at * width) <= 2 && abs(mark.last + 1 - columns * width) <= 2, "the $what spans pixels $mark, and its cells ${at * width} to ${columns * width}")
            val drift = drawn.inkCenter(1, at, columns - 1) - drawn.inkCenter(2, 0, word.length - 1) - at * width
            assertTrue(abs(drift) <= 0.5, "the word under the $what is drawn ${"%.2f".format(drift)} pixels from its cells")
        }
    }

    @Test
    fun `a font whose glyphs are not a cell wide has each one placed in its cell`() {
        // No terminal is meant to be drawn in such a font, but one style of a font may be
        // wider than the rest, and then its runs must not drift either.
        val drawn = draw("proportional", listOf(marks(last), TerminalRow(listOf(run(0, "i".repeat(40) + " ".repeat(20), underline = true)))), family = FontFamily.SansSerif)
        val drift = drawn.drift(0, last)
        assertTrue(abs(drift) <= 0.5, "column $last is drawn ${"%.2f".format(drift)} pixels from its cell")
        assertEquals(listOf(2, 60), drawn.pieces, "a piece for each glyph, and for each blank only where a line is drawn under it")
    }

    @Test
    fun `text after wide characters starts in its own cell`() {
        // Wide characters are a run each, two cells wide, in whatever font has them.
        val wide = listOf("日", "本", "語", "😀", "한", "☺\uFE0F")
        val rows = listOf(
            TerminalRow(listOf(run(0, "|")) + wide.mapIndexed { index, char -> run(60 + index * 2, char, cells = 2) } + run(72, " ".repeat(last - 72) + "|")),
            marks(last),
        )
        val drawn = draw("wide", rows)
        val drift = drawn.inkCenter(0, last) - drawn.inkCenter(1, last)
        assertTrue(abs(drift) <= 0.5, "column $last is drawn ${"%.2f".format(drift)} pixels from where it is without them")
        assertEquals(listOf(rows[0].runs.size, 1), drawn.pieces, "each wide character is laid out whole, whatever it is made of")
        // Each wide character's ink is within its two cells.
        wide.indices.forEach { index ->
            val first = 60 + index * 2
            val center = drawn.inkCenter(0, first, first + 1)
            assertTrue(center > first * drawn.cell.width && center < (first + 2) * drawn.cell.width, "\"${wide[index]}\" is centered at $center, in columns $first and ${first + 1}")
        }
    }
}
