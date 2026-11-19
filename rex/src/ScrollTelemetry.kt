package org.example.kotlintest.rex

import org.example.kotlintest.ghostty.TerminalFrame
import org.example.kotlintest.ghostty.TerminalRow
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * Measures scrolling, to say why it feels the way it does (backlog-053). Off unless asked
 * for, and then everything it is told becomes a line of text:
 *
 * - `wheel`: a turn of the wheel or a move on a touchpad as it arrived, with what the
 *   toolkit said of it and what was asked of the terminal for it;
 * - `handled`: what the terminal did with it, which is to scroll its own view or to tell
 *   its program, and how long it waited its turn;
 * - `frame`: a frame read from the terminal, how long reading took, and how many of its
 *   rows weren't in the frame before;
 * - `draw`: a frame drawn, how long laying out its text and drawing took, and how long
 *   after the wheel that asked for it;
 * - `tick`: the gap between two of the window's frames while scrolling, which is where a
 *   frame that came late or not at all shows;
 * - `summary`: all of that for one go at scrolling, in one line.
 *
 * On the desktop it is asked for with the environment variable `TONO_SCROLL_TELEMETRY` or
 * the system property `tono.scrollTelemetry`, set to a file to add the lines to, or to `1`
 * for standard error. In a browser, with `?scrollTelemetry` in the page's address or
 * `tono.scrollTelemetry` in its storage, and the lines go to the console. The summaries
 * also go to the app's debug log.
 *
 * Times are in microseconds since the app started. While it is off, each call is one read
 * of a flag.
 */
object ScrollTelemetry {
    private val origin = TimeSource.Monotonic.markNow()
    private var sink: ((String) -> Unit)? = scrollTelemetrySink()

    @Volatile
    var enabled: Boolean = sink != null
        private set

    /** Starts measuring, with the lines going to [lines]. For tests and measuring harnesses. */
    fun start(lines: (String) -> Unit) = locked(this) {
        reset()
        sink = lines
        enabled = true
    }

    fun stop() = locked(this) {
        enabled = false
        sink = null
        reset()
    }

    /** Microseconds since the app started, on a clock that only goes forward. */
    fun micros(): Long = origin.elapsedNow().inWholeMicroseconds

    private class Wheel(val id: Int, val at: Long) {
        var report = false
    }

    /** A frame as it was read, until it is drawn or one after it is. */
    private class Published(val frame: TerminalFrame, val at: Long, val wheels: List<Wheel>, val answers: List<Wheel>)

    private var wheelIds = 0
    private val arrived = ArrayList<Wheel>() // not yet handled
    private val handled = ArrayList<Wheel>() // handled, and not yet in a frame
    private val awaitingAnswer = ArrayList<Wheel>() // told to a program, which hasn't drawn since
    private val published = ArrayList<Published>()
    private var lastRows: List<TerminalRow> = emptyList()

    /** The terminal last scrolled, which is the one whose frames are counted. */
    private var watched: Any? = null
    private var lastDrawAt = 0L
    private var lastWheelAt = 0L

    // One go at scrolling, for the summary.
    private var burstStart = 0L
    private var wheels = 0
    private var preciseWheels = 0
    private var rowsScrolled = 0
    private var reports = 0
    private var frames = 0
    private var framesNotDrawn = 0
    private val buildMicros = ArrayList<Long>()
    private val drawMicros = ArrayList<Long>()
    private val scrollLatency = ArrayList<Long>()
    private val answerLatency = ArrayList<Long>()
    private val ticks = ArrayList<Long>()
    private var rowsLaidOut = 0
    private var rowsFresh = 0

    private fun reset() {
        arrived.clear(); handled.clear(); awaitingAnswer.clear(); published.clear()
        lastRows = emptyList()
        resetBurst()
    }

    private fun resetBurst() {
        burstStart = 0; wheels = 0; preciseWheels = 0; rowsScrolled = 0; reports = 0; frames = 0; framesNotDrawn = 0
        buildMicros.clear(); drawMicros.clear(); scrollLatency.clear(); answerLatency.clear(); ticks.clear()
        rowsLaidOut = 0; rowsFresh = 0
    }

