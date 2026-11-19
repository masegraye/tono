package org.example.kotlintest.rex

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.example.kotlintest.ghostty.GhosttySearch
import org.example.kotlintest.ghostty.GhosttyTerminal
import org.example.kotlintest.ghostty.SearchHighlights

/**
 * A search of one terminal's screen and history, as it stands: what is looked for, and
 * what has been found. There is one while the terminal's search is open.
 *
 * Letters of the English alphabet match in either case, and everything else as written:
 * that is how libghostty-vt searches, and Rex Rune with it.
 */
data class TerminalSearch(
    val query: String,
    /** Goes up each time the search is asked for, which is when its field should take the keyboard. */
    val opened: Int = 0,
    /** How many matches have been found, so far if not [complete]. */
    val total: Int = 0,
    /** Which match is selected, counting from 0 for the newest, or null when none is. */
    val selected: Int? = null,
    /** Whether the whole history has been looked through. */
    val complete: Boolean = true,
    /** The matches on screen, to mark. */
    val highlights: SearchHighlights = SearchHighlights.None,
) {
    /** The selected match's place among them all, as the search bar says it: "3 of 17". Empty when nothing is looked for. */
    val count: String
        get() = when {
            query.isEmpty() -> ""
            total == 0 -> if (complete) "No matches" else "Searching…"
            selected != null -> "${selected + 1} of $total"
            else -> if (total == 1) "1 match" else "$total matches"
        }
}

/**
 * Runs a terminal's search and publishes what it finds into [state], which whoever opened
 * the search has already set: this fills in the results, and only while the state still
 * asks for the text they are for.
 *
 * Used on the terminal's thread, like the terminal. [refresh] is to be called after anything
 * that changes the terminal or scrolls its view, which is also what picks up new output.
 */
internal class TerminalSearching(private val state: MutableStateFlow<TerminalSearch?>, private val now: () -> Long = ::nowMillis) {
    private var search: GhosttySearch? = null
    private var query = ""

    /** Whether the newest match is to be selected as soon as there is one, as it is after the text changes. */
    private var selectNewest = false

    /** Whether there is history not yet looked through, so that [refresh] should be called again soon. */
    var behind = false
        private set

    /** Looks for [query] from now on, or for nothing when it is null, which ends the search. */
    fun find(terminal: GhosttyTerminal, query: String?) {
        if (query == null) return close()
        val search = search ?: GhosttySearch(terminal).also { search = it }
        this.query = query
        search.setNeedle(query)
        selectNewest = true
    }

    /** Selects the next match, which is the next older one, or the previous. The view scrolls to it. */
    fun step(forward: Boolean) {
        val search = search ?: return
        if (if (forward) search.selectNext() else search.selectPrevious()) selectNewest = false
    }

    /** Takes in what the terminal holds now, looks through more of it for a few milliseconds at most, and publishes the results. */
    fun refresh() {
        val search = search ?: return
        val deadline = now() + BUDGET_MILLIS
        var complete = search.advance()
        while (!complete && now() < deadline) complete = search.advance()
        if (selectNewest && search.selected == null && search.total > 0 && search.selectNext()) complete = search.advance()
        if (search.selected != null) selectNewest = false
        behind = !complete
        val total = search.total
        val selected = search.selected
        val highlights = search.highlights()
        state.update { current ->
            if (current?.query != query) current
            else current.copy(total = total, selected = selected, complete = complete, highlights = highlights)
        }
    }

    /** Ends the search. To be called before the terminal is closed. */
    fun close() {
        search?.close()
        search = null
        behind = false
    }

    private companion object {
        /** How long one [refresh] looks through history before letting the terminal be drawn. */
        const val BUDGET_MILLIS = 6L
    }
}
