@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.kotlintest.ghostty

import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise

/*
 * libghostty-vt as a WebAssembly module. The page loads ghostty-glue.js, which holds the
 * module and does the cell-by-cell work beside it; each function here is one call into that.
 * See the top of the glue for why it is split this way.
 *
 * Bytes cross as strings of one character per byte, which go over in one piece where an
 * array would go an element at a time.
 */

private fun glueInit(url: String): Promise<JsAny?> = js("globalThis.GhosttyGlue.init(url)")
private fun glueTerminalNew(columns: Int, rows: Int): Int = js("globalThis.GhosttyGlue.terminalNew(columns, rows)")
private fun glueTerminalRestore(binary: String): String = js("globalThis.GhosttyGlue.terminalRestore(binary)")
private fun glueTerminalFree(terminal: Int): Unit = js("globalThis.GhosttyGlue.terminalFree(terminal)")
private fun glueTerminalWrite(terminal: Int, binary: String): Unit = js("globalThis.GhosttyGlue.terminalWrite(terminal, binary)")
private fun glueTerminalResize(terminal: Int, columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int): Unit =
    js("globalThis.GhosttyGlue.terminalResize(terminal, columns, rows, cellWidthPx, cellHeightPx)")
private fun glueTerminalColumns(terminal: Int): Int = js("globalThis.GhosttyGlue.terminalColumns(terminal)")
private fun glueTerminalRows(terminal: Int): Int = js("globalThis.GhosttyGlue.terminalRows(terminal)")
private fun glueTerminalWidthPx(terminal: Int): Int = js("globalThis.GhosttyGlue.terminalWidthPx(terminal)")
private fun glueTerminalHeightPx(terminal: Int): Int = js("globalThis.GhosttyGlue.terminalHeightPx(terminal)")
private fun glueTerminalSetColors(terminal: Int, foreground: Int, background: Int, cursor: Int, ansi: String): Unit =
    js("globalThis.GhosttyGlue.terminalSetColors(terminal, foreground, background, cursor, ansi)")
private fun glueTerminalScrollBy(terminal: Int, rows: Int): Unit = js("globalThis.GhosttyGlue.terminalScrollBy(terminal, rows)")
private fun glueTerminalViewportTop(terminal: Int): Double = js("globalThis.GhosttyGlue.terminalViewportTop(terminal)")
private fun glueTerminalScrollByPrompts(terminal: Int, count: Int): Unit = js("globalThis.GhosttyGlue.terminalScrollByPrompts(terminal, count)")
private fun glueTerminalScrollToBottom(terminal: Int): Unit = js("globalThis.GhosttyGlue.terminalScrollToBottom(terminal)")
private fun glueTerminalBracketedPaste(terminal: Int): Boolean = js("globalThis.GhosttyGlue.terminalBracketedPaste(terminal)")
private fun glueTerminalHyperlinkAt(terminal: Int, column: Int, row: Int): String = js("globalThis.GhosttyGlue.terminalHyperlinkAt(terminal, column, row)")
private fun glueTerminalRowWraps(terminal: Int, row: Int): Boolean = js("globalThis.GhosttyGlue.terminalRowWraps(terminal, row)")
private fun gluePasteEncode(binary: String, bracketed: Boolean): String = js("globalThis.GhosttyGlue.pasteEncode(binary, bracketed)")
private fun glueRendererNew(): Int = js("globalThis.GhosttyGlue.rendererNew()")
private fun glueRendererFree(state: Int): Unit = js("globalThis.GhosttyGlue.rendererFree(state)")
private fun glueRendererInvalidate(state: Int): Unit = js("globalThis.GhosttyGlue.rendererInvalidate(state)")
private fun glueRendererOverscan(state: Int, rows: Int): Unit = js("globalThis.GhosttyGlue.rendererOverscan(state, rows)")
private fun glueRender(state: Int, terminal: Int): String = js("globalThis.GhosttyGlue.render(state, terminal)")
private fun glueImageRead(terminal: Int, id: Int): String = js("globalThis.GhosttyGlue.imageRead(terminal, id)")
private fun glueMouseNew(): String = js("globalThis.GhosttyGlue.mouseNew()")
private fun glueMouseFree(encoder: Int, event: Int): Unit = js("globalThis.GhosttyGlue.mouseFree(encoder, event)")
private fun glueMouseEncode(
    encoder: Int, event: Int, terminal: Int, action: Int, button: Int, mods: Int,
    x: Float, y: Float, cellWidth: Int, cellHeight: Int, anyButtonDown: Boolean,
): String = js("globalThis.GhosttyGlue.mouseEncode(encoder, event, terminal, action, button, mods, x, y, cellWidth, cellHeight, anyButtonDown)")

