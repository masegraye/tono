@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.kotlintest

import androidx.compose.ui.input.pointer.fromKeyword
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.delay
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

/** Resolves to null when the text is on the clipboard, and to the browser's reason when it isn't. */
private fun writeClipboard(text: String): Promise<JsAny?> = js(
    """(async () => {
        try {
            if (!navigator.clipboard) return 'the page has no clipboard, as when it is not served securely';
            await navigator.clipboard.writeText(text);
            return null;
        } catch (error) {
            return 'the browser refused (' + (error && error.name ? error.name + ': ' + error.message : error) + ')';
        }
    })()""",
)

internal actual suspend fun setClipboardText(text: String): String? =
    runCatching { (writeClipboard(text).await<JsAny?>() as? JsString)?.toString() }.getOrElse { "the browser refused" }

internal actual fun pageHasFocus(): Boolean = js("document.hasFocus()")

private fun timeOfDay(millis: Double): String =
    js("(() => { const d = new Date(millis); return d.toTimeString().slice(0, 8) + '.' + String(d.getMilliseconds()).padStart(3, '0'); })()")

internal actual fun clockTime(millis: Long): String = timeOfDay(millis.toDouble())

/**
 * The desktop's toolkit has tooltips of its own; here it is a popup shown once the pointer
 * has rested on [content] for half a second, and taken away when it leaves or presses.
 */
@Composable
internal actual fun Tooltip(tip: @Composable () -> Unit, content: @Composable () -> Unit) {
    var over by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(over) {
        if (over) delay(TOOLTIP_DELAY_MILLIS)
        shown = over
    }
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    Box(
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    // Looked at on the way down to the content, and left for it to handle.
                    when (awaitPointerEvent(PointerEventPass.Initial).type) {
                        PointerEventType.Enter, PointerEventType.Move -> over = true
                        PointerEventType.Exit, PointerEventType.Press -> over = false
                    }
                }
            }
        },
    ) {
        content()
        if (shown) Popup(popupPositionProvider = remember(gap) { BelowAnchor(gap) }, content = tip)
    }
}

private const val TOOLTIP_DELAY_MILLIS = 500L

/** Under what it describes, clear of the pointer, and moved in from the window's edges. */
private class BelowAnchor(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height) below else anchorBounds.top - gap - popupContentSize.height
        return IntOffset(x, y.coerceAtLeast(0))
    }
}

internal actual val runsInBrowser: Boolean = true

internal actual val fontsArriveLater: Boolean = true

private fun isFullScreen(): Boolean = js("document.fullscreenElement != null")

/**
 * In full screen a page may ask for the whole keyboard, which gets it the keys a browser
 * otherwise keeps (Control with T, N, W or Tab) and Escape, which would otherwise leave full
 * screen; holding Escape still does. Only some browsers have that to ask for. The rest go
 * full screen and keep their keys.
 */
private fun toggleFullScreen(): Unit = js(
    """{
        if (document.fullscreenElement) {
            document.exitFullscreen();
        } else {
            document.documentElement.requestFullscreen()
                .then(() => { if (navigator.keyboard && navigator.keyboard.lock) return navigator.keyboard.lock(); })
                .catch(() => {});
        }
    }""",
)

internal actual val fullScreen: FullScreen? = object : FullScreen {
    override val isOn: Boolean get() = isFullScreen()

    override fun toggle() = toggleFullScreen()
}

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

/** A new tab that is given no way back to this page, and isn't told where it was sent from. */
private fun openTab(url: String): Unit = js("{ window.open(url, '_blank', 'noopener,noreferrer'); }")

/**
 * A browser opens a tab for a page only while it is handling a click or a key press, or
 * shortly after, which is when this is called. It doesn't say whether it did.
 */
internal actual fun openUrl(url: String, log: (message: String, isError: Boolean) -> Unit) {
    runCatching { openTab(url) }
        .onSuccess { log("asked the browser to open $url", false) }
        .onFailure { log("couldn't open $url: the browser refused", true) }
}

/** Starts watching whether the page has the browser's focus, and returns the function that stops. */
private fun watchPageFocus(report: (Boolean) -> Unit): JsAny = js(
    """(() => {
        const say = () => report(document.hasFocus() && document.visibilityState === 'visible');
        addEventListener('focus', say);
        addEventListener('blur', say);
        document.addEventListener('visibilitychange', say);
        return () => {
            removeEventListener('focus', say);
            removeEventListener('blur', say);
            document.removeEventListener('visibilitychange', say);
        };
    })()""",
)

@Composable
internal actual fun WatchPageFocus(onChange: (focused: Boolean) -> Unit) {
    val report by rememberUpdatedState(onChange)
    DisposableEffect(Unit) {
        val watching = watchPageFocus { report(it) }
        onDispose { stop(watching) }
    }
}

/*
 * Notifications, shown by the browser, which shows them only for a page its user has said
 * may. A page not served securely has none to ask for, and neither does a browser on a
 * phone, where only a service worker can show one.
 */

private fun notificationPermission(): String = js("(typeof Notification === 'undefined' || !isSecureContext) ? 'unavailable' : Notification.permission")

private fun askNotificationPermission(): Promise<JsAny?> =
    js("(typeof Notification === 'undefined') ? Promise.resolve('unavailable') : Promise.resolve(Notification.requestPermission()).catch(() => 'unavailable')")

private fun accessNamed(permission: String?): NotificationAccess = when (permission) {
    "granted" -> NotificationAccess.Granted
    "default" -> NotificationAccess.Undecided
    "denied" -> NotificationAccess.Refused
    else -> NotificationAccess.Unavailable
}

internal actual fun notificationAccess(): NotificationAccess = accessNamed(notificationPermission())

