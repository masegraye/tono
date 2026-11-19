package org.example.kotlintest.rex

import kotlinx.serialization.json.JsonObject

/*
 * Two ways a program in a terminal says something without printing it. Ringing the bell asks
 * for attention: a build that finished, a prompt that is waiting. OSC 9;4 says how far along
 * it is, as a download or a compiler does. The server tells every client about both, with the
 * terminal events `bell` and `progress_report`, and each client decides what to show.
 *
 * What is shown follows Rex Rune (`src/app/activity.rs`): a bell marks its tab only if the
 * tab was out of sight when it rang, and until the tab is next looked at; progress is the
 * tab's, taken from whichever of its panes has the most to say.
 *
 * The server keeps neither. A client that connects later is not told of a bell that rang
 * before, nor of progress reported before, until the program reports again.
 */

/**
 * The progress a program last reported with OSC 9;4.
 *
 * @param percent how far along it is, from 0 to 100, or null if it didn't say
 */
data class TerminalProgress(val kind: Kind, val percent: Int? = null) {
    /** In order of how much each wants attention, least first. */
    enum class Kind {
        /** Busy, with no saying how far along. */
        Indeterminate,
        /** Working, and this far along. */
        Normal,
        Paused,
        /** Something went wrong, perhaps this far along. */
        Error,
    }

    companion object {
        /**
         * Reads a `progress_report` event's own fields. Null when the program has stopped
         * reporting progress, and for a kind of report this client doesn't know, which
         * shows nothing sooner than something wrong.
         */
        fun parse(fields: JsonObject?): TerminalProgress? {
            // The server passes on whatever number the program wrote; only 0 to 100 means anything.
            val percent = fields?.long("progress")?.takeIf { it in 0..100 }?.toInt()
            return when (fields?.string("state")) {
                "set" -> TerminalProgress(Kind.Normal, percent)
                "indeterminate" -> TerminalProgress(Kind.Indeterminate)
                "pause" -> TerminalProgress(Kind.Paused, percent)
                "error" -> TerminalProgress(Kind.Error, percent)
                else -> null
            }
        }
    }
}

/**
 * A tab's progress: that of the pane with the most to say. Trouble comes before a pause and
 * a pause before work going well, and a pane that says how far along it is comes before one
 * that doesn't.
 */
fun HostState.progressOf(window: WindowView): TerminalProgress? =
    window.panes.mapNotNull { progress[it.blockId] }.maxWithOrNull(compareBy({ it.kind }, { it.percent != null }))

/** Whether a program in a tab rang the bell while the tab was out of sight, and it hasn't been looked at since. */
fun HostState.hasBell(window: WindowView): Boolean = window.id in bells

/**
 * Whether a tab has anything to show beyond its name: a program that says what it is doing,
 * a bell not yet seen, or progress.
 */
fun HostState.hasNews(window: WindowView): Boolean =
    activityOf(window) != ActivityState.Idle || hasBell(window) || progressOf(window) != null

/**
 * The state after a program rang the bell in a terminal. The terminal's tab is marked,
 * unless it is the tab being looked at: then the user was there to hear it.
 *
 * @param inFront whether this host's tab on screen is in front of the user: it is the host
 *   showing, in a window that has focus
 */
internal fun HostState.rang(sessionId: String, blockId: String, inFront: Boolean): HostState {
    val tab = views[sessionId]?.windows?.firstOrNull { tab -> tab.panes.any { it.blockId == blockId } } ?: return this
    val watched = inFront && sessionId == selectedSessionId && tab.id == window?.id
    return if (watched || tab.id in bells) this else copy(bells = bells + tab.id)
}

/** The state after a `progress_report` event from a terminal, given the event's own fields. */
internal fun HostState.reported(blockId: String, fields: JsonObject?): HostState {
    val now = TerminalProgress.parse(fields)
    return when {
        now == progress[blockId] -> this
        now == null -> copy(progress = progress - blockId)
        else -> copy(progress = progress + (blockId to now))
    }
}

/**
 * The state once the user has seen what is on screen: the bell of the tab in front is
 * forgotten, and so is the bell of any tab that is no longer there.
 *
 * @param inFront as for [rang]
 */
internal fun HostState.looked(inFront: Boolean): HostState {
    if (bells.isEmpty()) return this
    val seen = if (inFront) window?.id else null
    val there = views.values.flatMapTo(HashSet()) { view -> view.windows.map { it.id } }
    val left = bells.filterTo(LinkedHashSet()) { it != seen && it in there }
    return if (left.size == bells.size) this else copy(bells = left)
}
