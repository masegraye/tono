package org.example.kotlintest.ghostty

import org.example.kotlintest.ghostty.ffi.GhosttyColorRgb
import org.example.kotlintest.ghostty.ffi.GhosttyFormatterScreenExtra
import org.example.kotlintest.ghostty.ffi.GhosttyFormatterTerminalExtra
import org.example.kotlintest.ghostty.ffi.GhosttyFormatterTerminalOptions
import org.example.kotlintest.ghostty.ffi.GhosttyTerminalScrollViewport
import org.example.kotlintest.ghostty.ffi.GhosttyTerminalScrollViewportValue
import org.example.kotlintest.ghostty.ffi.GhosttyVt
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
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
class GhosttyTerminal private constructor(internal val handle: MemorySegment) : AutoCloseable {

    /** A terminal restored from a snapshot, and how many bytes the snapshot occupied. */
    class Restored(val terminal: GhosttyTerminal, val snapshotBytes: Int)

    companion object {
        /** An empty terminal. */
        fun create(columns: Int, rows: Int): GhosttyTerminal {
            GhosttyLibrary.ensureLoaded()
            return Arena.ofConfined().use { arena ->
                val out = arena.allocate(ADDRESS)
                check("terminal_new", GhosttyVt.ghostty_terminal_new(NULL, out, columns.toShort(), rows.toShort()))
                GhosttyTerminal(out.get(ADDRESS, 0))
            }
        }

        /**
         * Restores a terminal from the Ghostty snapshot at the start of [bytes]: its screen,
         * scrollback, modes, and any half-received escape sequence. Bytes after the snapshot
         * are left alone; [Restored.snapshotBytes] says where they start.
         */
        fun restore(bytes: ByteArray, length: Int = bytes.size): Restored {
            GhosttyLibrary.ensureLoaded()
            return Arena.ofConfined().use { arena ->
                val buffer = arena.allocate(length.toLong())
                MemorySegment.copy(bytes, 0, buffer, JAVA_BYTE, 0, length)
                val decoderOut = arena.allocate(ADDRESS)
                check("snapshot_decoder_new_buf", GhosttyVt.ghostty_snapshot_decoder_new_buf(NULL, decoderOut, buffer, length.toLong()))
                val decoder = decoderOut.get(ADDRESS, 0)
                try {
                    val terminalOut = arena.allocate(ADDRESS)
                    check("snapshot_decoder_decode", GhosttyVt.ghostty_snapshot_decoder_decode(decoder, terminalOut))
                    val consumed = arena.allocate(JAVA_LONG)
                    check(
                        "snapshot_decoder_get",
                        GhosttyVt.ghostty_snapshot_decoder_get(decoder, GhosttyVt.GHOSTTY_SNAPSHOT_DECODER_DATA_SOURCE_OFFSET(), consumed),
                    )
                    Restored(GhosttyTerminal(terminalOut.get(ADDRESS, 0)), consumed.get(JAVA_LONG, 0).toInt())
                } finally {
                    GhosttyVt.ghostty_snapshot_decoder_free(decoder)
                }
            }
        }
    }

    val columns: Int get() = readShort(GhosttyVt.GHOSTTY_TERMINAL_DATA_COLS())
    val rows: Int get() = readShort(GhosttyVt.GHOSTTY_TERMINAL_DATA_ROWS())

    /** Feeds program output to the terminal. */
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        if (length <= 0) return
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(length.toLong())
            MemorySegment.copy(bytes, offset, buffer, JAVA_BYTE, 0, length)
            GhosttyVt.ghostty_terminal_vt_write(handle, buffer, length.toLong())
        }
    }

    fun write(text: String) = write(text.toByteArray())

    /** Changes the grid's size, reflowing wrapped lines. Pixel sizes are 0 when unknown. */
    fun resize(columns: Int, rows: Int, cellWidthPx: Int = 0, cellHeightPx: Int = 0) {
        check("terminal_resize", GhosttyVt.ghostty_terminal_resize(handle, columns.toShort(), rows.toShort(), cellWidthPx, cellHeightPx))
    }

    /** Sets the colors used where a program hasn't chosen one. */
    fun setColors(colors: TerminalColors) = Arena.ofConfined().use { arena ->
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
    fun scrollViewport(rows: Int) = scroll(GhosttyVt.GHOSTTY_SCROLL_VIEWPORT_DELTA(), rows.toLong())

    /** Returns the view to the live screen. */
    fun scrollToBottom() = scroll(GhosttyVt.GHOSTTY_SCROLL_VIEWPORT_BOTTOM(), 0)

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

    override fun close() = GhosttyVt.ghostty_terminal_free(handle)

    private fun readShort(data: Int): Int = Arena.ofConfined().use { arena ->
        val out = arena.allocate(JAVA_SHORT)
        check("terminal_get", GhosttyVt.ghostty_terminal_get(handle, data, out))
        out.get(JAVA_SHORT, 0).toInt() and 0xFFFF
    }
}

internal val NULL: MemorySegment = MemorySegment.NULL

internal fun writeRgb(segment: MemorySegment, offset: Long, color: Int) {
    segment.set(JAVA_BYTE, offset, (color shr 16).toByte())
    segment.set(JAVA_BYTE, offset + 1, (color shr 8).toByte())
    segment.set(JAVA_BYTE, offset + 2, color.toByte())
}

internal fun readRgb(segment: MemorySegment, offset: Long = 0): Int =
    ((segment.get(JAVA_BYTE, offset).toInt() and 0xFF) shl 16) or
        ((segment.get(JAVA_BYTE, offset + 1).toInt() and 0xFF) shl 8) or
        (segment.get(JAVA_BYTE, offset + 2).toInt() and 0xFF)
