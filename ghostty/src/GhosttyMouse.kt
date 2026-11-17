package org.example.kotlintest.ghostty

import org.example.kotlintest.ghostty.ffi.GhosttyMouseEncoderSize
import org.example.kotlintest.ghostty.ffi.GhosttyMousePosition
import org.example.kotlintest.ghostty.ffi.GhosttyVt
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.MemorySegment.NULL
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BOOLEAN
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_LONG

enum class MouseAction { Press, Release, Motion }

/** The buttons a terminal can be told about. The wheel is two of them: a notch is a press. */
enum class MouseButton { Left, Right, Middle, WheelUp, WheelDown }

/**
 * Something the pointer did over a terminal.
 *
 * @param button the button pressed or released, or the one held during a motion, if any
 * @param x where, in pixels from the terminal's left edge
 * @param y in pixels from its top edge
 * @param cellWidth the size of a character cell as drawn, in pixels, which turns the position into a cell
 * @param anyButtonDown whether any button is down after this event, which some programs ask to be told
 */
data class MouseInput(
    val action: MouseAction,
    val button: MouseButton?,
    val x: Float,
    val y: Float,
    val cellWidth: Int,
    val cellHeight: Int,
    val shift: Boolean = false,
    val control: Boolean = false,
    val alt: Boolean = false,
    val anyButtonDown: Boolean = action == MouseAction.Press,
)

/**
 * Turns pointer events into the bytes a terminal's program asked for. A program says whether
 * it wants the mouse at all, which events, and in what form, by setting terminal modes;
 * Neovim, htop and tmux all do. libghostty-vt keeps those modes, so it does the encoding.
 *
 * One of these serves one terminal: it remembers the cell last moved into, so that moving
 * within a cell says nothing twice. Not thread-safe, like the terminal it reads.
 */
class GhosttyMouse : AutoCloseable {
    private val arena = Arena.ofConfined()
    private val encoder: MemorySegment
    private val event: MemorySegment
    private val size = GhosttyMouseEncoderSize.allocate(arena)
    private val position = GhosttyMousePosition.allocate(arena)
    private val flag = arena.allocate(JAVA_BOOLEAN)
    private val buffer = arena.allocate(BUFFER_BYTES)
    private val written = arena.allocate(JAVA_LONG)

    init {
        GhosttyLibrary.ensureLoaded()
        val out = arena.allocate(ADDRESS)
        check("mouse_encoder_new", GhosttyVt.ghostty_mouse_encoder_new(NULL, out))
        encoder = out.get(ADDRESS, 0)
        check("mouse_event_new", GhosttyVt.ghostty_mouse_event_new(NULL, out))
        event = out.get(ADDRESS, 0)
    }

    // The cell of the last motion reported. The encoder can remember this itself, but it
    // forgets whenever its options are set, and they are set afresh for every event.
    private var lastMotionCell = -1L

    /**
     * What to send the program in [terminal] for [input]. Empty when it wants nothing: it
     * isn't tracking the mouse, or not this kind of event, or the pointer is still in the
     * cell last reported.
     */
    fun encode(terminal: GhosttyTerminal, input: MouseInput): ByteArray {
        if (input.cellWidth <= 0 || input.cellHeight <= 0) return EMPTY
        if (input.action == MouseAction.Motion) {
            val cell = ((input.y / input.cellHeight).toLong() shl 32) or ((input.x / input.cellWidth).toLong() and 0xFFFFFFFFL)
            if (cell == lastMotionCell) return EMPTY
            lastMotionCell = cell
        } else {
            lastMotionCell = -1L
        }
        // The program may have changed what it wants since the last event.
        GhosttyVt.ghostty_mouse_encoder_setopt_from_terminal(encoder, terminal.handle)

        GhosttyMouseEncoderSize.size(size, GhosttyMouseEncoderSize.sizeof())
        GhosttyMouseEncoderSize.screen_width(size, terminal.columns * input.cellWidth)
        GhosttyMouseEncoderSize.screen_height(size, terminal.rows * input.cellHeight)
        GhosttyMouseEncoderSize.cell_width(size, input.cellWidth)
        GhosttyMouseEncoderSize.cell_height(size, input.cellHeight)
        GhosttyMouseEncoderSize.padding_top(size, 0)
        GhosttyMouseEncoderSize.padding_bottom(size, 0)
        GhosttyMouseEncoderSize.padding_left(size, 0)
        GhosttyMouseEncoderSize.padding_right(size, 0)
        GhosttyVt.ghostty_mouse_encoder_setopt(encoder, GhosttyVt.GHOSTTY_MOUSE_ENCODER_OPT_SIZE(), size)
        flag.set(JAVA_BOOLEAN, 0, input.anyButtonDown)
        GhosttyVt.ghostty_mouse_encoder_setopt(encoder, GhosttyVt.GHOSTTY_MOUSE_ENCODER_OPT_ANY_BUTTON_PRESSED(), flag)

        GhosttyVt.ghostty_mouse_event_set_action(
            event,
            when (input.action) {
                MouseAction.Press -> GhosttyVt.GHOSTTY_MOUSE_ACTION_PRESS()
                MouseAction.Release -> GhosttyVt.GHOSTTY_MOUSE_ACTION_RELEASE()
                MouseAction.Motion -> GhosttyVt.GHOSTTY_MOUSE_ACTION_MOTION()
            },
        )
        when (input.button) {
            null -> GhosttyVt.ghostty_mouse_event_clear_button(event)
            MouseButton.Left -> GhosttyVt.ghostty_mouse_event_set_button(event, GhosttyVt.GHOSTTY_MOUSE_BUTTON_LEFT())
            MouseButton.Right -> GhosttyVt.ghostty_mouse_event_set_button(event, GhosttyVt.GHOSTTY_MOUSE_BUTTON_RIGHT())
            MouseButton.Middle -> GhosttyVt.ghostty_mouse_event_set_button(event, GhosttyVt.GHOSTTY_MOUSE_BUTTON_MIDDLE())
            MouseButton.WheelUp -> GhosttyVt.ghostty_mouse_event_set_button(event, GhosttyVt.GHOSTTY_MOUSE_BUTTON_FOUR())
            MouseButton.WheelDown -> GhosttyVt.ghostty_mouse_event_set_button(event, GhosttyVt.GHOSTTY_MOUSE_BUTTON_FIVE())
        }
        val mods = (if (input.shift) GhosttyVt.GHOSTTY_MODS_SHIFT() else 0) or
            (if (input.control) GhosttyVt.GHOSTTY_MODS_CTRL() else 0) or
            (if (input.alt) GhosttyVt.GHOSTTY_MODS_ALT() else 0)
        GhosttyVt.ghostty_mouse_event_set_mods(event, mods.toShort())
        GhosttyMousePosition.x(position, input.x)
        GhosttyMousePosition.y(position, input.y)
        GhosttyVt.ghostty_mouse_event_set_position(event, position)

        check("mouse_encoder_encode", GhosttyVt.ghostty_mouse_encoder_encode(encoder, event, buffer, BUFFER_BYTES, written))
        val length = written.get(JAVA_LONG, 0)
        return if (length == 0L) EMPTY else buffer.asSlice(0, length).toArray(JAVA_BYTE)
    }

    override fun close() {
        GhosttyVt.ghostty_mouse_event_free(event)
        GhosttyVt.ghostty_mouse_encoder_free(encoder)
        arena.close()
    }

    private companion object {
        /** The longest report, pixel coordinates in SGR form, is under thirty bytes. */
        const val BUFFER_BYTES = 64L
        val EMPTY = ByteArray(0)
    }
}
