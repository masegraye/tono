package org.example.kotlintest

import org.example.kotlintest.rex.HostConnection
import org.example.kotlintest.rex.HostState
import org.example.kotlintest.rex.SessionSummary
import org.example.kotlintest.rex.SessionView
import org.example.kotlintest.rex.WindowView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavigationHistoryTest {
    private fun tab(id: String, host: String = "Local", session: String = "work") = NavigationStop(host, session, id)

    private val a = tab("a")
    private val b = tab("b")
    private val c = tab("c")
    private val d = tab("d")
    private val anywhere: (NavigationStop) -> Boolean = { true }

    private var time = 0L
    private fun history(limit: Int = 128) = NavigationHistory(limit = limit, patience = 1_000, now = { time })

    /** A history that has seen these tabs, one after another. */
    private fun seen(vararg stops: NavigationStop) = history().also { h -> stops.forEach(h::observe) }

    /** Steps, and arrives. */
    private fun NavigationHistory.go(forward: Boolean, here: NavigationStop?, reachable: (NavigationStop) -> Boolean = anywhere): NavigationStop? =
        step(forward, here, reachable)?.also(::observe)

    @Test
    fun `back goes to the tab shown before, and forward returns`() {
        val h = seen(a, b, c)
        assertEquals(b, h.go(forward = false, here = c))
        assertEquals(a, h.go(forward = false, here = b))
        assertEquals(b, h.go(forward = true, here = a))
        assertEquals(c, h.go(forward = true, here = b))
        // Going back and forward adds nothing.
        assertEquals(listOf(a, b, c), h.places)
    }

    @Test
    fun `there is nowhere to go at either end, and the history is left as it was`() {
        val h = seen(a, b)
        assertNull(h.step(forward = true, here = b, reachable = anywhere))
        assertFalse(h.canStep(forward = true, here = b, reachable = anywhere))
        assertTrue(h.canStep(forward = false, here = b, reachable = anywhere))
        assertEquals(a, h.go(forward = false, here = b))
        assertNull(h.step(forward = false, here = a, reachable = anywhere))
        assertFalse(h.canStep(forward = false, here = a, reachable = anywhere))
        assertEquals(b, h.go(forward = true, here = a))
        assertNull(history().step(forward = false, here = null, reachable = anywhere))
    }

    @Test
    fun `seeing the same tab again is not a step`() {
        val h = seen(a, a, b, b, b)
        assertEquals(listOf(a, b), h.places)
    }

    @Test
    fun `going somewhere new from the middle forgets what was ahead`() {
        val h = seen(a, b, c)
        h.go(forward = false, here = c)
        h.go(forward = false, here = b)
        h.observe(d)
        assertEquals(listOf(a, d), h.places)
        assertNull(h.step(forward = true, here = d, reachable = anywhere))
        assertEquals(a, h.go(forward = false, here = d))
    }

    @Test
    fun `a tab that closed is passed over`() {
        val h = seen(a, b, c)
        h.retain { it != b }
        assertEquals(listOf(a, c), h.places)
        assertEquals(a, h.go(forward = false, here = c))
    }

    @Test
    fun `closing a tab between two visits to another leaves one visit`() {
        val h = seen(a, b, a, c)
        h.retain { it != b }
        assertEquals(listOf(a, c), h.places)
        assertEquals(a, h.go(forward = false, here = c))
        assertNull(h.step(forward = false, here = a, reachable = anywhere))
    }

    @Test
    fun `when the tab showing closes, back goes to the one before it and not the one that took its place`() {
        val h = seen(a, b, c)
        // c closes, and the server shows its neighbour d.
        h.retain { it != c }
        h.observe(d)
        assertEquals(listOf(a, b, d), h.places)
        assertEquals(b, h.go(forward = false, here = d))
    }

    @Test
    fun `closing every tab behind leaves nothing to go back to`() {
        val h = seen(a, b, c)
        h.go(forward = false, here = c)
        h.go(forward = false, here = b)
        h.retain { it != a } // a, the tab showing, closes; b takes its place
        assertEquals(-1, h.position)
        h.observe(b)
        // Arriving at b is going somewhere new, as any arrival is, so what was ahead is forgotten.
        assertEquals(listOf(b), h.places)
        assertFalse(h.canStep(forward = false, here = b, reachable = anywhere))
        assertFalse(h.canStep(forward = true, here = b, reachable = anywhere))
    }

    @Test
    fun `a tab on a host that is not connected is passed over and kept`() {
        val away = tab("x", host = "studio")
        val h = seen(a, away, b, c)
        val connected: (NavigationStop) -> Boolean = { it.host != "studio" }
        assertEquals(b, h.go(forward = false, here = c, reachable = connected))
        assertEquals(a, h.go(forward = false, here = b, reachable = connected))
        assertEquals(listOf(a, away, b, c), h.places)
        // The host is back.
        assertEquals(away, h.go(forward = true, here = a))
    }

    @Test
    fun `a step that would land where the user already is carries on`() {
        // b closed under the user and a, which was before it, is what shows now.
        val h = seen(a, b)
        assertNull(h.step(forward = false, here = a, reachable = anywhere))
        val k = seen(c, a, b)
        assertEquals(c, k.go(forward = false, here = a))
    }

    @Test
    fun `what shows on the way to a place asked for is not recorded`() {
        val there = tab("y", host = "studio", session = "build")
        val passing = tab("x", host = "studio", session = "old")
        val h = seen(a)
        h.heading(there)
        h.observe(passing) // the other host comes up with the tab it had last
        h.observe(tab("z", host = "studio", session = "build")) // the session, with the tab it had last
        assertEquals(there, h.headingFor)
        h.observe(there)
        assertNull(h.headingFor)
        assertEquals(listOf(a, there), h.places)
    }

    @Test
    fun `heading for a session arrives at whichever tab of it shows`() {
        val h = seen(a)
        h.heading(NavigationStop("Local", "notes"))
        h.observe(tab("n1", session = "notes"))
        assertNull(h.headingFor)
        assertEquals(listOf(a, tab("n1", session = "notes")), h.places)
    }

    @Test
    fun `the same goes for a step back to another host`() {
        val far = tab("y", host = "studio", session = "build")
        val h = seen(far, a, b)
        h.go(forward = false, here = b)
        assertEquals(far, h.step(forward = false, here = a, reachable = anywhere))
        h.observe(tab("x", host = "studio", session = "old"))
        assertEquals(listOf(far, a, b), h.places)
        h.observe(far)
        assertEquals(0, h.position)
        assertEquals(a, h.go(forward = true, here = far))
    }

    @Test
    fun `two presses before the first has arrived go two steps`() {
        val h = seen(a, b, c)
        assertEquals(b, h.step(forward = false, here = c, reachable = anywhere))
        assertEquals(a, h.step(forward = false, here = c, reachable = anywhere))
        h.observe(b) // on the way
        h.observe(a)
        assertEquals(0, h.position)
        assertEquals(listOf(a, b, c), h.places)
    }

    @Test
    fun `a place never reached is given up on, and the history is as it was`() {
        val h = seen(a, b, c)
        h.step(forward = false, here = c, reachable = anywhere)
        time += 2_000
        assertNull(h.headingFor)
        assertEquals(2, h.position)
        // Still at c, so back is b again.
        assertEquals(b, h.go(forward = false, here = c))
    }

    @Test
    fun `a place that closes while it is being gone to is given up on at once`() {
        val h = seen(a, b, c)
        h.step(forward = false, here = c, reachable = anywhere)
        h.retain { it != b }
        assertNull(h.headingFor)
        assertEquals(listOf(a, c), h.places)
        assertEquals(1, h.position)
    }

    @Test
    fun `asking for a place while a step back is under way drops the step`() {
        val h = seen(a, b, c)
        h.step(forward = false, here = c, reachable = anywhere)
        h.heading(d)
        h.observe(d)
        assertEquals(listOf(a, b, c, d), h.places)
    }

    @Test
    fun `only so many places are remembered, the newest`() {
        val h = history(limit = 3)
        listOf(a, b, c, d).forEach(h::observe)
        assertEquals(listOf(b, c, d), h.places)
        assertEquals(2, h.position)
    }

    // What is there, going by what the hosts report.

    private fun layout(session: String, vararg tabs: String) = session to SessionView(
        sessionId = session, label = session, revision = 1, activeWindowId = tabs.firstOrNull(),
        windows = tabs.map { WindowView(it, it, focusedBlockId = null, zoomedBlockId = null, panes = emptyList()) },
    )

    private fun host(sessions: List<String>, vararg layouts: Pair<String, SessionView>, connection: HostConnection = HostConnection.Connected) = HostState(
        connection = connection,
        sessions = sessions.map { SessionSummary(it, it) },
        selectedSessionId = sessions.firstOrNull(),
        views = layouts.toMap(),
    )

    @Test
    fun `the place shown is the tab showing on a connected host`() {
        assertEquals(NavigationStop("Local", "work", "w1"), placeShown("Local", host(listOf("work"), layout("work", "w1", "w2"))))
        // A session with no tabs is a place too.
        assertEquals(NavigationStop("Local", "work"), placeShown("Local", host(listOf("work"), layout("work"))))
        // Nowhere, until the layout has been read, and while the host is away.
        assertNull(placeShown("Local", host(listOf("work"))))
        assertNull(placeShown("Local", host(listOf("work"), layout("work", "w1"), connection = HostConnection.Connecting)))
        assertNull(placeShown(null, null))
    }

    @Test
    fun `a place is gone when its tab, its session or its host is`() {
        val hosts = mapOf(
            "Local" to host(listOf("work", "notes"), layout("work", "w1")),
            "away" to host(listOf("s"), connection = HostConnection.Disconnected("unreachable")),
        )
        assertTrue(isStillThere(NavigationStop("Local", "work", "w1"), hosts))
        assertFalse(isStillThere(NavigationStop("Local", "work", "closed"), hosts))
        assertFalse(isStillThere(NavigationStop("Local", "closed", "w1"), hosts))
        assertFalse(isStillThere(NavigationStop("removed", "work", "w1"), hosts))
        // A session whose layout hasn't been read may still have the tab.
        assertTrue(isStillThere(NavigationStop("Local", "notes", "n1"), hosts))
        assertTrue(isReachable(NavigationStop("Local", "notes", "n1"), hosts))
        // A host that is away keeps its places, and they can't be gone to.
        assertTrue(isStillThere(NavigationStop("away", "s", "t"), hosts))
        assertFalse(isReachable(NavigationStop("away", "s", "t"), hosts))
    }

    @Test
    fun `the side buttons of a mouse are the ones that go through history`() {
        // Elsewhere, Compose's Back and Forward.
        assertEquals(false, historyButton(3, x11 = false))
        assertEquals(true, historyButton(4, x11 = false))
        // Under X11 those two are the wheel tilted sideways, and the side buttons come after.
        assertNull(historyButton(3, x11 = true))
        assertNull(historyButton(4, x11 = true))
        assertEquals(false, historyButton(5, x11 = true))
        assertEquals(true, historyButton(6, x11 = true))
        for (index in 0..2) assertNull(historyButton(index, x11 = true))
        assertNull(historyButton(null, x11 = false))
    }
}
