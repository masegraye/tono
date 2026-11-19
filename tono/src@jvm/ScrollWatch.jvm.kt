package org.example.kotlintest

import org.example.kotlintest.rex.ScrollTelemetry
import java.awt.EventQueue
import java.nio.file.Path

/**
 * Watches the two threads scrolling depends on while it is measured ([ScrollTelemetry]):
 * the one that draws and takes the wheel, and the one the terminals live on. A thread that
 * doesn't get to its work for a while is written down as a `stall` with where it was, and
 * every wait of the drawing thread over half a frame as a `ui_lag`.
 *
 * With `TONO_SCROLL_JFR=<file>` (or `-Dtono.scrollJfr=<file>`) a Java Flight Recorder
 * recording of the whole run is written there too, for what the stacks don't say. It is
 * read with `jfr print` or JDK Mission Control.
 *
 * Only looks while the wheel has turned in the last second and a half, and is never
 * started unless measuring was asked for.
 */
object ScrollWatch {
    fun start() {
        if (!ScrollTelemetry.enabled) return
        (System.getProperty("tono.scrollJfr") ?: System.getenv("TONO_SCROLL_JFR"))?.takeIf { it.isNotEmpty() }?.let(::record)
        Thread(::watch, "tono-scroll-watch").apply { isDaemon = true }.start()
    }

    private fun record(file: String) {
        runCatching {
            val recording = jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"))
            recording.destination = Path.of(file)
            recording.setDumpOnExit(true)
            recording.start()
            ScrollTelemetry.note("jfr", ",\"file\":\"${file.replace("\\", "/").replace("\"", "")}\"")
        }.onFailure { System.err.println("tono: no Flight Recorder recording: ${it.message ?: it}") }
    }

    /** Whether the drawing thread has been asked to answer and hasn't yet. */
    @Volatile private var waiting = false

    private fun watch() {
        var posted = 0L
        var told = false
        var terminalTold = 0L
        while (true) {
            if (!ScrollTelemetry.active) {
                Thread.sleep(50)
                continue
            }
            Thread.sleep(4)
            val now = ScrollTelemetry.micros()
            if (!waiting) {
                waiting = true
                told = false
                posted = now
                val at = posted
                EventQueue.invokeLater {
                    val lag = ScrollTelemetry.micros() - at
                    if (lag > UI_LAG_MICROS) ScrollTelemetry.note("ui_lag", ",\"blocked_us\":$lag")
                    waiting = false
                }
            } else if (!told && now - posted > STALL_MICROS) {
                told = true
                ScrollTelemetry.stall("ui", now - posted, stackOf("AWT-EventQueue"))
            }
            val busy = ScrollTelemetry.terminalBusySince
            if (busy != 0L && busy != terminalTold && now - busy > STALL_MICROS) {
                terminalTold = busy
                ScrollTelemetry.stall("rex-terminals", now - busy, stackOf("rex-terminals"))
            }
        }
    }

    private val threads = HashMap<String, Thread>()

    private fun stackOf(name: String): List<String> {
        val thread = threads[name]?.takeIf { it.isAlive }
            ?: Thread.getAllStackTraces().keys.firstOrNull { it.name.startsWith(name) }?.also { threads[name] = it }
            ?: return emptyList()
        return thread.stackTrace.take(16).map { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
    }

    /** Half a frame of a screen that shows sixty a second. */
    private const val UI_LAG_MICROS = 8_000L
    private const val STALL_MICROS = 25_000L
}
