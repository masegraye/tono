package org.example.kotlintest

import org.example.kotlintest.rex.KeyChord
import org.example.kotlintest.rex.RexKey
import java.awt.event.KeyEvent

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
    val shift = event.isShiftDown
    val control = event.isControlDown
    val alt = event.isAltDown
    val meta = event.isMetaDown
    val mods = listOfNotNull(
        "shift".takeIf { shift }, "ctrl".takeIf { control }, "alt".takeIf { alt }, "super".takeIf { meta },
    ).joinToString("+")

    val unshifted = UNSHIFTED[name] ?: 0
    val char = event.keyChar
    val printable = char != KeyEvent.CHAR_UNDEFINED && char.code >= 0x20 && char.code != 0x7F
    // With a modifier held the key is a command, not text; the server encodes it from the key.
    val text = if (printable && !control && !alt && !meta && event.id == KeyEvent.KEY_PRESSED) char.toString() else ""
    val consumed = if (shift && text.isNotEmpty() && text[0].code != unshifted) "shift" else ""

    val action = when {
        event.id == KeyEvent.KEY_RELEASED -> RexKey.Action.Release
        repeat -> RexKey.Action.Repeat
        else -> RexKey.Action.Press
    }
    return RexKey(name, mods, consumed, text, unshifted, action)
}

/**
 * The key and modifiers of a press, in the form keymaps are written in (`ctrl+shift+t`), for
 * looking up shortcuts. Null for keys that can't be bound, such as a modifier on its own.
 */
internal fun keyChord(event: KeyEvent): KeyChord? {
    val name = keyName(event) ?: return null
    val key = CHORD_KEYS[name]
        ?: name.removePrefix("key_").takeIf { name.startsWith("key_") }
        ?: name.removePrefix("digit_").takeIf { name.startsWith("digit_") }
        ?: name.takeIf { it.matches(Regex("f\\d+")) }
        ?: return null
    return KeyChord(key, ctrl = event.isControlDown, alt = event.isAltDown, shift = event.isShiftDown, cmd = event.isMetaDown)
}

/** How Rex spells the keys whose names differ from the ones the server takes in `client.key`. */
private val CHORD_KEYS = mapOf(
    "enter" to "enter", "tab" to "tab", "space" to "space", "escape" to "escape", "backspace" to "backspace",
    "delete" to "delete", "insert" to "insert", "home" to "home", "end" to "end",
    "page_up" to "pageup", "page_down" to "pagedown",
    "arrow_up" to "up", "arrow_down" to "down", "arrow_left" to "left", "arrow_right" to "right",
    "minus" to "-", "equal" to "=", "bracket_left" to "[", "bracket_right" to "]", "backslash" to "\\",
    "semicolon" to ";", "quote" to "'", "backquote" to "`", "comma" to ",", "period" to ".", "slash" to "/",
)

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

/** What each key types without shift, on a US layout. Bindings such as ctrl+n match on it. */
private val UNSHIFTED: Map<String, Int> = buildMap {
    for (letter in 'a'..'z') put("key_$letter", letter.code)
    for (digit in '0'..'9') put("digit_$digit", digit.code)
    put("space", ' '.code)
    put("minus", '-'.code)
    put("equal", '='.code)
    put("bracket_left", '['.code)
    put("bracket_right", ']'.code)
    put("backslash", '\\'.code)
    put("semicolon", ';'.code)
    put("quote", '\''.code)
    put("backquote", '`'.code)
    put("comma", ','.code)
    put("period", '.'.code)
    put("slash", '/'.code)
}