private fun glueSelectionNew(): Int = js("globalThis.GhosttyGlue.selectionNew()")
private fun glueSelectionFree(gesture: Int): Unit = js("globalThis.GhosttyGlue.selectionFree(gesture)")
private fun glueSelectionPress(gesture: Int, terminal: Int, x: Float, y: Float, cellWidth: Int, cellHeight: Int, timeMillis: Double): Unit =
    js("globalThis.GhosttyGlue.selectionPress(gesture, terminal, x, y, cellWidth, cellHeight, timeMillis)")
private fun glueSelectionDrag(gesture: Int, terminal: Int, x: Float, y: Float, cellWidth: Int, cellHeight: Int): Boolean =
    js("globalThis.GhosttyGlue.selectionDrag(gesture, terminal, x, y, cellWidth, cellHeight)")
private fun glueSelectionRelease(gesture: Int, terminal: Int, x: Float, y: Float, cellWidth: Int, cellHeight: Int): Unit =
    js("globalThis.GhosttyGlue.selectionRelease(gesture, terminal, x, y, cellWidth, cellHeight)")
private fun glueSelectionAutoscroll(gesture: Int, terminal: Int, x: Float, y: Float, cellWidth: Int, cellHeight: Int): Boolean =
    js("globalThis.GhosttyGlue.selectionAutoscroll(gesture, terminal, x, y, cellWidth, cellHeight)")
private fun glueSelectionAll(terminal: Int): Unit = js("globalThis.GhosttyGlue.selectionAll(terminal)")
private fun glueSelectionClear(terminal: Int): Unit = js("globalThis.GhosttyGlue.selectionClear(terminal)")
private fun glueSelectionText(terminal: Int): String = js("globalThis.GhosttyGlue.selectionText(terminal)")

private fun glueSearchNew(terminal: Int): Int = js("globalThis.GhosttyGlue.searchNew(terminal)")
private fun glueSearchFree(search: Int): Unit = js("globalThis.GhosttyGlue.searchFree(search)")
private fun glueSearchSetNeedle(search: Int, binary: String): Unit = js("globalThis.GhosttyGlue.searchSetNeedle(search, binary)")
private fun glueSearchAdvance(search: Int): Boolean = js("globalThis.GhosttyGlue.searchAdvance(search)")
private fun glueSearchSelect(search: Int, forward: Boolean): Boolean = js("globalThis.GhosttyGlue.searchSelect(search, forward)")
private fun glueSearchTotal(search: Int): Int = js("globalThis.GhosttyGlue.searchTotal(search)")
private fun glueSearchSelected(search: Int): Int = js("globalThis.GhosttyGlue.searchSelected(search)")
private fun glueSearchHighlights(search: Int): String = js("globalThis.GhosttyGlue.searchHighlights(search)")

/** Bytes as a string of one character each. */
private fun ByteArray.toBinary(offset: Int = 0, length: Int = size - offset): String =
    CharArray(length) { (this[offset + it].toInt() and 0xFF).toChar() }.concatToString()

private fun String.binaryToBytes(): ByteArray = ByteArray(length) { this[it].code.toByte() }

/** Where the page serves the library from, beside the app itself. */
private const val LIBRARY_URL = "ghostty-vt.wasm"

private var loaded = false

actual suspend fun loadGhostty() {
    if (loaded) return
    glueInit(LIBRARY_URL).await<JsAny?>()
    loaded = true
}

