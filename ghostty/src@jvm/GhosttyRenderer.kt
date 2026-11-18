package org.example.kotlintest.ghostty

import org.example.kotlintest.ghostty.ffi.GhosttyColorRgb
import org.example.kotlintest.ghostty.ffi.GhosttyRenderStateColors
import org.example.kotlintest.ghostty.ffi.GhosttyRenderStateCursor
import org.example.kotlintest.ghostty.ffi.GhosttyStyle
import org.example.kotlintest.ghostty.ffi.GhosttyVt
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BOOLEAN
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT

/**
 * Reads what a [GhosttyTerminal] currently shows, as a [TerminalFrame].
 *
 * libghostty-vt tracks which rows changed since the last read, so [render] only rebuilds
 * those and reuses the [TerminalRow] objects of the rest. Use one renderer per terminal,
 * on the terminal's thread.
 */
actual class GhosttyRenderer actual constructor() : AutoCloseable {
    private val arena = Arena.ofShared()

    // Handles are opaque pointers. The "ref" segments hold a handle so it can be passed by address.
    private val state: MemorySegment
    private val iteratorRef = arena.allocate(ADDRESS)
    private val cellsRef = arena.allocate(ADDRESS)
    private val iterator: MemorySegment
    private val cells: MemorySegment

    // Reused for every read, so drawing a frame allocates nothing off-heap.
    private val boolOut = arena.allocate(JAVA_BOOLEAN)
    private val intOut = arena.allocate(JAVA_INT)
    private val longOut = arena.allocate(JAVA_LONG)
    private val rowIdOut = arena.allocate(JAVA_LONG, 2)
    private val rgbOut = arena.allocate(GhosttyColorRgb.layout())
    private val styleOut = arena.allocate(GhosttyStyle.layout())
    private val cursorOut = arena.allocate(GhosttyRenderStateCursor.layout())
    private val colorsOut = arena.allocate(GhosttyRenderStateColors.layout())
    private var codepointsCapacity = 16
    private var codepointsOut = arena.allocate(JAVA_INT, codepointsCapacity.toLong())

    private val imagesRef = arena.allocate(ADDRESS)
    private val images: MemorySegment
    private val pointerOut = arena.allocate(ADDRESS)
    /** The images already copied out, by number and version. */
    private var imageCache = HashMap<ImageKey, TerminalImage>()
    private var placements: List<TerminalImagePlacement> = emptyList()
    private var placementsGeneration = -1L

    private var rowCache = HashMap<RowId, TerminalRow>()
    private var cachedForeground = -1
    private var cachedBackground = -1
    private var rebuildEverything = true

    init {
        GhosttyLibrary.ensureLoaded()
        val out = arena.allocate(ADDRESS)
        check("render_state_new", GhosttyVt.ghostty_render_state_new(NULL, out))
        state = out.get(ADDRESS, 0)
        check("render_state_row_iterator_new", GhosttyVt.ghostty_render_state_row_iterator_new(NULL, iteratorRef))
        iterator = iteratorRef.get(ADDRESS, 0)
        check("render_state_row_cells_new", GhosttyVt.ghostty_render_state_row_cells_new(NULL, cellsRef))
        cells = cellsRef.get(ADDRESS, 0)
        check("render_state_image_iterator_new", GhosttyVt.ghostty_render_state_image_iterator_new(NULL, imagesRef))
        images = imagesRef.get(ADDRESS, 0)
        GhosttyStyle.size(styleOut, GhosttyStyle.sizeof())
        GhosttyRenderStateCursor.size(cursorOut, GhosttyRenderStateCursor.sizeof())
        GhosttyRenderStateColors.size(colorsOut, GhosttyRenderStateColors.sizeof())
    }

    /** Forgets the cached rows, so the next [render] rebuilds every one. Call it after changing colors. */
    actual fun invalidate() {
        rebuildEverything = true
    }

    actual fun render(terminal: GhosttyTerminal): TerminalFrame {
        check("render_state_update", GhosttyVt.ghostty_render_state_update(state, terminal.handle))

        check("render_state_get", GhosttyVt.ghostty_render_state_get(state, GhosttyVt.GHOSTTY_RENDER_STATE_DATA_COLORS(), colorsOut))
        val background = readRgb(GhosttyRenderStateColors.background(colorsOut))
        val foreground = readRgb(GhosttyRenderStateColors.foreground(colorsOut))
        val cursorColor = if (GhosttyRenderStateColors.cursor_has_value(colorsOut)) readRgb(GhosttyRenderStateColors.cursor(colorsOut)) else null

        check("render_state_get", GhosttyVt.ghostty_render_state_get(state, GhosttyVt.GHOSTTY_RENDER_STATE_DATA_DIRTY(), intOut))
        // Rows bake in the default colors, so a change of defaults invalidates all of them too.
        val everything = rebuildEverything ||
            intOut.get(JAVA_INT, 0) == GhosttyVt.GHOSTTY_RENDER_STATE_DIRTY_FULL() ||
            foreground != cachedForeground || background != cachedBackground

        check("render_state_get", GhosttyVt.ghostty_render_state_get(state, GhosttyVt.GHOSTTY_RENDER_STATE_DATA_COLS(), intOut))
        val columns = intOut.get(JAVA_SHORT, 0).toInt() and 0xFFFF

        check("render_state_get", GhosttyVt.ghostty_render_state_get(state, GhosttyVt.GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR(), iteratorRef))
        val rows = ArrayList<TerminalRow>()
        val nextCache = HashMap<RowId, TerminalRow>()
        while (GhosttyVt.ghostty_render_state_row_iterator_next(iterator)) {
            // A row keeps its id as it scrolls, so an unchanged row is found again wherever it moved to.
            rowGet(GhosttyVt.GHOSTTY_RENDER_STATE_ROW_DATA_ID(), rowIdOut)
            val id = RowId(rowIdOut.get(JAVA_LONG, 0), rowIdOut.get(JAVA_LONG, 8))
            rowGet(GhosttyVt.GHOSTTY_RENDER_STATE_ROW_DATA_DIRTY(), boolOut)
            val dirty = boolOut.get(JAVA_BOOLEAN, 0)
            val row = (if (everything || dirty) null else rowCache[id]) ?: run {
                rowGet(GhosttyVt.GHOSTTY_RENDER_STATE_ROW_DATA_CELLS(), cellsRef)
                buildRow(foreground, background)
            }
            nextCache[id] = row
            rows += row
        }
        rowCache = nextCache
        cachedForeground = foreground
        cachedBackground = background
        rebuildEverything = false

        check("render_state_get", GhosttyVt.ghostty_render_state_get(state, GhosttyVt.GHOSTTY_RENDER_STATE_DATA_CURSOR(), cursorOut))
        val cursor = readCursor(cursorColor)
        readImages(terminal)
        check("render_state_clean", GhosttyVt.ghostty_render_state_clean(state))
        return TerminalFrame(columns, rows, background, foreground, cursor, placements, terminal.cellWidthPx, terminal.cellHeightPx)
    }

    /**
     * Reads which images are on screen and where, when that changed since the last frame.
     * Most frames change no image, and an image's pixels are copied out once per version.
     */
    private fun readImages(terminal: GhosttyTerminal) {
        if (GhosttyVt.ghostty_render_state_get(state, GhosttyVt.GHOSTTY_RENDER_STATE_DATA_IMAGE_PLACEMENTS_GENERATION(), longOut) != GhosttyVt.GHOSTTY_SUCCESS()) return
        val generation = longOut.get(JAVA_LONG, 0)
        if (generation == placementsGeneration) return
        placementsGeneration = generation
        if (GhosttyVt.ghostty_render_state_get(state, GhosttyVt.GHOSTTY_RENDER_STATE_DATA_IMAGE_ITERATOR(), imagesRef) != GhosttyVt.GHOSTTY_SUCCESS()) return

        val found = ArrayList<TerminalImagePlacement>()
        val nextCache = HashMap<ImageKey, TerminalImage>()
        while (GhosttyVt.ghostty_render_state_image_iterator_next(images)) {
            if (imageInt(GhosttyVt.GHOSTTY_RENDER_STATE_IMAGE_DATA_PROTOCOL()) != GhosttyVt.GHOSTTY_IMAGE_PROTOCOL_KITTY()) continue
            val id = imageInt(GhosttyVt.GHOSTTY_RENDER_STATE_IMAGE_DATA_IMAGE_ID())
            imageGet(GhosttyVt.GHOSTTY_RENDER_STATE_IMAGE_DATA_IMAGE_GENERATION(), longOut)
            val key = ImageKey(id, longOut.get(JAVA_LONG, 0))
            // Pixels still on their way (an image restored from a snapshot) are drawn once they arrive.
            val image = nextCache[key] ?: imageCache[key] ?: terminal.readImage(id) ?: continue
            nextCache[key] = image
            found += TerminalImagePlacement(
                image = image,
                layer = when (imageInt(GhosttyVt.GHOSTTY_RENDER_STATE_IMAGE_DATA_LAYER())) {
                    GhosttyVt.GHOSTTY_RENDER_STATE_IMAGE_LAYER_BELOW_BACKGROUND() -> TerminalImagePlacement.Layer.BelowBackground
                    GhosttyVt.GHOSTTY_RENDER_STATE_IMAGE_LAYER_BELOW_TEXT() -> TerminalImagePlacement.Layer.BelowText
                    else -> TerminalImagePlacement.Layer.AboveText
                },
                column = imageInt(GhosttyVt.GHOSTTY_RENDER_STATE_IMAGE_DATA_VIEWPORT_COL()),
                row = imageInt(GhosttyVt.GHOSTTY_RENDER_STATE_IMAGE_DATA_VIEWPORT_ROW()),
                columns = imageInt(GhosttyVt.GHOSTTY_RENDER_STATE_IMAGE_DATA_GRID_COLS()),
                rows = imageInt(GhosttyVt.GHOSTTY_RENDER_STATE_IMAGE_DATA_GRID_ROWS()),
                sourceX = imageInt(GhosttyVt.GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_X()),
                sourceY = imageInt(GhosttyVt.GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_Y()),
                sourceWidth = imageInt(GhosttyVt.GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_WIDTH()),
                sourceHeight = imageInt(GhosttyVt.GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_HEIGHT()),
                pixelWidth = imageInt(GhosttyVt.GHOSTTY_RENDER_STATE_IMAGE_DATA_PIXEL_WIDTH()),
                pixelHeight = imageInt(GhosttyVt.GHOSTTY_RENDER_STATE_IMAGE_DATA_PIXEL_HEIGHT()),
                offsetX = imageInt(GhosttyVt.GHOSTTY_RENDER_STATE_IMAGE_DATA_CELL_OFFSET_X()),
                offsetY = imageInt(GhosttyVt.GHOSTTY_RENDER_STATE_IMAGE_DATA_CELL_OFFSET_Y()),
            )
        }
        imageCache = nextCache
        placements = found
    }

    private fun imageGet(data: Int, out: MemorySegment) =
        check("render_state_image_get", GhosttyVt.ghostty_render_state_image_get(images, data, out))

    private fun imageInt(data: Int): Int {
        imageGet(data, intOut)
        return intOut.get(JAVA_INT, 0)
    }

    private fun readCursor(color: Int?): TerminalCursor? {
        if (!GhosttyRenderStateCursor.visible(cursorOut) || !GhosttyRenderStateCursor.viewport_has_value(cursorOut)) return null
        val shape = when (GhosttyRenderStateCursor.visual_style(cursorOut)) {
            GhosttyVt.GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BAR() -> TerminalCursor.Shape.Bar
            GhosttyVt.GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_UNDERLINE() -> TerminalCursor.Shape.Underline
            GhosttyVt.GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK_HOLLOW() -> TerminalCursor.Shape.HollowBlock
            else -> TerminalCursor.Shape.Block
        }
        return TerminalCursor(
            column = GhosttyRenderStateCursor.viewport_x(cursorOut).toInt() and 0xFFFF,
            row = GhosttyRenderStateCursor.viewport_y(cursorOut).toInt() and 0xFFFF,
            shape = shape,
            blinking = GhosttyRenderStateCursor.blinking(cursorOut),
            color = color,
        )
    }

    /** Builds the row the cells handle currently points at. */
    private fun buildRow(defaultForeground: Int, defaultBackground: Int): TerminalRow {
        val runs = ArrayList<TerminalRun>()
        val pending = PendingRun()
        var column = 0
        while (GhosttyVt.ghostty_render_state_row_cells_next(cells)) {
            val thisColumn = column++
            cellGet(GhosttyVt.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW(), longOut)
            check("cell_get", GhosttyVt.ghostty_cell_get(longOut.get(JAVA_LONG, 0), GhosttyVt.GHOSTTY_CELL_DATA_WIDE(), intOut))
            val wide = intOut.get(JAVA_INT, 0)
            // The second half of a wide character: the wide cell before it already covers this one.
            if (wide == GhosttyVt.GHOSTTY_CELL_WIDE_SPACER_TAIL()) continue

            var foreground = defaultForeground
            var background: Int? = null
            var bold = false
            var italic = false
            var underline = false
            var strikethrough = false
            var invisible = false

            // A cell can carry a background without any other styling, so always ask.
            if (cellTry(GhosttyVt.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR(), rgbOut)) background = readRgb(rgbOut)
            cellGet(GhosttyVt.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_HAS_STYLING(), boolOut)
            if (boolOut.get(JAVA_BOOLEAN, 0)) {
                if (cellTry(GhosttyVt.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR(), rgbOut)) foreground = readRgb(rgbOut)
                cellGet(GhosttyVt.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE(), styleOut)
                bold = GhosttyStyle.bold(styleOut)
                italic = GhosttyStyle.italic(styleOut)
                underline = GhosttyStyle.underline(styleOut) != GhosttyVt.GHOSTTY_SGR_UNDERLINE_NONE()
                strikethrough = GhosttyStyle.strikethrough(styleOut)
                invisible = GhosttyStyle.invisible(styleOut)
                if (GhosttyStyle.inverse(styleOut)) {
                    val swapped = background ?: defaultBackground
                    background = foreground
                    foreground = swapped
                }
                if (GhosttyStyle.faint(styleOut)) foreground = blend(foreground, background ?: defaultBackground)
            }

            cellGet(GhosttyVt.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN(), intOut)
            val codepoints = if (invisible) 0 else intOut.get(JAVA_INT, 0)
            if (codepoints == 0 && background == null) {
                // Nothing to draw here. A gap ends the run, since runs are contiguous.
                pending.flushInto(runs)
                continue
            }

            val text: String
            val plain: Boolean
            if (codepoints == 0) {
                text = " "
                plain = true
            } else {
                if (codepoints > codepointsCapacity) {
                    codepointsCapacity = codepoints * 2
                    codepointsOut = arena.allocate(JAVA_INT, codepointsCapacity.toLong())
                }
                cellGet(GhosttyVt.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF(), codepointsOut)
                val first = codepointsOut.get(JAVA_INT, 0)
                plain = codepoints == 1 && wide == GhosttyVt.GHOSTTY_CELL_WIDE_NARROW() && isPlain(first)
                text = if (codepoints == 1 && first < 0x10000) {
                    first.toChar().toString()
                } else {
                    buildString { for (index in 0 until codepoints) appendCodePoint(codepointsOut.getAtIndex(JAVA_INT, index.toLong())) }
                }
            }

            val width = if (wide == GhosttyVt.GHOSTTY_CELL_WIDE_WIDE()) 2 else 1
            if (!pending.accepts(thisColumn, plain, foreground, background, bold, italic, underline, strikethrough)) {
                pending.flushInto(runs)
                pending.start(thisColumn, plain, foreground, background, bold, italic, underline, strikethrough)
            }
            pending.append(text, width)
        }
        pending.flushInto(runs)
        return TerminalRow(runs)
    }

    private fun rowGet(data: Int, out: MemorySegment) =
        check("render_state_row_get", GhosttyVt.ghostty_render_state_row_get(iterator, data, out))

    private fun cellGet(data: Int, out: MemorySegment) =
        check("render_state_row_cells_get", GhosttyVt.ghostty_render_state_row_cells_get(cells, data, out))

    /** For values a cell may not have, such as an explicit color: false when it has none. */
    private fun cellTry(data: Int, out: MemorySegment): Boolean =
        GhosttyVt.ghostty_render_state_row_cells_get(cells, data, out) == GhosttyVt.GHOSTTY_SUCCESS()

    actual override fun close() {
        GhosttyVt.ghostty_render_state_image_iterator_free(images)
        GhosttyVt.ghostty_render_state_row_cells_free(cells)
        GhosttyVt.ghostty_render_state_row_iterator_free(iterator)
        GhosttyVt.ghostty_render_state_free(state)
        arena.close()
    }

    private data class RowId(val low: Long, val high: Long)
    private data class ImageKey(val id: Int, val generation: Long)

    /** The run being put together as cells are read. */
    private class PendingRun {
        private val text = StringBuilder()
        private var open = false
        private var column = 0
        private var cells = 0
        private var plain = false
        private var foreground = 0
        private var background: Int? = null
        private var bold = false
        private var italic = false
        private var underline = false
        private var strikethrough = false

        /** Whether a cell at [column] with these attributes can join the run. */
        fun accepts(
            column: Int, plain: Boolean, foreground: Int, background: Int?,
            bold: Boolean, italic: Boolean, underline: Boolean, strikethrough: Boolean,
        ): Boolean = open && plain && this.plain && column == this.column + cells &&
            foreground == this.foreground && background == this.background &&
            bold == this.bold && italic == this.italic && underline == this.underline && strikethrough == this.strikethrough

        fun start(
            column: Int, plain: Boolean, foreground: Int, background: Int?,
            bold: Boolean, italic: Boolean, underline: Boolean, strikethrough: Boolean,
        ) {
            open = true
            text.setLength(0)
            cells = 0
            this.column = column
            this.plain = plain
            this.foreground = foreground
            this.background = background
            this.bold = bold
            this.italic = italic
            this.underline = underline
            this.strikethrough = strikethrough
        }

        fun append(cellText: String, width: Int) {
            text.append(cellText)
            cells += width
        }

        fun flushInto(runs: MutableList<TerminalRun>) {
            if (!open) return
            open = false
            runs += TerminalRun(column, cells, text.toString(), foreground, background, bold, italic, underline, strikethrough)
        }
    }
}

/**
 * Whether a monospace font can be trusted to draw this character exactly one cell wide, so
 * that it can share a run with its neighbors: printable ASCII, Latin letters, and the
 * box-drawing and block characters that terminal interfaces are built from.
 */
private fun isPlain(codepoint: Int): Boolean =
    codepoint in 0x20..0x7E || codepoint in 0xA0..0x17F || codepoint in 0x2500..0x259F

/** Halfway between two colors, for faint text. */
private fun blend(color: Int, towards: Int): Int {
    fun channel(shift: Int) = (((color shr shift) and 0xFF) + ((towards shr shift) and 0xFF)) / 2
    return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}
