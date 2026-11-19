package org.example.kotlintest

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import org.example.kotlintest.rex.KeyChord
import org.example.kotlintest.rex.Shortcut
import org.example.kotlintest.rex.ShortcutTarget

/*
 * Change Shortcut…, after Rex.app's command of that name. The palette lists every action
 * with its keys and where they come from; choosing one opens this, which records the next
 * keys pressed and offers what to do: give the action those keys, leave it with none, or
 * put it back as it was before this app changed it. What is changed is this app's alone,
 * kept in its workspace and laid over Rex's configuration file (rex/src/Shortcuts.kt).
 */

/** What Change Shortcut… can do to an action. */
internal enum class ShortcutChoice { Set, Remove, Reset }

/** One of the things offered, as a line: what it does, and something about it. */
internal class ShortcutOption(val choice: ShortcutChoice, val title: String, val detail: String)

/**
 * What to offer for a shortcut, most likely first.
 *
 * @param recorded the keys pressed since it opened, if any have been
 * @param usedFor what else those keys are bound to now, in words
 * @param defaults the keys the action has when nothing has changed them, as shown, or null for none
 */
internal fun shortcutOptions(shortcut: Shortcut, recorded: KeyChord?, usedFor: List<String>, defaults: String?): List<ShortcutOption> = buildList {
    if (recorded != null) {
        val detail = when {
            usedFor.isNotEmpty() -> "now ${usedFor.joinToString(" and ") { "“$it”" }}, which will lose it"
            shortcut.bindings.size == 1 && shortcut.bindings[0].keys == listOf(recorded) -> "its shortcut already"
            shortcut.bindings.isNotEmpty() -> "in place of ${shortcut.keysShown()}"
            else -> ""
        }
        add(ShortcutOption(ShortcutChoice.Set, "Set Shortcut to ${recorded.display()}", detail))
    }
    shortcut.keysShown()?.let { add(ShortcutOption(ShortcutChoice.Remove, "Remove Shortcut", "$it goes to the terminal")) }
    // Only where this app changed something: otherwise it is as the layers beneath have it already.
    if (shortcut.changed) add(ShortcutOption(ShortcutChoice.Reset, "Reset to Default", defaults ?: "no shortcut"))
}

/**
 * Whether keys can be made a shortcut here. A key that types, alone or with Shift, can't:
 * bound, it could no longer be typed into a terminal. Function keys can, and the keys that
 * move about when Shift is held, as Shift+PageUp is.
 */
internal fun canRecord(chord: KeyChord): Boolean =
    chord.ctrl || chord.alt || chord.cmd || chord.key.matches(Regex("f\\d+")) || (chord.shift && chord.key.length > 1 && chord.key !in TYPING_KEYS)

private val TYPING_KEYS = setOf("enter", "tab", "space", "backspace", "escape")

@Composable
fun ChangeShortcut(ui: RexUiState, commands: RexCommands, target: ShortcutTarget) {
    val client = ui.client
    val keymap by client.keymap.collectAsState()
    val overrides by client.shortcuts.collectAsState()
    var recorded by remember(target) { mutableStateOf<KeyChord?>(null) }
    /** Keys pressed that can't be a shortcut, to say why nothing happened. */
    var refused by remember(target) { mutableStateOf<KeyChord?>(null) }
    var selected by remember(target) { mutableIntStateOf(0) }
    val shortcut = remember(target, keymap, overrides) { client.shortcutList(listOf(target)).single() }
    val conflicts = remember(target, keymap, overrides, recorded) { recorded?.let { client.shortcutConflicts(target.action, target.args, it) }.orEmpty() }
    val defaults = client.defaultShortcuts(target.action, target.args).takeIf { it.isNotEmpty() }?.joinToString(", ") { it.keys.joinToString(" ") { key -> key.display() } }
    val options = shortcutOptions(shortcut, recorded, conflicts.map { commands.titleOf(it) }.distinct(), defaults)
    val focus = remember { FocusRequester() }
    LaunchedEffect(target) { focus.requestFocus() }

    fun close() {
        ui.shortcutEdit = null
        ui.returnFocusToTerminal()
    }

    fun choose(option: ShortcutOption) {
        // Close first: a question may open in its place.
        ui.shortcutEdit = null
        when (option.choice) {
            ShortcutChoice.Set -> {
                val chord = recorded ?: return
                val set = { client.setShortcut(target.action, target.args, chord) }
                val losers = conflicts.map { commands.titleOf(it) }.distinct()
                // Taking a key from something else is asked about, and happens only on a yes.
                if (losers.isEmpty()) set() else ui.confirmation = Confirmation(
                    title = "${chord.display()} is already a shortcut",
                    text = "It is the shortcut for ${losers.joinToString(" and ") { "“$it”" }}. Setting it for “${target.title}” leaves ${if (losers.size == 1) "that" else "those"} without it.",
                    confirmLabel = "Replace",
                    destructive = false,
                    onConfirm = set,
                )
            }
            ShortcutChoice.Remove -> client.removeShortcut(target.action, target.args)
            ShortcutChoice.Reset -> {
                val reset = { client.resetShortcut(target.action, target.args) }
                val losers = client.shortcutResetConflicts(target.action, target.args)
                if (losers.isEmpty()) reset() else ui.confirmation = Confirmation(
                    title = "Its default keys were given to something else",
                    text = "In this app, ${losers.joinToString(" and ") { "${it.keys.joinToString(" ") { key -> KeyChord.parse(key)?.display() ?: key }} was set for “${commands.titleOf(org.example.kotlintest.rex.KeyBinding(emptyList(), it.action, it.args))}”" }}. Resetting “${target.title}” takes that back.",
                    confirmLabel = "Reset",
                    destructive = false,
                    onConfirm = reset,
                )
            }
        }
        ui.returnFocusToTerminal()
    }

    PaletteFrame(onDismiss = ::close) {
        Column(
            // Every key pressed while this is open is this's: the keys being recorded are
            // shortcuts already, most of them, and none of them is to run.
            Modifier.fillMaxWidth().focusRequester(focus).focusable().onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                val chord = keyChord(event) ?: return@onPreviewKeyEvent true // a modifier on its own
                val bare = !chord.ctrl && !chord.alt && !chord.shift && !chord.cmd
                when {
                    bare && chord.key == "escape" -> close()
                    bare && chord.key == "down" -> if (options.isNotEmpty()) selected = (selected + 1) % options.size
                    bare && chord.key == "up" -> if (options.isNotEmpty()) selected = (selected - 1).mod(options.size)
                    bare && chord.key == "enter" -> options.getOrNull(selected.coerceIn(0, (options.size - 1).coerceAtLeast(0)))?.let(::choose)
                    canRecord(chord) -> { recorded = chord; refused = null; selected = 0 }
                    else -> refused = chord
                }
                true
            },
        ) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
                Text("Change Shortcut: ${target.title}", style = MaterialTheme.typography.titleSmall)
                Text(
                    shortcut.keysShown()?.let { "Now $it (${shortcut.sourceShown()})" } ?: "Now ${shortcut.sourceShown()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    recorded?.display() ?: "Press the new keys",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (recorded != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    refused?.let { "${it.display()} is for typing. Hold Ctrl or Alt with it." }
                        ?: if (recorded == null) "For this app alone. Esc cancels." else "Enter chooses, other keys record again, Esc cancels.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (options.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(vertical = 4.dp)) {
                options.forEachIndexed { index, option ->
                    PaletteRow(option.title, option.detail, shortcut = null, selected = index == selected.coerceIn(0, options.size - 1), onClick = { choose(option) })
                }
            }
        }
    }
}
