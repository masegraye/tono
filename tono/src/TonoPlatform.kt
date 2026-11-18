package org.example.kotlintest

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.KeyEvent
import org.example.kotlintest.rex.KeyChord
import org.example.kotlintest.rex.RexKey

/*
 * What the interface needs from wherever it runs. The desktop has a window toolkit with its
 * own key codes, a clipboard to read on the spot, and programs to play a sound with; a
 * browser has key names of its own, a clipboard it hands over when it is ready, and audio.
 */

/**
 * A key event in the form the Rex server takes, which is the physical key by its W3C name
 * with what it typed. Null for keys that aren't sent: the modifiers on their own, and keys
 * with no name.
 */
internal expect fun rexKey(event: KeyEvent, repeat: Boolean): RexKey?

/**
 * The key and modifiers of a press, in the form keymaps are written in (`ctrl+shift+t`), for
 * looking up shortcuts. Null for keys that can't be bound, such as a modifier on its own.
 */
internal expect fun keyChord(event: KeyEvent): KeyChord?

/**
 * Says whether Control is down, whatever in the window has the keyboard, and that it is up
 * when the window loses focus, since the release would then never be seen.
 */
@Composable
internal expect fun WatchControlKey(onChange: (held: Boolean) -> Unit)

/** The text on the clipboard, or null if there is none or it can't be read. */
internal expect suspend fun clipboardText(): String?

/** A moment as the time of day, to the millisecond, for the debug log. */
internal expect fun clockTime(millis: Long): String

/** Shows [tip] when the pointer rests on [content]. Where that isn't possible, [content] alone. */
@Composable
internal expect fun Tooltip(tip: @Composable () -> Unit, content: @Composable () -> Unit)

/**
 * Plays one of the desktop's sounds, by its name in the sound theme (see [soundFor]), and
 * says in [log] how that went.
 */
internal expect suspend fun playSound(id: String, log: (message: String, isError: Boolean) -> Unit)
