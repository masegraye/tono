package org.example.kotlintest

import org.example.kotlintest.rex.ActivityState
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

    @Test
    fun `hosts are listed home first, then connected ones by name, then the rest by name`() {
        val up = HostState(connection = HostConnection.Connected)
        val down = HostState(connection = HostConnection.Disconnected("no answer"))
        val trying = HostState(connection = HostConnection.Connecting)
        val hosts = listOf("zeta" to up, "omamac" to down, "Local" to up, "alpha" to trying, "Beta" to up, "apple" to up)
        assertEquals(listOf("Local", "apple", "Beta", "zeta", "alpha", "omamac"), sidebarOrder(hosts, home = "Local") { it }.map { it.first })
        // This machine stays on top even while its own server is down.
        assertEquals(listOf("Local", "zeta"), sidebarOrder(listOf("zeta" to up, "Local" to down), home = "Local") { it }.map { it.first })
    }
}

class ReorderTest {
    private val moves = ArrayList<Pair<String, String?>>()

    /** Three rows of 20 stacked from 0, and a taller fourth. */
    private fun column(): ReorderState = ReorderState { key, before -> moves += key to before }.apply {
        keys = listOf("a", "b", "c", "d")
        place("a", 0f, 20f); place("b", 20f, 20f); place("c", 40f, 20f); place("d", 60f, 50f)
    }

    @Test
    fun `dragging a row past its neighbours moves it before the one it lands above`() {
        val state = column()
        state.start("c")
        state.dragBy(-25f) // its middle is now above b's, below a's
        assertEquals(20f, state.translationOf("b"), "b steps down into the room c left")
        assertEquals(0f, state.translationOf("a"))
        assertEquals(0f, state.translationOf("d"))
        state.drop()
        assertEquals(listOf<Pair<String, String?>>("c" to "b"), moves)
        assertEquals(-20f, state.translationOf("c"), "it rests in the gap until the new order arrives")
    }

    @Test
    fun `dragging to the end moves it after everything`() {
        val state = column()
        state.start("a")
        state.dragBy(500f) // far past the end: held at the bottom of the column
        assertEquals(90f, state.translationOf("a"))
        assertEquals(-20f, state.translationOf("d"))
        state.drop()
        assertEquals(listOf<Pair<String, String?>>("a" to null), moves)
    }

    @Test
    fun `a row let go where it started moves nothing`() {
        val state = column()
        state.start("b")
        state.dragBy(6f)
        state.drop()
        assertEquals(emptyList<Pair<String, String?>>(), moves)
        assertEquals(0f, state.translationOf("b"))
    }

    @Test
    fun `a new order ends the drag`() {
        val state = column()
        state.start("b")
        state.dragBy(30f)
        state.keys = listOf("a", "c", "b", "d") // someone else moved a tab meanwhile
        assertEquals(0f, state.translationOf("b"))
        assertEquals(0f, state.translationOf("c"))
        state.drop()
        assertEquals(emptyList<Pair<String, String?>>(), moves)
    }
}

class SoundsTest {
    @Test
    fun `a burst of news makes one sound, the most pressing`() {
        val gate = SoundGate(gatherMillis = 100, quietMillis = 1_000)
        assertEquals(true, gate.offer(ActivityState.Done, 0), "the first starts a burst")
        assertEquals(false, gate.offer(ActivityState.Blocked, 40), "the rest join it")
        assertEquals(false, gate.offer(ActivityState.Error, 80))
        assertEquals(ActivityState.Blocked, gate.take(), "waiting for you outranks an error, which outranks done")
        assertEquals(null, gate.take())
    }

    @Test
    fun `news that arrives while a sound is playing is dropped, not played late`() {
        val gate = SoundGate(gatherMillis = 100, quietMillis = 1_000)
        gate.offer(ActivityState.Done, 0)
        assertEquals(ActivityState.Done, gate.take())
        assertEquals(false, gate.offer(ActivityState.Error, 500))
        assertEquals(null, gate.take())
        assertEquals(true, gate.offer(ActivityState.Done, 1_000), "after a second, the next one is heard")
    }

    @Test
    fun `only waiting, failing and finishing have a sound`() {
        assertEquals("dialog-warning", soundFor(ActivityState.Blocked))
        assertEquals("dialog-error", soundFor(ActivityState.Error))
        assertEquals("complete", soundFor(ActivityState.Done))
        assertEquals(null, soundFor(ActivityState.Working))
        assertEquals(null, soundFor(ActivityState.Idle))
    }

    @Test
    fun `the sound is played by whichever player is installed`() {
        val file = java.nio.file.Path.of("/usr/share/sounds/freedesktop/stereo/complete.oga")
        fun programs(vararg installed: String) = { name: String -> if (name in installed) java.nio.file.Path.of("/usr/bin/$name") else null }
        assertEquals(
            listOf("/usr/bin/mpv", "--no-video", "--no-terminal", "--really-quiet", file.toString()),
            soundCommand("complete", programs("mpv", "canberra-gtk-play", "pw-play")) { file },
            "mpv first, as Omarchy plays its own sounds",
        )
        assertEquals(listOf("/usr/bin/canberra-gtk-play", "-i", "complete"), soundCommand("complete", programs("canberra-gtk-play", "pw-play")) { file })
        assertEquals(listOf("/usr/bin/canberra-gtk-play", "-i", "complete"), soundCommand("complete", programs("mpv", "canberra-gtk-play")) { null }, "it finds the theme itself")
        assertEquals(listOf("/usr/bin/pw-play", file.toString()), soundCommand("complete", programs("pw-play", "paplay")) { file })
        assertEquals(listOf("/usr/bin/paplay", file.toString()), soundCommand("complete", programs("paplay")) { file })
        assertEquals(null, soundCommand("complete", programs("paplay")) { null }, "a player that needs a file, and no file")
        assertEquals(null, soundCommand("complete", programs()) { file })
    }
}

