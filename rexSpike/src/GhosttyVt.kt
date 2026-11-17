package org.example.kotlintest.rexspike

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BOOLEAN
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle
import java.nio.file.Path

/**
 * A terminal emulated by libghostty-vt, Ghostty's terminal core, called through Java's
 * foreign function API. Not thread-safe: use a terminal from one thread at a time.
 */
class GhosttyTerminal private constructor(private val handle: MemorySegment) : AutoCloseable {

    companion object {
        /**
         * Restores the terminal from the snapshot at the start of [bytes]. Returns it with
         * the number of bytes the snapshot occupied; anything after that is live output.
         */
        fun restore(bytes: ByteArray, length: Int = bytes.size): Pair<GhosttyTerminal, Int> =
            Arena.ofConfined().use { arena ->
                val buffer = arena.allocate(length.toLong())
                MemorySegment.copy(bytes, 0, buffer, JAVA_BYTE, 0, length)

                val decoderOut = arena.allocate(ADDRESS)
                check("snapshot_decoder_new_buf", Lib.snapshotDecoderNewBuf(NULL, decoderOut, buffer, length.toLong()))
                val decoder = decoderOut.get(ADDRESS, 0)
                try {
                    val terminalOut = arena.allocate(ADDRESS)
                    check("snapshot_decoder_decode", Lib.snapshotDecoderDecode(decoder, terminalOut))
                    val consumedOut = arena.allocate(JAVA_LONG)
                    check("snapshot_decoder_get", Lib.snapshotDecoderGet(decoder, DECODER_DATA_SOURCE_OFFSET, consumedOut))
                    GhosttyTerminal(terminalOut.get(ADDRESS, 0)) to consumedOut.get(JAVA_LONG, 0).toInt()
                } finally {
                    Lib.snapshotDecoderFree(decoder)
                }
            }
    }

    val columns: Int get() = readShort(TERMINAL_DATA_COLS)
    val rows: Int get() = readShort(TERMINAL_DATA_ROWS)
    val cursorColumn: Int get() = readShort(TERMINAL_DATA_CURSOR_X)
    val cursorRow: Int get() = readShort(TERMINAL_DATA_CURSOR_Y)

    /** Feeds program output (text and escape sequences) to the terminal. */
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        if (length == 0) return
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(length.toLong())
            MemorySegment.copy(bytes, offset, buffer, JAVA_BYTE, 0, length)
            Lib.terminalVtWrite(handle, buffer, length.toLong())
        }
    }

    /** The terminal's contents, scrollback included, as plain text. */
    fun text(): String = Arena.ofConfined().use { arena ->
        // Sized structs: each carries its own sizeof so the library can evolve them.
        val options = arena.allocate(FORMATTER_OPTIONS)
        options.set(JAVA_LONG, 0, FORMATTER_OPTIONS.byteSize())
        options.set(JAVA_INT, 8, FORMATTER_FORMAT_PLAIN)
        options.set(JAVA_BOOLEAN, 13, true) // trim trailing whitespace
        options.set(JAVA_LONG, 16, TERMINAL_EXTRA.byteSize())
        options.set(JAVA_LONG, 32, SCREEN_EXTRA.byteSize())

        val formatterOut = arena.allocate(ADDRESS)
        check("formatter_terminal_new", Lib.formatterTerminalNew(NULL, formatterOut, handle, options))
        val formatter = formatterOut.get(ADDRESS, 0)
        try {
            val pointerOut = arena.allocate(ADDRESS)
            val lengthOut = arena.allocate(JAVA_LONG)
            check("formatter_format_alloc", Lib.formatterFormatAlloc(formatter, NULL, pointerOut, lengthOut))
            val length = lengthOut.get(JAVA_LONG, 0)
            val pointer = pointerOut.get(ADDRESS, 0)
            try {
                String(pointer.reinterpret(length).toArray(JAVA_BYTE), Charsets.UTF_8)
            } finally {
                Lib.free(NULL, pointer, length)
            }
        } finally {
            Lib.formatterFree(formatter)
        }
    }

    override fun close() = Lib.terminalFree(handle)

    private fun readShort(data: Int): Int = Arena.ofConfined().use { arena ->
        val out = arena.allocate(JAVA_SHORT)
        check("terminal_get", Lib.terminalGet(handle, data, out))
        out.get(JAVA_SHORT, 0).toInt() and 0xFFFF
    }
}

