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

/** Whether the app is a page in a browser, where some things are the browser's to allow and some it hasn't got. */
internal expect val runsInBrowser: Boolean

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
 * Whether a pointer button Compose reports is the mouse's forward button (true) or its back
 * button (false), the two on the side of a mouse that go through history. Null for every
 * other button, and for all of them where Compose can't be trusted to say: see
 * [WatchHistoryButtons].
 */
internal expect fun historyButton(button: PointerButton?): Boolean?

/**
 * Says when one of the mouse's side buttons is pressed anywhere in the window, where
 * Compose doesn't: on the desktop. Between this and [historyButton] a press is reported once.
 */
@Composable
internal expect fun WatchHistoryButtons(onPress: (forward: Boolean) -> Unit)

/**
 * Why notifications can't be shown here, when [notificationAccess] says they can't, as far
 * as that can be told.
 */
internal expect fun notificationsMissing(): NotificationsMissing

internal enum class NotificationsMissing {
    /** The page isn't served securely, and a browser gives such a page none. */
    Insecure,

    /** An iPhone or iPad, whose browser gives them only to a web app opened from the Home Screen. */
    NeedsHomeScreen,

    /** There is no saying. On the desktop: no program to show them with. */
    Unknown,
}

/**
 * Keeps the browser's own way back (its button, a phone's gesture) inside the app while
 * something is open that it should close: [depth] is how many steps back that something is
 * from the app itself, and [onBack] is called for each step the browser's user takes. A step
 * taken in the app instead, which lowers [depth], is taken out of the browser's history too,
 * so back never leaves the page for something that was closed already. Nothing on the desktop.
 */
@Composable
internal expect fun WatchBrowserBack(depth: Int, onBack: () -> Unit)

/**
 * What this machine has for running a Rex server of its own, as far as can be told.
 *
 * @param rexPath where the `rex` command is, or null if it isn't on `PATH`
 * @param socketEnabled whether the `rex.socket` user unit is enabled, so that the server starts by itself; null if systemd can't be asked
 * @param socketActive whether that unit is active just now; null if systemd can't be asked
 */
internal data class LocalServerSetup(val rexPath: String?, val socketEnabled: Boolean? = null, val socketActive: Boolean? = null)

/** Looks at this machine: see [LocalServerSetup]. Null where the app can't look, which is a browser. Changes nothing. */
internal expect suspend fun localServerSetup(): LocalServerSetup?

/** The two things this app will run to get a server going, each only when its button is pressed. */
internal enum class LocalServerCommand(val title: String, val arguments: List<String>) {
    /** Starts a server that runs until the machine restarts. */
    Start("Start Server", listOf("server", "start")),

    /** Has the server start by itself from now on: on Linux, enables the `rex.socket` user unit. */
    StartAtLogin("Start at Login", listOf("server", "install")),
}

/**
 * Runs `rex` with a command's arguments and waits for it. Returns null if it succeeded, and
 * otherwise what it printed, or why it couldn't be run, to show. Never throws.
 */
internal expect suspend fun runRex(command: LocalServerCommand): String?

/**
 * A wheel event as rows of a terminal whose rows are [cellHeight] pixels high, of which it
 * shows [rows]: see [notchedWheelTurn] and [browserWheelTurn]. [delta] is what Compose
 * gives for the event, which is not the same thing everywhere.
 */
internal expect fun wheelTurn(event: androidx.compose.ui.input.pointer.PointerEvent, delta: Float, cellHeight: Float, density: Float, rows: Int): WheelTurn

/** What the window system said of a turn of the wheel, for [org.example.kotlintest.rex.ScrollTelemetry]. Null where it can't be read. */
internal expect fun wheelDetails(event: androidx.compose.ui.input.pointer.PointerEvent): String?
