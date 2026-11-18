package org.example.kotlintest

import org.example.kotlintest.rex.HostState
import org.example.kotlintest.rex.PaneRect
import org.example.kotlintest.rex.PaneView
import org.example.kotlintest.rex.ProgramCopy
import org.example.kotlintest.rex.SessionView
import org.example.kotlintest.rex.WindowView
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which of the copies programs make reach the clipboard. */
class ProgramCopyTest {
    private fun pane(id: String) = PaneView(id, PaneRect.Full, label = null, ownerClientId = null, isTerminal = true, floating = false)

    private fun tab(id: String, focused: String, vararg panes: String) =
        WindowView(id, id, focusedBlockId = focused, zoomedBlockId = null, panes = panes.map(::pane))

    /** On screen: the session `work`, its tab `editor`, and in it the pane `left`, with `right` beside it. */
    private val state = HostState(
        selectedSessionId = "work",
        views = mapOf(
            "work" to SessionView("work", "work", revision = 1, activeWindowId = "editor", windows = listOf(tab("editor", "left", "left", "right"), tab("logs", "tail", "tail"))),
            "notes" to SessionView("notes", "notes", revision = 1, activeWindowId = "todo", windows = listOf(tab("todo", "list", "list"))),
        ),
    )

    private fun copy(host: String = "Local", session: String = "work", block: String = "left") = ProgramCopy(host, session, block, "copied by a program")

    private fun takes(copy: ProgramCopy = copy(), windowFocused: Boolean = true, overlayOpen: Boolean = false, shownHost: String? = "Local", state: HostState? = this.state) =
        takesCopy(copy, windowFocused, overlayOpen, shownHost, state)

    @Test
    fun `a copy from the focused pane of the tab on screen is taken`() {
        assertTrue(takes())
    }

    @Test
    fun `a copy from another pane of the tab is dropped`() {
        assertFalse(takes(copy(block = "right")))
    }

    @Test
    fun `a copy from another tab is dropped`() {
        assertFalse(takes(copy(block = "tail")))
    }

    @Test
    fun `a copy from another session is dropped`() {
        assertFalse(takes(copy(session = "notes", block = "list")))
    }

    @Test
    fun `a copy from a host that isn't the one showing is dropped`() {
        assertFalse(takes(copy(host = "studio")))
        assertFalse(takes(shownHost = null, state = null))
    }

    @Test
    fun `a copy is dropped while the window doesn't have the keyboard`() {
        assertFalse(takes(windowFocused = false))
    }

    @Test
    fun `a copy is dropped while the palette or a dialog is open over the terminals`() {
        assertFalse(takes(overlayOpen = true))
    }

    @Test
    fun `a copy is dropped once its pane has lost the keyboard`() {
        val moved = state.copy(views = state.views + ("work" to state.views.getValue("work").let { it.copy(windows = listOf(tab("editor", "right", "left", "right"))) }))
        assertFalse(takes(state = moved))
        assertTrue(takes(copy(block = "right"), state = moved))
    }
}
