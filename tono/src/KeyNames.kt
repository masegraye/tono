package org.example.kotlintest

import org.example.kotlintest.rex.KeyChord
import org.example.kotlintest.rex.RexKey

/*
 * Keys by the names Rex knows them by, which are the W3C `KeyboardEvent.code` names in
 * snake_case (`key_a`, `digit_1`, `bracket_left`). Each platform works out a key's name from
 * its own events; what is done with the name is the same everywhere, and is here.
 */

/**
 * The key Rex is sent for a press, release or repeat.
 *
 * @param typed the character the key typed, if it typed one that prints
 */
internal fun rexKeyNamed(
    name: String, typed: Char?, pressed: Boolean, repeat: Boolean,
    shift: Boolean, control: Boolean, alt: Boolean, meta: Boolean,
): RexKey {
    val mods = listOfNotNull(
        "shift".takeIf { shift }, "ctrl".takeIf { control }, "alt".takeIf { alt }, "super".takeIf { meta },
    ).joinToString("+")
    val unshifted = UNSHIFTED[name] ?: 0
    val printable = typed != null && typed.code >= 0x20 && typed.code != 0x7F
    // With a modifier held the key is a command, not text; the server encodes it from the key.
    val text = if (printable && !control && !alt && !meta && pressed) typed.toString() else ""
    val consumed = if (shift && text.isNotEmpty() && text[0].code != unshifted) "shift" else ""
    val action = when {
        !pressed -> RexKey.Action.Release
        repeat -> RexKey.Action.Repeat
        else -> RexKey.Action.Press
    }
    return RexKey(name, mods, consumed, text, unshifted, action)
}

/** The chord a named key makes with these modifiers, or null for a key that can't be bound. */
internal fun keyChordNamed(name: String, shift: Boolean, control: Boolean, alt: Boolean, meta: Boolean): KeyChord? {
    val key = CHORD_KEYS[name]
        ?: name.removePrefix("key_").takeIf { name.startsWith("key_") }
        ?: name.removePrefix("digit_").takeIf { name.startsWith("digit_") }
        ?: name.takeIf { it.matches(Regex("f\\d+")) }
        ?: return null
    return KeyChord(key, ctrl = control, alt = alt, shift = shift, cmd = meta)
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
