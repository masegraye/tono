package org.example.kotlintest

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Watched for the whole window through the toolkit, so it is right whatever has the keyboard. */
@Composable
internal actual fun WatchControlKey(onChange: (held: Boolean) -> Unit) {
    val report by rememberUpdatedState(onChange)
    DisposableEffect(Unit) {
        val listener = AWTEventListener { event ->
            when (event) {
                is KeyEvent -> report(event.isControlDown)
                is WindowEvent -> if (event.id == WindowEvent.WINDOW_LOST_FOCUS || event.id == WindowEvent.WINDOW_DEACTIVATED) report(false)
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.KEY_EVENT_MASK or AWTEvent.WINDOW_EVENT_MASK or AWTEvent.WINDOW_FOCUS_EVENT_MASK)
        onDispose { Toolkit.getDefaultToolkit().removeAWTEventListener(listener) }
    }
}

internal actual suspend fun setClipboardText(text: String): String? = runCatching {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}.exceptionOrNull()?.let { it.message ?: it::class.simpleName ?: "the clipboard refused" }

internal actual fun pageHasFocus(): Boolean = true

internal actual fun clockTime(millis: Long): String = TIME.format(Instant.ofEpochMilli(millis))

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal actual fun Tooltip(tip: @Composable () -> Unit, content: @Composable () -> Unit) {
    TooltipArea(tooltip = tip, delayMillis = 500, tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 18.dp)), content = content)
}

internal actual val fontsArriveLater: Boolean = false

/** A window on the desktop is made full screen by the window manager, with its own key. */
internal actual val fullScreen: FullScreen? = null

/**
 * The command that plays a sound from the desktop's sound theme, using whichever player is
 * installed. mpv comes first: it is what Omarchy itself plays its sounds with (see its
 * low-battery hook), so it is there on every Omarchy desktop and is treated by the sound
 * server as any other program. Then libcanberra's player, which knows the theme by name,
 * then PipeWire's and PulseAudio's. Null if there is no player, or no such sound for it.
 */
internal fun soundCommand(id: String, program: (String) -> Path?, soundFile: (String) -> Path?): List<String>? {
    val file = soundFile(id)
    if (file != null) program("mpv")?.let { return listOf(it.toString(), "--no-video", "--no-terminal", "--really-quiet", file.toString()) }
    program("canberra-gtk-play")?.let { return listOf(it.toString(), "-i", id) }
    val player = program("pw-play") ?: program("paplay") ?: return null
    return file?.let { listOf(player.toString(), it.toString()) }
}

