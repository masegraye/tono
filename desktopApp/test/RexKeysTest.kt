package org.example.kotlintest

import org.example.kotlintest.rex.RexKey
import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RexKeysTest {
    private val source = object : Component() {}

    private fun event(
        code: Int,
        char: Char = KeyEvent.CHAR_UNDEFINED,
        modifiers: Int = 0,
        id: Int = KeyEvent.KEY_PRESSED,
        location: Int = KeyEvent.KEY_LOCATION_STANDARD,
    ) = KeyEvent(source, id, 0L, modifiers, code, char, location)

    @Test
    fun `a letter types itself`() {
        assertEquals(
            RexKey("key_a", mods = "", consumedMods = "", text = "a", unshiftedCodepoint = 'a'.code, action = RexKey.Action.Press),
            rexKey(event(KeyEvent.VK_A, 'a'), repeat = false),
        )
    }

    @Test
    fun `shift is consumed when it changes what is typed`() {
        val capital = rexKey(event(KeyEvent.VK_A, 'A', InputEvent.SHIFT_DOWN_MASK), repeat = false)!!
        assertEquals("shift", capital.mods)
        assertEquals("shift", capital.consumedMods)
        assertEquals("A", capital.text)
        assertEquals('a'.code, capital.unshiftedCodepoint)

        val bang = rexKey(event(KeyEvent.VK_1, '!', InputEvent.SHIFT_DOWN_MASK), repeat = false)!!
        assertEquals("digit_1", bang.key)
        assertEquals("!", bang.text)
        assertEquals("shift", bang.consumedMods)
        assertEquals('1'.code, bang.unshiftedCodepoint)
    }

    @Test
    fun `a key held with control is a command, not text`() {
        // AWT reports ctrl+c as the control character 0x03.
        val key = rexKey(event(KeyEvent.VK_C, '\u0003', InputEvent.CTRL_DOWN_MASK), repeat = false)!!
        assertEquals("key_c", key.key)
        assertEquals("ctrl", key.mods)
        assertEquals("", key.text)
        assertEquals("", key.consumedMods)
        assertEquals('c'.code, key.unshiftedCodepoint)
    }

    @Test
    fun `modifiers are listed in the order the server expects`() {
        val all = InputEvent.SHIFT_DOWN_MASK or InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK or InputEvent.META_DOWN_MASK
        assertEquals("shift+ctrl+alt+super", rexKey(event(KeyEvent.VK_X, modifiers = all), repeat = false)!!.mods)
    }

    @Test
    fun `keys that type nothing are named and carry no text`() {
        fun name(code: Int, char: Char = KeyEvent.CHAR_UNDEFINED, location: Int = KeyEvent.KEY_LOCATION_STANDARD) =
            rexKey(event(code, char, location = location), repeat = false)!!.let { it.key to it.text }

        assertEquals("enter" to "", name(KeyEvent.VK_ENTER, '\n'))
        assertEquals("numpad_enter" to "", name(KeyEvent.VK_ENTER, '\n', KeyEvent.KEY_LOCATION_NUMPAD))
        assertEquals("tab" to "", name(KeyEvent.VK_TAB, '\t'))
        assertEquals("backspace" to "", name(KeyEvent.VK_BACK_SPACE, '\b'))
        assertEquals("escape" to "", name(KeyEvent.VK_ESCAPE, '\u001b'))
        assertEquals("delete" to "", name(KeyEvent.VK_DELETE, '\u007f'))
        assertEquals("arrow_up" to "", name(KeyEvent.VK_UP))
        assertEquals("page_down" to "", name(KeyEvent.VK_PAGE_DOWN))
        assertEquals("f5" to "", name(KeyEvent.VK_F5))
        assertEquals("f13" to "", name(KeyEvent.VK_F13))
        assertEquals("space" to " ", name(KeyEvent.VK_SPACE, ' '))
        assertEquals("slash" to "/", name(KeyEvent.VK_SLASH, '/'))
        assertEquals("numpad_7" to "7", name(KeyEvent.VK_NUMPAD7, '7', KeyEvent.KEY_LOCATION_NUMPAD))
    }

    @Test
    fun `press, repeat and release are told apart`() {
        assertEquals(RexKey.Action.Press, rexKey(event(KeyEvent.VK_J, 'j'), repeat = false)!!.action)
        assertEquals(RexKey.Action.Repeat, rexKey(event(KeyEvent.VK_J, 'j'), repeat = true)!!.action)
        val release = rexKey(event(KeyEvent.VK_J, 'j', id = KeyEvent.KEY_RELEASED), repeat = false)!!
        assertEquals(RexKey.Action.Release, release.action)
        assertEquals("", release.text)
    }

    @Test
    fun `modifier keys on their own are not sent`() {
        assertNull(rexKey(event(KeyEvent.VK_SHIFT, modifiers = InputEvent.SHIFT_DOWN_MASK), repeat = false))
        assertNull(rexKey(event(KeyEvent.VK_CONTROL, modifiers = InputEvent.CTRL_DOWN_MASK), repeat = false))
    }
}
