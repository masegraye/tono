package org.example.kotlintest

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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

internal actual suspend fun clipboardText(): String? = runCatching {
    Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
}.getOrNull()

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
