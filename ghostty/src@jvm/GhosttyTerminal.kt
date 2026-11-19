package org.example.kotlintest.ghostty

import org.example.kotlintest.ghostty.ffi.GhosttyColorRgb
import org.example.kotlintest.ghostty.ffi.GhosttyFormatterScreenExtra
import org.example.kotlintest.ghostty.ffi.GhosttyFormatterTerminalExtra
import org.example.kotlintest.ghostty.ffi.GhosttyFormatterTerminalOptions
import org.example.kotlintest.ghostty.ffi.GhosttyGridRef
import org.example.kotlintest.ghostty.ffi.GhosttyPoint
import org.example.kotlintest.ghostty.ffi.GhosttyPointCoordinate
import org.example.kotlintest.ghostty.ffi.GhosttyPointValue
import org.example.kotlintest.ghostty.ffi.GhosttyTerminalModeConfig
import org.example.kotlintest.ghostty.ffi.GhosttyTerminalScrollViewport
import org.example.kotlintest.ghostty.ffi.GhosttyTerminalScrollViewportValue
import org.example.kotlintest.ghostty.ffi.GhosttyVt
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BOOLEAN
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT

/**
 * A terminal emulated by libghostty-vt, Ghostty's terminal core. It turns a program's
 * output (text and escape sequences) into a grid of styled cells with scrollback.
 *
 * Not thread-safe: use a terminal, and any [GhosttyRenderer] reading it, from one thread
 * at a time.
 */
