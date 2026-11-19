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
        assertEquals(KeyChord.parse("ctrl+shift+pageup"), chord(KeyEvent.VK_PAGE_UP, ctrl or shift))
        assertEquals(KeyChord.parse("ctrl+shift+pagedown"), chord(KeyEvent.VK_PAGE_DOWN, ctrl or shift))
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
    fun `back and forward have Rex Rune's names, and brackets with control and alt`() {
        val actions = clientActions().associateBy { it.name }
        assertEquals(listOf("ctrl+alt+["), actions.getValue("client.history.back").keys)
        assertEquals(listOf("ctrl+alt+]"), actions.getValue("client.history.forward").keys)
        assertEquals(KeyChord.parse("ctrl+alt+["), chord(KeyEvent.VK_OPEN_BRACKET, ctrl or alt))
        assertEquals(KeyChord.parse("ctrl+alt+]"), chord(KeyEvent.VK_CLOSE_BRACKET, ctrl or alt))
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
    fun `tabs and sessions are managed by Rex's names, and a tab moves with control, shift and page up or down`() {
        val actions = clientActions().associateBy { it.name }
        for (name in listOf("client.tab.move", "client.tab.close_others", "client.tab.mute", "session.rename")) assertNotNull(actions[name], name)
        assertEquals(listOf("ctrl+shift+pageup"), actions.getValue("client.tab.move.backward").keys)
        assertEquals(listOf("ctrl+shift+pagedown"), actions.getValue("client.tab.move.forward").keys)
        // Moving takes a direction, so the palette lists the two that name theirs.
        assertEquals(false, actions.getValue("client.tab.move").inPalette)
        for (name in listOf("client.tab.move.backward", "client.tab.move.forward", "client.tab.close_others", "client.tab.mute", "session.rename")) {
            assertTrue(actions.getValue(name).inPalette, name)
        }
    }

    @Test
    fun `a path is inserted and a directory gone to by Rex's names, and both are in the palette`() {
        val actions = clientActions().associateBy { it.name }
        val insert = actions.getValue("pane.insert_path")
        val go = actions.getValue("pane.go_to_directory")
        assertEquals("Insert Path…", insert.title)
        assertEquals("Go to Directory…", go.title)
        assertTrue(insert.inPalette && go.inPalette)
        // Control+Shift+I opens a browser's developer tools, and the find keys have both G's.
        assertEquals(listOf("ctrl+alt+i"), insert.keys)
        assertEquals(emptyList(), go.keys)
    }

    @Test
    fun `the browser says what choosing each row does`() {
        val typed = org.example.kotlintest.rex.PathRow("~/dev", "/home/dev/dev", org.example.kotlintest.rex.PathRow.Kind.Typed)
        val folder = org.example.kotlintest.rex.PathRow("src", "/home/dev/src", org.example.kotlintest.rex.PathRow.Kind.Folder)
        val file = org.example.kotlintest.rex.PathRow("a.txt", "/home/dev/a.txt", org.example.kotlintest.rex.PathRow.Kind.File)
        assertEquals(listOf("Insert typed path", "Folder", "File"), listOf(typed, folder, file).map { pathRowDetail(it, foldersOnly = false) })
        assertEquals(listOf("Go to typed folder", "Folder"), listOf(typed, folder).map { pathRowDetail(it, foldersOnly = true) })
    }

    private fun bound(action: String) = org.example.kotlintest.rex.KeyBinding(listOf(KeyChord("x")), action)

    @Test
    fun `a key bound to an action that can't run now is still the app's, and never the terminal's`() {
        val ours = { name: String -> name.startsWith("pane.") }
        // Control+Alt+Left in a tab with one pane: nowhere to go, so nothing happens, and nothing is typed.
        assertTrue(takesKey(listOf(bound("pane.focus.left")), ours) { false })
        assertTrue(takesKey(listOf(bound("pane.focus.left")), ours) { true })
        // A key bound to nothing of the app's goes to the terminal.
        assertEquals(false, takesKey(emptyList(), ours) { false })
        assertEquals(false, takesKey(listOf(bound("terminal.clear")), ours) { false }, "a server action that couldn't run")
    }

    @Test
    fun `bindings are tried in order, and one that hands the key to the server ends it`() {
        val ours = { _: String -> true }
        val tried = mutableListOf<String>()
        assertTrue(takesKey(listOf(bound("a"), bound("b"), bound("c")), ours) { tried += it.action; it.action == "b" })
        assertEquals(listOf("a", "b"), tried)
        assertEquals(false, takesKey(listOf(bound("client.key.forward"), bound("a")), ours) { true })
        assertEquals(false, takesKey(listOf(bound("a"), bound("client.key.forward")), ours) { false }, "even after one of the app's that couldn't run")
    }

    @Test
    fun `the pane keys that leaked to a one-pane tab are all the app's`() {
        val actions = clientActions().associateBy { it.name }
        for (side in listOf("left", "right", "up", "down")) {
            assertEquals(listOf("ctrl+alt+$side"), actions.getValue("pane.focus.$side").keys)
            assertEquals(listOf("ctrl+alt+shift+$side"), actions.getValue("pane.move.$side").keys)
        }
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
