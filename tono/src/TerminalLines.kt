package org.example.kotlintest

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.FontLoadResult
import org.example.kotlintest.ghostty.TerminalRun
import org.example.kotlintest.ghostty.TerminalUnderline
import org.jetbrains.skia.Font
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// The lines a program asks for under, over and through its text (SGR 4 and 4:1 to 4:5, 53
// and 9). They are drawn here and not by the text layout, a run of cells at a time: a line
// is as long as its cells whatever the glyphs in them measure, so it has no gaps where a
// run is laid out a character at a time, and it runs under blanks.

/**
 * Where a font says the lines under and through its text go, in pixels at the size it is
 * drawn: how far below the baseline each is (above, if negative) and how thick. Null for
 * what the font doesn't say.
 */
internal class FontLines(
    val underlineOffset: Float?,
    val underlineThickness: Float?,
    val strikeOffset: Float?,
    val strikeThickness: Float?,
    val xHeight: Float?,
)

/** What the font a style names says about its lines, or null if which font that is can't be told. */
internal fun fontLines(fonts: FontFamily.Resolver, style: TextStyle, fontSizePx: Float): FontLines? = runCatching {
    val resolved = fonts.resolve(style.fontFamily, style.fontWeight ?: FontWeight.Normal, style.fontStyle ?: FontStyle.Normal).value
    val typeface = (resolved as? FontLoadResult)?.typeface ?: return null
    val metrics = Font(typeface, fontSizePx).metrics
    // A font that leaves a value out reads as zero or as nothing, depending on the value.
    fun Float?.given() = this?.takeIf { it.isFinite() && it != 0f }
    FontLines(
        underlineOffset = metrics.underlinePosition.given()?.takeIf { it > 0f },
        underlineThickness = metrics.underlineThickness.given()?.takeIf { it > 0f },
        strikeOffset = metrics.strikeoutPosition.given()?.takeIf { it < 0f },
        strikeThickness = metrics.strikeoutThickness.given()?.takeIf { it > 0f },
        xHeight = metrics.xHeight.given()?.takeIf { it > 0f },
    )
}.getOrNull()

/**
 * Where the lines go in a cell, and how thick they are. Straight lines are on whole pixels,
 * so that they are sharp, and all of them are inside the cell, so that a row's lines never
 * reach into the next row.
 *
 * @param baseline how far down the cell its text's baseline is
 * @param fontSize the size the text is drawn at, in pixels
 * @param font what the font says about its lines; what it doesn't say is a fraction of [fontSize]
 */
internal class CellLines(private val cellWidth: Float, cellHeight: Float, baseline: Float, fontSize: Float, font: FontLines? = null) {
    private val height = cellHeight.roundToInt()

    /** How thick a line under or over the text is. */
    val thickness: Int = wholeThickness(font?.underlineThickness ?: (fontSize / 14f))

    /** The top of a single line under the text: clear of the letters standing on the baseline, and in the cell. */
    val underlineTop: Int = (baseline + (font?.underlineOffset ?: (fontSize / 10f))).roundToInt()
        .coerceAtLeast(baseline.roundToInt() + 1)
        .coerceAtMost(height - thickness)
        .coerceAtLeast(0)

    /** The top of the second of two lines under the text, and of the first, which moves up when both don't fit under it. */
    val doubleLowerTop: Int = (underlineTop + 2 * thickness).coerceAtMost(height - thickness).coerceAtLeast(0)
    val doubleUpperTop: Int = (doubleLowerTop - 2 * thickness).coerceAtLeast(0)

    val strikeThickness: Int = wholeThickness(font?.strikeThickness ?: font?.underlineThickness ?: (fontSize / 14f))

    /**
     * A thickness in whole pixels: the next whole number up, as in Ghostty, so that a line
     * the font makes a pixel and a third thick is two pixels on a dense screen and not as
     * thin as on any other. A hair over a whole number is taken for that number.
     */
    private fun wholeThickness(thickness: Float): Int = ceil(thickness - 0.15f).toInt().coerceIn(1, (height / 4).coerceAtLeast(1))

