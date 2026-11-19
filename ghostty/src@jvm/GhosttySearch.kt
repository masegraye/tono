package org.example.kotlintest.ghostty

import org.example.kotlintest.ghostty.ffi.GhosttyPointCoordinate
import org.example.kotlintest.ghostty.ffi.GhosttySelectionBuffer
import org.example.kotlintest.ghostty.ffi.GhosttyString
import org.example.kotlintest.ghostty.ffi.GhosttyVt
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import org.example.kotlintest.ghostty.ffi.GhosttySelection as Match

actual class GhosttySearch actual constructor(private val terminal: GhosttyTerminal) : AutoCloseable {
    private val arena = Arena.ofConfined()
    private val search: MemorySegment

    // Reused for every call.
    private val number = arena.allocate(JAVA_LONG)
    private val one = Match.allocate(arena)
    private val buffer = GhosttySelectionBuffer.allocate(arena)
    private val coordinate = GhosttyPointCoordinate.allocate(arena)

    /** Room for the matches near the view, grown when there are more. */
    private var matchesArena = Arena.ofConfined()
    private var matches = Match.allocateArray(INITIAL_MATCHES, matchesArena)
    private var matchesCapacity = INITIAL_MATCHES

    init {
        GhosttyLibrary.ensureLoaded()
        val out = arena.allocate(ADDRESS)
        check("search_new", GhosttyVt.ghostty_search_new(NULL, out, terminal.handle))
        search = out.get(ADDRESS, 0)
    }

    actual fun setNeedle(text: String) {
        val bytes = text.toByteArray()
        if (bytes.isEmpty()) {
            check("search_set", GhosttyVt.ghostty_search_set(search, GhosttyVt.GHOSTTY_SEARCH_OPT_NEEDLE(), NULL))
            return
        }
        Arena.ofConfined().use { scratch ->
            val data = scratch.allocate(bytes.size.toLong())
            MemorySegment.copy(bytes, 0, data, JAVA_BYTE, 0, bytes.size)
            val needle = GhosttyString.allocate(scratch)
            GhosttyString.ptr(needle, data)
            GhosttyString.len(needle, bytes.size.toLong())
            check("search_set", GhosttyVt.ghostty_search_set(search, GhosttyVt.GHOSTTY_SEARCH_OPT_NEEDLE(), needle))
        }
    }

    actual fun advance(): Boolean {
        check("search_feed", GhosttyVt.ghostty_search_feed(search))
        // Looking through what was just taken in. It stops by itself when it wants more.
        var ticks = 0
        while (status() == GhosttyVt.GHOSTTY_SEARCH_STATUS_RUNNING() && ticks++ < MAX_TICKS) {
            check("search_tick", GhosttyVt.ghostty_search_tick(search, NULL))
        }
        return status() == GhosttyVt.GHOSTTY_SEARCH_STATUS_COMPLETE()
    }

    actual fun selectNext(): Boolean = select(GhosttyVt.GHOSTTY_SEARCH_OPT_SELECT_NEXT())

    actual fun selectPrevious(): Boolean = select(GhosttyVt.GHOSTTY_SEARCH_OPT_SELECT_PREV())

    private fun select(option: Int): Boolean = when (val result = GhosttyVt.ghostty_search_set(search, option, NULL)) {
        GhosttyVt.GHOSTTY_SUCCESS() -> true
        GhosttyVt.GHOSTTY_NO_VALUE() -> false
        else -> { check("search_set", result); false }
    }

    actual val total: Int
        get() {
            check("search_get", GhosttyVt.ghostty_search_get(search, GhosttyVt.GHOSTTY_SEARCH_DATA_TOTAL_MATCHES(), number))
            return number.get(JAVA_LONG, 0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

    actual val selected: Int?
        get() = when (val result = GhosttyVt.ghostty_search_get(search, GhosttyVt.GHOSTTY_SEARCH_DATA_SELECTED_INDEX(), number)) {
            GhosttyVt.GHOSTTY_SUCCESS() -> number.get(JAVA_LONG, 0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            GhosttyVt.GHOSTTY_NO_VALUE() -> null
            else -> { check("search_get", result); null }
        }

    actual fun highlights(): SearchHighlights {
        val columns = terminal.columns
        val rows = terminal.rows
        Match.size(one, Match.sizeof())
        val selected = if (GhosttyVt.ghostty_search_get(search, GhosttyVt.GHOSTTY_SEARCH_DATA_SELECTED_MATCH(), one) == GhosttyVt.GHOSTTY_SUCCESS()) {
            cellsOf(one, columns, rows)
        } else emptyList()

        val others = ArrayList<SelectedCells>()
        for (index in 0 until readMatchesNearView()) {
            val cells = cellsOf(Match.asSlice(matches, index.toLong()), columns, rows)
            // The selected match is among them, and is marked in its own way.
            if (cells != selected) others += cells
        }
        return SearchHighlights(others, selected)
    }

    /** Fills [matches] with the matches on and around the view, and returns how many there are. */
    private fun readMatchesNearView(): Int {
        while (true) {
            for (index in 0 until matchesCapacity) Match.size(Match.asSlice(matches, index), Match.sizeof())
            GhosttySelectionBuffer.ptr(buffer, matches)
            GhosttySelectionBuffer.cap(buffer, matchesCapacity)
            GhosttySelectionBuffer.len(buffer, 0)
            val result = GhosttyVt.ghostty_search_get(search, GhosttyVt.GHOSTTY_SEARCH_DATA_VIEWPORT_MATCHES(), buffer)
            val length = GhosttySelectionBuffer.len(buffer)
            when (result) {
                GhosttyVt.GHOSTTY_SUCCESS() -> return length.toInt()
                GhosttyVt.GHOSTTY_OUT_OF_SPACE() -> {
                    if (length <= matchesCapacity) return 0 // it asked for no more room than it had
                    matchesArena.close()
                    matchesArena = Arena.ofConfined()
                    matchesCapacity = length * 2
                    matches = Match.allocateArray(matchesCapacity, matchesArena)
                }
                else -> return 0
            }
        }
    }

    /** A match's cells in the view. Its two ends are places in the terminal, good until the terminal next changes. */
    private fun cellsOf(match: MemorySegment, columns: Int, rows: Int): List<SelectedCells> {
        val start = inView(Match.start(match))
        val end = inView(Match.end(match))
        // A match is given first end first, but nothing promises it.
        val (first, last) = if (start != null && end != null && (start.second > end.second || (start.second == end.second && start.first > end.first))) end to start else start to end
        return cellsBetween(first?.first, first?.second, last?.first, last?.second, columns, rows)
    }

    /** The column and row of the view a place in the terminal is at, or null when it is out of view. */
    private fun inView(place: MemorySegment): Pair<Int, Int>? {
        if (GhosttyVt.ghostty_terminal_point_from_grid_ref(terminal.handle, place, GhosttyVt.GHOSTTY_POINT_TAG_VIEWPORT(), coordinate) != GhosttyVt.GHOSTTY_SUCCESS()) return null
        return (GhosttyPointCoordinate.x(coordinate).toInt() and 0xFFFF) to GhosttyPointCoordinate.y(coordinate)
    }

    private fun status(): Int {
        check("search_get", GhosttyVt.ghostty_search_get(search, GhosttyVt.GHOSTTY_SEARCH_DATA_STATUS(), number))
        return number.get(JAVA_INT, 0)
    }

    actual override fun close() {
        GhosttyVt.ghostty_search_free(search)
        matchesArena.close()
        arena.close()
    }

    private companion object {
        const val INITIAL_MATCHES = 64L
        /** How much looking one [advance] does before it lets something else happen. */
        const val MAX_TICKS = 64
    }
}