internal actual suspend fun playSound(id: String, log: (message: String, isError: Boolean) -> Unit) = withContext(Dispatchers.IO) {
    val command = soundCommand(id, ::findProgram, ::findSound)
    if (command == null) {
        log("no way to play $id: it needs mpv, libcanberra, PipeWire or PulseAudio, and the freedesktop sound theme", true)
        return@withContext
    }
    runCatching {
        // The name of the sound is one of three fixed words, and nothing a server or a
        // program says is ever part of the command.
        val process = ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.close()
        if (!process.waitFor(PLAY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
    }.onSuccess { log("played $id with ${Path.of(command.first()).fileName}", false) }
        .onFailure { log("couldn't play $id: ${it.message}", true) }
    Unit
}

private const val PLAY_TIMEOUT_SECONDS = 5L

/**
 * The command that opens an address with the desktop's choice of program for it: `xdg-open`
 * on Linux, `open` on a Mac. Null if there is none. The address is one argument of its own
 * and no shell reads it, and it starts with its scheme, so it can't be taken for an option.
 */
internal fun openCommand(url: String, osName: String, program: (String) -> Path?): List<String>? =
    (if (osName.startsWith("Mac", ignoreCase = true)) Path.of("/usr/bin/open") else program("xdg-open"))?.let { listOf(it.toString(), url) }

internal actual fun openUrl(url: String, log: (message: String, isError: Boolean) -> Unit) {
    val command = openCommand(url, System.getProperty("os.name").orEmpty(), ::findProgram)
    if (command == null) {
        log("couldn't open $url: there is no xdg-open", true)
        return
    }
    // Off the interface's thread: starting a program can take a moment, and so can its answer.
    Thread({ runOpener(command, url, log) }, "open-link").apply { isDaemon = true }.start()
}

/** Runs the command that opens [url], waits a while for it to say how that went, and says so in [log]. */
internal fun runOpener(command: List<String>, url: String, log: (message: String, isError: Boolean) -> Unit) {
    val opener = Path.of(command.first()).fileName
    runCatching {
        val process = ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.close()
        // It hands the address on and exits; one that stays is left to finish in its own time.
        if (process.waitFor(OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.exitValue() else 0
    }.onSuccess { code -> if (code == 0) log("opened $url with $opener", false) else log("couldn't open $url: $opener exited with $code", true) }
        .onFailure { log("couldn't open $url: ${it.message}", true) }
}

private const val OPEN_TIMEOUT_SECONDS = 10L

/** Looks on PATH, in its absolute directories only, so nothing in the current folder is ever run. */
private fun findProgram(name: String): Path? = System.getenv("PATH").orEmpty().split(':')
    .filter { it.startsWith("/") }
    .map { Path.of(it, name) }
    .firstOrNull(Files::isExecutable)

private fun findSound(id: String): Path? {
    val home = System.getenv("XDG_DATA_HOME")?.takeIf { it.startsWith("/") } ?: (System.getProperty("user.home") + "/.local/share")
    val shared = System.getenv("XDG_DATA_DIRS")?.takeIf { it.isNotBlank() } ?: "/usr/local/share:/usr/share"
    return (listOf(home) + shared.split(':')).filter { it.startsWith("/") }
        .map { Path.of(it, "sounds/freedesktop/stereo/$id.oga") }
        .firstOrNull(Files::isRegularFile)
}

@Composable
internal actual fun WatchPageFocus(onChange: (focused: Boolean) -> Unit) = Unit

/*
 * Notifications, shown by the desktop's notification service (the freedesktop one, which
 * every Linux desktop has: mako, dunst, GNOME's, KDE's, Omarchy's shell). It is reached with
 * `notify-send`, the program that comes with the service's own library, rather than by
 * speaking D-Bus from here. Each notification has a `notify-send` of its own that stays for
 * as long as the notification is up: it is told the notification's number, is told if the
 * notification is clicked, and ends when the notification is closed.
 */

/** A notification that is up: the `notify-send` waiting on it, and the number the service gave it. */
private class ShownNotification(val process: Process, val id: Long?)

private val shownNotifications = java.util.concurrent.ConcurrentHashMap<String, ShownNotification>()

/** One notification is handed over at a time, so that the second of two for a tab replaces the first. */
private val notifying = kotlinx.coroutines.sync.Mutex()

/** The address of the D-Bus session to reach the notification service on, when it isn't the desktop's own. For tests. */
@Volatile internal var notificationBus: String? = null

/** Starts one of the programs that talk to the notification service, with nothing to read and no errors kept. */
private fun startQuietly(command: List<String>, keepOutput: Boolean = false): Process {
    val builder = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD)
    if (!keepOutput) builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
    notificationBus?.let { builder.environment()["DBUS_SESSION_BUS_ADDRESS"] = it }
    return builder.start().also { it.outputStream.close() }
}

private val endNotifiersOnExit by lazy {
    // The notifications stay; only the programs waiting on them go, so none outlives the app.
    Runtime.getRuntime().addShutdownHook(Thread { shownNotifications.values.forEach { it.process.destroy() } })
}

internal actual fun notificationAccess(): NotificationAccess =
    if (findProgram("notify-send") != null) NotificationAccess.Granted else NotificationAccess.Unavailable

internal actual suspend fun requestNotificationAccess(): NotificationAccess = notificationAccess()

/**
 * The command that shows a notification and waits on it. It prints the notification's
 * number, then `default` if the notification is clicked, and ends when it is closed.
 *
 * The title and the text are whatever a server or a program said. They are handed over as
 * arguments of their own, after `--`, so nothing in them is read as an option, and no shell
 * is involved. The text is escaped, because services read it as markup.
 *
 * @param replaces the number of the notification this one takes the place of, if there is one
 */
internal fun notifyCommand(program: Path, note: DesktopNote, replaces: Long?): List<String> = buildList {
    add(program.toString())
    add("--app-name=$APP_NAME")
    // The app's own icon, and the entry it was installed with, for desktops that group by app.
    add("--icon=tono")
    add("--hint=string:desktop-entry:tono")
    add("--urgency=${if (note.urgent) "critical" else "normal"}")
    add("--print-id")
    // An action called `default` is the one a desktop carries out when the notification itself is clicked.
    add("--action=default=Show")
    add("--wait")
    if (replaces != null) add("--replace-id=$replaces")
    add("--")
    add(note.title.ifBlank { APP_NAME })
    if (note.body.isNotBlank()) add(markupEscaped(note.body))
}

/** Text as a notification service that reads markup will show it: as written. */
internal fun markupEscaped(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * The command that closes a notification by its number, with whichever of GLib's and
 * systemd's D-Bus tools is installed. Null if neither is.
 */
internal fun closeNotificationCommand(id: Long, program: (String) -> Path?): List<String>? {
    val service = "org.freedesktop.Notifications"
    val path = "/org/freedesktop/Notifications"
    program("gdbus")?.let { return listOf(it.toString(), "call", "--session", "--dest", service, "--object-path", path, "--method", "$service.CloseNotification", id.toString()) }
    program("busctl")?.let { return listOf(it.toString(), "--user", "call", service, path, service, "CloseNotification", "u", id.toString()) }
    return null
}

internal actual suspend fun showNotification(note: DesktopNote, log: (message: String, isError: Boolean) -> Unit, onClick: () -> Unit) = withContext(Dispatchers.IO) {
    val program = findProgram("notify-send")
    if (program == null) {
        log("no way to show a notification: it needs notify-send, which comes with libnotify", true)
        return@withContext
    }
    runCatching {
        notifying.lock()
        try {
            endNotifiersOnExit
            val before = shownNotifications[note.tag]
            val process = startQuietly(notifyCommand(program, note, before?.id), keepOutput = true)
            val output = process.inputStream.bufferedReader()
            // The first line is the number. With no service to give one, there is no line, or a nought.
            val id = output.readLine()?.trim()?.toLongOrNull()?.takeIf { it > 0 }
            if (id == null) {
                process.destroy()
                log("the desktop didn't take the notification: is a notification service running?", true)
                return@runCatching
            }
            val shown = ShownNotification(process, id)
            shownNotifications[note.tag] = shown
            // The one before has been replaced under the same number, and its program would wait for ever.
            before?.process?.destroy()
            keepWithinLimit()
            log(if (before?.id == id) "replaced notification $id" else "showed notification $id", false)
            // Follows the notification until it is closed, on a thread of its own: it may be up for hours.
            Thread({
                val clicked = runCatching { output.lineSequence().any { it.trim() == "default" } }.getOrDefault(false)
                // Clicked or not, this notification is over; a later one for its tab starts afresh.
                shownNotifications.remove(note.tag, shown)
                process.destroy()
                if (clicked) {
                    // A desktop may leave a clicked notification up. Newer versions of notify-send
                    // close it themselves; asking a second time does no harm.
                    closeNotificationNumbered(id)
                    runCatching { onClick() }
                }
            }, "tono-notification-$id").apply { isDaemon = true }.start()
        } finally {
            notifying.unlock()
        }
    }.onFailure { log("couldn't show a notification: ${it.message}", true) }
    Unit
}

/** Stops waiting on the oldest notifications when there are a great many up. They stay where they are, and can no longer be clicked through. */
private fun keepWithinLimit() {
    val over = shownNotifications.size - MAX_NOTIFICATIONS
    if (over <= 0) return
    shownNotifications.entries.sortedBy { it.value.id ?: 0 }.take(over).forEach { (tag, shown) ->
        if (shownNotifications.remove(tag, shown)) shown.process.destroy()
    }
}

internal actual fun closeNotification(tag: String) {
    val shown = shownNotifications.remove(tag) ?: return
    Thread({
        shown.id?.let(::closeNotificationNumbered)
        shown.process.destroy()
    }, "tono-notification-close").apply { isDaemon = true }.start()
}

/** Asks the desktop to close a notification, and waits a moment for it. One already closed is no trouble. */
private fun closeNotificationNumbered(id: Long) {
    val command = closeNotificationCommand(id, ::findProgram) ?: return
    runCatching {
        val closing = startQuietly(command)
        if (!closing.waitFor(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) closing.destroyForcibly()
    }
}

/**
 * Asks the toolkit to raise the window and give it the keyboard. Whether that happens is the
 * window manager's to say: Hyprland as Omarchy sets it up (`misc:focus_on_activate`) goes to
 * the window, on whichever workspace it is; others only mark it as wanting attention.
 */
internal actual fun bringWindowToFront() {
    java.awt.EventQueue.invokeLater {
        java.awt.Window.getWindows().firstOrNull { it is java.awt.Frame && it.isShowing }?.let { window ->
            runCatching {
                window.toFront()
                window.requestFocus()
            }
        }
    }
}

private const val MAX_NOTIFICATIONS = 32
private const val CLOSE_TIMEOUT_SECONDS = 3L

internal actual fun resizePointer(sideways: Boolean): androidx.compose.ui.input.pointer.PointerIcon =
    androidx.compose.ui.input.pointer.PointerIcon(java.awt.Cursor(if (sideways) java.awt.Cursor.E_RESIZE_CURSOR else java.awt.Cursor.N_RESIZE_CURSOR))

/** Not on the desktop, where the toolkit is asked instead: see [WatchHistoryButtons]. */
internal actual fun historyButton(button: PointerButton?): Boolean? = null

/**
 * The window toolkit is asked, since Compose doesn't pass these presses on under X11: it
 * knows five buttons, and there the side buttons are the sixth and seventh.
 */
@Composable
internal actual fun WatchHistoryButtons(onPress: (forward: Boolean) -> Unit) {
    val report by rememberUpdatedState(onPress)
    DisposableEffect(Unit) {
        val x11 = Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.X11.XToolkit"
        val listener = AWTEventListener { event ->
            if (event is MouseEvent && event.id == MouseEvent.MOUSE_PRESSED) historyButton(event.button, x11)?.let { report(it) }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
        onDispose { Toolkit.getDefaultToolkit().removeAWTEventListener(listener) }
    }
}

/**
 * Which of a mouse's side buttons has the number the window toolkit gives it, counting from 1.
 *
 * Elsewhere they are 4 and 5. Under X11, which is also what the toolkit speaks on Wayland,
 * they are 6 and 7: X numbers the side buttons 8 and 9, after the four directions of the
 * wheel, and the toolkit takes two off for the vertical wheel only. That leaves 4 and 5 for
 * the wheel tilted left and right, which a touchpad sends for a sideways scroll, and which
 * must not go through history. Seen with a real toolkit on a nested X server.
 */
internal fun historyButton(button: Int, x11: Boolean): Boolean? = when (button) {
    if (x11) 6 else 4 -> false
    if (x11) 7 else 5 -> true
    else -> null
}