    /** The top of a line through the text: where the font says, or else halfway up its small letters. */
    val strikeTop: Int = (baseline + (font?.strikeOffset ?: -((font?.xHeight ?: (fontSize * 0.5f)) / 2f)) - strikeThickness / 2f).roundToInt()
        .coerceIn(0, (height - strikeThickness).coerceAtLeast(0))

    // A dotted line: dots twice as long as the line is thick or so, as far apart as they are
    // long, a whole number of them to a cell so that they keep step from cell to cell.
    val dotsPerCell: Int = (cellWidth / (4f * thickness)).roundToInt().coerceAtLeast(1)
    val dotLength: Int = (cellWidth / dotsPerCell / 2f).roundToInt().coerceAtLeast(1)

    /** A dashed line: one dash to a cell, in its middle. */
    val dashLength: Int = (cellWidth * DASH_OF_CELL).roundToInt().coerceAtLeast(1)

    // A curly line: a sine wave, one wave to a cell, so that it is the same in every cell
    // and joins up between runs. It rises and falls as steeply as a diagonal at most, which
    // is the height that looks like other terminals' at any cell size.
    private val curlRise: Float = (cellWidth / (2f * PI.toFloat())).coerceAtLeast(1f).coerceAtMost(((height - thickness - 0.5f) / 2f).coerceAtLeast(0.5f))

    // Half a pixel thicker than a straight line: a slanting line a pixel thick covers no
    // pixel wholly, and looks fainter than the straight lines beside it.
    private val curlThickness = thickness + 0.5f

    /** How far down the cell the middle of the wave is: with its crests where a single line would be, or as low as fits. */
    private val curlMiddle: Float = (underlineTop + curlThickness / 2f + curlRise).coerceAtMost(height - curlThickness / 2f - curlRise)

    /** The pixel rows of the cell the wave's ink is in. */
    val curlTop: Int = floor(curlMiddle - curlRise - curlThickness / 2f).toInt().coerceAtLeast(0)
    val curlHeight: Int = (ceil(curlMiddle + curlRise + curlThickness / 2f).toInt().coerceAtMost(height) - curlTop).coerceAtLeast(1)

    private var wave: ImageBitmap? = null
    private val tints = HashMap<Int, ColorFilter>()

    /**
     * The wave under a whole row at least [width] pixels long, from the row's first cell on,
     * as how much of each pixel it covers. It is drawn once and then copied from, since
     * stroking a curve under every run of every frame cost several times what the run's text
     * does; and being one picture of the row, the piece under each run is in step with the rest.
     */
    fun curl(width: Int): ImageBitmap {
        wave?.takeIf { it.width >= width }?.let { return it }
        val cells = ceil(maxOf(width, 2 * (wave?.width ?: 0), MIN_WAVE_PIXELS) / cellWidth).toInt()
        // A quarter of a sine wave as one curve, which is closer to it than a pixel can show.
        val quarter = cellWidth / 4f
        val nearX = quarter * SINE_NEAR / (PI.toFloat() / 2f)
        val farX = quarter * SINE_FAR / (PI.toFloat() / 2f)
        val middle = curlMiddle - curlTop
        val path = Path()
        path.moveTo(-cellWidth, middle)
        // From a cell before the row to one after it, so that the wave's ends are out of the picture.
        for (cell in -1..cells) {
            val x = cell * cellWidth
            // Up first: y grows downwards.
            path.cubicTo(x + nearX, middle - curlRise * SINE_NEAR, x + farX, middle - curlRise, x + quarter, middle - curlRise)
            path.cubicTo(x + 2 * quarter - farX, middle - curlRise, x + 2 * quarter - nearX, middle - curlRise * SINE_NEAR, x + 2 * quarter, middle)
            path.cubicTo(x + 2 * quarter + nearX, middle + curlRise * SINE_NEAR, x + 2 * quarter + farX, middle + curlRise, x + 3 * quarter, middle + curlRise)
            path.cubicTo(x + cellWidth - farX, middle + curlRise, x + cellWidth - nearX, middle + curlRise * SINE_NEAR, x + cellWidth, middle)
        }
        val drawn = ImageBitmap(ceil(cells * cellWidth).toInt(), curlHeight, ImageBitmapConfig.Alpha8)
        val paint = Paint().apply {
            color = Color.White
            style = PaintingStyle.Stroke
            strokeWidth = curlThickness
            isAntiAlias = true
        }
        Canvas(drawn).drawPath(path, paint)
        wave = drawn
        return drawn
    }