    private fun line(at: Long, text: String) {
        sink?.invoke("scroll $at $text")
    }

    /**
     * A wheel event arrived, on the thread that draws. Returns a number to hand to
     * [wheelHandled], or 0 while this is off.
     *
     * @param terminal the terminal it was over, as [frame] is told of it
     * @param delta what the toolkit gave: 1 is a notch of a wheel, and a touchpad gives fractions
     * @param asked what was asked of the terminal for it, in words
     * @param native what the window system said of the event, where that can be read
     */
    fun wheel(terminal: Any?, delta: Float, asked: String, native: String?): Int {
        if (!enabled) return 0
        return locked(this) {
            val now = micros()
            val wheel = Wheel(++wheelIds, now)
            if (watched !== terminal) {
                watched = terminal
                lastRows = emptyList()
            }
            arrived += wheel
            if (wheels == 0) burstStart = now
            val precise = delta != delta.toInt().toFloat()
            wheels++
            if (precise) preciseWheels++
            val gap = if (lastWheelAt == 0L) -1 else now - lastWheelAt
            lastWheelAt = now
            line(now, "wheel id=${wheel.id} delta=$delta precise=$precise gap_us=$gap asked=\"$asked\"" + (native?.let { " native=\"$it\"" } ?: ""))
            wheel.id
        }
    }

    /**
     * What the terminal did with a wheel event, on the terminal's thread: scrolled its view
     * by [scrolledRows], or sent its program a report of [reportBytes] bytes, or neither.
     */
    fun wheelHandled(id: Int, scrolledRows: Int, reportBytes: Int) {
        if (!enabled || id == 0) return
        locked(this) {
            val now = micros()
            val index = arrived.indexOfFirst { it.id == id }
            if (index < 0) return@locked
            val wheel = arrived.removeAt(index)
            handled += wheel
            if (reportBytes > 0) {
                wheel.report = true
                reports++
            }
            rowsScrolled += if (scrolledRows < 0) -scrolledRows else scrolledRows
            line(now, "handled id=$id waited_us=${now - wheel.at} " + if (reportBytes > 0) "report_bytes=$reportBytes" else "scrolled_rows=$scrolledRows")
        }
    }

    /**
     * A frame was read from [terminal], on the terminal's thread, which took [micros].
     * [output] is whether the terminal's program had written since the frame before.
     */
    fun frame(terminal: Any?, frame: TerminalFrame, micros: Long, output: Boolean) {
        if (!enabled) return
        locked(this) {
            if (terminal !== watched) return@locked
            val now = micros()
            val before = lastRows.toHashSet()
            val fresh = frame.rows.count { it !in before }
            var moved = 0
            val limit = minOf(frame.rows.size, lastRows.size)
            for (index in 0 until limit) if (frame.rows[index] !== lastRows[index]) moved++
            lastRows = frame.rows
            val scrolling = handled.isNotEmpty() || awaitingAnswer.isNotEmpty() || now - lastWheelAt < BURST_MICROS
            if (!scrolling) return@locked
            val answered = if (output && moved > 0) ArrayList(awaitingAnswer).also { awaitingAnswer.clear() } else emptyList<Wheel>()
            val scrolled = ArrayList<Wheel>()
            for (wheel in handled) if (wheel.report) awaitingAnswer += wheel else scrolled += wheel
            handled.clear()
            published += Published(frame, now, scrolled, answered)
            if (published.size > 64) published.removeAt(0)
            frames++
            buildMicros += micros
            rowsFresh += fresh
            line(now, "frame build_us=$micros rows=${frame.rows.size} runs=${frame.rows.sumOf { it.runs.size }} rows_fresh=$fresh rows_changed=$moved output=$output wheels=${scrolled.size} answers=${answered.size}")
        }
    }

