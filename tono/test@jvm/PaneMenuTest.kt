package org.example.kotlintest

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.example.kotlintest.rex.KeyChord
import org.example.kotlintest.rex.RexAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaneMenuTest {
    private val actions = clientActions()
    private val server = listOf(
        RexAction(CLEAR_TERMINAL, "Clear Terminal", "Terminal"),
        RexAction(RESET_TERMINAL, "Reset Terminal", "Terminal"),
    )

    private fun menu(
        unavailable: Set<String> = emptySet(),
        serverActions: List<RexAction> = server,
        hasTerminal: Boolean = true,
        hasSelection: Boolean = false,
        zoomed: Boolean = false,
        shortcuts: Map<String, String> = emptyMap(),
    ) = paneMenuRows(actions, serverActions, { it.name !in unavailable }, hasTerminal, hasSelection, zoomed) { shortcuts[it] }

    private fun List<List<PaneMenuRow>>.row(action: String) = flatten().first { it.action == action }

    @Test
    fun `the menu lists what can be done to a pane, in groups, in Rex's order`() {
        assertEquals(
            listOf(
                // Rex.app's Copy and Paste, and Rex Rune's Select All after them.
                listOf("Copy", "Paste", "Select All"),
                listOf("Find…", "Insert Path…", "Go to Directory…"),
                // Rex.app's order for the splits and the zoom.
                listOf("Split Right", "Split Left", "Split Down", "Split Up", "Zoom Pane", "Balance Splits"),
                listOf("Move Pane to Tab…", "Move Pane to New Tab", "Rename Pane…"),
                // Rex.app ends on Close Pane and Reset Terminal.
                listOf("Close Pane", "Clear Terminal", "Reset Terminal"),
            ),
            menu().map { group -> group.map { it.title } },
        )
    }

    @Test
    fun `each row runs the action of that name`() {
        assertEquals(
            listOf(
                "pane.copy", "pane.paste", "pane.select_all",
                "client.find.open", "pane.insert_path", "pane.go_to_directory",
                "pane.split.right", "pane.split.left", "pane.split.down", "pane.split.up", "pane.zoom", "pane.balance",
                "pane.move_to_tab", "pane.move_to_new_tab", "pane.rename",
                "pane.close", "com.superlogical.terminal.clear", "com.superlogical.terminal.reset",
            ),
            menu().flatten().map { it.action },
        )
    }

    @Test
    fun `every row that is this app's names one of its actions, and none is listed twice`() {
        val names = actions.map { it.name }.toSet()
        val items = PANE_MENU.flatten()
        for (item in items.filter { !it.server }) assertTrue(item.action in names, item.action)
        assertEquals(items.size, items.map { it.action }.toSet().size)
        assertEquals(items.size, items.map { it.title }.toSet().size)
    }

    @Test
    fun `copy is dimmed until something is selected`() {
        assertFalse(menu().row("pane.copy").enabled)
        assertTrue(menu(hasSelection = true).row("pane.copy").enabled)
        // Nothing else waits for a selection.
        assertEquals(listOf("pane.copy"), menu().flatten().filter { !it.enabled }.map { it.action })
    }

    @Test
    fun `a row that can't be used stays in its place, dimmed`() {
        val alone = setOf("pane.balance", "pane.move_to_tab", "pane.move_to_new_tab")
        val rows = menu(unavailable = alone, hasSelection = true)

        assertEquals(menu().map { group -> group.map { it.action } }, rows.map { group -> group.map { it.action } })
        assertEquals(alone, rows.flatten().filter { !it.enabled }.map { it.action }.toSet())
    }

    @Test
    fun `copy with a selection is still dimmed where there is no terminal to copy from`() {
        assertFalse(menu(unavailable = setOf("pane.copy"), hasSelection = true).row("pane.copy").enabled)
    }

    @Test
    fun `what the server does is listed only if the server offers it, and needs a terminal`() {
        val without = menu(serverActions = emptyList())
        assertEquals(listOf("Close Pane"), without.last().map { it.title })

        val onlyReset = menu(serverActions = server.drop(1))
        assertEquals(listOf("Close Pane", "Reset Terminal"), onlyReset.last().map { it.title })

        val noTerminal = menu(hasTerminal = false)
        assertFalse(noTerminal.row(CLEAR_TERMINAL).enabled)
        assertFalse(noTerminal.row(RESET_TERMINAL).enabled)
        assertTrue(menu().row(RESET_TERMINAL).enabled)
    }

    @Test
    fun `a zoomed pane is offered the way back`() {
        assertEquals("Unzoom Pane", menu(zoomed = true).row("pane.zoom").title)
        assertEquals("Zoom Pane", menu().row("pane.zoom").title)
    }

    @Test
    fun `a row shows the key that does the same, if there is one`() {
        val rows = menu(shortcuts = mapOf("pane.copy" to "Ctrl+Shift+C", RESET_TERMINAL to "Ctrl+Alt+R"))

        assertEquals("Ctrl+Shift+C", rows.row("pane.copy").shortcut)
        assertEquals("Ctrl+Alt+R", rows.row(RESET_TERMINAL).shortcut)
        assertNull(rows.row("pane.rename").shortcut)
    }

    @Test
    fun `the menu has Rex Rune's action and key`() {
        val action = assertNotNull(actions.firstOrNull { it.name == "pane.actions" })
        assertEquals(listOf("ctrl+shift+space"), action.keys)
        assertNotNull(KeyChord.parse(action.keys.single()))
        // It opens the menu, so it isn't a row of it.
        assertTrue(PANE_MENU.flatten().none { it.action == "pane.actions" })
    }

    // --- Moving through it with the arrow keys ---

    @Test
    fun `the arrows move through the rows that can be used, and round at the ends`() {
        val usable = listOf(false, true, true, false, true)

        assertEquals(1, stepMenuRow(usable, -1, down = true))
        assertEquals(4, stepMenuRow(usable, -1, down = false))
        assertEquals(2, stepMenuRow(usable, 1, down = true))
        assertEquals(4, stepMenuRow(usable, 2, down = true))
        assertEquals(1, stepMenuRow(usable, 4, down = true))
        assertEquals(4, stepMenuRow(usable, 1, down = false))
        assertEquals(2, stepMenuRow(usable, 4, down = false))
    }

    @Test
    fun `with one usable row the arrows stay on it, and with none they go nowhere`() {
        assertEquals(1, stepMenuRow(listOf(false, true, false), 1, down = true))
        assertEquals(1, stepMenuRow(listOf(false, true, false), 1, down = false))
        assertNull(stepMenuRow(listOf(false, false), -1, down = true))
        assertNull(stepMenuRow(emptyList(), -1, down = true))
    }

    // --- Where it opens ---

    private val window = IntSize(1000, 800)
    private val size = IntSize(260, 500)

    @Test
    fun `the menu opens with its corner where the click was`() {
        assertEquals(IntOffset(300, 200), menuPosition(IntOffset(300, 200), size, window))
        assertEquals(IntOffset(740, 300), menuPosition(IntOffset(740, 300), size, window))
    }

    @Test
    fun `near the right edge it opens to the left of the click`() {
        assertEquals(IntOffset(640, 200), menuPosition(IntOffset(900, 200), size, window))
    }

    @Test
    fun `near the bottom it opens above the click`() {
        assertEquals(IntOffset(300, 200), menuPosition(IntOffset(300, 700), size, window))
        assertEquals(IntOffset(640, 290), menuPosition(IntOffset(900, 790), size, window))
    }

    @Test
    fun `with room neither below nor above it is moved up until it fits`() {
        assertEquals(IntOffset(300, 300), menuPosition(IntOffset(300, 400), size, window))
    }

    @Test
    fun `in a window smaller than the menu it starts at the top and the left`() {
        assertEquals(IntOffset(0, 0), menuPosition(IntOffset(100, 100), size, IntSize(200, 300)))
    }
}
