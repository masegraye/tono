package org.example.kotlintest.ghostty

import org.example.kotlintest.ghostty.ffi.GhosttyGridRef
import org.example.kotlintest.ghostty.ffi.GhosttyPoint
import org.example.kotlintest.ghostty.ffi.GhosttyPointCoordinate
import org.example.kotlintest.ghostty.ffi.GhosttyPointValue
import org.example.kotlintest.ghostty.ffi.GhosttySelectionGestureGeometry
import org.example.kotlintest.ghostty.ffi.GhosttySurfacePosition
import org.example.kotlintest.ghostty.ffi.GhosttyTerminalSelectionFormatOptions
import org.example.kotlintest.ghostty.ffi.GhosttyVt
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import org.example.kotlintest.ghostty.ffi.GhosttySelection as Selected

actual class GhosttySelection actual constructor() : AutoCloseable {
    private val arena = Arena.ofConfined()
    private val gesture: MemorySegment
    private val pressed: MemorySegment
    private val dragged: MemorySegment
    private val released: MemorySegment
    private val ticked: MemorySegment

    // Reused for every event.
    private val point = GhosttyPoint.allocate(arena)
    private val cell = GhosttyGridRef.allocate(arena)
    private val position = GhosttySurfacePosition.allocate(arena)
    private val geometry = GhosttySelectionGestureGeometry.allocate(arena)
    private val selected = Selected.allocate(arena)
    private val number = arena.allocate(JAVA_LONG)

    /** The terminal last selected in, which the library needs told when the gesture is freed. */
    private var lastTerminal: GhosttyTerminal? = null

    init {
        GhosttyLibrary.ensureLoaded()
        val out = arena.allocate(ADDRESS)
        check("selection_gesture_new", GhosttyVt.ghostty_selection_gesture_new(NULL, out))
        gesture = out.get(ADDRESS, 0)
        fun event(type: Int): MemorySegment {
            check("selection_gesture_event_new", GhosttyVt.ghostty_selection_gesture_event_new(NULL, out, type))
            return out.get(ADDRESS, 0)
        }
        pressed = event(GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_PRESS())
        dragged = event(GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_DRAG())
        released = event(GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_RELEASE())
        ticked = event(GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_AUTOSCROLL_TICK())
        GhosttyGridRef.size(cell, GhosttyGridRef.sizeof())
        Selected.size(selected, Selected.sizeof())
    }

    actual fun press(terminal: GhosttyTerminal, at: MouseInput, timeMillis: Long) {
        if (!aim(terminal, at)) return
        set(pressed, GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_REF(), cell)
        set(pressed, GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_POSITION(), position)
        // A second press counts as a double click when it is soon enough and within a cell of the first.
        number.set(JAVA_DOUBLE, 0, at.cellWidth.toDouble())
        set(pressed, GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_REPEAT_DISTANCE(), number)
        number.set(JAVA_LONG, 0, timeMillis * 1_000_000)
        set(pressed, GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_TIME_NS(), number)
        number.set(JAVA_LONG, 0, REPEAT_CLICK_MILLIS * 1_000_000)
        set(pressed, GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_REPEAT_INTERVAL_NS(), number)
        // A plain press selects nothing, and so drops what was selected.
        apply(terminal, pressed, clearWhenNone = true)
    }

    actual fun drag(terminal: GhosttyTerminal, at: MouseInput): Boolean {
        if (!aim(terminal, at)) return false
        set(dragged, GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_REF(), cell)
        set(dragged, GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_POSITION(), position)
        set(dragged, GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_GEOMETRY(), geometry)
        // Dragging back to where the press was selects nothing again.
        apply(terminal, dragged, clearWhenNone = true)
        return wantsScrolling(terminal)
    }

    actual fun release(terminal: GhosttyTerminal, at: MouseInput) {
        if (!aim(terminal, at)) return
        set(released, GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_REF(), cell)
        apply(terminal, released, clearWhenNone = false)
    }

    actual fun autoscroll(terminal: GhosttyTerminal, at: MouseInput): Boolean {
        if (!aim(terminal, at)) return false
        set(ticked, GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_VIEWPORT(), GhosttyPointValue.coordinate(GhosttyPoint.value(point)))
        set(ticked, GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_POSITION(), position)
        set(ticked, GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_GEOMETRY(), geometry)
        apply(terminal, ticked, clearWhenNone = false)
        return wantsScrolling(terminal)
    }

    actual fun selectAll(terminal: GhosttyTerminal) {
        if (GhosttyVt.ghostty_terminal_select_all(terminal.handle, selected) == GhosttyVt.GHOSTTY_SUCCESS()) install(terminal, selected)
    }

    actual fun clear(terminal: GhosttyTerminal) = install(terminal, NULL)

    actual fun text(terminal: GhosttyTerminal): String = Arena.ofConfined().use { scratch ->
        val options = scratch.allocate(GhosttyTerminalSelectionFormatOptions.layout())
        GhosttyTerminalSelectionFormatOptions.size(options, GhosttyTerminalSelectionFormatOptions.sizeof())
        GhosttyTerminalSelectionFormatOptions.emit(options, GhosttyVt.GHOSTTY_FORMATTER_FORMAT_PLAIN())
        // As Ghostty copies: a line the terminal wrapped is one line, and trailing blanks are left out.
        GhosttyTerminalSelectionFormatOptions.unwrap(options, true)
        GhosttyTerminalSelectionFormatOptions.trim(options, true)
        GhosttyTerminalSelectionFormatOptions.selection(options, NULL) // the terminal's own
        val pointerOut = scratch.allocate(ADDRESS)
        val lengthOut = scratch.allocate(JAVA_LONG)
        val result = GhosttyVt.ghostty_terminal_selection_format_alloc(terminal.handle, NULL, options, pointerOut, lengthOut)
        if (result == GhosttyVt.GHOSTTY_NO_VALUE()) return ""
        check("terminal_selection_format_alloc", result)
        val pointer = pointerOut.get(ADDRESS, 0)
        val length = lengthOut.get(JAVA_LONG, 0)
        if (pointer == NULL || length == 0L) return ""
        try {
            String(pointer.reinterpret(length).toArray(JAVA_BYTE), Charsets.UTF_8)
        } finally {
            GhosttyVt.ghostty_free(NULL, pointer, length)
        }
    }

    actual override fun close() {
        GhosttyVt.ghostty_selection_gesture_free(gesture, lastTerminal?.handle ?: NULL)
        for (event in listOf(pressed, dragged, released, ticked)) GhosttyVt.ghostty_selection_gesture_event_free(event)
        arena.close()
    }

    /**
     * Fills in where [at] is: the cell under it, the position in pixels, and the terminal's
     * size. A position outside the terminal counts as the nearest cell at its edge, which is
     * what dragging past the edge means. False if the terminal has no such cell.
     */
    private fun aim(terminal: GhosttyTerminal, at: MouseInput): Boolean {
        if (at.cellWidth <= 0 || at.cellHeight <= 0) return false
        val columns = terminal.columns
        val rows = terminal.rows
        if (columns <= 0 || rows <= 0) return false
        lastTerminal = terminal
        GhosttyPoint.tag(point, GhosttyVt.GHOSTTY_POINT_TAG_VIEWPORT())
        val coordinate = GhosttyPointValue.coordinate(GhosttyPoint.value(point))
        GhosttyPointCoordinate.x(coordinate, (at.x / at.cellWidth).toInt().coerceIn(0, columns - 1).toShort())
        GhosttyPointCoordinate.y(coordinate, (at.y / at.cellHeight).toInt().coerceIn(0, rows - 1))
        if (GhosttyVt.ghostty_terminal_grid_ref(terminal.handle, point, cell) != GhosttyVt.GHOSTTY_SUCCESS()) return false
        GhosttySurfacePosition.x(position, at.x.toDouble())
        GhosttySurfacePosition.y(position, at.y.toDouble())
        GhosttySelectionGestureGeometry.columns(geometry, columns)
        GhosttySelectionGestureGeometry.cell_width(geometry, at.cellWidth)
        GhosttySelectionGestureGeometry.padding_left(geometry, 0)
        GhosttySelectionGestureGeometry.screen_height(geometry, rows * at.cellHeight)
        return true
    }

    private fun set(event: MemorySegment, option: Int, value: MemorySegment) =
        check("selection_gesture_event_set", GhosttyVt.ghostty_selection_gesture_event_set(event, option, value))

    /** Gives the gesture an event, and makes what it says is selected the terminal's selection. */
    private fun apply(terminal: GhosttyTerminal, event: MemorySegment, clearWhenNone: Boolean) {
        when (val result = GhosttyVt.ghostty_selection_gesture_event(gesture, terminal.handle, event, selected)) {
            GhosttyVt.GHOSTTY_SUCCESS() -> install(terminal, selected)
            GhosttyVt.GHOSTTY_NO_VALUE() -> if (clearWhenNone) install(terminal, NULL)
            else -> check("selection_gesture_event", result)
        }
    }

    /** The terminal takes the selection over from here, and moves it with its text. */
    private fun install(terminal: GhosttyTerminal, selection: MemorySegment) =
        check("terminal_set", GhosttyVt.ghostty_terminal_set(terminal.handle, GhosttyVt.GHOSTTY_TERMINAL_OPT_SELECTION(), selection))

    private fun wantsScrolling(terminal: GhosttyTerminal): Boolean {
        val result = GhosttyVt.ghostty_selection_gesture_get(gesture, terminal.handle, GhosttyVt.GHOSTTY_SELECTION_GESTURE_DATA_AUTOSCROLL(), number)
        return result == GhosttyVt.GHOSTTY_SUCCESS() && number.get(JAVA_INT, 0) != GhosttyVt.GHOSTTY_SELECTION_GESTURE_AUTOSCROLL_NONE()
    }

    private companion object {
        /** How soon a press has to follow the last to make a double or triple click. */
        const val REPEAT_CLICK_MILLIS = 500L
    }
}
