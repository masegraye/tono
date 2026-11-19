package org.example.kotlintest.rex

/*
 * The rules for what can be done to a tab besides showing it: moving it one place within
 * its session, closing every other tab, and keeping it quiet on this client. They are Rex
 * Rune's (`src/app/tabs.rs`). A tab's place is the server's, so a move changes it for every
 * client; whether a tab is muted is this client's alone, and the server never hears of it.
 */

/** Where a tab goes when it moves: just before [beforeWindowId], or to the end of its session when that is null. */
data class TabMove(val beforeWindowId: String?)

/**
 * The move that takes the tab [windowId] one place along [order], its session's tabs as the
 * server lists them: toward the end if [forward], else toward the start. Null when there is
 * nowhere to go, for the first tab going backward and the last going forward, and for a tab
 * that isn't in the session.
 */
fun tabMove(order: List<String>, windowId: String, forward: Boolean): TabMove? {
    val place = order.indexOf(windowId)
    return when {
        place < 0 -> null
        !forward -> order.getOrNull(place - 1)?.let(::TabMove)
        place == order.lastIndex -> null
        // Past its neighbour means before the one after that, or at the end if there is none.
        else -> TabMove(order.getOrNull(place + 2))
    }
}

/**
 * Which way a `client.tab.move` goes, from its `direction`: true for forward. Rex.app says
 * backward and forward, Rex Rune left and right as well; up and down are the same in a
 * sidebar, where tabs run top to bottom. Null for anything else.
 */
fun tabMoveIsForward(direction: String?): Boolean? = when (direction?.lowercase()) {
    "forward", "right", "down", "next" -> true
    "backward", "left", "up", "previous" -> false
    else -> null
}

/**
 * The tabs to close when every tab but [keep] is to go. [named] are the others as they were
 * when the user was asked, and [open] is what the session has now: a tab opened since was
 * never agreed to and stays, one closed since is skipped, and if the tab that was to stay
 * has itself gone, nothing closes, since the session would be left with nothing in it.
 */
fun tabsToClose(open: List<String>, keep: String, named: List<String>): List<String> =
    if (keep !in open) emptyList() else named.filter { it != keep && it in open }.distinct()

/**
 * A tab this client keeps quiet: no sound and no notification for what happens in it. Kept
 * between runs in the workspace, in Rex Rune's shape.
 *
 * @param endpoint the address of the tab's server, as its host is saved
 * @param window the tab's id, which is unique on its server
 */
data class MutedTab(val endpoint: String, val session: String, val window: String)

/** Whether the tab [window] on the server at [endpoint] is one of these. */
fun List<MutedTab>.mutes(endpoint: String, window: String): Boolean = any { it.window == window && it.endpoint == endpoint }

/** These with [tab] muted or not. The list never grows past [MUTED_TABS_LIMIT]: the oldest go first. */
fun List<MutedTab>.with(tab: MutedTab, muted: Boolean): List<MutedTab> {
    val others = filterNot { it.endpoint == tab.endpoint && it.window == tab.window }
    return if (muted) (others + tab).takeLast(MUTED_TABS_LIMIT) else others
}

/**
 * These without the tabs of the server at [endpoint] that are known to have gone: a tab
 * closed, which shows as its session's layout having been read and not listing it, or a
 * session ended, which shows as a connected server not listing it. A server that isn't
 * connected says nothing about what ended, so its tabs are kept, as are other servers'.
 */
fun List<MutedTab>.stillOpen(endpoint: String, state: HostState): List<MutedTab> = filterNot { tab ->
    if (tab.endpoint != endpoint) return@filterNot false
    val closed = state.views[tab.session]?.let { view -> view.windows.none { it.id == tab.window } } == true
    val ended = state.connection == HostConnection.Connected && state.sessions.none { it.id == tab.session }
    closed || ended
}

/** As many muted tabs as are remembered, so a server never visited again can't make the list grow without end. */
const val MUTED_TABS_LIMIT = 256
