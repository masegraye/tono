package org.example.kotlintest.rex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Shortcuts changed in this app, for this app alone.
 *
 * Rex stacks a client's keys in layers: the defaults the client ships with, then the
 * account's configuration file, then the client's scripts, then what a person recorded in
 * the client itself. This is that last layer. It is kept in the workspace and sent with
 * every `client.keymap` request as the one layer of `overrides`; the server applies it and
 * answers with what each key does, so nothing here works out a keymap except for the time
 * before the server has answered.
 *
 * What changes the layer is here too (set, remove, reset), so that the Change Shortcut…
 * command and a settings window edit the same thing the same way.
 */

/** The layer's name, which the server gives back as the `source` of its bindings and of warnings about it. */
const val SHORTCUT_LAYER = "tono"

/** A key bound here. [keys] are spelled as Rex spells them; more than one is a sequence. */
data class ShortcutBinding(val keys: List<String>, val action: String, val args: JsonObject? = null)

/**
 * A key taken off the layers beneath.
 *
 * @param action the one action to take off the keys, or null to clear them whatever is bound there
 * @param args the arguments the removed binding had. Kept only to know which shortcut the
 *   removal belongs to (Control+1 is Go to Tab's with `index` 1); the server isn't sent them.
 */
data class ShortcutRemoval(val keys: List<String>, val action: String? = null, val args: JsonObject? = null)

/** Something a key can be bound to: an action, with arguments or without, and what to call it. */
data class ShortcutTarget(
    val action: String,
    val args: JsonObject? = null,
    val title: String,
    val category: String = "",
    val keywords: String = "",
)

/**
 * A target as the keymap has it now.
 *
 * @param bindings its keys, topmost first; empty if it has none
 * @param changed whether this app's layer says anything about it, so that there is something to reset
 */
data class Shortcut(val target: ShortcutTarget, val bindings: List<KeyBinding>, val changed: Boolean) {
    /** The keys, for showing: `Ctrl+K`, or `Ctrl+Shift+N, Ctrl+Alt+N`, or null. */
    fun keysShown(): String? = bindings.takeIf { it.isNotEmpty() }?.joinToString(", ") { binding -> binding.keys.joinToString(" ") { it.display() } }

    /** Where the keys come from, in a word or two: `default`, `Tono`, `configuration file (init.lua:12)`. */
    fun sourceShown(): String = when (val source = bindings.firstOrNull()?.source) {
        null -> if (bindings.isNotEmpty()) "" else if (changed) "removed in Tono" else "no shortcut"
        "default" -> if (changed) "default, changed in Tono" else "default"
        SHORTCUT_LAYER -> "Tono"
        else -> "configuration file ($source)"
    }
}

/**
 * This app's layer of shortcuts. Never changed in place: each change gives a new one.
 *
 * Removals act only on the layers beneath, never on [bindings], which is the server's rule.
 */
data class ShortcutOverrides(val bindings: List<ShortcutBinding> = emptyList(), val removals: List<ShortcutRemoval> = emptyList()) {
    val isEmpty: Boolean get() = bindings.isEmpty() && removals.isEmpty()

    /** Whether the layer says anything about a target. */
    fun changes(action: String, args: JsonObject?): Boolean =
        bindings.any { it.action == action && sameArgs(it.args, args) } || removals.any { it.action == action && sameArgs(it.args, args) }

    /**
     * Gives a target one shortcut, [chord], in place of whatever it had. The target's other
     * keys come off, and so does anything else bound to [chord]: ask first, with [conflicts].
     *
     * @param keymap what each key does now, as the server last resolved it
     */
    fun set(action: String, args: JsonObject?, chord: KeyChord, keymap: Keymap): ShortcutOverrides {
        val keys = listOf(chord.toWire())
        val beneath = keymap.bindings.filter { it.source != SHORTCUT_LAYER }
        val here = beneath.any { it.isFor(action, args) && it.keys == listOf(chord) }
        val gone = beneath.filter { (it.isFor(action, args)) != (it.keys == listOf(chord)) }
        return copy(
            // Bound beneath already: taking the others off is all there is to do.
            bindings = bindings.filterNot { (it.action == action && sameArgs(it.args, args)) || it.keys == keys } +
                if (here) emptyList() else listOf(ShortcutBinding(keys, action, args?.takeIf { it.isNotEmpty() })),
            removals = removals.plusRemovalsOf(gone),
        ).tidied()
    }

    /** Leaves a target with no shortcut, so that its keys go to the terminal again. */
    fun remove(action: String, args: JsonObject?, keymap: Keymap): ShortcutOverrides = copy(
        bindings = bindings.filterNot { it.action == action && sameArgs(it.args, args) },
        removals = removals.plusRemovalsOf(keymap.bindings.filter { it.source != SHORTCUT_LAYER && it.isFor(action, args) }),
    ).tidied()

    /**
     * Forgets what this layer says about a target, so that it has the keys the layers
     * beneath give it. Anything this layer bound to one of the target's default keys comes
     * off as well, or the default would be back in name only: ask first, with [resetConflicts].
     */
    fun reset(action: String, args: JsonObject?, defaults: List<KeyBinding> = emptyList()): ShortcutOverrides {
        val taken = resetConflicts(action, args, defaults)
        return copy(
            bindings = bindings.filterNot { (it.action == action && sameArgs(it.args, args)) || it in taken },
            removals = removals.filterNot { it.action == action && sameArgs(it.args, args) },
        ).tidied()
    }

