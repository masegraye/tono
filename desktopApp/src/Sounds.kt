package org.example.kotlintest

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.kotlintest.rex.ActivityState
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The sound for something a program reported, by its name in the desktop's sound theme, so
 * it is whatever the desktop plays for a warning, an error, or something finishing. Null
 * for states that aren't news.
 */
internal fun soundFor(state: ActivityState): String? = when (state) {
    ActivityState.Blocked -> "dialog-warning" // it is waiting for you
    ActivityState.Error -> "dialog-error"
    ActivityState.Done -> "complete"
    else -> null
}

/**
 * Lets one sound through for a burst of news. Several programs often report at once, and a
 * chorus says less than a single sound: what arrives within [gatherMillis] of the first is
 * weighed together and the most pressing wins (waiting for you, then an error, then done);
 * anything after that, until [quietMillis] have passed, is dropped rather than played late.
 */
internal class SoundGate(private val gatherMillis: Long = 100, private val quietMillis: Long = 1_000) {
    private var pending: ActivityState? = null
    private var startedAt = Long.MIN_VALUE / 2

    /** Takes one piece of news. True if this starts a burst: wait [gatherMillis], then [take]. */
    @Synchronized
    fun offer(state: ActivityState, nowMillis: Long): Boolean {
        val sinceStart = nowMillis - startedAt
        if (sinceStart < quietMillis) {
            if (sinceStart < gatherMillis && pending != null) pending = maxOf(pending!!, state)
            return false
        }
        startedAt = nowMillis
        pending = state
        return true
    }

    /** What the burst came to. */
    @Synchronized
    fun take(): ActivityState? = pending.also { pending = null }
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

/** Plays a sound when a program in a tab you aren't looking at wants you, fails, or finishes. */
class Sounds(private val scope: CoroutineScope, private val log: (message: String, isError: Boolean) -> Unit) {
    private val gate = SoundGate()

    /** Asks for the sound for [state]. Returns at once; the sound, if this one gets through, follows. */
    fun request(state: ActivityState) {
        if (soundFor(state) == null || !gate.offer(state, System.currentTimeMillis())) return
        scope.launch(Dispatchers.IO) {
            delay(GATHER_MILLIS)
            gate.take()?.let(::soundFor)?.let(::play)
        }
    }

    private fun play(id: String) {
        val command = soundCommand(id, ::findProgram, ::findSound)
        if (command == null) {
            log("no way to play $id: it needs mpv, libcanberra, PipeWire or PulseAudio, and the freedesktop sound theme", true)
            return
        }
        runCatching {
            // The name of the sound is one of three fixed words, and nothing a server or a
            // program says is ever part of the command.
            val process = ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            process.outputStream.close()
            if (!process.waitFor(PLAY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
        }.onSuccess { log("played $id with ${Path.of(command.first()).fileName}", false) }
            .onFailure { log("couldn't play $id: ${it.message}", true) }
    }

    private companion object {
        const val GATHER_MILLIS = 100L
        const val PLAY_TIMEOUT_SECONDS = 5L

        /** Looks on PATH, in its absolute directories only, so nothing in the current folder is ever run. */
        fun findProgram(name: String): Path? = System.getenv("PATH").orEmpty().split(':')
            .filter { it.startsWith("/") }
            .map { Path.of(it, name) }
            .firstOrNull(Files::isExecutable)

        fun findSound(id: String): Path? {
            val home = System.getenv("XDG_DATA_HOME")?.takeIf { it.startsWith("/") } ?: (System.getProperty("user.home") + "/.local/share")
            val shared = System.getenv("XDG_DATA_DIRS")?.takeIf { it.isNotBlank() } ?: "/usr/local/share:/usr/share"
            return (listOf(home) + shared.split(':')).filter { it.startsWith("/") }
                .map { Path.of(it, "sounds/freedesktop/stereo/$id.oga") }
                .firstOrNull(Files::isRegularFile)
        }
    }
}