class GhosttyException(call: String, code: Int) :
    RuntimeException("ghostty_$call failed: ${RESULT_NAMES[code] ?: "result $code"}")

private fun check(call: String, result: Int) {
    if (result != 0) throw GhosttyException(call, result)
}

private val NULL: MemorySegment = MemorySegment.NULL

private val RESULT_NAMES = mapOf(
    -1 to "out of memory", -2 to "invalid value", -3 to "out of space", -4 to "no value",
    -5 to "I/O error", -6 to "limit exceeded", -7 to "rejected",
)

// Enum values from ghostty/vt/{snapshot,terminal,formatter}.h
private const val DECODER_DATA_SOURCE_OFFSET = 2
private const val TERMINAL_DATA_COLS = 1
private const val TERMINAL_DATA_ROWS = 2
private const val TERMINAL_DATA_CURSOR_X = 3
private const val TERMINAL_DATA_CURSOR_Y = 4
private const val FORMATTER_FORMAT_PLAIN = 0

// Struct layouts, checked against the C compiler's sizeof/offsetof for the pinned headers.
private val SCREEN_EXTRA: MemoryLayout = MemoryLayout.structLayout(
    JAVA_LONG.withName("size"),
    MemoryLayout.sequenceLayout(6, JAVA_BOOLEAN), // cursor, style, hyperlink, protection, kitty_keyboard, charsets
    MemoryLayout.paddingLayout(2),
)
private val TERMINAL_EXTRA: MemoryLayout = MemoryLayout.structLayout(
    JAVA_LONG.withName("size"),
    MemoryLayout.sequenceLayout(6, JAVA_BOOLEAN), // palette, modes, scrolling_region, tabstops, pwd, keyboard
    MemoryLayout.paddingLayout(2),
    SCREEN_EXTRA.withName("screen"),
)
private val FORMATTER_OPTIONS: MemoryLayout = MemoryLayout.structLayout(
    JAVA_LONG.withName("size"),
    JAVA_INT.withName("emit"),
    JAVA_BOOLEAN.withName("unwrap"),
    JAVA_BOOLEAN.withName("trim"),
    MemoryLayout.paddingLayout(2),
    TERMINAL_EXTRA.withName("extra"),
    ADDRESS.withName("selection"),
)

/** The C functions this spike calls. `GHOSTTY_VT_LIB` overrides where the library is loaded from. */
private object Lib {
    private val linker = Linker.nativeLinker()
    private val symbols: SymbolLookup = SymbolLookup.libraryLookup(libraryPath(), Arena.global())

    private fun libraryPath(): Path =
        Path.of(System.getenv("GHOSTTY_VT_LIB") ?: "native/out/lib/libghostty-vt.so").toAbsolutePath()

    private fun function(name: String, descriptor: FunctionDescriptor): MethodHandle {
        val address = symbols.find("ghostty_$name").orElseThrow { UnsatisfiedLinkError("ghostty_$name") }
        return linker.downcallHandle(address, descriptor)
    }

