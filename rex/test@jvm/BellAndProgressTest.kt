package org.example.kotlintest.rex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private fun pane(blockId: String) = PaneView(blockId, PaneRect.Full, label = null, ownerClientId = null, isTerminal = true, floating = false)

/** A tab called [id] whose panes are `<id>-a`, `<id>-b` and so on. */
private fun tab(id: String, panes: Int = 1, zoomed: String? = null) =
    WindowView(id, id, focusedBlockId = "$id-a", zoomedBlockId = zoomed, panes = (0 until panes).map { pane("$id-${'a' + it}") })

private fun session(id: String, vararg tabs: WindowView) = id to SessionView(id, id, revision = 1, activeWindowId = tabs.firstOrNull()?.id, windows = tabs.toList())

private fun fields(text: String) = Json.parseToJsonElement(text).jsonObject

/** Two sessions. "work" is the one selected, showing its first tab, w1; w2 has two panes. */
private val host = HostState(
    connection = HostConnection.Connected,
    sessions = listOf(SessionSummary("work", "work"), SessionSummary("notes", "notes")),
    selectedSessionId = "work",
    views = mapOf(session("work", tab("w1"), tab("w2", panes = 2)), session("notes", tab("n1"))),
)

class BellTest {
    @Test
    fun `a bell in the tab being looked at marks nothing`() {
        assertSame(host, host.rang("work", "w1-a", inFront = true))
    }

    @Test
    fun `a bell in a tab out of sight marks it`() {
        val state = host.rang("work", "w2-b", inFront = true)
        assertEquals(setOf("w2"), state.bells)
        assertTrue(state.hasBell(tab("w2")))
        assertFalse(state.hasBell(tab("w1")))
    }

    @Test
    fun `a bell in another session marks its tab, even the one that session would show`() {
        assertEquals(setOf("n1"), host.rang("notes", "n1-a", inFront = true).bells)
    }

    @Test
    fun `a bell in the tab on screen marks it when the window is not in front`() {
        assertEquals(setOf("w1"), host.rang("work", "w1-a", inFront = false).bells)
    }

    @Test
    fun `a bell in a pane hidden behind a zoomed one is still in the tab being looked at`() {
        val zoomed = host.copy(views = host.views + session("work", tab("w1", panes = 2, zoomed = "w1-a")))
        assertSame(zoomed, zoomed.rang("work", "w1-b", inFront = true))
    }

    @Test
    fun `the tab being looked at is the one this app chose, not the session's active one`() {
        val elsewhere = host.copy(shownWindows = mapOf("work" to "w2"))
        assertEquals(setOf("w1"), elsewhere.rang("work", "w1-a", inFront = true).bells)
        assertSame(elsewhere, elsewhere.rang("work", "w2-a", inFront = true))
    }

    @Test
    fun `a bell from a terminal in no known tab marks nothing`() {
        assertSame(host, host.rang("work", "gone", inFront = false))
        assertSame(host, host.rang("unread", "w1-a", inFront = false))
    }

    @Test
    fun `ringing twice is the same as ringing once`() {
        val once = host.rang("work", "w2-a", inFront = true)
        assertSame(once, once.rang("work", "w2-b", inFront = true))
    }

    @Test
    fun `going to a tab forgets its bell and leaves the others`() {
        val rung = host.rang("work", "w2-a", inFront = true).rang("notes", "n1-a", inFront = true)
        assertSame(rung, rung.looked(inFront = true), "w1 is on screen, and it has no bell")
        val there = rung.copy(shownWindows = mapOf("work" to "w2")).looked(inFront = true)
        assertEquals(setOf("n1"), there.bells)
    }

    @Test
    fun `a bell stays while the window is not in front, and goes when it comes back`() {
        val rung = host.rang("work", "w1-a", inFront = false)
        assertSame(rung, rung.looked(inFront = false))
        assertEquals(emptySet(), rung.looked(inFront = true).bells)
    }

    @Test
    fun `selecting another session forgets the bell of the tab that session shows`() {
        val rung = host.rang("notes", "n1-a", inFront = true)
        assertEquals(emptySet(), rung.copy(selectedSessionId = "notes").looked(inFront = true).bells)
    }

    @Test
    fun `a bell goes with its tab when the tab is closed`() {
        val rung = host.rang("work", "w2-a", inFront = true).rang("notes", "n1-a", inFront = true)
        val closed = rung.copy(views = rung.views + session("work", tab("w1"))).looked(inFront = true)
        assertEquals(setOf("n1"), closed.bells)
    }
}

class ProgressTest {
    private fun parse(text: String) = TerminalProgress.parse(fields(text))