actual class GhosttyTerminal private constructor(internal val handle: MemorySegment) : AutoCloseable {

    /** A terminal restored from a snapshot, and how many bytes the snapshot occupied. */
    actual class Restored(actual val terminal: GhosttyTerminal, actual val snapshotBytes: Int)

    actual companion object {
        actual fun create(columns: Int, rows: Int): GhosttyTerminal {
            GhosttyLibrary.ensureLoaded()
            return Arena.ofConfined().use { arena ->
                val out = arena.allocate(ADDRESS)
                check("terminal_new", GhosttyVt.ghostty_terminal_new(NULL, out, columns.toShort(), rows.toShort()))
                GhosttyTerminal(out.get(ADDRESS, 0)).also { it.allowImages() }
            }
        }

        /**
         * Restores a terminal from the Ghostty snapshot at the start of [bytes]: its screen,
         * scrollback, modes, and any half-received escape sequence. Bytes after the snapshot
         * are left alone; [Restored.snapshotBytes] says where they start.
         */
        actual fun restore(bytes: ByteArray, length: Int): Restored {
            GhosttyLibrary.ensureLoaded()
            return Arena.ofConfined().use { arena ->
                val buffer = arena.allocate(length.toLong())
                MemorySegment.copy(bytes, 0, buffer, JAVA_BYTE, 0, length)
                val decoderOut = arena.allocate(ADDRESS)
                check("snapshot_decoder_new_buf", GhosttyVt.ghostty_snapshot_decoder_new_buf(NULL, decoderOut, buffer, length.toLong()))
                val decoder = decoderOut.get(ADDRESS, 0)
                try {
                    // A snapshot can carry the images on screen; they are dropped unless there is room for them.
                    val limit = arena.allocate(JAVA_LONG).also { it.set(JAVA_LONG, 0, IMAGE_STORAGE_BYTES) }
                    check(
                        "snapshot_decoder_set",
                        GhosttyVt.ghostty_snapshot_decoder_set(decoder, GhosttyVt.GHOSTTY_SNAPSHOT_DECODER_OPT_KITTY_IMAGE_STORAGE_LIMIT(), limit),
                    )
                    val terminalOut = arena.allocate(ADDRESS)
                    check("snapshot_decoder_decode", GhosttyVt.ghostty_snapshot_decoder_decode(decoder, terminalOut))
                    val consumed = arena.allocate(JAVA_LONG)
                    check(
                        "snapshot_decoder_get",
                        GhosttyVt.ghostty_snapshot_decoder_get(decoder, GhosttyVt.GHOSTTY_SNAPSHOT_DECODER_DATA_SOURCE_OFFSET(), consumed),
                    )
                    Restored(GhosttyTerminal(terminalOut.get(ADDRESS, 0)).also { it.allowImages() }, consumed.get(JAVA_LONG, 0).toInt())
                } finally {
                    GhosttyVt.ghostty_snapshot_decoder_free(decoder)
                }
            }
        }
    }

    actual val columns: Int get() = readShort(GhosttyVt.GHOSTTY_TERMINAL_DATA_COLS())
    actual val rows: Int get() = readShort(GhosttyVt.GHOSTTY_TERMINAL_DATA_ROWS())

    /** Feeds program output to the terminal. */
    actual fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(length.toLong())
            MemorySegment.copy(bytes, offset, buffer, JAVA_BYTE, 0, length)
            GhosttyVt.ghostty_terminal_vt_write(handle, buffer, length.toLong())
        }
    }

    fun write(text: String) = write(text.toByteArray())

    /** Changes the grid's size, reflowing wrapped lines. Pixel sizes are 0 when unknown. */
    actual fun resize(columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        check("terminal_resize", GhosttyVt.ghostty_terminal_resize(handle, columns.toShort(), rows.toShort(), cellWidthPx, cellHeightPx))
    }

    /** Sets the colors used where a program hasn't chosen one. */
    actual fun setColors(colors: TerminalColors): Unit = Arena.ofConfined().use { arena ->
        val rgb = arena.allocate(GhosttyColorRgb.layout())
        fun set(option: Int, color: Int?) {
            if (color != null) writeRgb(rgb, 0, color)
            check("terminal_set", GhosttyVt.ghostty_terminal_set(handle, option, if (color == null) NULL else rgb))
        }
        set(GhosttyVt.GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND(), colors.foreground)
        set(GhosttyVt.GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND(), colors.background)
        set(GhosttyVt.GHOSTTY_TERMINAL_OPT_COLOR_CURSOR(), colors.cursor)
        if (colors.ansi.isNotEmpty()) {
            // The palette is set whole: start from the standard 256 colors and replace the first 16.
            val palette = arena.allocate(GhosttyColorRgb.layout(), 256)
            GhosttyVt.ghostty_color_palette_default(palette)
            colors.ansi.forEachIndexed { index, color -> writeRgb(palette, index * GhosttyColorRgb.sizeof(), color) }
            check("terminal_set", GhosttyVt.ghostty_terminal_set(handle, GhosttyVt.GHOSTTY_TERMINAL_OPT_COLOR_PALETTE(), palette))
        }
    }

    /** Scrolls the view through the scrollback: negative is up, towards older output. */
    actual fun scrollViewport(rows: Int): Unit = scroll(GhosttyVt.GHOSTTY_SCROLL_VIEWPORT_DELTA(), rows.toLong())

    /** Returns the view to the live screen. */
    actual fun scrollToBottom(): Unit = scroll(GhosttyVt.GHOSTTY_SCROLL_VIEWPORT_BOTTOM(), 0)

    /** Moves the view by [count] shell prompts: negative is up, to older ones. Nothing moves without prompt marks. */
    actual fun scrollByPrompts(count: Int): Unit = scroll(GhosttyVt.GHOSTTY_SCROLL_VIEWPORT_DELTA_PROMPT(), count.toLong())

    // The value is a union of whole numbers the size of a pointer, so one setter serves every tag.
    private fun scroll(tag: Int, delta: Long) = Arena.ofConfined().use { arena ->
        val behavior = arena.allocate(GhosttyTerminalScrollViewport.layout())
        GhosttyTerminalScrollViewport.tag(behavior, tag)
        GhosttyTerminalScrollViewportValue.delta(GhosttyTerminalScrollViewport.value(behavior), delta)
        GhosttyVt.ghostty_terminal_scroll_viewport(handle, behavior)
    }

    /** The terminal's contents as plain text, one line per row, trailing blanks removed. */
    fun text(): String = Arena.ofConfined().use { arena ->
        // "Sized" structs carry their own size so the library can grow them compatibly.
        val options = arena.allocate(GhosttyFormatterTerminalOptions.layout())
        GhosttyFormatterTerminalOptions.size(options, GhosttyFormatterTerminalOptions.sizeof())
        GhosttyFormatterTerminalOptions.emit(options, GhosttyVt.GHOSTTY_FORMATTER_FORMAT_PLAIN())
        GhosttyFormatterTerminalOptions.trim(options, true)
        val extra = GhosttyFormatterTerminalOptions.extra(options)
        GhosttyFormatterTerminalExtra.size(extra, GhosttyFormatterTerminalExtra.sizeof())
        GhosttyFormatterScreenExtra.size(GhosttyFormatterTerminalExtra.screen(extra), GhosttyFormatterScreenExtra.sizeof())

        val formatterOut = arena.allocate(ADDRESS)
        check("formatter_terminal_new", GhosttyVt.ghostty_formatter_terminal_new(NULL, formatterOut, handle, options))
        val formatter = formatterOut.get(ADDRESS, 0)
        try {
            val pointerOut = arena.allocate(ADDRESS)
            val lengthOut = arena.allocate(JAVA_LONG)
            check("formatter_format_alloc", GhosttyVt.ghostty_formatter_format_alloc(formatter, NULL, pointerOut, lengthOut))
            val pointer = pointerOut.get(ADDRESS, 0)
            val length = lengthOut.get(JAVA_LONG, 0)
            try {
                String(pointer.reinterpret(length).toArray(JAVA_BYTE), Charsets.UTF_8)
            } finally {
                GhosttyVt.ghostty_free(NULL, pointer, length)
            }
        } finally {
            GhosttyVt.ghostty_formatter_free(formatter)
        }
    }

    /**
     * Whether the terminal's program has asked for pasted text to be bracketed (mode 2004),
     * which lets it tell a paste from typing.
     */
    actual val bracketedPaste: Boolean
        get() = Arena.ofConfined().use { arena ->
            val config = arena.allocate(GhosttyTerminalModeConfig.layout())
            GhosttyTerminalModeConfig.mode(config, MODE_BRACKETED_PASTE)
            check("terminal_get", GhosttyVt.ghostty_terminal_get(handle, GhosttyVt.GHOSTTY_TERMINAL_DATA_MODE(), config))
            GhosttyTerminalModeConfig.value(config)
        }

    actual fun hyperlinkAt(column: Int, row: Int): String? = cellAt(column, row) { arena, cell ->
        val lengthOut = arena.allocate(JAVA_LONG)
        // Asked twice: first for how long the address is, then for the address.
        if (GhosttyVt.ghostty_grid_ref_hyperlink_uri(cell, NULL, 0, lengthOut) != GhosttyVt.GHOSTTY_OUT_OF_SPACE()) return@cellAt null
        val length = lengthOut.get(JAVA_LONG, 0)
        if (length <= 0 || length > MAX_HYPERLINK_BYTES) return@cellAt null
        val buffer = arena.allocate(length)
        if (GhosttyVt.ghostty_grid_ref_hyperlink_uri(cell, buffer, length, lengthOut) != GhosttyVt.GHOSTTY_SUCCESS()) return@cellAt null
        String(buffer.asSlice(0, lengthOut.get(JAVA_LONG, 0)).toArray(JAVA_BYTE), Charsets.UTF_8)
    }

    actual fun rowWraps(row: Int): Boolean = cellAt(0, row) { arena, cell ->
        val rowOut = arena.allocate(JAVA_LONG)
        if (GhosttyVt.ghostty_grid_ref_row(cell, rowOut) != GhosttyVt.GHOSTTY_SUCCESS()) return@cellAt false
        val wraps = arena.allocate(JAVA_BOOLEAN)
        GhosttyVt.ghostty_row_get(rowOut.get(JAVA_LONG, 0), GhosttyVt.GHOSTTY_ROW_DATA_WRAP(), wraps) == GhosttyVt.GHOSTTY_SUCCESS() && wraps.get(JAVA_BOOLEAN, 0)
    } ?: false

    /** Looks at one cell of the view. Null if there is no such cell. The reference to it is good only until the terminal next changes. */
    private inline fun <T> cellAt(column: Int, row: Int, read: (Arena, MemorySegment) -> T?): T? {
        if (column !in 0 until columns || row !in 0 until rows) return null
        return Arena.ofConfined().use { arena ->
            val point = GhosttyPoint.allocate(arena)
            GhosttyPoint.tag(point, GhosttyVt.GHOSTTY_POINT_TAG_VIEWPORT())
            val coordinate = GhosttyPointValue.coordinate(GhosttyPoint.value(point))
            GhosttyPointCoordinate.x(coordinate, column.toShort())
            GhosttyPointCoordinate.y(coordinate, row)
            val cell = GhosttyGridRef.allocate(arena)
            GhosttyGridRef.size(cell, GhosttyGridRef.sizeof())
            if (GhosttyVt.ghostty_terminal_grid_ref(handle, point, cell) != GhosttyVt.GHOSTTY_SUCCESS()) null else read(arena, cell)
        }
    }

    /** A snapshot that [restore] can rebuild this terminal from. */
    fun snapshot(): ByteArray = Arena.ofConfined().use { arena ->
        val pointerOut = arena.allocate(ADDRESS)
        val lengthOut = arena.allocate(JAVA_LONG)
        check("snapshot_encode_alloc", GhosttyVt.ghostty_snapshot_encode_alloc(handle, NULL, pointerOut, lengthOut))
        val pointer = pointerOut.get(ADDRESS, 0)
        val length = lengthOut.get(JAVA_LONG, 0)
        try {
            pointer.reinterpret(length).toArray(JAVA_BYTE)
        } finally {
            GhosttyVt.ghostty_free(NULL, pointer, length)
        }
    }

    actual override fun close() = GhosttyVt.ghostty_terminal_free(handle)

    /** The size of a cell in pixels, as the terminal was last told, or zero. Programs size images by it. */
    val cellWidthPx: Int get() = readInt(GhosttyVt.GHOSTTY_TERMINAL_DATA_WIDTH_PX()) / columns.coerceAtLeast(1)
    val cellHeightPx: Int get() = readInt(GhosttyVt.GHOSTTY_TERMINAL_DATA_HEIGHT_PX()) / rows.coerceAtLeast(1)

    private fun readInt(data: Int): Int = Arena.ofConfined().use { arena ->
        val out = arena.allocate(JAVA_LONG)
        if (GhosttyVt.ghostty_terminal_get(handle, data, out) == GhosttyVt.GHOSTTY_SUCCESS()) out.get(java.lang.foreign.ValueLayout.JAVA_INT, 0) else 0
    }

    /**
     * Copies out the pixels of an image a program sent, as RGBA. Null if there is no such
     * image, its pixels haven't arrived yet, or they are in a form this doesn't know.
     */
    internal fun readImage(id: Int): TerminalImage? = Arena.ofConfined().use { arena ->
        val graphics = arena.allocate(ADDRESS)
        if (GhosttyVt.ghostty_terminal_get(handle, GhosttyVt.GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS(), graphics) != GhosttyVt.GHOSTTY_SUCCESS()) return null
        val image = GhosttyVt.ghostty_kitty_graphics_image(graphics.get(ADDRESS, 0), id)
        if (image == NULL) return null
        val number = arena.allocate(JAVA_LONG)
        fun int(data: Int): Int? =
            if (GhosttyVt.ghostty_kitty_graphics_image_get(image, data, number) == GhosttyVt.GHOSTTY_SUCCESS()) number.get(java.lang.foreign.ValueLayout.JAVA_INT, 0) else null
        val width = int(GhosttyVt.GHOSTTY_KITTY_IMAGE_DATA_WIDTH()) ?: return null
        val height = int(GhosttyVt.GHOSTTY_KITTY_IMAGE_DATA_HEIGHT()) ?: return null
        val format = int(GhosttyVt.GHOSTTY_KITTY_IMAGE_DATA_FORMAT()) ?: return null
        val pointer = arena.allocate(ADDRESS)
        if (GhosttyVt.ghostty_kitty_graphics_image_get(image, GhosttyVt.GHOSTTY_KITTY_IMAGE_DATA_DATA_PTR(), pointer) != GhosttyVt.GHOSTTY_SUCCESS()) return null
        if (GhosttyVt.ghostty_kitty_graphics_image_get(image, GhosttyVt.GHOSTTY_KITTY_IMAGE_DATA_DATA_LEN(), number) != GhosttyVt.GHOSTTY_SUCCESS()) return null
        val length = number.get(JAVA_LONG, 0)
        val data = pointer.get(ADDRESS, 0)
        if (data == NULL || width <= 0 || height <= 0 || length <= 0) return null
        val rgba = toRgba(format, data.reinterpret(length).toArray(JAVA_BYTE), width * height) ?: return null
        TerminalImage(id, width, height, rgba)
    }

    /**
     * Lets programs show images with the Kitty graphics protocol, which is off until a
     * terminal is given room to keep them. Only pixels sent in the output itself are taken:
     * the protocol can also name a file or shared memory to read, and what a remote program
     * prints must not be able to make this machine read its own files.
     */
    private fun allowImages() = Arena.ofConfined().use { arena ->
        val limit = arena.allocate(JAVA_LONG).also { it.set(JAVA_LONG, 0, IMAGE_STORAGE_BYTES) }
        check("terminal_set", GhosttyVt.ghostty_terminal_set(handle, GhosttyVt.GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT(), limit))
        val off = arena.allocate(java.lang.foreign.ValueLayout.JAVA_BOOLEAN).also { it.set(java.lang.foreign.ValueLayout.JAVA_BOOLEAN, 0, false) }
        check("terminal_set", GhosttyVt.ghostty_terminal_set(handle, GhosttyVt.GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_MEDIUM_FILE(), off))
        check("terminal_set", GhosttyVt.ghostty_terminal_set(handle, GhosttyVt.GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_MEDIUM_SHARED_MEM(), off))
        // Temporary files are allowed by naming a directory for them; none is named.
        check("terminal_set", GhosttyVt.ghostty_terminal_set(handle, GhosttyVt.GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_MEDIUM_TEMP_FILE(), NULL))
    }

    private fun readShort(data: Int): Int = Arena.ofConfined().use { arena ->
        val out = arena.allocate(JAVA_SHORT)
        check("terminal_get", GhosttyVt.ghostty_terminal_get(handle, data, out))
        out.get(JAVA_SHORT, 0).toInt() and 0xFFFF
    }
}

