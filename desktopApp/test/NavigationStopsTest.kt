package org.example.kotlintest

import org.example.kotlintest.rex.HostConnection
import org.example.kotlintest.rex.HostState
import org.example.kotlintest.rex.SessionSummary
import org.example.kotlintest.rex.SessionView
import org.example.kotlintest.rex.WindowView
import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationStopsTest {
    private fun layout(session: String, vararg tabs: String) = session to SessionView(
        sessionId = session, label = session, revision = 1, activeWindowId = tabs.firstOrNull(),
        windows = tabs.map { WindowView(it, it, focusedBlockId = null, zoomedBlockId = null, panes = emptyList()) },
    )

    private fun host(sessions: List<String>, vararg layouts: Pair<String, SessionView>) = HostState(
        connection = HostConnection.Connected,
        sessions = sessions.map { SessionSummary(it, it) },
        selectedSessionId = sessions.firstOrNull(),
        views = layouts.toMap(),
    )

    private val local = "Local" to host(listOf("work", "notes"), layout("work", "w1", "w2"), layout("notes", "n1"))
    private val studio = "studio" to host(listOf("build"), layout("build", "b1", "b2"))

    @Test
    fun `tabs are one list across every session and host, in sidebar order`() {
        assertEquals(
            listOf(
                NavigationStop("Local", "work", "w1"),
                NavigationStop("Local", "work", "w2"),
                NavigationStop("Local", "notes", "n1"),
                NavigationStop("studio", "build", "b1"),
                NavigationStop("studio", "build", "b2"),
            ),
            linearTabs(listOf(local, studio)),
        )
    }

    @Test
    fun `a session whose layout has not been read yet has no tabs to list`() {
        val reading = "Local" to host(listOf("work", "notes"), layout("notes", "n1"))
        assertEquals(listOf(NavigationStop("Local", "notes", "n1")), linearTabs(listOf(reading)))
    }

    @Test
    fun `hosts that are not connected are left out`() {
        val down = "down" to HostState(connection = HostConnection.Disconnected("unreachable"), sessions = listOf(SessionSummary("s", "s")))
        assertEquals(linearTabs(listOf(local)), linearTabs(listOf(down, local)))
        assertEquals(emptyList(), linearSessions(listOf(down)))
    }

    @Test
    fun `folding a session or a host away takes its tabs out of the numbering`() {
        // With "work" folded, its two tabs are gone and "notes" starts at 1.
        assertEquals(
            listOf(NavigationStop("Local", "notes", "n1"), NavigationStop("studio", "build", "b1"), NavigationStop("studio", "build", "b2")),
            linearTabs(listOf(local, studio), collapsedSessions = setOf("work")),
        )
        assertEquals(
            listOf(NavigationStop("studio", "build", "b1"), NavigationStop("studio", "build", "b2")),
            linearTabs(listOf(local, studio), collapsedHosts = setOf("Local")),
        )
        assertEquals(emptyList(), linearTabs(listOf(local, studio), setOf("Local"), setOf("build")))
    }

    @Test
    fun `sessions are listed in the same order`() {
        assertEquals(
            listOf(NavigationStop("Local", "work"), NavigationStop("Local", "notes"), NavigationStop("studio", "build")),
            linearSessions(listOf(local, studio)),
        )
    }

    @Test
    fun `the first eight tabs have their own digit and nine is the last`() {
        assertEquals(listOf(1, 2, 3), (0 until 3).map { tabShortcutDigit(it, 3) })
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), (0 until 8).map { tabShortcutDigit(it, 8) })
        // With eleven tabs, the ninth and tenth have no digit, and the eleventh is Control+9.
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, null, null, 9), (0 until 11).map { tabShortcutDigit(it, 11) })
    }
}
