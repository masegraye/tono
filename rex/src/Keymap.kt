package org.example.kotlintest.rex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One key press with its modifiers, such as Ctrl+Shift+T. */
data class KeyChord(
    val key: String,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val cmd: Boolean = false,
) {
    /** The spelling Rex uses in keymaps and configuration files, such as `ctrl+shift+t`. */
    fun toWire(): String = listOfNotNull(
        "ctrl".takeIf { ctrl }, "alt".takeIf { alt }, "shift".takeIf { shift }, "cmd".takeIf { cmd }, key,
    ).joinToString("+")

    /** For showing to a person, such as `Ctrl+Shift+T`. */
    fun display(): String = listOfNotNull(
        "Ctrl".takeIf { ctrl }, "Alt".takeIf { alt }, "Shift".takeIf { shift }, "Super".takeIf { cmd },
        DISPLAY_NAMES[key] ?: key.replaceFirstChar { it.uppercase() },
    ).joinToString("+")

    companion object {
        /** Reads a key as Rex spells it. Returns null for a spelling this client doesn't understand. */
        fun parse(text: String): KeyChord? {
            val spelled = text.trim().lowercase()
            if (spelled.isEmpty()) return null
            // The plus key itself: "+", or "ctrl++".
            val plusKey = spelled == "+" || spelled.endsWith("++")
            val parts = (if (plusKey) spelled.dropLast(1) else spelled).split('+').filter { it.isNotEmpty() }
            val key = if (plusKey) "+" else parts.lastOrNull() ?: return null
            val modifiers = if (plusKey) parts else parts.dropLast(1)
            var chord = KeyChord(normalizeKey(key))
            for (modifier in modifiers) {
                chord = when (modifier) {
                    "ctrl", "control" -> chord.copy(ctrl = true)
                    "alt", "opt", "option" -> chord.copy(alt = true)
                    "shift" -> chord.copy(shift = true)
                    "cmd", "super", "meta", "win" -> chord.copy(cmd = true)
                    else -> return null
                }
            }
            return chord
        }

        /** `[KeyK]` names a key by position; this client treats it as the key a US layout puts there. */
        private fun normalizeKey(key: String): String = when {
            key.startsWith("[key") && key.endsWith("]") && key.length == 6 -> key.substring(4, 5)
            key.startsWith("[digit") && key.endsWith("]") && key.length == 8 -> key.substring(6, 7)
            else -> key
        }

        private val DISPLAY_NAMES = mapOf(
            "pageup" to "PageUp", "pagedown" to "PageDown", "left" to "←", "right" to "→", "up" to "↑", "down" to "↓",
            "enter" to "Enter", "tab" to "Tab", "space" to "Space", "escape" to "Esc", "backspace" to "Backspace",
        )
    }
}

/** Something a client or the server can do by name, such as `client.tab.new`. */
data class RexAction(
    val name: String,
    val title: String,
    val category: String,
    /** More words to find the action by. */
    val keywords: String = "",
    /** What performs it: `client`, `lua` (the configuration file), or `block`. */
    val kind: String = "client",
)

/** Keys bound to an action. More than one key is a sequence, pressed in order. */
data class KeyBinding(
    val keys: List<KeyChord>,
    val action: String,
    val args: JsonObject? = null,
    /** Where the binding came from: a layer name such as `default`, or a file and line. */
    val source: String? = null,
    /**
     * The keys as the server spelled them, where that isn't how [KeyChord.toWire] spells them
     * (`ctrl+[KeyK]`). A removal has to use the same spelling to take the binding off.
     */
    val spelled: List<String>? = null,
) {
    /** The keys as a removal names them. */
    val wireKeys: List<String> get() = spelled ?: keys.map { it.toWire() }

    /** Whether this binds [action] with these arguments exactly. No arguments and empty ones are the same. */
    fun isFor(action: String, args: JsonObject?): Boolean = this.action == action && sameArgs(this.args, args)
}

/** A problem the server found in the keymap, such as a binding to an action that doesn't exist. */
data class KeymapDiagnostic(
    val severity: String,
    val message: String,
    /** The layer or `file:line` it is about, if known. */
    val source: String? = null,
    val action: String? = null,
    val key: String? = null,
)

internal fun sameArgs(one: JsonObject?, other: JsonObject?): Boolean = one?.takeIf { it.isNotEmpty() } == other?.takeIf { it.isNotEmpty() }

