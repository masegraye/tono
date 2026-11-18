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

internal actual fun clockTime(millis: Long): String = TIME.format(Instant.ofEpochMilli(millis))

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal actual fun Tooltip(tip: @Composable () -> Unit, content: @Composable () -> Unit) {
    TooltipArea(tooltip = tip, delayMillis = 500, tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 18.dp)), content = content)
}

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