internal val NULL: MemorySegment = MemorySegment.NULL

/** A link's address longer than this is not read. Nothing that long is one a person would follow. */
private const val MAX_HYPERLINK_BYTES = 8192L

/** DEC private mode 2004. A mode is its number, with the top bit set for ANSI modes. */
private const val MODE_BRACKETED_PASTE: Short = 2004

/**
 * Prepares text for pasting into a terminal: control characters that could run commands
 * are replaced with spaces, and the text is wrapped in bracketed-paste markers when
 * [bracketed] is true, or has its newlines turned into carriage returns when it isn't.
 */
actual fun encodePaste(text: String, bracketed: Boolean): ByteArray {
    GhosttyLibrary.ensureLoaded()
    val input = text.toByteArray()
    return Arena.ofConfined().use { arena ->
        val data = arena.allocate(maxOf(input.size, 1).toLong())
        MemorySegment.copy(input, 0, data, JAVA_BYTE, 0, input.size)
        val capacity = input.size + 16L // room for the bracketed-paste markers
        val buffer = arena.allocate(capacity)
        val written = arena.allocate(JAVA_LONG)
        check("paste_encode", GhosttyVt.ghostty_paste_encode(data, input.size.toLong(), bracketed, buffer, capacity, written))
        buffer.asSlice(0, written.get(JAVA_LONG, 0)).toArray(JAVA_BYTE)
    }
}

internal fun writeRgb(segment: MemorySegment, offset: Long, color: Int) {
    segment.set(JAVA_BYTE, offset, (color shr 16).toByte())
    segment.set(JAVA_BYTE, offset + 1, (color shr 8).toByte())
    segment.set(JAVA_BYTE, offset + 2, color.toByte())
}

internal fun readRgb(segment: MemorySegment, offset: Long = 0): Int =
    ((segment.get(JAVA_BYTE, offset).toInt() and 0xFF) shl 16) or
        ((segment.get(JAVA_BYTE, offset + 1).toInt() and 0xFF) shl 8) or
        (segment.get(JAVA_BYTE, offset + 2).toInt() and 0xFF)