/**
 * What each key does, as the server resolved it: this client's default bindings with the
 * account's configuration file layered on top.
 */
class Keymap(
    /** In the order to try them: for a key bound more than once, the first available action wins. */
    val bindings: List<KeyBinding>,
    /** Actions the server performs, such as clearing a terminal, that keys can be bound to. */
    val serverActions: List<RexAction> = emptyList(),
    /** What the server found wrong with the keymap. */
    val diagnostics: List<KeymapDiagnostic> = emptyList(),
) {
    /** The bindings on a single key press, in the order to try them. Sequences aren't supported yet. */
    fun bindingsFor(chord: KeyChord): List<KeyBinding> = bindings.filter { it.keys.size == 1 && it.keys[0] == chord }

    /** The key to show beside an action in a menu, if it has one. */
    fun shortcutFor(action: String, args: JsonObject? = null): KeyChord? =
        bindings.firstOrNull { it.keys.size == 1 && it.action == action && (args == null || it.args == args) }?.keys?.first()

    /** Every binding of an action with exactly these arguments, topmost first. */
    fun bindingsOf(action: String, args: JsonObject? = null): List<KeyBinding> = bindings.filter { it.isFor(action, args) }
}

/**
 * The `client.keymap` request that declares this client's actions and default bindings, and
 * the shortcuts changed in this app, which the server lays over its configuration file.
 */
internal fun keymapRequest(actions: List<RexAction>, defaults: List<KeyBinding>, overrides: ShortcutOverrides = ShortcutOverrides()): JsonObject = buildJsonObject {
    put("actions", buildJsonArray {
        for (action in actions) add(buildJsonObject {
            put("name", action.name)
            put("kind", "client")
            put("title", action.title)
            put("category", action.category)
            if (action.keywords.isNotEmpty()) put("keywords", action.keywords)
        })
    })
    put("defaults", buildJsonArray {
        add(buildJsonObject {
            put("name", "default")
            put("bindings", buildJsonArray {
                for (binding in defaults) add(buildJsonObject {
                    put("keys", buildJsonArray { binding.keys.forEach { add(JsonPrimitive(it.toWire())) } })
                    put("action", binding.action)
                    binding.args?.let { put("args", it) }
                })
            })
        })
    })
    // Left out when nothing was changed: an empty layer would only be something to name.
    if (!overrides.isEmpty) put("overrides", buildJsonArray { add(overrides.toLayer()) })
    put("server_actions", "all")
}

internal fun parseKeymap(result: JsonObject): Keymap {
    val bindings = (result["bindings"] as? JsonArray).orEmpty().mapNotNull { element ->
        val binding = element as? JsonObject ?: return@mapNotNull null
        // Modes (such as a resize mode) need the client to track which is active. Not yet.
        if (binding.string("mode") != null) return@mapNotNull null
        val keys = (binding["keys"] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.content?.let(KeyChord::parse) }
        if (keys.isEmpty() || keys.any { it == null }) return@mapNotNull null
        val chords = keys.filterNotNull()
        val written = (binding["keys"] as JsonArray).map { (it as JsonPrimitive).content }
        KeyBinding(
            chords, binding.string("action") ?: return@mapNotNull null, binding["args"] as? JsonObject, binding.string("source"),
            spelled = written.takeIf { it != chords.map(KeyChord::toWire) },
        )
    }
    val actions = (result["actions"] as? JsonArray).orEmpty().mapNotNull { element ->
        val action = element as? JsonObject ?: return@mapNotNull null
        // Every method of a block is listed; the ones meant for people have a title.
        val title = action.string("title") ?: return@mapNotNull null
        RexAction(action.string("name") ?: return@mapNotNull null, title, action.string("category") ?: "Actions", action.string("keywords").orEmpty(), action.string("kind") ?: "lua")
    }
    val diagnostics = (result["diagnostics"] as? JsonArray).orEmpty().mapNotNull { element ->
        val problem = element as? JsonObject ?: return@mapNotNull null
        KeymapDiagnostic(problem.string("severity") ?: "warning", problem.string("message") ?: return@mapNotNull null, problem.string("source"), problem.string("action"), problem.string("key"))
    }
    return Keymap(bindings, actions, diagnostics)
}
