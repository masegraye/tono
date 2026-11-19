package org.example.kotlintest

import org.example.kotlintest.rex.ForegroundProgram
import org.example.kotlintest.rex.HostConnection
import org.example.kotlintest.rex.HostState
import org.example.kotlintest.rex.PaneRect
import org.example.kotlintest.rex.PaneView
import org.example.kotlintest.rex.SessionSummary
import org.example.kotlintest.rex.SessionView
import org.example.kotlintest.rex.TabName
import org.example.kotlintest.rex.WindowView
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowTitleTest {
    @Test
    fun `the title leads with the tab, then names its session and host`() {
        assertEquals("vim - lunar-basin · studio", windowTitle("vim", "lunar-basin", "studio"))
    }

    @Test
    fun `a part that is missing is left out`() {
        assertEquals("lunar-basin · studio", windowTitle(null, "lunar-basin", "studio"))
        assertEquals("lunar-basin · studio", windowTitle("  ", "lunar-basin", "studio"))
        assertEquals("vim - lunar-basin", windowTitle("vim", "lunar-basin", null))
    }

    @Test
    fun `with nothing to show it is the app's name`() {
        assertEquals("Tono · studio", windowTitle(null, null, "studio"))
        assertEquals("Tono", windowTitle(null, null, null))
        assertEquals("Tono", windowTitle("", " ", ""))
        assertEquals("Tono", windowTitle(host = null, state = null))
    }

    @Test
    fun `a tab called by its program has its directory after it`() {
        assertEquals("vim - ~/rex", TabName("vim", "~/rex").inTitle())
        assertEquals("vim", TabName("vim").inTitle())
        assertEquals("deploy", TabName("deploy").inTitle())
    }

    private fun pane(block: String) = PaneView(block, PaneRect.Full, label = null, ownerClientId = null, isTerminal = true, floating = false)

    private fun tab(id: String, label: String, block: String) =
        WindowView(id, label, focusedBlockId = block, zoomedBlockId = null, panes = listOf(pane(block)))

    /** A host showing session "s1", called lunar-basin, with a named tab and one never named. */
    private val state = HostState(
        connection = HostConnection.Connected,
        sessions = listOf(SessionSummary("s1", "lunar-basin"), SessionSummary("s2", "notes")),
        selectedSessionId = "s1",
        views = mapOf(
            "s1" to SessionView(
                "s1", "lunar-basin", revision = 1, activeWindowId = "w-named",
                windows = listOf(tab("w-named", "dev", "b1"), tab("w-0000000089abcdef", "Window 89abcdef", "b2")),
            ),
        ),
        programs = mapOf("b2" to ForegroundProgram("nvim", cwd = "/home/mase/dev/rex", home = "/home/mase")),
    )

    @Test
    fun `it names the tab on screen in the selected session`() {
        assertEquals("dev - lunar-basin · studio", windowTitle("studio", state))
    }

    @Test
    fun `it follows the tab this app shows, which need not be the session's active one`() {
        val shown = state.copy(shownWindows = mapOf("s1" to "w-0000000089abcdef"))
        assertEquals("nvim - ~/dev/rex - lunar-basin · studio", windowTitle("studio", shown))
    }

    @Test
    fun `a tab nobody named shows the server's placeholder until its program is known`() {
        val shown = state.copy(shownWindows = mapOf("s1" to "w-0000000089abcdef"), programs = emptyMap())
        assertEquals("Window 89abcdef - lunar-basin · studio", windowTitle("studio", shown))
    }

    @Test
    fun `a session whose layout has not been read yet is named without a tab`() {
        assertEquals("notes · studio", windowTitle("studio", state.copy(selectedSessionId = "s2")))
    }

    @Test
    fun `a host with no session chosen is the app's name and the host`() {
        assertEquals("Tono · studio", windowTitle("studio", state.copy(selectedSessionId = null)))
        assertEquals("Tono · studio", windowTitle("studio", HostState()))
    }
}
