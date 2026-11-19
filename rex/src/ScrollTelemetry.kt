package org.example.kotlintest.rex

import org.example.kotlintest.ghostty.TerminalFrame
import org.example.kotlintest.ghostty.TerminalRow
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * Measures scrolling, to say why it feels the way it does (backlog-053, backlog-054). Off
 * unless asked for, and then everything it is told becomes a line: one JSON object, with
 * `t`, the time in microseconds on one clock that only goes forward, and `ev`, what it is.
 * `tools/scroll/analyze.py` reads the lines and `tools/scroll/graph.py` draws them.
 *
 * The first line, `start`, says what `t` is counted from (`mono_us`, the machine's own
 * monotonic clock at `t`, where that can be read, and `wall_ms`), so that a record made by
 * something else on the same machine can be laid beside it.
 *
 * What the wheel became:
 *
 * - `wheel`: a turn of the wheel or a move on a touchpad as it arrived, with what the
 *   toolkit said of it and what was asked of the terminal for it;
 * - `handled`: what the terminal did with it, which is to scroll its own view or to tell
 *   its program, and how long it waited its turn;
 * - `frame`: a frame read from the terminal, how long reading took, and how many of its
 *   rows weren't in the frame before;
 * - `draw`: a frame drawn, how long laying out its text and drawing took, and how long
 *   after the wheel that asked for it;
 * - `glide`: the same frame drawn again nearer its place, with smooth scrolling on;
 * - `tick`: the gap between two of the window's frames while scrolling, which is where a
 *   frame that came late or not at all shows;
 * - `summary`: all of that for one go at scrolling, in one line.
 *
 * A turn of the wheel that a program takes becomes a report, sent on the control
 * connection, and what the program draws for it comes back on the terminal's stream:
 *
 * - `send_queued`: a report was asked to be sent (`seq`, and `wheel`, the turn it is for),
 *   with how many requests were waiting on that connection ahead of it (`depth`);
 * - `send_start`: its turn came, after `waited_us`;
 * - `send_written`: it was handed to the socket's writer, with how many reports it carries;
 * - `send_acked`: the server answered it, `rtt_us` later;
 * - `rx`: a chunk of a terminal's output arrived from its socket (`stream`, `n`, `bytes`);
 * - `feed`: chunks were given to the terminal on its thread: which (`first_n`, `chunks`),
 *   how long the oldest waited (`waited_us`), how long the terminal took (`feed_us`), and
 *   the synchronized updates (mode 2026) begun and ended in them (`sync_begin`, `sync_end`);
 * - `cmds`: things asked of the terminal were done on its thread, and how long that took;
 * - `frame` also says why it was read (`cause`), when the terminal's thread woke for it
 *   (`wake_us` before), how long the thread had waited for something to do before that
 *   (`slept_us`), and whether the program is in the middle of a synchronized update (`sync`);
 * - `held`: no frame was read after output, because the program is in the middle of a
 *   synchronized update, and the last finished frame stays up;
 * - `action`: any other request on the control connection that waited or took long;
 * - `stall`: a thread didn't get to its work for `blocked_us`, with where it was (`stack`).
 *
 * Lines other than `wheel` are written only for a second and a half after a turn of the
 * wheel, and no more than [MAX_LINES] in all.
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

    private fun started() = line(micros(), "start", ",\"version\":2${telemetryClock()}")

    /** Starts measuring, with the lines going to [lines]. For tests and measuring harnesses. */
    fun start(lines: (String) -> Unit) = locked(this) {
        reset()
        sink = lines
        linesWritten = 0
        enabled = true
        started()
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
    private class Published(val frame: TerminalFrame, val number: Int, val at: Long, val wheels: List<Wheel>, val answers: List<Wheel>)

    private var wheelIds = 0
    private var frameNumbers = 0
    private var lastHandled = 0
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
    private var glides = 0

    private fun reset() {
        arrived.clear(); handled.clear(); awaitingAnswer.clear(); published.clear()
        lastRows = emptyList()
        resetBurst()
    }

    private fun resetBurst() {
        burstStart = 0; wheels = 0; preciseWheels = 0; rowsScrolled = 0; reports = 0; frames = 0; framesNotDrawn = 0
        buildMicros.clear(); drawMicros.clear(); scrollLatency.clear(); answerLatency.clear(); ticks.clear()
        rowsLaidOut = 0; rowsFresh = 0; glides = 0
    }

    private var linesWritten = 0

    /** Writes one line: [fields] is the rest of a JSON object, starting with a comma, or empty. */
    private fun line(at: Long, event: String, fields: String = "") {
        val to = sink ?: return
        if (linesWritten >= MAX_LINES) {
            if (linesWritten == MAX_LINES) {
                linesWritten++
                to("{\"t\":$at,\"ev\":\"truncated\",\"lines\":$MAX_LINES}")
            }
            return
        }
        linesWritten++
        to("{\"t\":$at,\"ev\":\"$event\"$fields}")
    }

    private fun quoted(text: String): String = buildString(text.length + 2) {
        append('"')
        for (char in text) when {
            char == '"' -> append("\\\"")
            char == '\\' -> append("\\\\")
            char == '\n' -> append("\\n")
            char < ' ' -> append(' ')
            else -> append(char)
        }
        append('"')
    }

    /** Whether the wheel turned so lately that what the app does is worth writing down. */
    val active: Boolean get() = enabled && isActive(micros())

    /** The wheel turned lately, or a report it became is still on its way. */
    private fun isActive(now: Long) = wheelIds > 0 && (now - lastWheelAt < ACTIVE_MICROS || now - lastSendAt < ACTIVE_MICROS)
    private var lastSendAt = -ACTIVE_MICROS

    private val numbers = HashMap<Any, Int>()

    /** A small number for a terminal or a stream, to tell it from the others in the lines. */
    private fun numberOf(thing: Any?): Int = if (thing == null) 0 else numbers.getOrPut(thing) {
        if (numbers.size > 4096) numbers.clear()
        ++numbered
    }
    private var numbered = 0

    /**
     * A wheel event arrived, on the thread that draws. Returns a number to hand to
     * [wheelHandled], or 0 while this is off.
     *
     * @param terminal the terminal it was over, as [frame] is told of it
     * @param delta what the toolkit gave: on the desktop 1 is a notch of a wheel, and in a browser it is pixels
     * @param precise whether it was taken for one of the small moves a touchpad sends, and not a notch
     * @param asked what was asked of the terminal for it, in words
     * @param native what the window system said of the event, where that can be read
     */
    fun wheel(terminal: Any?, delta: Float, precise: Boolean, asked: String, native: String?): Int {
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
            wheels++
            if (precise) preciseWheels++
            val gap = if (lastWheelAt == 0L) -1 else now - lastWheelAt
            lastWheelAt = now
            line(now, "wheel", ",\"id\":${wheel.id},\"term\":${numberOf(terminal)},\"delta\":$delta,\"precise\":$precise,\"gap_us\":$gap,\"asked\":${quoted(asked)}" + (native?.let { ",\"native\":${quoted(it)}" } ?: ""))
            wheel.id
        }
    }

    /** A wheel event too small to scroll a row by itself was added to what the next ones bring, and nothing was asked of the terminal. */
    fun wheelHeld(id: Int) {
        if (!enabled || id == 0) return
        locked(this) {
            arrived.removeAll { it.id == id }
            line(micros(), "held", ",\"id\":$id")
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
            lastHandled = id
            line(now, "handled", ",\"id\":$id,\"waited_us\":${now - wheel.at}," + if (reportBytes > 0) "\"report_bytes\":$reportBytes" else "\"scrolled_rows\":$scrolledRows")
        }
    }

    /** What the terminal's thread did before it read a frame: see [frame]. */
    class Turn {
        /** When the thread woke for this turn, by [micros]. */
        var wokeAt = 0L
        /** How long it had waited for something to do since the frame before. */
        var sleptMicros = 0L
        var output = false
        var commands = 0
        var timer = false
        /** Whether the program is in the middle of a synchronized update (mode 2026) as the frame is read. */
        var sync = false
    }

    /**
     * A frame was read from [terminal], on the terminal's thread, which took [micros].
     * [output] is whether the terminal's program had written since the frame before.
     * [turn] says more of how the thread came to read it, where that was kept.
     */
    fun frame(terminal: Any?, frame: TerminalFrame, micros: Long, output: Boolean, turn: Turn? = null) {
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
            val scrolling = handled.isNotEmpty() || awaitingAnswer.isNotEmpty() || isActive(now)
            if (!scrolling) return@locked
            val answered = if (output && moved > 0) ArrayList(awaitingAnswer).also { awaitingAnswer.clear() } else emptyList<Wheel>()
            val scrolled = ArrayList<Wheel>()
            for (wheel in handled) if (wheel.report) awaitingAnswer += wheel else scrolled += wheel
            handled.clear()
            val number = ++frameNumbers
            published += Published(frame, number, now, scrolled, answered)
            if (published.size > 64) published.removeAt(0)
            frames++
            buildMicros += micros
            rowsFresh += fresh
            val cause = if (turn == null) "" else buildString {
                append(",\"cause\":\"")
                if (turn.output) append("output")
                if (turn.commands > 0) append(if (isEmpty() || last() == '"') "command" else "+command")
                if (turn.timer) append(if (last() == '"') "timer" else "+timer")
                append("\",\"wake_us\":${now - micros - turn.wokeAt},\"slept_us\":${turn.sleptMicros},\"sync\":${turn.sync}")
            }
            line(
                now, "frame",
                ",\"n\":$number,\"term\":${numberOf(terminal)},\"build_us\":$micros,\"rows\":${frame.rows.size},\"runs\":${frame.rows.sumOf { it.runs.size }},\"rows_fresh\":$fresh,\"rows_changed\":$moved,\"output\":$output" +
                    ",\"wheels\":${scrolled.map { it.id }},\"answers\":${answered.map { it.id }}$cause",
            )
        }
    }

    // --- What a report goes through on its way out, and what comes back (backlog-054) ---

    private var sendSeqs = 0
    private var actionsWaiting = 0
    private var reportsInFlight = 0

    /**
     * Input the terminal worked out for its program, a mouse report, was asked to be sent:
     * on the terminal's thread, right after [wheelHandled] for the turn of the wheel it is
     * for. Returns a number to tell the rest of its way by, or 0 while this is off.
     */
    fun sendQueued(bytes: Int): Int {
        if (!enabled) return 0
        return locked(this) {
            val seq = ++sendSeqs
            val wheel = lastHandled
            lastHandled = 0
            line(micros(), "send_queued", ",\"seq\":$seq,\"wheel\":$wheel,\"bytes\":$bytes,\"depth\":$actionsWaiting,\"in_flight\":$reportsInFlight")
            seq
        }
    }

    /** Its turn on the control connection came, [queuedAt] having been when it was asked for. */
    fun sendStart(seq: Int, queuedAt: Long) {
        if (!enabled || seq == 0) return
        locked(this) { micros().let { lastSendAt = it; line(it, "send_start", ",\"seq\":$seq,\"waited_us\":${it - queuedAt}") } }
    }

    /**
     * A request of [bytes] bytes of input was handed to the socket's writer, carrying the
     * reports numbered [first] to [last] ([sendQueued]). [startedAt] is when sending began.
     */
    fun sendWritten(first: Int, last: Int, bytes: Int, startedAt: Long) {
        if (!enabled || first == 0) return
        locked(this) {
            reportsInFlight++
            micros().let { lastSendAt = it; line(it, "send_written", ",\"seq\":$first,\"last_seq\":$last,\"bytes\":$bytes,\"write_us\":${it - startedAt},\"in_flight\":$reportsInFlight") }
        }
    }

    /** The server answered the request that carried the reports from [first] on, which was written at [writtenAt]. */
    fun sendAcked(first: Int, writtenAt: Long, failed: Boolean = false) {
        if (!enabled || first == 0) return
        locked(this) {
            if (reportsInFlight > 0) reportsInFlight--
            micros().let { lastSendAt = it; line(it, "send_acked", ",\"seq\":$first,\"rtt_us\":${it - writtenAt}" + if (failed) ",\"failed\":true" else "") }
        }
    }

    /** Something was asked of the control connection, which does one thing at a time. Returns when, to hand to [actionDone]. */
    fun actionQueued(): Long {
        if (!enabled) return 0
        return locked(this) {
            actionsWaiting++
            micros()
        }
    }

    /** What was asked at [queuedAt] is done, having started at [startedAt]. Written down if it waited or took long while the wheel turns. */
    fun actionDone(what: String?, queuedAt: Long, startedAt: Long) {
        if (queuedAt == 0L) return
        locked(this) {
            if (actionsWaiting > 0) actionsWaiting--
            if (!enabled) return@locked
            val now = micros()
            if (isActive(now) && (startedAt - queuedAt > 1_000 || now - startedAt > 1_000)) {
                line(now, "action", ",\"what\":${quoted(what ?: "request")},\"waited_us\":${startedAt - queuedAt},\"ran_us\":${now - startedAt},\"depth\":$actionsWaiting")
            }
        }
    }

    /** When each chunk of a stream arrived, until it is fed to its terminal. */
    private class Arrivals {
        var count = 0
        val at = ArrayDeque<Long>()
        val bytes = ArrayDeque<Int>()
    }

    private val arrivals = HashMap<Any, Arrivals>()

    /** A chunk of [bytes] bytes of a terminal's output arrived from the socket of [stream]: on whichever thread reads it. */
    fun received(stream: Any, bytes: Int) {
        if (!enabled) return
        locked(this) {
            val now = micros()
            val known = arrivals.getOrPut(stream) { Arrivals() }
            known.count++
            known.at.addLast(now)
            known.bytes.addLast(bytes)
            // Never more than this is kept: a terminal that isn't being read gets no line for what is dropped.
            if (known.at.size > 8192) { known.at.removeFirst(); known.bytes.removeFirst() }
            if (isActive(now)) line(now, "rx", ",\"stream\":${numberOf(stream)},\"n\":${known.count},\"bytes\":$bytes")
        }
    }

    /** A chunk of [stream] was taken for something other than feeding the terminal, as its first ones are, which hold the snapshot. */
    fun taken(stream: Any) {
        if (!enabled) return
        locked(this) {
            arrivals[stream]?.let { if (it.at.isNotEmpty()) { it.at.removeFirst(); it.bytes.removeFirst() } }
        }
    }

    /** The stream is closed, and what was kept of it is let go. */
    fun streamClosed(stream: Any) {
        if (!enabled) return
        locked(this) { arrivals.remove(stream); numbers.remove(stream) }
    }

    /**
     * Chunks of [stream] were given to [terminal], on its thread: [chunks] of them, of
     * [bytes] in all, which the terminal took [micros] over. [syncBegins] and [syncEnds]
     * count the synchronized updates begun and ended in them, and [marks] are the numbers a
     * test program put in its output to say which report it had drawn for ([Marks]).
     */
    fun fed(terminal: Any?, stream: Any, chunks: Int, bytes: Int, micros: Long, syncBegins: Int, syncEnds: Int, marks: List<Int>) {
        if (!enabled) return
        locked(this) {
            val now = micros()
            val known = arrivals[stream]
            var oldest = -1L
            var first = 0
            if (known != null) {
                first = known.count - known.at.size + 1
                repeat(minOf(chunks, known.at.size)) {
                    val at = known.at.removeFirst()
                    known.bytes.removeFirst()
                    if (oldest < 0) oldest = at
                }
            }
            if (!isActive(now)) return@locked
            line(
                now - micros, "feed",
                ",\"term\":${numberOf(terminal)},\"stream\":${numberOf(stream)},\"first_n\":$first,\"chunks\":$chunks,\"bytes\":$bytes,\"feed_us\":$micros" +
                    ",\"waited_us\":${if (oldest < 0) -1 else now - micros - oldest},\"sync_begin\":$syncBegins,\"sync_end\":$syncEnds" +
                    (if (marks.isEmpty()) "" else ",\"marks\":$marks"),
            )
        }
    }

    /** No frame of [terminal] was read after what just happened, because its program is in the middle of a synchronized update. */
    fun held(terminal: Any?) {
        if (!enabled) return
        locked(this) {
            val now = micros()
            if (terminal === watched && isActive(now)) line(now, "held", ",\"term\":${numberOf(terminal)}")
        }
    }

    /** [count] things asked of [terminal] were done on its thread, which took [micros]. */
    fun commandsDone(terminal: Any?, count: Int, micros: Long) {
        if (!enabled) return
        locked(this) {
            val now = micros()
            if (isActive(now)) line(now - micros, "cmds", ",\"term\":${numberOf(terminal)},\"count\":$count,\"run_us\":$micros")
        }
    }

    /**
     * When the terminals' thread last began a turn of work that it hasn't finished, by
     * [micros], or 0 while it waits for something to do. For whoever watches for a thread
     * that is stuck ([stall]).
     */
    @Volatile
    var terminalBusySince = 0L

    /** A thread named [thread] didn't get to its work for [blockedMicros]; [stack] is where it was, innermost first. */
    fun stall(thread: String, blockedMicros: Long, stack: List<String>) {
        if (!enabled) return
        locked(this) { line(micros(), "stall", ",\"thread\":${quoted(thread)},\"blocked_us\":$blockedMicros,\"stack\":[${stack.joinToString(",") { quoted(it) }}]") }
    }

    /** Anything else worth a line: [fields] is the rest of a JSON object, starting with a comma. */
    fun note(event: String, fields: String) {
        if (!enabled) return
        locked(this) { line(micros(), event, fields) }
    }

    /**
     * Finds what matters to the timing in a terminal's output without changing it: where a
     * synchronized update begins and ends (`ESC [ ? 2026 h` and `l`), and the marks a test
     * program leaves to say which report it has just drawn for (`ESC ] 7777 ; n BEL`, which
     * a terminal ignores). A sequence cut in two by the end of a chunk is missed.
     */
    class Marks {
        var syncBegins = 0
        var syncEnds = 0
        val drawn = ArrayList<Int>()

        fun clear() { syncBegins = 0; syncEnds = 0; drawn.clear() }

        fun scan(bytes: ByteArray, offset: Int, length: Int) {
            val end = offset + length
            var index = offset
            while (index < end - 5) {
                if (bytes[index] != ESCAPE) { index++; continue }
                val next = bytes[index + 1]
                if (next == BRACKET && index + 7 < end && bytes[index + 2] == QUESTION && bytes[index + 3] == TWO && bytes[index + 4] == ZERO && bytes[index + 5] == TWO && bytes[index + 6] == SIX) {
                    when (bytes[index + 7]) {
                        HIGH -> syncBegins++
                        LOW -> syncEnds++
                    }
                    index += 8
                } else if (next == OSC && index + 7 < end && bytes[index + 2] == SEVEN && bytes[index + 3] == SEVEN && bytes[index + 4] == SEVEN && bytes[index + 5] == SEVEN && bytes[index + 6] == SEMICOLON) {
                    var at = index + 7
                    var number = 0
                    while (at < end && bytes[at] in ZERO..NINE) number = number * 10 + (bytes[at++] - ZERO)
                    drawn += number
                    index = at
                } else index++
            }
        }

        private companion object {
            const val ESCAPE: Byte = 0x1b
            const val BRACKET = '['.code.toByte()
            const val OSC = ']'.code.toByte()
            const val QUESTION = '?'.code.toByte()
            const val ZERO = '0'.code.toByte()
            const val TWO = '2'.code.toByte()
            const val SIX = '6'.code.toByte()
            const val SEVEN = '7'.code.toByte()
            const val NINE = '9'.code.toByte()
            const val SEMICOLON = ';'.code.toByte()
            const val HIGH = 'h'.code.toByte()
            const val LOW = 'l'.code.toByte()
        }
    }

    /**
     * A frame was drawn, on the thread that draws: its text took [layoutMicros] to lay out,
     * for [laidOut] rows that had no layout yet, out of [micros] in all. [offset] is how many
     * pixels from its place it was drawn, which is not zero on the way there with smooth scrolling.
     */
    fun drawn(frame: TerminalFrame, micros: Long, layoutMicros: Long, laidOut: Int, offset: Float = 0f) {
        if (!enabled) return
        locked(this) {
            val index = published.indexOfFirst { it.frame === frame }
            val now = micros()
            if (index < 0) {
                // Not a frame made by scrolling, or one drawn before: drawn again on its way to its place, if it is on one.
                if (offset != 0f) {
                    glides++
                    drawMicros += micros
                    line(now, "glide", ",\"offset_px\":$offset,\"draw_us\":$micros,\"rows_laid_out\":$laidOut")
                }
                return@locked
            }
            var scrollWorst = -1L
            var answerWorst = -1L
            val scrolledIds = ArrayList<Int>()
            val answerIds = ArrayList<Int>()
            val number = published[index].number
            val readAt = published[index].at
            for (at in 0..index) {
                val entry = published[at]
                for (wheel in entry.wheels) (now - wheel.at).let { scrollLatency += it; if (it > scrollWorst) scrollWorst = it; scrolledIds += wheel.id }
                for (wheel in entry.answers) (now - wheel.at).let { answerLatency += it; if (it > answerWorst) answerWorst = it; answerIds += wheel.id }
            }
            framesNotDrawn += index
            repeat(index + 1) { published.removeAt(0) }
            drawMicros += micros
            rowsLaidOut += laidOut
            val gap = if (lastDrawAt == 0L) -1 else now - lastDrawAt
            lastDrawAt = now
            line(
                now, "draw",
                ",\"frame\":$number,\"draw_us\":$micros,\"layout_us\":$layoutMicros,\"rows_laid_out\":$laidOut,\"offset_px\":$offset,\"frames_skipped\":$index,\"since_last_draw_us\":$gap,\"waited_us\":${now - micros - readAt}" +
                    ",\"wheels\":$scrolledIds,\"answers\":$answerIds" +
                    (if (scrollWorst >= 0) ",\"wheel_to_draw_us\":$scrollWorst" else "") +
                    (if (answerWorst >= 0) ",\"wheel_to_answer_drawn_us\":$answerWorst" else ""),
            )
        }
    }

    /** Whether a wheel event came so lately that the window's frames are worth timing: see [tick]. */
    val scrolling: Boolean get() = active

    /** The window drew a frame [micros] after the one before, while [scrolling]. */
    fun tick(micros: Long) {
        if (!enabled) return
        locked(this) {
            ticks += micros
            line(micros(), "tick", ",\"gap_us\":$micros")
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
                if (preciseWheels > 0) append(" ($preciseWheels of them a touchpad's small moves)")
                append(" in ${oneDecimal(seconds * 1000)} ms")
                append("; scrolled $rowsScrolled rows; reports to the program $reports")
                append("; frames read $frames (${describe(buildMicros)} ms, $rowsFresh new rows), of which never drawn $framesNotDrawn")
                append("; drawn ${drawMicros.size - glides} and $glides more on the way to their places (${describe(drawMicros)} ms, $rowsLaidOut rows laid out)")
                if (scrollLatency.isNotEmpty()) append("; wheel to drawn ${describe(scrollLatency)} ms")
                if (answerLatency.isNotEmpty()) append("; wheel to the program's answer drawn ${describe(answerLatency)} ms")
                if (awaitingAnswer.isNotEmpty()) append("; ${awaitingAnswer.size} reports the program drew nothing for")
                if (ticks.isNotEmpty()) append("; window frames ${ticks.size} (${describe(ticks)} ms), late $late")
            }
            line(now, "summary", ",\"text\":${quoted(text)}")
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

    /** How long after a wheel event everything is still written down. */
    const val ACTIVE_MICROS = 1_500_000L

    /** The most lines written, after which there is one saying so and no more. */
    const val MAX_LINES = 2_000_000

    init {
        if (enabled) started()
    }
}

/**
 * What a `start` line says of the clock: the rest of a JSON object, starting with a comma.
 * On the desktop `mono_us`, the machine's monotonic clock now, and `wall_ms`.
 */
internal expect fun telemetryClock(): String

/** Where the lines of [ScrollTelemetry] go, or null when nobody asked for them. */
internal expect fun scrollTelemetrySink(): ((String) -> Unit)?
