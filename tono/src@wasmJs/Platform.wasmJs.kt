@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.kotlintest

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import kotlinx.coroutines.await
import org.example.kotlintest.rex.KeyChord
import org.example.kotlintest.rex.RexKey
import org.example.kotlintest.rex.pageUrl
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.Promise

/*
 * In a browser.
 *
 * A browser keeps some keys for itself, and a page never sees them: Control with T, W, N or
 * Tab, among others. Shortcuts on those keys don't work in a tab; they do when the page runs
 * as an installed app window, which leaves the keyboard to the page.
 */

internal actual fun rexKey(event: KeyEvent, repeat: Boolean): RexKey? {
    val name = KEY_NAMES[event.key] ?: return null
    return rexKeyNamed(
        name,
        // A key that types has its character as its code point; one that doesn't has none.
        typed = event.utf16CodePoint.takeIf { it in 0x20..0xFFFF }?.toChar(),
        pressed = event.type != KeyEventType.KeyUp,
        repeat = repeat,
        shift = event.isShiftPressed, control = event.isCtrlPressed, alt = event.isAltPressed, meta = event.isMetaPressed,
    )
}

internal actual fun keyChord(event: KeyEvent): KeyChord? {
    val name = KEY_NAMES[event.key] ?: return null
    return keyChordNamed(name, shift = event.isShiftPressed, control = event.isCtrlPressed, alt = event.isAltPressed, meta = event.isMetaPressed)
}

/** Rex's name for each key Compose knows. A browser reports keys by position, as Rex wants them. */
private val KEY_NAMES: Map<Key, String> = buildMap {
    val letters = listOf(
        Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
        Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
    )
    letters.forEachIndexed { index, key -> put(key, "key_" + ('a' + index)) }
    listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
        .forEachIndexed { index, key -> put(key, "digit_$index") }
    listOf(Key.NumPad0, Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4, Key.NumPad5, Key.NumPad6, Key.NumPad7, Key.NumPad8, Key.NumPad9)
        .forEachIndexed { index, key -> put(key, "numpad_$index") }
    listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12)
        .forEachIndexed { index, key -> put(key, "f${index + 1}") }
    put(Key.Enter, "enter")
    put(Key.NumPadEnter, "numpad_enter")
    put(Key.Backspace, "backspace")
    put(Key.Tab, "tab")
    put(Key.Spacebar, "space")
    put(Key.Escape, "escape")
    put(Key.Delete, "delete")
    put(Key.Insert, "insert")
    put(Key.MoveHome, "home")
    put(Key.MoveEnd, "end")
    put(Key.PageUp, "page_up")
    put(Key.PageDown, "page_down")
    put(Key.DirectionUp, "arrow_up")
    put(Key.DirectionDown, "arrow_down")
    put(Key.DirectionLeft, "arrow_left")
    put(Key.DirectionRight, "arrow_right")
    put(Key.Minus, "minus")
    put(Key.Equals, "equal")
    put(Key.LeftBracket, "bracket_left")
    put(Key.RightBracket, "bracket_right")
    put(Key.Backslash, "backslash")
    put(Key.Semicolon, "semicolon")
    put(Key.Apostrophe, "quote")
    put(Key.Grave, "backquote")
    put(Key.Comma, "comma")
    put(Key.Period, "period")
    put(Key.Slash, "slash")
    put(Key.NumPadAdd, "numpad_add")
    put(Key.NumPadSubtract, "numpad_subtract")
    put(Key.NumPadMultiply, "numpad_multiply")
    put(Key.NumPadDivide, "numpad_divide")
    put(Key.NumPadDot, "numpad_decimal")
    put(Key.PrintScreen, "print_screen")
}

/** Starts watching the page's key events and focus, and returns the function that stops. */
private fun watchControl(report: (Boolean) -> Unit): JsAny = js(
    """(() => {
        const key = (event) => report(event.ctrlKey);
        const blur = () => report(false);
        addEventListener('keydown', key, true);
        addEventListener('keyup', key, true);
        addEventListener('blur', blur);
        return () => {
            removeEventListener('keydown', key, true);
            removeEventListener('keyup', key, true);
            removeEventListener('blur', blur);
        };
    })()""",
)

private fun stop(watching: JsAny): Unit = js("watching()")

@Composable
internal actual fun WatchControlKey(onChange: (held: Boolean) -> Unit) {
    val report by rememberUpdatedState(onChange)
    DisposableEffect(Unit) {
        val watching = watchControl { report(it) }
        onDispose { stop(watching) }
    }
}

private fun readClipboard(): Promise<JsAny?> = js("navigator.clipboard.readText()")

/** The browser may ask the user first, and refuses a page that isn't served securely. */
internal actual suspend fun clipboardText(): String? = runCatching { (readClipboard().await<JsAny?>() as? JsString)?.toString() }.getOrNull()

private fun timeOfDay(millis: Double): String =
    js("(() => { const d = new Date(millis); return d.toTimeString().slice(0, 8) + '.' + String(d.getMilliseconds()).padStart(3, '0'); })()")

internal actual fun clockTime(millis: Long): String = timeOfDay(millis.toDouble())

/** Not yet in a browser: the buttons work the same without it. */
@Composable
internal actual fun Tooltip(tip: @Composable () -> Unit, content: @Composable () -> Unit) = content()

private fun playAudio(url: String): Promise<JsAny?> = js("new Audio(url).play()")

/**
 * The desktop's own sounds, fetched from the page's server. A browser plays nothing until
 * the page has been clicked or typed into, which by the time a program finishes it has.
 */
internal actual suspend fun playSound(id: String, log: (message: String, isError: Boolean) -> Unit) {
    runCatching { playAudio(pageUrl("api/tono/sounds/$id")).await<JsAny?>() }
        .onSuccess { log("played $id", false) }
        .onFailure { log("couldn't play $id: the browser refused, or the sound isn't there", true) }
}