actual class GhosttyTerminal private constructor(internal val handle: Int) : AutoCloseable {

    actual class Restored(actual val terminal: GhosttyTerminal, actual val snapshotBytes: Int)

    actual companion object {
        actual fun create(columns: Int, rows: Int): GhosttyTerminal = GhosttyTerminal(glueTerminalNew(columns, rows))

        actual fun restore(bytes: ByteArray, length: Int): Restored {
            val (terminal, consumed) = glueTerminalRestore(bytes.toBinary(0, length)).split(',')
            return Restored(GhosttyTerminal(terminal.toInt()), consumed.toInt())
        }
    }

    actual val columns: Int get() = glueTerminalColumns(handle)
    actual val rows: Int get() = glueTerminalRows(handle)

    /** The size of a cell in pixels, as the terminal was last told, or zero. */
    internal val cellWidthPx: Int get() = glueTerminalWidthPx(handle) / columns.coerceAtLeast(1)
    internal val cellHeightPx: Int get() = glueTerminalHeightPx(handle) / rows.coerceAtLeast(1)

    actual fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (length > 0) glueTerminalWrite(handle, bytes.toBinary(offset, length))
    }

    actual fun resize(columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) =
        glueTerminalResize(handle, columns, rows, cellWidthPx, cellHeightPx)

    actual fun setColors(colors: TerminalColors) =
        glueTerminalSetColors(handle, colors.foreground, colors.background, colors.cursor ?: -1, colors.ansi.joinToString(","))

    actual fun scrollViewport(rows: Int) = glueTerminalScrollBy(handle, rows)

    actual fun scrollToBottom() = glueTerminalScrollToBottom(handle)

    actual val viewportTop: Long get() = glueTerminalViewportTop(handle).toLong()

    actual fun scrollByPrompts(count: Int) = glueTerminalScrollByPrompts(handle, count)

    actual val bracketedPaste: Boolean get() = glueTerminalBracketedPaste(handle)

    actual fun hyperlinkAt(column: Int, row: Int): String? =
        glueTerminalHyperlinkAt(handle, column, row).takeIf { it.isNotEmpty() }?.binaryToBytes()?.decodeToString()

    actual fun rowWraps(row: Int): Boolean = glueTerminalRowWraps(handle, row)

    /**
     * Copies out the pixels of an image a program sent, as RGBA. Null if there is no such
     * image, its pixels haven't arrived yet, or they are in a form the glue doesn't know.
     */
    internal fun readImage(id: Int): TerminalImage? {
        val described = glueImageRead(handle, id)
        // "width,height," and then the pixels, which may hold any byte, commas included.
        val first = described.indexOf(',')
        val second = described.indexOf(',', first + 1)
        if (first < 0 || second < 0) return null
        val width = described.substring(0, first).toInt()
        val height = described.substring(first + 1, second).toInt()
        val rgba = ByteArray(described.length - second - 1) { described[second + 1 + it].code.toByte() }
        return TerminalImage(id, width, height, rgba)
    }

    actual override fun close() = glueTerminalFree(handle)
}