    /** What else [chord] is bound to now, topmost first: the bindings that [set] would take off it. */
    fun conflicts(action: String, args: JsonObject?, chord: KeyChord, keymap: Keymap): List<KeyBinding> =
        keymap.bindingsFor(chord).filterNot { it.isFor(action, args) }

    /** The bindings of this layer that sit on a target's default keys, which [reset] would take off. */
    fun resetConflicts(action: String, args: JsonObject?, defaults: List<KeyBinding>): List<ShortcutBinding> {
        val keys = defaults.filter { it.isFor(action, args) }.map { it.wireKeys }
        return bindings.filter { it.keys in keys && !(it.action == action && sameArgs(it.args, args)) }
    }

    private fun List<ShortcutRemoval>.plusRemovalsOf(gone: List<KeyBinding>): List<ShortcutRemoval> {
        val added = gone.map { binding ->
            // A key bound to a function in the configuration file has no action to name, so the key is cleared.
            if (binding.action == FORWARDED_KEY) ShortcutRemoval(binding.wireKeys)
            else ShortcutRemoval(binding.wireKeys, binding.action, binding.args?.takeIf { it.isNotEmpty() })
        }
        return (this + added).distinct()
    }

    /** A removal that clears a key outright is there to make room for a binding of this layer, and goes when that does. */
    private fun tidied(): ShortcutOverrides = copy(removals = removals.filter { removal -> removal.action != null || bindings.any { it.keys == removal.keys } })

    /** The layer as `client.keymap` takes it. */
    fun toLayer(): JsonObject = buildJsonObject {
        put("name", SHORTCUT_LAYER)
        if (bindings.isNotEmpty()) put("bindings", bindingsJson())
        if (removals.isNotEmpty()) put("removals", buildJsonArray {
            // Two removals that differ only in what they are remembered for are one to the server.
            for ((keys, action) in removals.map { it.keys to it.action }.distinct()) add(buildJsonObject {
                put("keys", keys.toJson())
                action?.let { put("action", it) }
            })
        })
    }

    /** As the workspace keeps it: the layer, with each removal's arguments beside it. */
    internal fun toJson(): JsonObject = buildJsonObject {
        put("bindings", bindingsJson())
        put("removals", buildJsonArray {
            for (removal in removals) add(buildJsonObject {
                put("keys", removal.keys.toJson())
                removal.action?.let { put("action", it) }
                removal.args?.let { put("args", it) }
            })
        })
    }

    private fun bindingsJson() = buildJsonArray {
        for (binding in bindings) add(buildJsonObject {
            put("keys", binding.keys.toJson())
            put("action", binding.action)
            binding.args?.let { put("args", it) }
        })
    }

    companion object {
        /** Reads what [toJson] writes. An entry that can't be read is left out. */
        internal fun parse(json: JsonObject?): ShortcutOverrides {
            fun keys(entry: JsonObject): List<String>? = (entry["keys"] as? JsonArray)
                ?.map { (it as? JsonPrimitive)?.takeIf { key -> key.isString }?.content?.let(::spelling) ?: return null }
                ?.takeIf { it.isNotEmpty() }
            fun entries(key: String) = (json?.get(key) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            return ShortcutOverrides(
                bindings = entries("bindings").mapNotNull { ShortcutBinding(keys(it) ?: return@mapNotNull null, it.string("action") ?: return@mapNotNull null, it.obj("args")?.takeIf { args -> args.isNotEmpty() }) },
                removals = entries("removals").mapNotNull { ShortcutRemoval(keys(it) ?: return@mapNotNull null, it.string("action"), it.obj("args")?.takeIf { args -> args.isNotEmpty() }) },
            )
        }

        /** A key written by hand in the workspace, spelled the way this app spells it. One named by position, or not understood, stays as written. */
        private fun spelling(key: String): String = if ('[' in key) key else KeyChord.parse(key)?.toWire() ?: key

        private fun List<String>.toJson() = buildJsonArray { this@toJson.forEach { add(JsonPrimitive(it)) } }
    }
}

/** What a key bound to a function in the configuration file comes back as. */
const val FORWARDED_KEY = "client.key.forward"

/** Each target with its keys in [keymap], in the order given. */
fun shortcutList(targets: List<ShortcutTarget>, keymap: Keymap, overrides: ShortcutOverrides): List<Shortcut> =
    targets.map { Shortcut(it, keymap.bindingsOf(it.action, it.args), overrides.changes(it.action, it.args)) }

/**
 * What each key does going by the defaults and this app's layer alone, top first, as the
 * server would resolve them with no configuration file. For before the server has answered,
 * and for when it can't be asked.
 */
fun resolveLocally(defaults: List<KeyBinding>, overrides: ShortcutOverrides): Keymap {
    val kept = defaults.filterNot { binding -> overrides.removals.any { it.keys == binding.wireKeys && (it.action == null || it.action == binding.action) } }
    val recorded = overrides.bindings.mapNotNull { binding ->
        KeyBinding(binding.keys.map { KeyChord.parse(it) ?: return@mapNotNull null }, binding.action, binding.args, SHORTCUT_LAYER)
    }
    // Later bindings sit above earlier ones, and a keymap lists the top first.
    return Keymap(recorded.reversed() + kept.map { it.copy(source = "default") }.reversed())
}
