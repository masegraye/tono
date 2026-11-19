package org.example.kotlintest

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import org.example.kotlintest.rex.KeyChord
import org.example.kotlintest.rex.RexKey
import org.example.kotlintest.rex.openableLink

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

/**
 * Puts text on the clipboard. Returns null once it is there, and otherwise why it isn't, for
 * the debug log. A browser refuses a page that isn't served securely or doesn't have focus,
 * and some refuse a write that doesn't follow a key press or a click. Never throws.
 */
internal expect suspend fun setClipboardText(text: String): String?

/**
 * Whether the page the app runs in has the browser's focus. In Chromium the app was seen to
 * go on being told its window was focused after another page had come in front of it, so
 * the page itself is asked. True on the desktop, where there is no page and the window's
 * own focus is the whole answer.
 */
internal expect fun pageHasFocus(): Boolean

/** A moment as the time of day, to the millisecond, for the debug log. */
internal expect fun clockTime(millis: Long): String

/** Shows [tip] when the pointer rests on [content]. Where that isn't possible, [content] alone. */
@Composable
internal expect fun Tooltip(tip: @Composable () -> Unit, content: @Composable () -> Unit)

/**
 * Whether a font can turn up after the text that needs it has been drawn. A browser has
 * only the fonts it was given, and fetches one for a character none of them has (Chinese,
 * say, or an emoji) when the character first appears; until it arrives the character is an
 * empty box. On the desktop every font is there from the start.
 */
internal expect val fontsArriveLater: Boolean

/**
 * The app filling the screen, where the app is what asks for that. Null on the desktop,
 * where it is the window manager's to do.
 */
internal expect val fullScreen: FullScreen?

internal interface FullScreen {
    val isOn: Boolean

    fun toggle()
}

/**
 * Plays one of the desktop's sounds, by its name in the sound theme (see [soundFor]), and
 * says in [log] how that went.
 */
internal expect suspend fun playSound(id: String, log: (message: String, isError: Boolean) -> Unit)

/**
 * Opens a link from a terminal's output, and says in [log] how that went. Only an address
 * that [openableLink] passes is opened: web pages, FTP and mail, and never a file or
 * anything that starts a program, since what a terminal shows may come from another machine.
 */
internal fun openLink(url: String, log: (message: String, isError: Boolean) -> Unit) {
    val safe = openableLink(url)
    if (safe == null) log("didn't open $url: only http, https, ftp and mailto links are opened", true) else openUrl(safe, log)
}

/**
 * Hands an address to what opens such addresses: the desktop's default browser or mail
 * program, or a new tab of the browser the app runs in, which is told nothing of the page
 * it came from. Returns at once. [url] is to have been through [openableLink].
 */
internal expect fun openUrl(url: String, log: (message: String, isError: Boolean) -> Unit)

/** Whether notifications can be shown here, as things stand. On the desktop that is whether there is a program to show them with. */
internal expect fun notificationAccess(): NotificationAccess

/**
 * Has a browser ask its user whether this page may show notifications, and says what they
 * answered. It must be called while a click or a key press is being handled. On the desktop
 * nobody is asked, and the answer is [notificationAccess].
 */
internal expect suspend fun requestNotificationAccess(): NotificationAccess

/**
 * Shows a notification, in place of the last one shown with the same tag if that is still
 * up, and says in [log] how that went. [onClick] is called if it is clicked, on any thread.
 * Never throws.
 */
internal expect suspend fun showNotification(note: DesktopNote, log: (message: String, isError: Boolean) -> Unit, onClick: () -> Unit)

/** Takes down the notification last shown with a tag, if it is still up. Returns at once. */
internal expect fun closeNotification(tag: String)

/**
 * Asks for the app's window to be in front and have the keyboard, as after a click on one
 * of its notifications. The desktop or the browser may decline.
 */
internal expect fun bringWindowToFront()

/** Says whether the page has the browser's focus, as [pageHasFocus] does, whenever that changes. Says nothing on the desktop. */
@Composable
internal expect fun WatchPageFocus(onChange: (focused: Boolean) -> Unit)

/**
 * Whether a pointer button is the mouse's forward button (true) or its back button (false),
 * the two on the side of a mouse that go through history. Null for every other button.
 */
internal expect fun historyButton(button: PointerButton?): Boolean?
