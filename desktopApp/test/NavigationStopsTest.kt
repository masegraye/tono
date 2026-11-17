package org.example.kotlintest

import org.example.kotlintest.rex.HostConnection
import org.example.kotlintest.rex.HostState
import org.example.kotlintest.rex.SessionSummary
import org.example.kotlintest.rex.SessionView
import org.example.kotlintest.rex.WindowView
import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationStopsTest {
    private fun window(id: String) = WindowView(id, id, focusedBlockId = null, zoomedBlockId = null, panes = emptyList())

    /** A connected host showing [selected], which has [tabs]. */
    private fun host(sessions: List<String>, selected: String, tabs: List<String>) = HostState(
        connection = HostConnection.Connected,
        sessions = sessions.map { SessionSummary(it, it) },
        selectedSessionId = selected,
        view = SessionView(selected, selected, revision = 1, activeWindowId = tabs.firstOrNull(), windows = tabs.map(::window)),
    )

    private val local = "Local" to host(listOf("work", "notes"), selected = "work", tabs = listOf("w1", "w2"))
    private val studio = "studio" to host(listOf("build"), selected = "build", tabs = listOf("b1", "b2", "b3"))

    @Test
    fun `the session being looked at is stepped through tab by tab`() {
        assertEquals(
            listOf(
                NavigationStop("Local", "work", "w1"),
                NavigationStop("Local", "work", "w2"),
                NavigationStop("Local", "notes"),
                NavigationStop("studio", "build"),
            ),
            navigationStops(listOf(local, studio), currentHost = "Local"),
        )
    }

    @Test
    fun `another host's session is one stop even though its tabs are known`() {
        // Entering it must not choose a tab: its active tab belongs to everyone attached to it.
        val stops = navigationStops(listOf(local, studio), currentHost = "Local")
        assertEquals(listOf(NavigationStop("studio", "build")), stops.filter { it.host == "studio" })

        // Once it is the one being looked at, its tabs are stops and Local's are not.
        assertEquals(
            listOf(
                NavigationStop("Local", "work"),
                NavigationStop("Local", "notes"),
                NavigationStop("studio", "build", "b1"),
                NavigationStop("studio", "build", "b2"),
                NavigationStop("studio", "build", "b3"),
            ),
            navigationStops(listOf(local, studio), currentHost = "studio"),
        )
    }

    @Test
    fun `stepping by session ignores tabs`() {
        assertEquals(
            listOf(NavigationStop("Local", "work"), NavigationStop("Local", "notes"), NavigationStop("studio", "build")),
            navigationStops(listOf(local, studio), currentHost = "Local", sessionsOnly = true),
        )
    }

    @Test
    fun `hosts that are not connected are skipped`() {
        val down = "down" to HostState(connection = HostConnection.Disconnected("unreachable"), sessions = listOf(SessionSummary("s", "s")))
        assertEquals(emptyList(), navigationStops(listOf(down), currentHost = "down"))
    }
}