    @Test
    fun `reports are read as the server spells them`() {
        assertEquals(TerminalProgress(TerminalProgress.Kind.Normal, 50), parse("""{"state":"set","progress":50}"""))
        assertEquals(TerminalProgress(TerminalProgress.Kind.Normal), parse("""{"state":"set"}"""))
        assertEquals(TerminalProgress(TerminalProgress.Kind.Normal), parse("""{"state":"set","progress":null}"""))
        assertEquals(TerminalProgress(TerminalProgress.Kind.Indeterminate), parse("""{"state":"indeterminate"}"""))
        assertEquals(TerminalProgress(TerminalProgress.Kind.Paused, 7), parse("""{"state":"pause","progress":7}"""))
        assertEquals(TerminalProgress(TerminalProgress.Kind.Paused), parse("""{"state":"pause"}"""))
        assertEquals(TerminalProgress(TerminalProgress.Kind.Error, 100), parse("""{"state":"error","progress":100}"""))
        assertEquals(TerminalProgress(TerminalProgress.Kind.Error), parse("""{"state":"error"}"""))
    }

    @Test
    fun `a report that removes progress, or one of a kind not known, is no progress`() {
        assertNull(parse("""{"state":"remove"}"""))
        assertNull(parse("""{"state":"remove","progress":50}"""))
        assertNull(parse("""{"state":"something new","progress":50}"""))
        assertNull(parse("""{}"""))
        assertNull(TerminalProgress.parse(null))
    }

    @Test
    fun `a percentage over a hundred is no percentage, and busy has none`() {
        // The server allows up to 255, since that is what a program can write.
        assertEquals(TerminalProgress(TerminalProgress.Kind.Error), parse("""{"state":"error","progress":101}"""))
        assertEquals(TerminalProgress(TerminalProgress.Kind.Normal), parse("""{"state":"set","progress":255}"""))
        assertEquals(TerminalProgress(TerminalProgress.Kind.Indeterminate), parse("""{"state":"indeterminate","progress":40}"""))
    }

    @Test
    fun `a terminal's progress is its newest report, and removing it clears it`() {
        val half = host.reported("w1-a", fields("""{"state":"set","progress":50}"""))
        assertEquals(mapOf("w1-a" to TerminalProgress(TerminalProgress.Kind.Normal, 50)), half.progress)
        assertSame(half, half.reported("w1-a", fields("""{"state":"set","progress":50}""")), "the same report again changes nothing")
        val failed = half.reported("w1-a", fields("""{"state":"error","progress":50}"""))
        assertEquals(TerminalProgress(TerminalProgress.Kind.Error, 50), failed.progress["w1-a"])
        assertEquals(emptyMap(), failed.reported("w1-a", fields("""{"state":"remove"}""")).progress)
        assertSame(host, host.reported("w1-a", fields("""{"state":"remove"}""")))
    }

    @Test
    fun `one terminal's progress leaves another's alone`() {
        val both = host.reported("w2-a", fields("""{"state":"set","progress":10}""")).reported("w2-b", fields("""{"state":"pause"}"""))
        assertEquals(setOf("w2-b"), both.reported("w2-a", fields("""{"state":"remove"}""")).progress.keys)
    }

    @Test
    fun `a tab shows the progress of the pane with the most to say`() {
        val w2 = tab("w2", panes = 2)
        fun of(a: TerminalProgress?, b: TerminalProgress?) =
            host.copy(progress = listOfNotNull(a?.let { "w2-a" to it }, b?.let { "w2-b" to it }).toMap()).progressOf(w2)
        val busy = TerminalProgress(TerminalProgress.Kind.Indeterminate)
        val half = TerminalProgress(TerminalProgress.Kind.Normal, 50)
        val paused = TerminalProgress(TerminalProgress.Kind.Paused)
        val failed = TerminalProgress(TerminalProgress.Kind.Error)
        assertNull(of(null, null))
        assertEquals(half, of(null, half))
        assertEquals(half, of(busy, half))
        assertEquals(paused, of(paused, half))
        assertEquals(failed, of(paused, failed))
        assertEquals(failed, of(failed, half))
        assertEquals(TerminalProgress(TerminalProgress.Kind.Error, 30), of(failed, TerminalProgress(TerminalProgress.Kind.Error, 30)), "of two alike, the one that says how far")
        assertNull(host.copy(progress = mapOf("w2-a" to half)).progressOf(tab("w1")), "another tab's progress is not this one's")
    }

    @Test
    fun `a tab with a bell or progress has news, and one with neither has none`() {
        assertFalse(host.hasNews(tab("w1")))
        assertTrue(host.copy(bells = setOf("w1")).hasNews(tab("w1")))
        assertTrue(host.copy(progress = mapOf("w1-a" to TerminalProgress(TerminalProgress.Kind.Indeterminate))).hasNews(tab("w1")))
        assertTrue(host.copy(activity = mapOf("w1-a" to ActivityState.Working)).hasNews(tab("w1")))
        assertFalse(host.copy(bells = setOf("w2"), progress = mapOf("w2-a" to TerminalProgress(TerminalProgress.Kind.Normal, 1))).hasNews(tab("w1")))
    }
}