    /** What colors the wave: a program uses a color or two for its curly lines, so they are kept. */
    fun tint(color: Int): ColorFilter {
        if (tints.size > MAX_TINTS) tints.clear()
        return tints.getOrPut(color) { ColorFilter.tint(Color(0xFF000000 or color.toLong())) }
    }

    private companion object {
        const val DASH_OF_CELL = 0.6f
        const val MIN_WAVE_PIXELS = 1024
        const val MAX_TINTS = 32

        // The control points of the curve that follows sin(x) from 0 to a quarter turn: (NEAR, NEAR) and (FAR, 1).
        const val SINE_NEAR = 0.5122866f
        const val SINE_FAR = 1.0023137f
    }
}

/** The pixels a run's cells span, left and right. Rounded, so that the lines of runs side by side meet and none reaches into a cell that isn't its own. */
private fun TerminalRun.left(cell: CellSize): Float = (column * cell.width).roundToInt().toFloat()
private fun TerminalRun.right(cell: CellSize): Float = ((column + cells) * cell.width).roundToInt().toFloat()

private fun opaque(color: Int) = Color(0xFF000000 or color.toLong())

/** Draws the line under a run, of the kind and in the color its program asked for. It goes under the text, as in Ghostty. */
internal fun DrawScope.drawUnderline(run: TerminalRun, rowTop: Float, cell: CellSize) {
    if (run.underline == TerminalUnderline.None) return
    val lines = cell.lines
    val color = opaque(run.underlineColor ?: run.foreground)
    val left = run.left(cell)
    val right = run.right(cell)
    val thickness = lines.thickness.toFloat()
    when (run.underline) {
        TerminalUnderline.None -> {}
        TerminalUnderline.Single -> drawRect(color, Offset(left, rowTop + lines.underlineTop), Size(right - left, thickness))
        TerminalUnderline.Double -> {
            drawRect(color, Offset(left, rowTop + lines.doubleUpperTop), Size(right - left, thickness))
            drawRect(color, Offset(left, rowTop + lines.doubleLowerTop), Size(right - left, thickness))
        }
        TerminalUnderline.Curly -> {
            val size = IntSize((right - left).toInt(), lines.curlHeight)
            if (size.width > 0) drawImage(
                image = lines.curl(right.toInt()),
                srcOffset = IntOffset(left.toInt(), 0),
                srcSize = size,
                dstOffset = IntOffset(left.toInt(), rowTop.roundToInt() + lines.curlTop),
                dstSize = size,
                colorFilter = lines.tint(run.underlineColor ?: run.foreground),
                filterQuality = FilterQuality.None,
            )
        }
        TerminalUnderline.Dotted -> {
            val size = Size(lines.dotLength.toFloat(), thickness)
            val step = cell.width / lines.dotsPerCell
            val first = run.column * lines.dotsPerCell
            // Each dot is put on a whole pixel, where its place in the row says, not counted on from the last one.
            for (dot in first until first + run.cells * lines.dotsPerCell) {
                drawRect(color, Offset((dot * step).roundToInt().toFloat(), rowTop + lines.underlineTop), size)
            }
        }
        TerminalUnderline.Dashed -> {
            val size = Size(lines.dashLength.toFloat(), thickness)
            val inset = (cell.width - lines.dashLength) / 2f
            for (column in run.column until run.column + run.cells) {
                drawRect(color, Offset((column * cell.width + inset).roundToInt().toFloat(), rowTop + lines.underlineTop), size)
            }
        }
    }
}

/** Draws the lines over and through a run, in its text's color. They go over the text. */
internal fun DrawScope.drawOverlineAndStrikethrough(run: TerminalRun, rowTop: Float, cell: CellSize) {
    if (!run.overline && !run.strikethrough) return
    val lines = cell.lines
    val color = opaque(run.foreground)
    val left = run.left(cell)
    val width = run.right(cell) - left
    if (run.overline) drawRect(color, Offset(left, rowTop), Size(width, lines.thickness.toFloat()))
    if (run.strikethrough) drawRect(color, Offset(left, rowTop + lines.strikeTop), Size(width, lines.strikeThickness.toFloat()))
}
