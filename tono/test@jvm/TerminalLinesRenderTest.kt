package org.example.kotlintest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import org.example.kotlintest.ghostty.TerminalFrame
import org.example.kotlintest.ghostty.TerminalRow
import org.example.kotlintest.ghostty.TerminalRun
import org.example.kotlintest.ghostty.TerminalUnderline
import org.jetbrains.skia.Bitmap
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The lines under, over and through a terminal's text as the desktop draws them, offscreen:
 * each kind of underline, in a color of its own, an overline and a strikethrough, at
 * several sizes and densities. The lines are drawn in pure red, green and blue on black and
 * the text they belong to is blank where they are measured, so that each can be told by its
 * color. The pictures are left in `<project>/build/terminal-line-renders/` to be looked at.
 */
class TerminalLinesRenderTest {
    private val outputDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "project.yaml").isFile }
        .resolve("build/terminal-line-renders")
        .apply { mkdirs() }

    private val columns = 40

    /** The desktop's monospace font, from its file, as in [TerminalTextRenderTest]. */
    private val font: FontFamily = runCatching {
        val process = ProcessBuilder("fc-match", "-f", "%{file}", "monospace").start()
        val file = File(process.inputStream.bufferedReader().use { it.readText() }.trim())
        process.waitFor()
        FontFamily(Font("monospace", file.readBytes()))
    }.getOrDefault(FontFamily.Monospace)

    private val red = 0xFF0000
    private val green = 0x00FF00
    private val blue = 0x0000FF
    private val white = 0xFFFFFF

    private class Drawn(val width: Int, val height: Int, private val pixels: IntArray, val cell: CellSize) {
        /** How much of a pixel is covered in one of the pure colors, from 0 to 1. */
        fun ink(x: Int, y: Int, color: Int): Float {
            if (x !in 0 until width || y !in 0 until height) return 0f
            val pixel = pixels[y * width + x]
            val shift = when (color) { 0xFF0000 -> 16; 0x00FF00 -> 8; else -> 0 }
            val others = listOf(16, 8, 0).filter { it != shift }.maxOf { (pixel shr it) and 0xFF }
            return (((pixel shr shift) and 0xFF) - others).coerceAtLeast(0) / 255f
        }

        fun rowTop(row: Int): Int = (row * cell.height).roundToInt()
        fun rowYs(row: Int): IntRange = rowTop(row) until rowTop(row + 1)
        fun left(column: Int): Int = (column * cell.width).roundToInt()

        /** The pixel rows of a terminal row that have ink of a color anywhere in these pixel columns, counted from the row's top. */
        fun inkedYs(row: Int, xs: IntRange, color: Int): List<Int> =
            rowYs(row).filter { y -> xs.any { x -> ink(x, y, color) > 0.5f } }.map { it - rowTop(row) }

        /** The stretches of pixel columns that are inked in a color at a pixel row. */
        fun stretches(y: Int, xs: IntRange, color: Int): List<IntRange> = buildList {
            var start = -1
            for (x in xs.first..xs.last + 1) {
                val inked = x <= xs.last && ink(x, y, color) > 0.5f
                if (inked && start < 0) start = x
                if (!inked && start >= 0) { add(start until x); start = -1 }
            }
        }

        /** Where down a pixel column the ink of a color is centered, from the top of the picture, or null if there is none. */
        fun inkCenter(x: Int, ys: IntRange, color: Int): Float? {
            val total = ys.sumOf { ink(x, it, color).toDouble() }
            if (total < 0.3) return null
            return (ys.sumOf { ink(x, it, color) * (it + 0.5) } / total).toFloat()
        }

        fun anyInk(ys: IntRange, color: Int): Boolean = ys.any { y -> (0 until width).any { x -> ink(x, y, color) > 0.1f } }
    }

    private fun draw(name: String?, rows: List<TerminalRow>, fontSize: Int = 13, density: Float = 1f, family: FontFamily = font, fontsLines: Boolean = true): Drawn {
        val frame = TerminalFrame(columns, rows, background = 0x000000, foreground = white, cursor = null)
        var cell: CellSize? = null
        val scene = ImageComposeScene(width = (columns * fontSize * density * 0.8f).toInt(), height = (rows.size * fontSize * density * 2.5f).toInt(), density = Density(density)) {
            val style = remember { TextStyle(fontFamily = family, fontSize = fontSize.sp) }
            val measurer = rememberTextMeasurer(cacheSize = 0)
            val fonts = LocalFontFamilyResolver.current
            val measured = remember { measureCell(measurer, style, Density(density), fonts.takeIf { fontsLines }) }
            val layouts = remember { RowLayouts(measured) }
            val pictures = remember { Pictures() }
            Canvas(Modifier.fillMaxSize()) {
                drawTerminal(frame, measured, layouts, measurer, style, pictures)
                cell = measured
            }
        }
        try {
            val image = scene.render(0)
            if (name != null) File(outputDir, "$name.png").writeBytes(image.encodeToData()!!.bytes)
            val bitmap = Bitmap.makeFromImage(image)
            val pixels = IntArray(image.width * image.height) { bitmap.getColor(it % image.width, it / image.width) }
            return Drawn(image.width, image.height, pixels, cell!!).also { image.close() }
        } finally {
            scene.close()
        }
    }

    private fun run(
        column: Int, text: String, underline: TerminalUnderline = TerminalUnderline.None, underlineColor: Int? = null,
        foreground: Int = white, strikethrough: Boolean = false, overline: Boolean = false, cells: Int = text.length,
    ) = TerminalRun(column, cells, text, foreground, background = null, underline = underline, underlineColor = underlineColor, strikethrough = strikethrough, overline = overline)

    private val blank = TerminalRow(emptyList())
    private val sizes = listOf(1f, 1.25f, 1.5f, 2f).flatMap { density -> listOf(10, 12, 13, 14, 16, 20).map { it to density } }

    /** Blank cells 3 to 12 underlined in red and 13 to 20 in green, two runs side by side, with nothing above or below. */
    private fun lined(underline: TerminalUnderline) = listOf(
        blank,
        TerminalRow(listOf(run(3, " ".repeat(10), underline, red), run(13, " ".repeat(8), underline, green))),
        blank,
    )

    private fun eachSize(underline: TerminalUnderline, check: (drawn: Drawn, where: String) -> Unit) {
        for ((size, density) in sizes) check(draw(null, lined(underline), size, density), "at ${size}sp and density $density")
        check(draw(null, lined(underline), fontsLines = false), "without the font's own measures")
    }

    private fun Drawn.assertInsideRow(where: String) {
        for (color in listOf(red, green)) assertTrue(!anyInk(rowYs(0), color) && !anyInk(rowYs(2), color), "$where: the line stays in its own row")
    }

    @Test
    fun `a single underline is one unbroken line under its cells, in its own color`() = eachSize(TerminalUnderline.Single) { drawn, where ->
        val lines = drawn.cell.lines
        val xs = drawn.left(3) until drawn.left(13)
        val ys = drawn.inkedYs(1, 0 until drawn.width, red)
        assertEquals((lines.underlineTop until lines.underlineTop + lines.thickness).toList(), ys, "$where: the pixel rows the line is on")
        for (y in ys) {
            assertEquals(listOf(xs), drawn.stretches(drawn.rowTop(1) + y, 0 until drawn.width, red), "$where: red from the first cell's edge to the last one's")
            assertEquals(listOf(drawn.left(13) until drawn.left(21)), drawn.stretches(drawn.rowTop(1) + y, 0 until drawn.width, green), "$where: and green from there on")
        }
        assertTrue(lines.underlineTop + lines.thickness <= drawn.cell.height.toInt(), "$where: inside the cell")
        drawn.assertInsideRow(where)
    }

    @Test
    fun `a double underline is two lines with a gap between them`() = eachSize(TerminalUnderline.Double) { drawn, where ->
        val thickness = drawn.cell.lines.thickness
        val ys = drawn.inkedYs(1, drawn.left(3) until drawn.left(13), red)
        assertEquals(2 * thickness, ys.size, "$where: two lines, pixel rows $ys")
        assertEquals(thickness, ys[thickness] - ys[thickness - 1] - 1, "$where: as far apart as they are thick, pixel rows $ys")
        for (y in ys) assertEquals(listOf(drawn.left(3) until drawn.left(13)), drawn.stretches(drawn.rowTop(1) + y, 0 until drawn.width, red), "$where: each unbroken")
        drawn.assertInsideRow(where)
    }

    @Test
    fun `a curly underline is a smooth wave, one to a cell, that carries on from run to run`() = eachSize(TerminalUnderline.Curly) { drawn, where ->
        val width = drawn.cell.width
        val ys = drawn.rowYs(1)
        var top = Float.MAX_VALUE
        var bottom = 0f
        val centers = (drawn.left(3) until drawn.left(21)).map { x ->
            val color = if (x < drawn.left(13)) red else green
            val center = assertNotNull(drawn.inkCenter(x, ys, color), "$where: the wave has no gap at pixel column $x")
            top = minOf(top, center)
            bottom = maxOf(bottom, center)
            x to center
        }
        val middle = (top + bottom) / 2
        val rise = (bottom - top) / 2
        assertTrue(rise >= 0.8f, "$where: the wave rises and falls by $rise pixels")
        // A sine wave as long as a cell, starting at each cell's edge and going up first. Ink is measured a pixel at a time, hence the allowance.
        for ((x, center) in centers) {
            val expected = middle - rise * sin(2 * PI * (x + 0.5 - 3 * width) / width).toFloat()
            assertTrue(abs(center - expected) <= 0.2f + rise * 0.25f, "$where: at pixel column $x the wave is at $center, and a sine wave would be at $expected")
        }
        drawn.assertInsideRow(where)
    }

    @Test
    fun `a dotted underline has the same dots in every cell`() = eachSize(TerminalUnderline.Dotted) { drawn, where ->
        val lines = drawn.cell.lines
        val y = drawn.rowTop(1) + lines.underlineTop
        val dots = drawn.stretches(y, 0 until drawn.width, red)
        assertEquals(10 * lines.dotsPerCell, dots.size, "$where: dots under ten cells")
        assertTrue(dots.all { it.count() == lines.dotLength }, "$where: each ${lines.dotLength} pixels long: $dots")
        val gaps = dots.zipWithNext { a, b -> b.first - a.last - 1 }
        assertTrue(gaps.all { it >= 1 } && gaps.max() - gaps.min() <= 1, "$where: evenly spaced: gaps of $gaps")
        // The green run's first dot is where the red run's next one would have been.
        val next = drawn.stretches(y, 0 until drawn.width, green).first()
        assertTrue(abs((next.first - dots.last().last - 1) - gaps.average()) <= 1.0, "$where: the next run's dots keep step")
        assertEquals((lines.underlineTop until lines.underlineTop + lines.thickness).toList(), drawn.inkedYs(1, 0 until drawn.width, red), "$where: the pixel rows the dots are on")
        drawn.assertInsideRow(where)
    }

    @Test
    fun `a dashed underline has one dash to a cell`() = eachSize(TerminalUnderline.Dashed) { drawn, where ->
        val lines = drawn.cell.lines
        val y = drawn.rowTop(1) + lines.underlineTop
        val dashes = drawn.stretches(y, 0 until drawn.width, red)
        assertEquals(10, dashes.size, "$where: a dash under each of ten cells")
        assertTrue(dashes.all { it.count() == lines.dashLength }, "$where: each ${lines.dashLength} pixels long: $dashes")
        assertTrue(lines.dashLength > 2 * lines.dotLength || lines.dotsPerCell > 1, "$where: and not to be taken for dots")
        dashes.forEachIndexed { index, dash ->
            val center = (dash.first + dash.last + 1) / 2f
            assertTrue(abs(center - (3 + index + 0.5f) * drawn.cell.width) <= 1f, "$where: dash $index is in the middle of its cell")
        }
        drawn.assertInsideRow(where)
    }

    @Test
    fun `an overline is along the top of its cells and a strikethrough through the middle of the text, in the text's color`() {
        for ((size, density) in sizes) {
            val where = "at ${size}sp and density $density"
            val rows = listOf(
                blank,
                // Both are the text's color, whatever color an underline has.
                TerminalRow(listOf(run(3, " ".repeat(10), overline = true, foreground = red), run(13, " ".repeat(8), TerminalUnderline.Single, blue, strikethrough = true, foreground = green))),
                blank,
            )
            val drawn = draw(null, rows, size, density)
            val lines = drawn.cell.lines
            assertEquals((0 until lines.thickness).toList(), drawn.inkedYs(1, 0 until drawn.width, red), "$where: the overline's pixel rows")
            assertEquals(listOf(drawn.left(3) until drawn.left(13)), drawn.stretches(drawn.rowTop(1), 0 until drawn.width, red), "$where: over its cells and no others")
            val through = drawn.inkedYs(1, 0 until drawn.width, green)
            assertEquals((lines.strikeTop until lines.strikeTop + lines.strikeThickness).toList(), through, "$where: the strikethrough's pixel rows")
            assertEquals(listOf(drawn.left(13) until drawn.left(21)), drawn.stretches(drawn.rowTop(1) + through.first(), 0 until drawn.width, green), "$where: through its cells, blank or not")
            // Between a third and three quarters of the way down to the baseline's whereabouts: through the small letters.
            val middle = lines.strikeTop + lines.strikeThickness / 2f
            assertTrue(middle > drawn.cell.height * 0.3f && middle < lines.underlineTop - size * density * 0.15f, "$where: the strikethrough is $middle pixels down a cell ${drawn.cell.height} high, the underline ${lines.underlineTop}")
            assertEquals((lines.underlineTop until lines.underlineTop + lines.thickness).toList(), drawn.inkedYs(1, 0 until drawn.width, blue), "$where: and the underline with it is where it is alone")
            drawn.assertInsideRow(where)
        }
    }

    @Test
    fun `an underline without a color of its own takes the text's, and lies clear of the letters`() {
        // Letters without descenders, in blue, with the line under them in the same blue and then in red.
        val rows = listOf(TerminalRow(listOf(run(1, "EHLM", TerminalUnderline.Single, foreground = blue), run(6, "EHLM", TerminalUnderline.Single, red, foreground = blue))))
        for ((size, density) in sizes) {
            val drawn = draw(null, rows, size, density)
            val lines = drawn.cell.lines
            val own = drawn.inkedYs(0, drawn.left(6) until drawn.left(10), red)
            assertEquals((lines.underlineTop until lines.underlineTop + lines.thickness).toList(), own, "at ${size}sp and density $density: the red line's pixel rows")
            val y = lines.underlineTop
            assertEquals(listOf(drawn.left(1) until drawn.left(5)), drawn.stretches(y, 0 until drawn.width, blue), "at ${size}sp and density $density: a blue line under the first word only")
            // The pixel row above the line has no solid ink right across a letter's cell: the letters stop short of it.
            assertTrue(drawn.stretches(y - 1, drawn.left(6) until drawn.left(10), blue).all { it.count() < drawn.cell.width * 0.9f }, "at ${size}sp and density $density: the line is apart from the letters")
        }
    }

    @Test
    fun `lines are unbroken in a font whose glyphs are placed one by one`() {
        // A proportional font, so that each character is laid out by itself: a line drawn with the text would stop and start.
        val rows = listOf(blank, TerminalRow(listOf(run(3, "il il il i", TerminalUnderline.Single, red, strikethrough = true, foreground = green))), blank)
        val drawn = draw("proportional", rows, family = FontFamily.SansSerif)
        val lines = drawn.cell.lines
        val xs = drawn.left(3) until drawn.left(13)
        assertEquals(listOf(xs), drawn.stretches(drawn.rowTop(1) + lines.underlineTop, 0 until drawn.width, red))
        assertEquals(listOf(xs), drawn.stretches(drawn.rowTop(1) + lines.strikeTop, 0 until drawn.width, green))
    }

    @Test
    fun `a wide character's cells are both underlined`() {
        // Wide blanks where the lines are measured, since a wide glyph reaches down over them; one is drawn too, to be looked at.
        val rows = listOf(TerminalRow(listOf(run(2, "\u3000", TerminalUnderline.Curly, red, cells = 2), run(4, "\u3000", TerminalUnderline.Single, red, cells = 2), run(6, "本", TerminalUnderline.Double, green, cells = 2))))
        val drawn = draw("wide", rows)
        val ys = drawn.rowYs(0)
        assertTrue((drawn.left(2) until drawn.left(4)).all { drawn.inkCenter(it, ys, red) != null }, "a wave under both cells")
        assertEquals(listOf(drawn.left(4) until drawn.left(6)), drawn.stretches(drawn.cell.lines.underlineTop, drawn.left(4) until drawn.width, red))
        // The glyph cuts through its line, so only the line's ends are looked for.
        val under = drawn.stretches(drawn.cell.lines.doubleLowerTop, 0 until drawn.width, green)
        assertEquals(drawn.left(6) to drawn.left(8) - 1, under.first().first to under.last().last)
    }

    /** Not a check: every kind of line with text, at the sizes a screen is likely to have, to be looked at. */
    @Test
    fun `pictures of every kind of line`() {
        val words = "Curly jumps, quietly"
        fun sample(row: Int, label: String, underline: TerminalUnderline = TerminalUnderline.None, color: Int? = null, strikethrough: Boolean = false, overline: Boolean = false) =
            TerminalRow(listOf(run(0, label.padEnd(10)), run(11, words, underline, color, strikethrough = strikethrough, overline = overline), run(32, "x", underline, color, strikethrough = strikethrough, overline = overline)))
        val rows = listOf(
            sample(0, "single", TerminalUnderline.Single),
            sample(1, "double", TerminalUnderline.Double),
            sample(2, "curly", TerminalUnderline.Curly),
            sample(3, "dotted", TerminalUnderline.Dotted),
            sample(4, "dashed", TerminalUnderline.Dashed),
            sample(5, "curly red", TerminalUnderline.Curly, 0xFF5555),
            sample(6, "double grn", TerminalUnderline.Double, 0x55FF55),
            sample(7, "strike", strikethrough = true),
            sample(8, "overline", overline = true),
            sample(9, "all three", TerminalUnderline.Curly, 0x5599FF, strikethrough = true, overline = true),
        )
        for (density in listOf(1f, 1.25f, 1.5f, 2f)) for (size in listOf(10, 13, 16)) draw("lines-${size}sp-at-$density", rows, size, density)
    }
}