actual class GhosttyRenderer actual constructor() : AutoCloseable {
    private val state = glueRendererNew()

    /** The rows of the last frame, by the id each keeps as it scrolls, to reuse the ones that didn't change. */
    private var rows = HashMap<String, TerminalRow>()

    /** The images already copied out, by number and version, and where the last frame put them. */
    private var imageCache = HashMap<String, TerminalImage>()
    private var placements: List<TerminalImagePlacement> = emptyList()

    actual fun invalidate() = glueRendererInvalidate(state)

    actual var overscan: Int = 0
        set(value) {
            val rows = value.coerceIn(0, 0xFFFF)
            if (rows == field) return
            field = rows
            glueRendererOverscan(state, rows)
        }

    actual fun render(terminal: GhosttyTerminal): TerminalFrame {
        // The glue describes the frame as one string; its `render` says how it is laid out.
        val records = glueRender(state, terminal.handle).split(RECORD)
        val header = records[0].split(FIELD)
        val foreground = header[2].toInt()
        val next = HashMap<String, TerminalRow>()
        val shown = ArrayList<TerminalRow>(records.size - 1)
        for (index in 1 until records.size) {
            val record = records[index]
            val fields = record.split(FIELD)
            val id = fields[0].substring(1)
            // "=" is a row unchanged since the last frame. The glue only says so for rows it
            // sent whole then, so it is here; an empty row stands in if it somehow isn't.
            val row = (if (record[0] == '=') rows[id] else null) ?: TerminalRow(
                buildList((fields.size - 1) / RUN_FIELDS) {
                    var at = 1
                    while (at + RUN_FIELDS <= fields.size) {
                        val flags = fields[at + 4].toInt()
                        add(
                            TerminalRun(
                                column = fields[at].toInt(),
                                cells = fields[at + 1].toInt(),
                                text = fields[at + 6],
                                foreground = fields[at + 2].toInt(),
                                background = fields[at + 3].toInt().takeIf { it >= 0 },
                                bold = flags and 1 != 0,
                                italic = flags and 2 != 0,
                                underline = TerminalUnderline.entries.getOrElse(flags shr 8) { TerminalUnderline.Single },
                                strikethrough = flags and 8 != 0,
                                underlineColor = fields[at + 5].toInt().takeIf { it >= 0 },
                                overline = flags and 16 != 0,
                            ),
                        )
                        at += RUN_FIELDS
                    }
                },
            )
            next[id] = row
            shown += row
        }
        rows = next
        val cursor = header[3].takeIf { it.isNotEmpty() }?.split(',')?.let { (column, row, shape, blinking, color) ->
            TerminalCursor(
                column = column.toInt(),
                row = row.toInt(),
                // GhosttyRenderStateCursorVisualStyle: bar, block, underline, hollow block.
                shape = when (shape.toInt()) {
                    0 -> TerminalCursor.Shape.Bar
                    2 -> TerminalCursor.Shape.Underline
                    3 -> TerminalCursor.Shape.HollowBlock
                    else -> TerminalCursor.Shape.Block
                },
                blinking = blinking != "0",
                color = color.toInt().takeIf { it >= 0 },
            )
        }
        readImages(terminal, header[4])
        val selection = header[5].takeIf { it.isNotEmpty() }?.split(';')?.map { cells ->
            val (row, first, last) = cells.split(',')
            SelectedCells(row.toInt(), first.toInt(), last.toInt())
        }
        // How many of the rows are from past the view's top and bottom, which come before and after its own.
        var above = header.getOrNull(6)?.toIntOrNull() ?: 0
        var below = header.getOrNull(7)?.toIntOrNull() ?: 0
        if (above + below > shown.size) { above = 0; below = 0 }
        return TerminalFrame(
            above = if (above == 0) emptyList() else shown.subList(0, above),
            below = if (below == 0) emptyList() else shown.subList(shown.size - below, shown.size),
            columns = header[0].toInt(),
            rows = if (above + below == 0) shown else shown.subList(above, shown.size - below),
            background = header[1].toInt(),
            foreground = foreground,
            cursor = cursor,
            images = placements,
            cellWidthPx = terminal.cellWidthPx,
            cellHeightPx = terminal.cellHeightPx,
            selection = selection ?: emptyList(),
        )
    }

    /**
     * Takes the glue's account of which images are on screen (its `describeImages` says how
     * it is written). Most frames change no image, and an image's pixels are copied out once
     * per version.
     */
    private fun readImages(terminal: GhosttyTerminal, described: String) {
        if (described == "=") return
        val found = ArrayList<TerminalImagePlacement>()
        val nextCache = HashMap<String, TerminalImage>()
        for (placement in described.split(';')) {
            val n = placement.split(',')
            if (n.size < 15) continue
            val key = n[0] + "." + n[1]
            // Pixels still on their way (an image restored from a snapshot) are drawn once they arrive.
            val image = nextCache[key] ?: imageCache[key] ?: terminal.readImage(n[0].toUInt().toInt()) ?: continue
            nextCache[key] = image
            found += TerminalImagePlacement(
                image = image,
                layer = when (n[2]) {
                    "0" -> TerminalImagePlacement.Layer.BelowBackground
                    "1" -> TerminalImagePlacement.Layer.BelowText
                    else -> TerminalImagePlacement.Layer.AboveText
                },
                column = n[3].toInt(), row = n[4].toInt(), columns = n[5].toInt(), rows = n[6].toInt(),
                sourceX = n[7].toInt(), sourceY = n[8].toInt(), sourceWidth = n[9].toInt(), sourceHeight = n[10].toInt(),
                pixelWidth = n[11].toInt(), pixelHeight = n[12].toInt(), offsetX = n[13].toInt(), offsetY = n[14].toInt(),
            )
        }
        imageCache = nextCache
        placements = found
    }

    actual override fun close() = glueRendererFree(state)

    private companion object {
        const val FIELD = '\u001f'
        const val RECORD = '\u001e'
        const val RUN_FIELDS = 7
    }
}

