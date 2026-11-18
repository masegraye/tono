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
private fun glueTerminalScrollToBottom(terminal: Int): Unit = js("globalThis.GhosttyGlue.terminalScrollToBottom(terminal)")
private fun glueTerminalBracketedPaste(terminal: Int): Boolean = js("globalThis.GhosttyGlue.terminalBracketedPaste(terminal)")
private fun gluePasteEncode(binary: String, bracketed: Boolean): String = js("globalThis.GhosttyGlue.pasteEncode(binary, bracketed)")
private fun glueRendererNew(): Int = js("globalThis.GhosttyGlue.rendererNew()")
private fun glueRendererFree(state: Int): Unit = js("globalThis.GhosttyGlue.rendererFree(state)")
private fun glueRendererInvalidate(state: Int): Unit = js("globalThis.GhosttyGlue.rendererInvalidate(state)")
private fun glueRender(state: Int, terminal: Int): String = js("globalThis.GhosttyGlue.render(state, terminal)")
private fun glueMouseNew(): String = js("globalThis.GhosttyGlue.mouseNew()")
private fun glueMouseFree(encoder: Int, event: Int): Unit = js("globalThis.GhosttyGlue.mouseFree(encoder, event)")
private fun glueMouseEncode(
    encoder: Int, event: Int, terminal: Int, action: Int, button: Int, mods: Int,
    x: Float, y: Float, cellWidth: Int, cellHeight: Int, anyButtonDown: Boolean,
): String = js("globalThis.GhosttyGlue.mouseEncode(encoder, event, terminal, action, button, mods, x, y, cellWidth, cellHeight, anyButtonDown)")

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

    actual val bracketedPaste: Boolean get() = glueTerminalBracketedPaste(handle)

    actual override fun close() = glueTerminalFree(handle)
}

actual class GhosttyRenderer actual constructor() : AutoCloseable {
    private val state = glueRendererNew()

    /** The rows of the last frame, by the id each keeps as it scrolls, to reuse the ones that didn't change. */
    private var rows = HashMap<String, TerminalRow>()

    actual fun invalidate() = glueRendererInvalidate(state)

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
                                text = fields[at + 5],
                                foreground = fields[at + 2].toInt(),
                                background = fields[at + 3].toInt().takeIf { it >= 0 },
                                bold = flags and 1 != 0,
                                italic = flags and 2 != 0,
                                underline = flags and 4 != 0,
                                strikethrough = flags and 8 != 0,
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
        return TerminalFrame(
            columns = header[0].toInt(),
            rows = shown,
            background = header[1].toInt(),
            foreground = foreground,
            cursor = cursor,
            // Images aren't read here yet: the WebAssembly build of the library leaves out
            // the functions that hand over their pixels.
            cellWidthPx = terminal.cellWidthPx,
            cellHeightPx = terminal.cellHeightPx,
        )
    }

    actual override fun close() = glueRendererFree(state)

    private companion object {
        const val FIELD = '\u001f'
        const val RECORD = '\u001e'
        const val RUN_FIELDS = 6
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

actual fun encodePaste(text: String, bracketed: Boolean): ByteArray =
    gluePasteEncode(text.encodeToByteArray().toBinary(), bracketed).binaryToBytes()
