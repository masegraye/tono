package org.example.kotlintest

import org.example.kotlintest.rex.KeyChord
import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RexShortcutsTest {
    private val source = object : Component() {}

    private fun chord(code: Int, modifiers: Int = 0, char: Char = KeyEvent.CHAR_UNDEFINED) =
        keyChord(KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, modifiers, code, char, KeyEvent.KEY_LOCATION_STANDARD))

    private val ctrl = InputEvent.CTRL_DOWN_MASK
    private val shift = InputEvent.SHIFT_DOWN_MASK
    private val alt = InputEvent.ALT_DOWN_MASK

    @Test
    fun `key presses are spelled the way keymaps are`() {
        assertEquals(KeyChord.parse("ctrl+1"), chord(KeyEvent.VK_1, ctrl))
        assertEquals(KeyChord.parse("ctrl+shift+t"), chord(KeyEvent.VK_T, ctrl or shift, 'T'))
        assertEquals(KeyChord.parse("ctrl+`"), chord(KeyEvent.VK_BACK_QUOTE, ctrl))
        assertEquals(KeyChord.parse("ctrl+tab"), chord(KeyEvent.VK_TAB, ctrl, '\t'))
        assertEquals(KeyChord.parse("ctrl+shift+tab"), chord(KeyEvent.VK_TAB, ctrl or shift, '\t'))
        assertEquals(KeyChord.parse("shift+pageup"), chord(KeyEvent.VK_PAGE_UP, shift))
        assertEquals(KeyChord.parse("ctrl+alt+left"), chord(KeyEvent.VK_LEFT, ctrl or alt))
        assertEquals(KeyChord.parse("ctrl+shift+enter"), chord(KeyEvent.VK_ENTER, ctrl or shift, '\n'))
        assertEquals(KeyChord.parse("ctrl+shift+]"), chord(KeyEvent.VK_CLOSE_BRACKET, ctrl or shift, '}'))
        assertEquals(KeyChord.parse("ctrl+="), chord(KeyEvent.VK_EQUALS, ctrl, '='))
        assertEquals(KeyChord.parse("ctrl+-"), chord(KeyEvent.VK_MINUS, ctrl, '-'))
        assertEquals(KeyChord("a"), chord(KeyEvent.VK_A, char = 'a'))
        assertEquals(KeyChord("f5"), chord(KeyEvent.VK_F5))
    }

    @Test
    fun `a modifier on its own is not a shortcut`() {
        assertNull(chord(KeyEvent.VK_CONTROL, ctrl))
        assertNull(chord(KeyEvent.VK_SHIFT, shift))
    }

    @Test
    fun `every default key is readable, and no two actions claim the same one`() {
        val bound = clientActions().flatMap { action -> (action.keys + action.argKeys.map { it.first }).map { it to action.name } }
        for ((keys, action) in bound) assertNotNull(KeyChord.parse(keys), "$action: $keys")
        val clashes = bound.groupBy({ KeyChord.parse(it.first) }, { it.second }).filterValues { it.size > 1 }
        assertTrue(clashes.isEmpty(), "bound more than once: $clashes")
    }

    @Test
    fun `the tab keys are control and a digit, with nine for the last tab`() {
        val goto = clientActions().first { it.name == "client.tab.goto" }
        assertEquals((1..9).map { "ctrl+$it" }, goto.argKeys.map { it.first })
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "-1"), goto.argKeys.map { it.second["index"].toString() })
    }

    @Test
    fun `panes are rearranged by Rex's names, and moved with control, alt, shift and an arrow`() {
        val actions = clientActions().associateBy { it.name }
        for (name in listOf("pane.move", "pane.resize", "pane.balance", "pane.move_to_tab", "pane.move_to_new_tab", "pane.rename")) assertNotNull(actions[name], name)
        for (side in listOf("left", "right", "up", "down")) assertEquals(listOf("ctrl+alt+shift+$side"), actions.getValue("pane.move.$side").keys)
        // Resizing takes a direction, so the palette lists it once for each.
        val resize = actions.getValue("pane.resize")
        assertEquals(false, resize.inPalette)
        assertEquals(listOf("Resize Pane Left", "Resize Pane Right", "Resize Pane Up", "Resize Pane Down"), resize.variants.map { it.first })
        assertEquals(listOf("\"left\"", "\"right\"", "\"up\"", "\"down\""), resize.variants.map { it.second["direction"].toString() })
    }

    @Test
    fun `action names are unique`() {
        val names = clientActions().map { it.name }
        assertEquals(names.size, names.toSet().size)
    }
}

class PaletteFilterTest {
    private fun item(title: String, detail: String = "", keywords: String = "") = PaletteItem(title, detail, keywords = keywords) {}

    private val items = listOf(
        item("New Tab", "Tab", "create window"),
        item("New Session", "Session", "create"),
        item("Close Tab", "Tab"),
        item("Split Pane Right", "Pane", "divide new terminal"),
        item("Go to Session: kotlin-test-dev", "Local", "session"),
    )

    private fun search(query: String) = filterPalette(items, query).map { it.title }

    @Test
    fun `an empty search lists everything in order`() {
        assertEquals(items.map { it.title }, search(""))
        assertEquals(items.map { it.title }, search("   "))
    }

    @Test
    fun `a title match comes before a keyword match`() {
        // "new" starts two titles, and is only a keyword of the split.
        assertEquals(listOf("New Tab", "New Session", "Split Pane Right"), search("new"))
    }

    @Test
    fun `every word has to match`() {
        assertEquals(listOf("New Tab"), search("new tab"))
        assertEquals(listOf("Close Tab"), search("tab close"))
        assertEquals(emptyList(), search("new nonsense"))
    }

    @Test
    fun `letters in order are enough`() {
        assertEquals(listOf("New Tab"), search("nwtb"))
    }

    @Test
    fun `places are found by where they are`() {
        assertEquals(listOf("Go to Session: kotlin-test-dev"), search("local"))
        assertEquals(listOf("Go to Session: kotlin-test-dev"), search("kotlin"))
    }
}
