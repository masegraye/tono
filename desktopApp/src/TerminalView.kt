package org.example.kotlintest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.example.kotlintest.ghostty.TerminalCursor
import org.example.kotlintest.ghostty.TerminalFrame
import org.example.kotlintest.ghostty.TerminalRow
import org.example.kotlintest.ghostty.TerminalRun
import org.example.kotlintest.rex.GridSize
import org.example.kotlintest.rex.RexKey
import org.example.kotlintest.rex.TerminalSession
import java.awt.event.KeyEvent
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Draws a live terminal and takes its keyboard input.
 *
 * The terminal is drawn at the size the server says it is. [onDesiredGrid] reports the size
 * that would fill this view instead, which the caller applies if it controls the terminal.
 *
 * @param focused whether this is the terminal the user is typing into
 * @param onKey a key was pressed, released, or is repeating while this view had focus
 * @param focusRequests change it to hand the keyboard back to this view, if it is the focused one
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TerminalView(
    terminal: TerminalSession,
    focused: Boolean,
    onFocusRequested: () -> Unit,
    onKey: (RexKey) -> Unit,
    onDesiredGrid: (GridSize) -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
    focusRequests: Int = 0,
) {
    val frame by terminal.frame.collectAsState()
    val fontFamily = MaterialTheme.typography.bodyMedium.fontFamily ?: FontFamily.Monospace
    val style = remember(fontFamily, fontSize) { TextStyle(fontFamily = fontFamily, fontSize = fontSize) }
    val measurer = rememberTextMeasurer(cacheSize = 0) // rows are cached below, by identity
    val cell = remember(style, measurer) { measureCell(measurer, style) }
    val layouts = remember(style) { RowLayouts() }

    var size by remember { mutableStateOf(IntSize.Zero) }
    // Wait for a resize to settle before asking for a new grid.
    LaunchedEffect(size, cell) {
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        delay(150)
        val columns = (size.width / cell.width).toInt()
        val rows = (size.height / cell.height).toInt()
        if (columns >= 2 && rows >= 1) onDesiredGrid(GridSize(columns, rows, cell.width.roundToInt(), cell.height.roundToInt()))
    }

    val focusRequester = remember { FocusRequester() }
    var hasKeyboard by remember { mutableStateOf(false) }
    LaunchedEffect(focused, focusRequests) { if (focused) focusRequester.requestFocus() }
    val keysDown = remember { HashSet<Int>() }

    Canvas(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { size = it }
            .focusRequester(focusRequester)
            .onFocusChanged { hasKeyboard = it.isFocused }
            .focusable()
            // Preview, so that Tab and the arrow keys reach the terminal instead of moving focus.
            .onPreviewKeyEvent { event ->
                val awt = event.awtEventOrNull ?: return@onPreviewKeyEvent false
                val repeat = when (awt.id) {
                    KeyEvent.KEY_PRESSED -> !keysDown.add(awt.keyCode)
                    KeyEvent.KEY_RELEASED -> { keysDown.remove(awt.keyCode); false }
                    else -> return@onPreviewKeyEvent false
                }
                val key = rexKey(awt, repeat) ?: return@onPreviewKeyEvent false
                onKey(key)
                true
            }
            .pointerInput(Unit) { detectTapGestures { onFocusRequested(); focusRequester.requestFocus() } }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (delta != 0f) terminal.scroll((delta * SCROLL_ROWS_PER_NOTCH).roundToInt().coerceIn(-50, 50).let { if (it == 0) if (delta > 0) 1 else -1 else it })
            },
    ) {
        val current = frame
        if (current == null) return@Canvas
        drawRect(Color(0xFF000000 or current.background.toLong()))
        layouts.retain(current.rows)
        current.rows.forEachIndexed { rowIndex, row ->
            drawRow(row, rowIndex, cell, layouts.of(row, measurer, style))
        }
        current.cursor?.let { drawCursor(it, current, cell, active = focused && hasKeyboard) }
    }
}

/** The size of one character cell, in pixels. */
internal class CellSize(val width: Float, val height: Float)

private fun measureCell(measurer: TextMeasurer, style: TextStyle): CellSize {
    // Averaged over a run of characters, because a single glyph's width is rounded to whole pixels.
    val sample = measurer.measure("MMMMMMMMMMMMMMMMMMMM", style, softWrap = false, maxLines = 1)
    return CellSize(sample.size.width / 20f, ceil(sample.size.height.toFloat()))
}

/** The laid-out text of each row on screen. A row object is reused while its content is unchanged, so its layout is too. */
private class RowLayouts {
    private var cache = IdentityHashMap<TerminalRow, List<TextLayoutResult?>>()

    /** Forgets rows that are no longer on screen. */
    fun retain(rows: List<TerminalRow>) {
        if (cache.size <= rows.size) return
        val kept = IdentityHashMap<TerminalRow, List<TextLayoutResult?>>()
        for (row in rows) cache[row]?.let { kept[row] = it }
        cache = kept
    }

    fun of(row: TerminalRow, measurer: TextMeasurer, style: TextStyle): List<TextLayoutResult?> =
        cache.getOrPut(row) { row.runs.map { run -> if (run.text.isBlank()) null else measurer.measure(AnnotatedString(run.text), style.merge(run), softWrap = false, maxLines = 1) } }
}

private fun TextStyle.merge(run: TerminalRun): TextStyle = copy(
    color = Color(0xFF000000 or run.foreground.toLong()),
    fontWeight = if (run.bold) FontWeight.Bold else FontWeight.Normal,
    fontStyle = if (run.italic) FontStyle.Italic else FontStyle.Normal,
    textDecoration = when {
        run.underline && run.strikethrough -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
        run.underline -> TextDecoration.Underline
        run.strikethrough -> TextDecoration.LineThrough
        else -> TextDecoration.None
    },
)

private fun DrawScope.drawRow(row: TerminalRow, rowIndex: Int, cell: CellSize, layouts: List<TextLayoutResult?>) {
    val top = rowIndex * cell.height
    row.runs.forEachIndexed { index, run ->
        val left = run.column * cell.width
        run.background?.let { background ->
            // Whole pixels, and a pixel of overlap, so adjacent backgrounds leave no seams.
            drawRect(
                color = Color(0xFF000000 or background.toLong()),
                topLeft = Offset(left.toInt().toFloat(), top),
                size = Size(ceil(run.cells * cell.width) + 1f, cell.height),
            )
        }
        layouts[index]?.let { drawText(it, topLeft = Offset(left, top)) }
    }
}

private fun DrawScope.drawCursor(cursor: TerminalCursor, frame: TerminalFrame, cell: CellSize, active: Boolean) {
    val color = Color(0xFF000000 or (cursor.color ?: frame.foreground).toLong())
    val topLeft = Offset(cursor.column * cell.width, cursor.row * cell.height)
    val whole = Size(cell.width, cell.height)
    when {
        // An outline while the terminal isn't being typed into, whatever its shape.
        !active || cursor.shape == TerminalCursor.Shape.HollowBlock -> drawRect(color, topLeft, whole, style = Stroke(width = 1.5f))
        cursor.shape == TerminalCursor.Shape.Bar -> drawRect(color, topLeft, Size(2f, cell.height))
        cursor.shape == TerminalCursor.Shape.Underline -> drawRect(color, topLeft + Offset(0f, cell.height - 2f), Size(cell.width, 2f))
        else -> drawRect(color.copy(alpha = 0.55f), topLeft, whole)
    }
}

private const val SCROLL_ROWS_PER_NOTCH = 3f
