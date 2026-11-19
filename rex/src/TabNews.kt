package org.example.kotlintest.rex

import kotlinx.serialization.json.JsonObject

/*
 * What happens in a tab that someone who isn't looking at it would want to be told: its
 * program started waiting for them, failed or finished, or the program asked to tell them
 * something itself, with OSC 9 or OSC 777, which the server passes on as the terminal event
 * `desktop_notification`. The client hears of these for every tab of every session, and
 * hands on the ones from tabs that are not in front of the user. What is made of them (a
 * sound, a notification) is the app's business.
 */

/** A tab, wherever it is: its host by label, its session and its own id. */
data class TabPlace(val host: String, val sessionId: String, val windowId: String)

/** News from a tab that isn't in front of the user. */
sealed interface TabNews {
    /** The tab it is from. Null if the terminal is in no tab this client knows, as when its layout hasn't been read yet. */
    val place: TabPlace?

    /** A program started waiting for the user, failed, or finished: [ActivityState.Blocked], [ActivityState.Error] or [ActivityState.Done]. */
    data class Activity(override val place: TabPlace?, val state: ActivityState) : TabNews

    /** A program asked for a notification. OSC 9 has only a body; OSC 777 has a title too. Either may be empty, not both. */
    data class Message(override val place: TabPlace?, val title: String, val body: String) : TabNews

    companion object {
        /** Reads a `desktop_notification` event's own fields. Null for one that says nothing. */
        fun message(place: TabPlace?, fields: JsonObject?): Message? {
            val title = fields?.string("title").orEmpty().trim()
            val body = fields?.string("body").orEmpty().trim()
            return if (title.isEmpty() && body.isEmpty()) null else Message(place, title, body)
        }
    }
}

/** The tab a terminal is in, on the host called [host], in whichever session has it. */
fun HostState.placeOf(host: String, blockId: String): TabPlace? {
    for ((sessionId, view) in views) {
        val tab = view.windows.firstOrNull { tab -> tab.panes.any { it.blockId == blockId } } ?: continue
        return TabPlace(host, sessionId, tab.id)
    }
    return null
}

/** The tab on screen, for a host called [host]. Whether the host is the one showing is not this state's to say. */
fun HostState.shownPlace(host: String): TabPlace? {
    val sessionId = selectedSessionId ?: return null
    return window?.let { TabPlace(host, sessionId, it.id) }
}