    private val snapshotDecoderNewBufHandle = function(
        "snapshot_decoder_new_buf", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG),
    )
    private val snapshotDecoderDecodeHandle = function(
        "snapshot_decoder_decode", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
    )
    private val snapshotDecoderGetHandle = function(
        "snapshot_decoder_get", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
    )
    private val snapshotDecoderFreeHandle = function("snapshot_decoder_free", FunctionDescriptor.ofVoid(ADDRESS))
    private val terminalVtWriteHandle = function(
        "terminal_vt_write", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG),
    )
    private val terminalGetHandle = function("terminal_get", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS))
    private val terminalFreeHandle = function("terminal_free", FunctionDescriptor.ofVoid(ADDRESS))
    private val formatterTerminalNewHandle = function(
        "formatter_terminal_new", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, FORMATTER_OPTIONS),
    )
    private val formatterFormatAllocHandle = function(
        "formatter_format_alloc", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
    )
    private val formatterFreeHandle = function("formatter_free", FunctionDescriptor.ofVoid(ADDRESS))
    private val freeHandle = function("free", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG))

    // invokeWithArguments boxes every call. That is fine for a spike; a real client should
    // generate exact-typed bindings (jextract, or Java sources) for the hot paths.
    fun snapshotDecoderNewBuf(allocator: MemorySegment, decoderOut: MemorySegment, data: MemorySegment, length: Long) =
        snapshotDecoderNewBufHandle.invokeWithArguments(allocator, decoderOut, data, length) as Int
    fun snapshotDecoderDecode(decoder: MemorySegment, terminalOut: MemorySegment) =
        snapshotDecoderDecodeHandle.invokeWithArguments(decoder, terminalOut) as Int
    fun snapshotDecoderGet(decoder: MemorySegment, data: Int, out: MemorySegment) =
        snapshotDecoderGetHandle.invokeWithArguments(decoder, data, out) as Int
    fun snapshotDecoderFree(decoder: MemorySegment) { snapshotDecoderFreeHandle.invokeWithArguments(decoder) }
    fun terminalVtWrite(terminal: MemorySegment, data: MemorySegment, length: Long) {
        terminalVtWriteHandle.invokeWithArguments(terminal, data, length)
    }
    fun terminalGet(terminal: MemorySegment, data: Int, out: MemorySegment) =
        terminalGetHandle.invokeWithArguments(terminal, data, out) as Int
    fun terminalFree(terminal: MemorySegment) { terminalFreeHandle.invokeWithArguments(terminal) }
    fun formatterTerminalNew(allocator: MemorySegment, formatterOut: MemorySegment, terminal: MemorySegment, options: MemorySegment) =
        formatterTerminalNewHandle.invokeWithArguments(allocator, formatterOut, terminal, options) as Int
    fun formatterFormatAlloc(formatter: MemorySegment, allocator: MemorySegment, pointerOut: MemorySegment, lengthOut: MemorySegment) =
        formatterFormatAllocHandle.invokeWithArguments(formatter, allocator, pointerOut, lengthOut) as Int
    fun formatterFree(formatter: MemorySegment) { formatterFreeHandle.invokeWithArguments(formatter) }
    fun free(allocator: MemorySegment, pointer: MemorySegment, length: Long) {
        freeHandle.invokeWithArguments(allocator, pointer, length)
    }
}

/**
 * The length of the complete snapshot at the start of [buffer], or null while more bytes
 * are needed. A snapshot is a 10-byte envelope (`GHOSTSNP` and a version) followed by
 * records of `tag: u16, length: u32, crc: u32, payload`, ending with a FINISH record.
 */
fun snapshotLength(buffer: ByteArray, size: Int): Int? {
    if (size < SNAPSHOT_ENVELOPE_BYTES) return null
    require(String(buffer, 0, 8, Charsets.US_ASCII) == "GHOSTSNP") { "The stream doesn't start with a Ghostty snapshot" }
    var position = SNAPSHOT_ENVELOPE_BYTES
    while (size - position >= RECORD_HEADER_BYTES) {
        val tag = littleEndian(buffer, position, 2).toInt()
        val payloadLength = littleEndian(buffer, position + 2, 4)
        val end = position + RECORD_HEADER_BYTES + payloadLength
        if (end > size) return null
        position = end.toInt()
        if (tag == RECORD_TAG_FINISH) return position
    }
    return null
}

/** The snapshot format version from the envelope. */
fun snapshotVersion(buffer: ByteArray): Int = littleEndian(buffer, 8, 2).toInt()

private const val SNAPSHOT_ENVELOPE_BYTES = 10
private const val RECORD_HEADER_BYTES = 10
private const val RECORD_TAG_FINISH = 6

internal fun littleEndian(bytes: ByteArray, offset: Int, count: Int): Long {
    var value = 0L
    for (index in count - 1 downTo 0) value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
    return value
}