actual class GhosttyMouse actual constructor() : AutoCloseable {
    private val encoder: Int
    private val event: Int

    init {
        val (first, second) = glueMouseNew().split(',')
        encoder = first.toInt()
        event = second.toInt()
    }

    // The cell of the last motion reported. The encoder can remember this itself, but it
    // forgets whenever its options are set, and they are set afresh for every event.
    private var lastMotionCell = -1L

    actual fun encode(terminal: GhosttyTerminal, input: MouseInput): ByteArray {
        if (input.cellWidth <= 0 || input.cellHeight <= 0) return EMPTY
        if (input.action == MouseAction.Motion) {
            val cell = ((input.y / input.cellHeight).toLong() shl 32) or ((input.x / input.cellWidth).toLong() and 0xFFFFFFFFL)
            if (cell == lastMotionCell) return EMPTY
            lastMotionCell = cell
        } else {
            lastMotionCell = -1L
        }
        val report = glueMouseEncode(
            encoder, event, terminal.handle,
            action = input.action.ordinal, // press, release, motion: the library's own order
            button = when (input.button) {
                null -> 0
                MouseButton.Left -> 1
                MouseButton.Right -> 2
                MouseButton.Middle -> 3
                MouseButton.WheelUp -> 4
                MouseButton.WheelDown -> 5
            },
            mods = (if (input.shift) 1 else 0) or (if (input.control) 2 else 0) or (if (input.alt) 4 else 0),
            x = input.x, y = input.y, cellWidth = input.cellWidth, cellHeight = input.cellHeight,
            anyButtonDown = input.anyButtonDown,
        )
        return if (report.isEmpty()) EMPTY else report.binaryToBytes()
    }

    actual override fun close() = glueMouseFree(encoder, event)

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

actual class GhosttySelection actual constructor() : AutoCloseable {
    private val gesture = glueSelectionNew()

    actual fun press(terminal: GhosttyTerminal, at: MouseInput, timeMillis: Long) =
        glueSelectionPress(gesture, terminal.handle, at.x, at.y, at.cellWidth, at.cellHeight, timeMillis.toDouble())

    actual fun drag(terminal: GhosttyTerminal, at: MouseInput): Boolean =
        glueSelectionDrag(gesture, terminal.handle, at.x, at.y, at.cellWidth, at.cellHeight)

    actual fun release(terminal: GhosttyTerminal, at: MouseInput) =
        glueSelectionRelease(gesture, terminal.handle, at.x, at.y, at.cellWidth, at.cellHeight)

    actual fun autoscroll(terminal: GhosttyTerminal, at: MouseInput): Boolean =
        glueSelectionAutoscroll(gesture, terminal.handle, at.x, at.y, at.cellWidth, at.cellHeight)

    actual fun selectAll(terminal: GhosttyTerminal) = glueSelectionAll(terminal.handle)

    actual fun clear(terminal: GhosttyTerminal) = glueSelectionClear(terminal.handle)

    actual fun text(terminal: GhosttyTerminal): String = glueSelectionText(terminal.handle).binaryToBytes().decodeToString()

    actual override fun close() = glueSelectionFree(gesture)
}

actual class GhosttySearch actual constructor(terminal: GhosttyTerminal) : AutoCloseable {
    private val search = glueSearchNew(terminal.handle)

    actual fun setNeedle(text: String) = glueSearchSetNeedle(search, text.encodeToByteArray().toBinary())

    actual fun advance(): Boolean = glueSearchAdvance(search)

    actual fun selectNext(): Boolean = glueSearchSelect(search, true)

    actual fun selectPrevious(): Boolean = glueSearchSelect(search, false)

    actual val total: Int get() = glueSearchTotal(search)

    actual val selected: Int? get() = glueSearchSelected(search).takeIf { it >= 0 }

    actual fun highlights(): SearchHighlights {
        // The glue's `searchHighlights` says how this is written.
        val (others, selected) = glueSearchHighlights(search).split('\u001f')
        return SearchHighlights(cells(others), cells(selected))
    }

    private fun cells(described: String): List<SelectedCells> =
        if (described.isEmpty()) emptyList()
        else described.split(';').map {
            val (row, first, last) = it.split(',')
            SelectedCells(row.toInt(), first.toInt(), last.toInt())
        }

    actual override fun close() = glueSearchFree(search)
}

actual fun encodePaste(text: String, bracketed: Boolean): ByteArray =
    gluePasteEncode(text.encodeToByteArray().toBinary(), bracketed).binaryToBytes()