internal actual suspend fun requestNotificationAccess(): NotificationAccess =
    runCatching { accessNamed((askNotificationPermission().await<JsAny?>() as? JsString)?.toString()) }.getOrElse { notificationAccess() }

/**
 * Shows a notification and keeps it by its tag, so it can be taken down. The browser itself
 * puts one in the place of another with the same tag; `renotify` has it say so again, where
 * the browser knows the word. Returns null once it is shown, and otherwise why it wasn't.
 */
private fun notify(tag: String, title: String, body: String, urgent: Boolean, icon: String, clicked: () -> Unit): String? = js(
    """(() => {
        try {
            const up = globalThis.tonoNotifications || (globalThis.tonoNotifications = new Map());
            const note = new Notification(title, { body, tag, icon, renotify: true, requireInteraction: urgent });
            note.onclick = () => { try { window.focus(); } catch (error) {} note.close(); clicked(); };
            note.onclose = () => { if (up.get(tag) === note) up.delete(tag); };
            up.set(tag, note);
            return null;
        } catch (error) {
            return String(error && error.message ? error.message : error);
        }
    })()""",
)

private fun closeNotificationTagged(tag: String): Unit = js(
    """{
        const up = globalThis.tonoNotifications;
        const note = up && up.get(tag);
        if (note) { up.delete(tag); note.close(); }
    }""",
)

internal actual suspend fun showNotification(note: DesktopNote, log: (message: String, isError: Boolean) -> Unit, onClick: () -> Unit) {
    val refusal = runCatching { notify(note.tag, note.title, note.body, note.urgent, pageUrl("icon-192.png"), onClick) }.getOrElse { "the browser refused" }
    if (refusal == null) log("showed a notification", false) else log("couldn't show a notification: $refusal", true)
}

internal actual fun closeNotification(tag: String) {
    runCatching { closeNotificationTagged(tag) }
}

/** A page may bring its window forward only as a notification of its own is clicked, which is done there. */
internal actual fun bringWindowToFront() {
    runCatching { focusWindow() }
}

private fun focusWindow(): Unit = js("window.focus()")

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal actual fun resizePointer(sideways: Boolean): androidx.compose.ui.input.pointer.PointerIcon =
    with(androidx.compose.ui.input.pointer.PointerIcon) { fromKeyword(if (sideways) "col-resize" else "row-resize") }

/** A browser numbers the two 3 and 4, which is where Compose has them. That it leaves the page alone is seen to in page-keys.js. */
internal actual fun historyButton(button: PointerButton?): Boolean? = when (button) {
    PointerButton.Back -> false
    PointerButton.Forward -> true
    else -> null
}

/** Nothing to watch: in a browser the presses come through Compose, as [historyButton] says. */
@Composable
internal actual fun WatchHistoryButtons(onPress: (forward: Boolean) -> Unit) {}

private fun notificationsMissingName(): String = js(
    """(() => {
        if (!isSecureContext) return 'insecure';
        const apple = /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
        const installed = navigator.standalone === true || matchMedia('(display-mode: standalone)').matches;
        return apple && !installed ? 'home-screen' : 'unknown';
    })()""",
)

internal actual fun notificationsMissing(): NotificationsMissing = when (notificationsMissingName()) {
    "insecure" -> NotificationsMissing.Insecure
    "home-screen" -> NotificationsMissing.NeedsHomeScreen
    else -> NotificationsMissing.Unknown
}

/*
 * The browser's back, kept in the app. An entry is added to the page's history for each
 * step something open is from the app, all of them this same page, so going back through
 * them loads nothing and is only heard of here.
 */

private fun pushHistoryEntry(): Unit = js("{ history.pushState({ tono: 'back' }, '') }")
private fun goBackInHistory(steps: Int): Unit = js("{ history.go(-steps) }")
private fun watchHistoryBack(onBack: () -> Unit): JsAny = js(
    """(() => {
        const say = () => onBack();
        addEventListener('popstate', say);
        return () => removeEventListener('popstate', say);
    })()""",
)

@Composable
internal actual fun WatchBrowserBack(depth: Int, onBack: () -> Unit) {
    val report by rememberUpdatedState(onBack)
    // How many entries of ours the history holds, and how many steps back of our own asking are still to be heard of.
    val entries = remember { intArrayOf(0) }
    val expected = remember { intArrayOf(0) }
    DisposableEffect(Unit) {
        val watching = watchHistoryBack {
            if (expected[0] > 0) expected[0]--
            else {
                if (entries[0] > 0) entries[0]--
                report()
            }
        }
        onDispose {
            stop(watching)
            // Closed some other way than by going back: the entries go, so that back means what it did before.
            if (entries[0] > 0) goBackInHistory(entries[0])
            entries[0] = 0
        }
    }
    DisposableEffect(depth) {
        while (entries[0] < depth) { pushHistoryEntry(); entries[0]++ }
        if (entries[0] > depth) {
            val steps = entries[0] - depth
            entries[0] = depth
            expected[0]++
            goBackInHistory(steps)
        }
        onDispose { }
    }
}

/** A page can't look at the machine it is served from; the server that serves it runs beside a Rex server already. */
internal actual suspend fun localServerSetup(): LocalServerSetup? = null

internal actual suspend fun runRex(command: LocalServerCommand): String? = "A page in a browser can't run rex."

private fun describeWheel(event: JsAny): String? = js("(event && typeof event.deltaY === 'number') ? ('dom deltaY=' + event.deltaY + ' deltaMode=' + event.deltaMode + ' timeStamp=' + event.timeStamp) : null")

internal actual fun wheelDetails(event: androidx.compose.ui.input.pointer.PointerEvent): String? =
    (event.nativeEvent as? JsAny)?.let { runCatching { describeWheel(it) }.getOrNull() }
