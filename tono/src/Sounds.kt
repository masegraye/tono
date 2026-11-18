package org.example.kotlintest

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.kotlintest.rex.ActivityState
import org.example.kotlintest.rex.nowMillis

/**
 * The sound for something a program reported, by its name in the desktop's sound theme, so
 * it is whatever the desktop plays for a warning, an error, or something finishing. Null
 * for states that aren't news.
 */
internal fun soundFor(state: ActivityState): String? = when (state) {
    ActivityState.Blocked -> "dialog-warning" // it is waiting for you
    ActivityState.Error -> "dialog-error"
    ActivityState.Done -> "complete"
    else -> null
}

/**
 * Lets one sound through for a burst of news. Several programs often report at once, and a
 * chorus says less than a single sound: what arrives within [gatherMillis] of the first is
 * weighed together and the most pressing wins (waiting for you, then an error, then done);
 * anything after that, until [quietMillis] have passed, is dropped rather than played late.
 *
 * Used from one thread: the one the app's interface runs on.
 */
internal class SoundGate(private val gatherMillis: Long = 100, private val quietMillis: Long = 1_000) {
    private var pending: ActivityState? = null
    private var startedAt = Long.MIN_VALUE / 2

    /** Takes one piece of news. True if this starts a burst: wait [gatherMillis], then [take]. */
    fun offer(state: ActivityState, nowMillis: Long): Boolean {
        val sinceStart = nowMillis - startedAt
        if (sinceStart < quietMillis) {
            if (sinceStart < gatherMillis && pending != null) pending = maxOf(pending!!, state)
            return false
        }
        startedAt = nowMillis
        pending = state
        return true
    }

    /** What the burst came to. */
    fun take(): ActivityState? = pending.also { pending = null }
}

/** Plays a sound when a program in a tab you aren't looking at wants you, fails, or finishes. */
class Sounds(private val scope: CoroutineScope, private val log: (message: String, isError: Boolean) -> Unit) {
    private val gate = SoundGate()

    /** Asks for the sound for [state]. Returns at once; the sound, if this one gets through, follows. */
    fun request(state: ActivityState) {
        if (soundFor(state) == null || !gate.offer(state, nowMillis())) return
        scope.launch {
            delay(GATHER_MILLIS)
            gate.take()?.let(::soundFor)?.let { playSound(it, log) }
        }
    }

    private companion object {
        const val GATHER_MILLIS = 100L
    }
}
