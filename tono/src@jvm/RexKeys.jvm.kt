package org.example.kotlintest

import org.example.kotlintest.rex.KeyChord
import org.example.kotlintest.rex.RexKey
import androidx.compose.ui.awt.awtEventOrNull
import java.awt.event.KeyEvent
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

/**
 * Turns a key event from the window system into the form the Rex server takes.
 *
 * The server wants the physical key, by its W3C `KeyboardEvent.code` name, and does the
 * rest itself. AWT reports keys by what a US layout calls them rather than by position, so
 * on other layouts some keys will be named for the wrong position; the text they type is
 * still right.
 *
 * Returns null for keys that aren't sent: the modifiers on their own, and keys with no name.
 */
internal fun rexKey(event: KeyEvent, repeat: Boolean): RexKey? {
    val name = keyName(event) ?: return null
    return rexKeyNamed(
        name,
        typed = event.keyChar.takeIf { it != KeyEvent.CHAR_UNDEFINED },
        pressed = event.id != KeyEvent.KEY_RELEASED,
        repeat = repeat,
        shift = event.isShiftDown, control = event.isControlDown, alt = event.isAltDown, meta = event.isMetaDown,
    )
}

/**
 * The key and modifiers of a press, in the form keymaps are written in (`ctrl+shift+t`), for
 * looking up shortcuts. Null for keys that can't be bound, such as a modifier on its own.
 */
internal fun keyChord(event: KeyEvent): KeyChord? {
    val name = keyName(event) ?: return null
    return keyChordNamed(name, shift = event.isShiftDown, control = event.isControlDown, alt = event.isAltDown, meta = event.isMetaDown)
}

/** The same, for the events Compose hands over: on the desktop each wraps one from the window toolkit. */
internal actual fun rexKey(event: ComposeKeyEvent, repeat: Boolean): RexKey? = event.awtEventOrNull?.let { rexKey(it, repeat) }

internal actual fun keyChord(event: ComposeKeyEvent): KeyChord? = event.awtEventOrNull?.let(::keyChord)

private fun keyName(event: KeyEvent): String? {
    val code = event.keyCode
    val numpad = event.keyLocation == KeyEvent.KEY_LOCATION_NUMPAD
    return when (code) {
        in KeyEvent.VK_A..KeyEvent.VK_Z -> "key_" + ('a' + (code - KeyEvent.VK_A))
        in KeyEvent.VK_0..KeyEvent.VK_9 -> "digit_" + (code - KeyEvent.VK_0)
        in KeyEvent.VK_NUMPAD0..KeyEvent.VK_NUMPAD9 -> "numpad_" + (code - KeyEvent.VK_NUMPAD0)
        in KeyEvent.VK_F1..KeyEvent.VK_F12 -> "f" + (code - KeyEvent.VK_F1 + 1)
        in KeyEvent.VK_F13..KeyEvent.VK_F24 -> "f" + (code - KeyEvent.VK_F13 + 13)
        KeyEvent.VK_ENTER -> if (numpad) "numpad_enter" else "enter"
        else -> NAMES[code]
    }
}

private val NAMES = mapOf(
    KeyEvent.VK_BACK_SPACE to "backspace",
    KeyEvent.VK_TAB to "tab",
    KeyEvent.VK_SPACE to "space",
    KeyEvent.VK_ESCAPE to "escape",
    KeyEvent.VK_DELETE to "delete",
    KeyEvent.VK_INSERT to "insert",
    KeyEvent.VK_HOME to "home",
    KeyEvent.VK_END to "end",
    KeyEvent.VK_PAGE_UP to "page_up",
    KeyEvent.VK_PAGE_DOWN to "page_down",
    KeyEvent.VK_UP to "arrow_up",
    KeyEvent.VK_DOWN to "arrow_down",
    KeyEvent.VK_LEFT to "arrow_left",
    KeyEvent.VK_RIGHT to "arrow_right",
    KeyEvent.VK_KP_UP to "arrow_up",
    KeyEvent.VK_KP_DOWN to "arrow_down",
    KeyEvent.VK_KP_LEFT to "arrow_left",
    KeyEvent.VK_KP_RIGHT to "arrow_right",
    KeyEvent.VK_MINUS to "minus",
    KeyEvent.VK_EQUALS to "equal",
    KeyEvent.VK_OPEN_BRACKET to "bracket_left",
    KeyEvent.VK_CLOSE_BRACKET to "bracket_right",
    KeyEvent.VK_BACK_SLASH to "backslash",
    KeyEvent.VK_SEMICOLON to "semicolon",
    KeyEvent.VK_QUOTE to "quote",
    KeyEvent.VK_BACK_QUOTE to "backquote",
    KeyEvent.VK_COMMA to "comma",
    KeyEvent.VK_PERIOD to "period",
    KeyEvent.VK_SLASH to "slash",
    KeyEvent.VK_ADD to "numpad_add",
    KeyEvent.VK_SUBTRACT to "numpad_subtract",
    KeyEvent.VK_MULTIPLY to "numpad_multiply",
    KeyEvent.VK_DIVIDE to "numpad_divide",
    KeyEvent.VK_DECIMAL to "numpad_decimal",
    KeyEvent.VK_PRINTSCREEN to "print_screen",
    KeyEvent.VK_PAUSE to "pause",
    KeyEvent.VK_CONTEXT_MENU to "context_menu",
)
