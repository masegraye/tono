package org.example.kotlintest

import org.example.kotlintest.rex.HostConnection
import org.example.kotlintest.rex.HostState

/**
 * The tabs this window has shown, in the order it showed them, for going back and forward
 * again. The rules are Rex Rune's.
 *
 * What counts as a step is the tab on screen changing, however that came about: a click in
 * the sidebar, a key, the palette, a notification, a new tab opening, or a tab closing under
 * you and its neighbour taking its place. Moving between the panes of a tab is not a step.
 * A tab that has closed is dropped, so going back passes over it; so is a place on a host
 * that isn't connected just now, though that one is kept for when the host is back.
 *
 * Going somewhere new from the middle of the history forgets what was ahead, as a browser
 * does. Going back or forward moves along it and adds nothing.
 *
 * It is this window's alone: no server is told, and it isn't remembered between runs.
 *
 * Getting somewhere can take a moment and pass through other places: showing a tab on
 * another host shows that host first, with whatever tab it had last. So a place that was
 * asked for is [heading]ed for, and what is seen on the way isn't recorded.
 *
 * @param limit how many places are remembered; the oldest go first
 * @param patience how long to wait to arrive where one is heading, in milliseconds
 * @param now a clock, in milliseconds
 */
internal class NavigationHistory(
    private val limit: Int = 128,
    private val patience: Long = 30_000,
    private val now: () -> Long = { 0 },
) {
    private val entries = ArrayList<NavigationStop>()

    /** Where in [entries] the user is; -1 when everything remembered is ahead, or nothing is. */
    private var cursor = -1
    private var heading: Heading? = null

    /** @param origin where the cursor was before setting out, to return to if the place is never reached */
    private class Heading(val to: NavigationStop, val origin: Int, val deadline: Long)

    /** What is remembered, oldest first, and the place among it the user is at. For tests and the debug log. */
    val places: List<NavigationStop> get() = entries.toList()
    val position: Int get() = cursor

    /** Where the user is being taken, if they aren't there yet. */
    val headingFor: NavigationStop? get() = current()?.to

    private fun current(): Heading? {
        val pending = heading ?: return null
        if (now() < pending.deadline) return pending
        giveUp()
        return null
    }

    /** Stops waiting to arrive: the history is as it was before setting out. */
    fun giveUp() {
        heading?.let { cursor = it.origin.coerceIn(-1, entries.lastIndex) }
        heading = null
    }

    /**
     * Says that the user asked to be shown [to], by anything but going back or forward.
     * Until they are there, or [patience] runs out, what [observe] sees is not recorded.
     * A stop with no tab is a session, reached when any tab of it is showing.
     */
    fun heading(to: NavigationStop) {
        val origin = current()?.origin ?: cursor
        // A step back that was still under way is abandoned for this.
        cursor = origin
        heading = Heading(to, origin, now() + patience)
    }

    /** Says where the user is now. Call it whenever that may have changed. */
    fun observe(here: NavigationStop) {
        current()?.let { pending ->
            val arrived = pending.to.host == here.host && pending.to.sessionId == here.sessionId &&
                (pending.to.windowId == null || pending.to.windowId == here.windowId)
            if (!arrived) return
            heading = null
        }
        if (entries.getOrNull(cursor) == here) return
        while (entries.size > cursor + 1) entries.removeAt(entries.lastIndex)
        entries += here
        if (entries.size > limit) entries.removeAt(0)
        cursor = entries.lastIndex
    }

    /**
     * Forgets the places for which [open] is false: tabs and sessions that have closed.
     * Two visits to one tab that this brings together become one.
     */
    fun retain(open: (NavigationStop) -> Boolean) {
        if (heading?.let { !open(it.to) } == true) giveUp()
        val oldCursor = cursor
        val oldOrigin = heading?.origin ?: cursor
        var newCursor = -1
        var newOrigin = -1
        val kept = ArrayList<NavigationStop>()
        entries.forEachIndexed { index, entry ->
            if (open(entry) && kept.lastOrNull() != entry) kept += entry
            // An entry dropped leaves the cursor on the last one kept before it.
            if (index <= oldCursor) newCursor = kept.lastIndex
            if (index <= oldOrigin) newOrigin = kept.lastIndex
        }
        entries.clear()
        entries += kept
        cursor = newCursor
        heading = heading?.let { Heading(it.to, newOrigin, it.deadline) }
    }

    /**
     * The place to go back or forward to, having moved to it, or null at the end of the
     * history, which is then left as it was. Places for which [reachable] is false are
     * passed over, and so is [here], where the user already is.
     *
     * The caller shows the place. Asked again before it is on screen, this carries on from
     * it, so two presses in a row go two steps.
     */
    fun step(forward: Boolean, here: NavigationStop?, reachable: (NavigationStop) -> Boolean): NavigationStop? {
        val pending = current()
        val origin = pending?.origin ?: cursor
        val from = pending?.to ?: here
        var next = cursor
        while (true) {
            next += if (forward) 1 else -1
            val stop = entries.getOrNull(next) ?: return null
            if (stop != from && reachable(stop)) {
                cursor = next
                heading = Heading(stop, origin, now() + patience)
                return stop
            }
        }
    }

    /** Whether there is anywhere to go back or forward to. */
    fun canStep(forward: Boolean, here: NavigationStop?, reachable: (NavigationStop) -> Boolean): Boolean {
        val from = current()?.to ?: here
        val candidates = if (forward) entries.drop(cursor + 1) else entries.take(cursor.coerceAtLeast(0))
        return candidates.any { it != from && reachable(it) }
    }
}

/**
 * Where the user is: the tab showing on the host showing, or the session alone if it has
 * no tab. Null while there is nothing to be at: the host isn't connected, or the session's
 * layout hasn't been read.
 */
internal fun placeShown(host: String?, state: HostState?): NavigationStop? {
    if (host == null || state == null || state.connection != HostConnection.Connected) return null
    val view = state.view ?: return null
    return NavigationStop(host, view.sessionId, state.window?.id)
}

/**
 * Whether a place in the history is still there. A host that has been removed takes its
 * places with it; one that isn't connected keeps them, since nothing says what has closed
 * there. On a connected host the session must be listed, and the tab must be in the
 * session's layout if that has been read.
 */
internal fun isStillThere(stop: NavigationStop, hosts: Map<String, HostState>): Boolean {
    val state = hosts[stop.host] ?: return false
    if (state.connection != HostConnection.Connected) return true
    if (state.sessions.none { it.id == stop.sessionId }) return false
    val view = state.views[stop.sessionId] ?: return true
    return stop.windowId == null || view.windows.any { it.id == stop.windowId }
}

/** Whether a place in the history can be shown now: it is still there, on a host that is connected. */
internal fun isReachable(stop: NavigationStop, hosts: Map<String, HostState>): Boolean =
    hosts[stop.host]?.connection == HostConnection.Connected && isStillThere(stop, hosts)
