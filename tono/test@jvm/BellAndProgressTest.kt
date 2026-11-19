package org.example.kotlintest

import org.example.kotlintest.rex.HostConnection
import org.example.kotlintest.rex.HostState
import org.example.kotlintest.rex.PaneRect
import org.example.kotlintest.rex.PaneView
import org.example.kotlintest.rex.SessionSummary
import org.example.kotlintest.rex.SessionView
import org.example.kotlintest.rex.TerminalProgress
import org.example.kotlintest.rex.TerminalProgress.Kind
import org.example.kotlintest.rex.WindowView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressDrawingTest {
    @Test
    fun `a known percentage fills that much of the track, and never too little to see`() {
        assertEquals(0f to 0.5f, progressSpan(TerminalProgress(Kind.Normal, 50)))
        assertEquals(0f to 1f, progressSpan(TerminalProgress(Kind.Normal, 100)))
        assertEquals(0f to 0.25f, progressSpan(TerminalProgress(Kind.Paused, 25)))
        assertEquals(0f to 0.75f, progressSpan(TerminalProgress(Kind.Error, 75)))
        val started = progressSpan(TerminalProgress(Kind.Normal, 0))!!
        assertTrue(started.second > 0f && started.second < 0.05f, "0% shows as a sliver, not as nothing: $started")
    }

    @Test
    fun `a pause or a failure with no percentage fills the track, and plain busy travels`() {
        assertEquals(0f to 1f, progressSpan(TerminalProgress(Kind.Paused)))
        assertEquals(0f to 1f, progressSpan(TerminalProgress(Kind.Error)))
        assertNull(progressSpan(TerminalProgress(Kind.Indeterminate)))
        assertNull(progressSpan(TerminalProgress(Kind.Normal)))
    }

    @Test
    fun `the travelling piece comes in at one end, leaves by the other, and stays on the track`() {
        assertEquals(0f to 0f, travellingSpan(0f))
        assertEquals(1f to 1f, travellingSpan(1f))
        var last = -1f
        for (step in 0..100) {
            val (start, end) = travellingSpan(step / 100f)
            assertTrue(start in 0f..1f && end in 0f..1f && end >= start, "at $step: $start to $end")
            assertTrue(end >= last, "it never goes backwards")
            last = end
        }
        val (start, end) = travellingSpan(0.5f)
        assertTrue(end - start > 0.25f, "whole in the middle: $start to $end")
    }

    @Test
    fun `progress is put in words`() {
        assertEquals("42%", progressWords(TerminalProgress(Kind.Normal, 42)))
        assertEquals("working", progressWords(TerminalProgress(Kind.Normal)))
        assertEquals("working", progressWords(TerminalProgress(Kind.Indeterminate)))
        assertEquals("paused 42%", progressWords(TerminalProgress(Kind.Paused, 42)))
        assertEquals("paused", progressWords(TerminalProgress(Kind.Paused)))
        assertEquals("failed at 42%", progressWords(TerminalProgress(Kind.Error, 42)))
        assertEquals("failed", progressWords(TerminalProgress(Kind.Error)))
    }

    @Test
    fun `a tab's row says its bell and its progress, or nothing`() {
        assertNull(tabNews(bell = false, progress = null))
        assertEquals("bell", tabNews(bell = true, progress = null))
        assertEquals("42%", tabNews(bell = false, progress = TerminalProgress(Kind.Normal, 42)))
        assertEquals("bell, failed", tabNews(bell = true, progress = TerminalProgress(Kind.Error)))
    }
}

class NewsFilterTest {
    private fun tab(id: String) = WindowView(id, id, focusedBlockId = "$id-block", zoomedBlockId = null, panes = listOf(PaneView("$id-block", PaneRect.Full, null, null, isTerminal = true, floating = false)))

    private val host = HostState(
        connection = HostConnection.Connected,
        sessions = listOf(SessionSummary("work", "work")),
        views = mapOf("work" to SessionView("work", "work", 1, "w1", listOf(tab("w1"), tab("w2"), tab("w3")))),
    )

    @Test
    fun `showing only active tabs keeps a tab with a bell or with progress`() {
        val state = host.copy(bells = setOf("w3"), progress = mapOf("w1-block" to TerminalProgress(Kind.Indeterminate)))
        assertEquals(
            listOf(NavigationStop("Local", "work", "w1"), NavigationStop("Local", "work", "w3")),
            linearTabs(listOf("Local" to state), activityOnly = true),
        )
        assertEquals(emptyList(), linearTabs(listOf("Local" to host), activityOnly = true))
    }
}
