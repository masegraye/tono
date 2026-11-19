package org.example.kotlintest.rexspike

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.example.kotlintest.ghostty.MouseAction
import org.example.kotlintest.ghostty.MouseButton
import org.example.kotlintest.ghostty.MouseInput
import org.example.kotlintest.ghostty.TerminalFrame
import org.example.kotlintest.rex.RexClient
import org.example.kotlintest.rex.ScrollTelemetry
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

/**
 * `--scroll <pattern>`: turns the wheel over the selected host's focused terminal with no
 * window, the way the app does, to measure what a program that takes the mouse makes of it
 * over a given link (backlog-054). Run it with `TONO_SCROLL_TELEMETRY=<file>`, which is
 * where what happened is written.
 *
 * The pattern is groups of `<notches>x<milliseconds apart>`, with commas between, such as
 * `10x100,30x8`: ten notches a tenth of a second apart, a pause, then thirty in a flick.
 * Groups go down and up in turn. `--pause <ms>` is the pause between groups (800).
 *
 * In place of a window, each frame the terminal reads counts as drawn at the next tick of a
 * screen that shows `--hz <n>` frames a second (60), which is when a window would have
 * drawn it. Not passive: the program in the terminal is sent the reports.
 */
internal suspend fun scroll(client: RexClient, args: Array<String>) {
    fun option(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val pattern = option("--scroll") ?: return
    val pause = option("--pause")?.toLongOrNull() ?: 800L
    val hertz = option("--hz")?.toDoubleOrNull() ?: 60.0
    if (!ScrollTelemetry.enabled) {
        println("Set TONO_SCROLL_TELEMETRY=<file> to have the scrolling written down.")
        return
    }
    val host = client.hosts.value.firstOrNull { it.saved.label == client.selectedHost.value } ?: error("No host is selected")
    val terminal = withTimeoutOrNull(10_000) {
        host.state.first { state -> state.window?.let { window -> state.terminals[window.focusedBlockId ?: window.panes.firstOrNull()?.blockId] } != null }
            .let { state -> state.terminals[state.window?.focusedBlockId ?: state.window?.panes?.firstOrNull()?.blockId] }
    } ?: error("No terminal opened")
    withTimeoutOrNull(10_000) { terminal.frame.first { it != null } } ?: error("The terminal drew nothing")
    println("scrolling ${terminal.blockId} at ${terminal.grid.value}: $pattern")

    // The screen: a frame is drawn at the tick after it was read.
    var running = true
    val screen = thread(isDaemon = true, name = "screen") {
        val interval = (1e9 / hertz).toLong()
        var next = System.nanoTime() + interval
        var last = System.nanoTime()
        var shown: TerminalFrame? = null
        while (running) {
            LockSupport.parkNanos(next - System.nanoTime())
            val now = System.nanoTime()
            if (now < next) continue
            next += interval
            if (next < now) next = now + interval
            val frame = terminal.frame.value
            if (frame != null && frame !== shown) {
                shown = frame
                ScrollTelemetry.drawn(frame, 0, 0, 0)
            }
            if (ScrollTelemetry.scrolling) ScrollTelemetry.tick((now - last) / 1000)
            last = now
        }
    }

    var down = true
    // On a thread of its own, as the window's is: the notches are timed by parking it.
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    for (group in pattern.split(',')) {
        val notches = group.substringBefore('x').trim().toInt()
        val apart = group.substringAfter('x').trim().toLong()
        repeat(notches) {
            val id = ScrollTelemetry.wheel(terminal, if (down) 1f else -1f, false, "${if (down) 3 else -3} rows", "harness")
            terminal.mouse(
                MouseInput(MouseAction.Press, if (down) MouseButton.WheelDown else MouseButton.WheelUp, x = 105f, y = 110f, cellWidth = 10, cellHeight = 20),
                scrollRows = if (down) 3 else -3, wheel = id,
            )
            // Not `delay`, whose milliseconds are too coarse for a flick's few.
            LockSupport.parkNanos(apart * 1_000_000)
        }
        delay(pause)
        ScrollTelemetry.summary()?.let { println("  $group: $it") }
        down = !down
    }
    }
    delay(ScrollTelemetry.ACTIVE_MICROS / 1000)
    running = false
    screen.join(200)
}