    /**
     * A frame was drawn, on the thread that draws: its text took [layoutMicros] to lay out,
     * for [laidOut] rows that had no layout yet, out of [micros] in all.
     */
    fun drawn(frame: TerminalFrame, micros: Long, layoutMicros: Long, laidOut: Int) {
        if (!enabled) return
        locked(this) {
            val index = published.indexOfFirst { it.frame === frame }
            if (index < 0) return@locked // not a frame made by scrolling, or drawn before
            val now = micros()
            var scrollWorst = -1L
            var answerWorst = -1L
            for (at in 0..index) {
                val entry = published[at]
                for (wheel in entry.wheels) (now - wheel.at).let { scrollLatency += it; if (it > scrollWorst) scrollWorst = it }
                for (wheel in entry.answers) (now - wheel.at).let { answerLatency += it; if (it > answerWorst) answerWorst = it }
            }
            framesNotDrawn += index
            repeat(index + 1) { published.removeAt(0) }
            drawMicros += micros
            rowsLaidOut += laidOut
            val gap = if (lastDrawAt == 0L) -1 else now - lastDrawAt
            lastDrawAt = now
            line(
                now,
                "draw draw_us=$micros layout_us=$layoutMicros rows_laid_out=$laidOut frames_skipped=$index since_last_draw_us=$gap" +
                    (if (scrollWorst >= 0) " wheel_to_draw_us=$scrollWorst" else "") +
                    (if (answerWorst >= 0) " wheel_to_answer_drawn_us=$answerWorst" else ""),
            )
        }
    }

    /** Whether a wheel event came so lately that the window's frames are worth timing: see [tick]. */
    val scrolling: Boolean get() = enabled && micros() - lastWheelAt < BURST_MICROS

    /** The window drew a frame [micros] after the one before, while [scrolling]. */
    fun tick(micros: Long) {
        if (!enabled) return
        locked(this) {
            ticks += micros
            line(micros(), "tick gap_us=$micros")
        }
    }

    /**
     * One line about the scrolling since the last summary, or null if there was none. To be
     * asked for once the wheel has been still a moment.
     */
    fun summary(): String? {
        if (!enabled) return null
        return locked(this) {
            if (wheels == 0) return@locked null
            val now = micros()
            val seconds = (lastWheelAt - burstStart) / 1_000_000.0
            // A frame of the window is late when it took half as long again as they usually do.
            val usual = percentile(ticks, 50)
            val late = if (usual > 0) ticks.count { it > usual * 3 / 2 } else 0
            val text = buildString {
                append("wheel events $wheels")
                if (preciseWheels > 0) append(" ($preciseWheels with fractions)")
                append(" in ${oneDecimal(seconds * 1000)} ms")
                append("; scrolled $rowsScrolled rows; reports to the program $reports")
                append("; frames read $frames (${describe(buildMicros)} ms, $rowsFresh new rows), of which never drawn $framesNotDrawn")
                append("; drawn ${drawMicros.size} (${describe(drawMicros)} ms, $rowsLaidOut rows laid out)")
                if (scrollLatency.isNotEmpty()) append("; wheel to drawn ${describe(scrollLatency)} ms")
                if (answerLatency.isNotEmpty()) append("; wheel to the program's answer drawn ${describe(answerLatency)} ms")
                if (awaitingAnswer.isNotEmpty()) append("; ${awaitingAnswer.size} reports the program drew nothing for")
                if (ticks.isNotEmpty()) append("; window frames ${ticks.size} (${describe(ticks)} ms), late $late")
            }
            line(now, "summary $text")
            awaitingAnswer.clear()
            resetBurst()
            text
        }
    }

    private fun percentile(values: List<Long>, percent: Int): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * percent / 100)]
    }

    /** The middle, the 95th in a hundred and the largest of some times, in milliseconds. */
    private fun describe(micros: List<Long>): String =
        if (micros.isEmpty()) "none" else "median ${oneDecimal(percentile(micros, 50) / 1000.0)}, p95 ${oneDecimal(percentile(micros, 95) / 1000.0)}, max ${oneDecimal(percentile(micros, 100) / 1000.0)}"

    private fun oneDecimal(value: Double): String {
        val tenths = kotlin.math.round(value * 10).toLong()
        return "${tenths / 10}.${if (tenths < 0) -tenths % 10 else tenths % 10}"
    }

    /** How long after a wheel event scrolling counts as still going on. */
    const val BURST_MICROS = 400_000L
}

/** Where the lines of [ScrollTelemetry] go, or null when nobody asked for them. */
internal expect fun scrollTelemetrySink(): ((String) -> Unit)?
