package org.example.kotlintest

import org.example.kotlintest.rex.KeyChord
import org.example.kotlintest.rex.Keymap
import org.example.kotlintest.rex.Shortcut
import org.example.kotlintest.rex.ShortcutOverrides
import org.example.kotlintest.rex.ShortcutTarget
import org.example.kotlintest.rex.resolveLocally
import org.example.kotlintest.rex.shortcutList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChangeShortcutTest {
    private fun chord(keys: String) = KeyChord.parse(keys)!!
    private val actions = clientActions()
    private val defaults = defaultKeyBindings(actions)
    private val shipped = resolveLocally(defaults, ShortcutOverrides())
    private val ours = { name: String -> actions.any { it.name == name } }

    /** What a key press comes to with a keymap: the actions run, and whether the key was the app's. */
    private fun press(keymap: Keymap, keys: String): Pair<Boolean, List<String>> {
        val ran = mutableListOf<String>()
        val taken = takesKey(keymap.bindingsFor(chord(keys)), ours) { ran += it.action; true }
        return taken to ran
    }

    @Test
    fun `as shipped, Control+K is the app's`() {
        assertEquals(true to listOf("session.switch"), press(shipped, "ctrl+k"))
        assertEquals(false to emptyList(), press(shipped, "ctrl+shift+k"))
    }

    @Test
    fun `a key whose only binding was removed goes to the terminal`() {
        val removed = ShortcutOverrides().remove("session.switch", null, shipped)
        assertEquals(false to emptyList(), press(resolveLocally(defaults, removed), "ctrl+k"))
    }

    @Test
    fun `moved to Control+Shift+K, that key is the app's and Control+K the shell's, until it is reset`() {
        val moved = ShortcutOverrides().set("session.switch", null, chord("ctrl+shift+k"), shipped)
        val keymap = resolveLocally(defaults, moved)
        assertEquals(false to emptyList(), press(keymap, "ctrl+k"))
        assertEquals(true to listOf("session.switch"), press(keymap, "ctrl+shift+k"))
        // Even when the action can't run, the new key is the app's and the old one is not.
        assertTrue(takesKey(keymap.bindingsFor(chord("ctrl+shift+k")), ours) { false })
        assertFalse(takesKey(keymap.bindingsFor(chord("ctrl+k")), ours) { false })

        val reset = resolveLocally(defaults, moved.reset("session.switch", null, defaults))
        assertEquals(true to listOf("session.switch"), press(reset, "ctrl+k"))
        assertEquals(false to emptyList(), press(reset, "ctrl+shift+k"))
    }

    @Test
    fun `a key taken from another action runs only the one it was given to`() {
        val replaced = ShortcutOverrides().set("session.switch", null, chord("ctrl+shift+f"), shipped)
        assertEquals(true to listOf("session.switch"), press(resolveLocally(defaults, replaced), "ctrl+shift+f"))
    }

    @Test
    fun `changing shortcuts is an action, with no key of its own, as in Rex for Mac`() {
        val action = actions.single { it.name == "client.shortcuts.edit" }
        assertEquals("Change Shortcut…", action.title)
        assertEquals(emptyList(), action.keys)
    }

    private val switch = ShortcutTarget("session.switch", null, "Change Session…", "Session")
    private fun listed(keymap: Keymap, overrides: ShortcutOverrides = ShortcutOverrides()): Shortcut = shortcutList(listOf(switch), keymap, overrides).single()

    @Test
    fun `before a key is pressed, what is offered is removing, and resetting if something was changed`() {
        assertEquals(listOf(ShortcutChoice.Remove), shortcutOptions(listed(shipped), null, emptyList(), "Ctrl+K").map { it.choice })

        val moved = ShortcutOverrides().set("session.switch", null, chord("ctrl+shift+k"), shipped)
        val options = shortcutOptions(listed(resolveLocally(defaults, moved), moved), null, emptyList(), "Ctrl+K")
        assertEquals(listOf(ShortcutChoice.Remove, ShortcutChoice.Reset), options.map { it.choice })
        assertEquals("Ctrl+K", options.last().detail, "what resetting brings back")

        val removed = ShortcutOverrides().remove("session.switch", null, shipped)
        assertEquals(listOf(ShortcutChoice.Reset), shortcutOptions(listed(resolveLocally(defaults, removed), removed), null, emptyList(), "Ctrl+K").map { it.choice })
    }

    @Test
    fun `once keys are pressed, setting them comes first, and says what they are taken from`() {
        val free = shortcutOptions(listed(shipped), chord("ctrl+shift+k"), emptyList(), "Ctrl+K")
        assertEquals(listOf(ShortcutChoice.Set, ShortcutChoice.Remove), free.map { it.choice })
        assertEquals("Set Shortcut to Ctrl+Shift+K", free.first().title)
        assertEquals("in place of Ctrl+K", free.first().detail)

        val taken = shortcutOptions(listed(shipped), chord("ctrl+shift+f"), listOf("Find…"), "Ctrl+K")
        assertEquals("now “Find…”, which will lose it", taken.first().detail)
    }

    @Test
    fun `keys that type are not recorded, and keys that don't are`() {
        for (keys in listOf("ctrl+k", "ctrl+shift+k", "alt+]", "f11", "shift+pageup", "ctrl+enter", "shift+f5")) assertTrue(canRecord(chord(keys)), keys)
        for (keys in listOf("k", "shift+k", "enter", "shift+enter", "tab", "shift+tab", "space", "backspace", "left", "1")) assertFalse(canRecord(chord(keys)), keys)
    }
}
