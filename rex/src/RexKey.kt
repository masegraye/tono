package org.example.kotlintest.rex

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A key event as the keyboard reported it, in the form `client.key` takes. The server
 * decides what it means: it runs any binding for the key, and otherwise encodes it for
 * the terminal, so the client needs no key encoder of its own.
 */
data class RexKey(
    /** The physical key, named after its W3C `KeyboardEvent.code` in snake_case, such as `key_a` or `arrow_up`. */
    val key: String,
    /** The modifiers held, joined with `+` in the order shift, ctrl, alt, super. Empty for none. */
    val mods: String,
    /** The modifiers the layout used to produce [text], such as `shift` for a capital letter. */
    val consumedMods: String,
    /** The text the key typed, or empty for keys that type nothing. */
    val text: String,
    /** The code point the key types without shift, or 0. Bindings such as ctrl+n match on it. */
    val unshiftedCodepoint: Int,
    val action: Action,
) {
    enum class Action { Press, Release, Repeat }

    fun toJson(): JsonObject = buildJsonObject {
        put("key", key)
        put("mods", mods)
        put("consumed_mods", consumedMods)
        put("text", text)
        put("unshifted_codepoint", unshiftedCodepoint)
        put("composing", false)
        put("action", action.name.lowercase())
    }
}
