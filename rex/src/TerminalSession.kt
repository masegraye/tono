package org.example.kotlintest.rex

import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.example.kotlintest.ghostty.GhosttyMouse
import org.example.kotlintest.ghostty.loadGhostty
import kotlin.concurrent.Volatile
import org.example.kotlintest.ghostty.GhosttyRenderer
import org.example.kotlintest.ghostty.GhosttySelection
import org.example.kotlintest.ghostty.MouseAction
import org.example.kotlintest.ghostty.MouseButton
import org.example.kotlintest.ghostty.MouseInput
import org.example.kotlintest.ghostty.SearchHighlights
import org.example.kotlintest.ghostty.GhosttyTerminal
import org.example.kotlintest.ghostty.TerminalColors
import org.example.kotlintest.ghostty.TerminalFrame

/** A terminal's size in cells, and the pixel size of a cell where the server knows it. */
data class GridSize(val columns: Int, val rows: Int, val cellWidthPx: Int = 0, val cellHeightPx: Int = 0)

/**
 * One live terminal: it follows a block's data stream, keeps a local copy of the terminal
 * in step with it, and publishes what to draw.
 *
 * The server's terminal is the real one. This copy exists so the client can draw it and
 * scroll through its history without asking.
 */
class TerminalSession internal constructor(
    val sessionId: String,
    val blockId: String,
    scope: CoroutineScope,
    colors: TerminalColors?,
    private val log: (String) -> Unit = {},
    /**
     * Called with input for the terminal's program that the terminal itself worked out, such
     * as the report of a mouse click. [claims] is true when it is the kind of input that
     * makes this client the terminal's owner, as typing does.
     */
    private val onInput: (data: String, claims: Boolean) -> Unit = { _, _ -> },
    private val open: suspend () -> RexTerminalStream,
) {
    sealed interface Status {
        data object Connecting : Status
        data object Live : Status
        /**
         * The server keeps refusing the stream for a [reason] that passes, so it is still
         * being asked for, less and less often. Said only once the first few tries have failed.
         */
        data class Retrying(val reason: String) : Status
        /** The stream is over. [blockClosed] means the terminal itself is gone, so don't retry. */
        data class Ended(val reason: String, val blockClosed: Boolean = false) : Status
    }

    private val mutableFrame = MutableStateFlow<TerminalFrame?>(null)
    private val mutableStatus = MutableStateFlow<Status>(Status.Connecting)
    private val mutableGrid = MutableStateFlow<GridSize?>(null)
    private val mutableExitCode = MutableStateFlow<Int?>(null)

    /** What to draw, or null until the snapshot has arrived. */
    val frame: StateFlow<TerminalFrame?> get() = mutableFrame
    val status: StateFlow<Status> get() = mutableStatus
    /** The terminal's size as the server last set it. */
    val grid: StateFlow<GridSize?> get() = mutableGrid
    /** Set once the terminal's program has exited. */
    val exitCode: StateFlow<Int?> get() = mutableExitCode

    private val mutableLink = MutableStateFlow<TerminalLink?>(null)
    private val mutableOpened = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** The link under the pointer while it is held over one to follow it (see [pointAt]), to mark and to say where it leads. */
    val link: StateFlow<TerminalLink?> get() = mutableLink

    /**
     * The addresses of links as they are clicked with Control held, for whoever shows this
     * terminal to open. Each has been through [openableLink]. One clicked while nobody is
     * listening is not kept.
     */
    val opened: SharedFlow<String> get() = mutableOpened

    /** What the pointer did that is for whoever shows the terminal to act on. */
    enum class Gesture {
        /** The button came up after selecting. Whether anything is selected is for [selectedText] to say. */
        Selected,

        /** A middle click that asks for a paste: see [mouse]. */
        Paste,

        /** A press of the right button that asks for the pane's menu: see [mouse]. */
        Menu,
    }

    private val mutableGestures = MutableSharedFlow<Gesture>(extraBufferCapacity = 4)

    /** The [Gesture]s as they are made. Nothing is kept for a collector that arrives later. */
    val gestures: SharedFlow<Gesture> get() = mutableGestures

    private val mutableSearch = MutableStateFlow<TerminalSearch?>(null)

    /** The terminal's search while it is open: what is looked for and what was found. Null while it is closed. */
    val search: StateFlow<TerminalSearch?> get() = mutableSearch

    /** How many times the search was asked for, and the text last looked for, which it starts with again. Both are the caller's thread's. */
    private var searchesOpened = 0
    private var lastSearched = ""

    /** Whether the terminal's program currently wants pasted text bracketed. */
    @Volatile
    var bracketedPaste: Boolean = false
        private set

    private sealed interface Command {
        class SetColors(val colors: TerminalColors) : Command
        class Scroll(val rows: Int) : Command
        class Mouse(val input: MouseInput, val scrollRows: Int, val timeMillis: Long, val middlePastes: Boolean, val wheel: Int) : Command
        class Overscan(val rows: Int) : Command
        class PointAt(val at: MouseInput?) : Command
        data object ScrollToBottom : Command
        data object SelectAll : Command
        data object ClearSelection : Command
        class ReadSelection(val text: CompletableDeferred<String>) : Command
        /** Look for this from now on, or for nothing when it is null. */
        class Search(val query: String?) : Command
        class SearchStep(val forward: Boolean) : Command
        class JumpToPrompt(val older: Boolean) : Command
    }

    /** Where the pointer is held to follow a link, or null. */
    private var pointingAt: MouseInput? = null

    /** When to scroll the next row, while a selection is dragged past the top or bottom edge. */
    private var scrollNextMillis = 0L

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var colors: TerminalColors? = colors
    private val job = scope.launch(terminalDispatcher) { run() }

    /** Sets the colors the terminal is drawn with where its program hasn't chosen one. */
    fun setColors(colors: TerminalColors) { commands.trySend(Command.SetColors(colors)) }

    /** Scrolls through the history: negative is up, towards older output. */
    fun scroll(rows: Int) { commands.trySend(Command.Scroll(rows)) }

    fun scrollToBottom() { commands.trySend(Command.ScrollToBottom) }

    /**
     * Moves the view to the shell prompt above its first row ([older]), or to the next one
     * below it, putting that prompt on the first row; past the newest prompt is the live
     * screen. Nothing is typed. A shell marks its prompts (OSC 133) when its integration
     * is on; without marks, and at the oldest prompt, nothing moves.
     */
    fun jumpToPrompt(older: Boolean) { commands.trySend(Command.JumpToPrompt(older)) }

    /**
     * Something was typed or pasted into the terminal: the view returns to the live screen,
     * where it will show, and what was selected is let go, as Ghostty does.
     */
    fun typedInto() {
        commands.trySend(Command.ScrollToBottom)
        commands.trySend(Command.ClearSelection)
    }

    /**
     * Tells the terminal's program what the pointer did, if it asked to know. Over a program
     * that hasn't asked, the left button selects text, and a turn of the wheel scrolls
     * through the history, by [scrollRows]: negative is up, towards older output. With Shift
     * held, the left button selects whatever the program asked.
     *
     * The position may be outside the terminal, which matters while selecting: dragging
     * past an edge selects up to it, and past the top or bottom scrolls.
     *
     * A selection the pointer finishes is told of in [gestures]. With [middlePastes], so is
     * a middle click on a program that hasn't asked for the mouse, or with Shift held on one
     * that has, and the program hears nothing of that click.
     *
     * The right button asks for the pane's menu, which is also told of in [gestures], and
     * the program hears nothing of that click either. With Shift held on a program that
     * asked for the mouse, the click is the program's and no menu is asked for.
     *
     * [wheel] is the number [ScrollTelemetry] gave a turn of the wheel, while it is measuring.
     */
    fun mouse(input: MouseInput, scrollRows: Int = 0, middlePastes: Boolean = false, wheel: Int = 0) { commands.trySend(Command.Mouse(input, scrollRows, nowMillis(), middlePastes, wheel)) }

    /**
     * Asks for [rows] rows past the top and the bottom of the view in every [frame] from now
     * on, where there are any, for whoever draws the terminal part of a row away from where
     * it rests. None is the way it starts.
     */
    fun overscan(rows: Int) { commands.trySend(Command.Overscan(rows)) }

    /** Adds a line to the app's debug log, under this terminal's name. */
    fun note(message: String) = log(message)

    /**
     * Says where the pointer is while Control is held, which is how a link is followed: if
     * there is a link there, [link] becomes it, and goes on being whatever is there as the
     * output changes. Null when the pointer has left or Control is up, and [link] with it.
     * Only the position and the cell size of [at] are read.
     */
    fun pointAt(at: MouseInput?) { commands.trySend(Command.PointAt(at)) }

    /** Selects everything in the terminal, its scrollback included. */
    fun selectAll() { commands.trySend(Command.SelectAll) }

    fun clearSelection() { commands.trySend(Command.ClearSelection) }

    /**
     * The selected text, as it is copied: a line the terminal wrapped is one line, and the
     * blanks after the end of a line are left out. Empty when nothing is selected, or the
     * terminal isn't there to ask.
     */
    suspend fun selectedText(): String {
        val text = CompletableDeferred<String>()
        if (!commands.trySend(Command.ReadSelection(text)).isSuccess) return ""
        return withTimeoutOrNull(SELECTION_TIMEOUT_MILLIS) { text.await() } ?: ""
    }

    /**
     * Opens the terminal's search, starting with the text last looked for here, or says
     * again that its field should have the keyboard if it is already open ([TerminalSearch.opened]).
     */
    fun openSearch() {
        val opened = ++searchesOpened
        val wasOpen = mutableSearch.value != null
        mutableSearch.update { (it ?: TerminalSearch(lastSearched, complete = lastSearched.isEmpty())).copy(opened = opened) }
        if (!wasOpen) commands.trySend(Command.Search(lastSearched))
    }

    /**
     * Looks for [query] in the screen and the history, if the search is open. The newest
     * match is selected and scrolled into view; output that arrives later is searched too.
     */
    fun search(query: String) {
        if (mutableSearch.value == null) return
        lastSearched = query
        mutableSearch.update { it?.copy(query = query, total = 0, selected = null, complete = query.isEmpty(), highlights = SearchHighlights.None) }
        commands.trySend(Command.Search(query))
    }

    /**
     * Selects the next match, which is the next older one, or the previous, going round at
     * either end, and scrolls it into view. With the search closed, as Rex Rune does: it
     * comes back on the text last looked for, at its newest match, and the keyboard stays
     * where it is; if nothing was ever looked for, this is [openSearch].
     */
    fun searchStep(forward: Boolean) {
        when {
            mutableSearch.value != null -> commands.trySend(Command.SearchStep(forward))
            lastSearched.isEmpty() -> openSearch()
            else -> {
                mutableSearch.value = TerminalSearch(lastSearched, opened = searchesOpened, complete = false)
                commands.trySend(Command.Search(lastSearched))
            }
        }
    }

    /** Closes the search and takes its marks off the screen. The view stays where it is. */
    fun closeSearch() {
        if (mutableSearch.value == null) return
        mutableSearch.value = null
        commands.trySend(Command.Search(null))
    }

    fun close() = job.cancel()

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun run() {
        var terminal: GhosttyTerminal? = null
        var selection: GhosttySelection? = null
        var renderer: GhosttyRenderer? = null
        var mouse: GhosttyMouse? = null
        var stream: RexTerminalStream? = null
        val searching = TerminalSearching(mutableSearch)
        try {
            loadGhostty() // in a browser the terminal library has to be fetched before its first use
            stream = openPatiently()
            val snapshot = receiveSnapshot(stream)
            val restored = GhosttyTerminal.restore(snapshot)
            terminal = restored.terminal
            renderer = GhosttyRenderer()
            mouse = GhosttyMouse()
            selection = GhosttySelection()
            val pointer = TerminalPointer(
                mouse, selection,
                // The frame is the one drawn after the terminal last changed, so it is what the terminal holds now.
                linkAt = { at, column, row -> mutableFrame.value?.let { linkAt(DrawnScreen(it, at), column, row) } },
                open = { url ->
                    log("a link was clicked: $url")
                    mutableOpened.tryEmit(url)
                },
                selected = { mutableGestures.tryEmit(Gesture.Selected) },
                paste = { mutableGestures.tryEmit(Gesture.Paste) },
                menu = { mutableGestures.tryEmit(Gesture.Menu) },
            )
            colors?.let(terminal::setColors)
            mutableGrid.value = GridSize(terminal.columns, terminal.rows)
            log("restored from a ${restored.snapshotBytes}-byte snapshot at ${terminal.columns}×${terminal.rows}")

            val live = terminal
            val scanner = RexStreamScanner(stream.nonce)
            // Measuring (ScrollTelemetry) is decided once, when the terminal opens, like the noting of its stream.
            val measuring = ScrollTelemetry.enabled
            val marks = ScrollTelemetry.Marks()
            val turn = ScrollTelemetry.Turn()
            fun apply(bytes: ByteArray, offset: Int) =
                scanner.feed(bytes, offset, onOutput = { data, from, length ->
                    if (measuring) marks.scan(data, from, length)
                    live.write(data, from, length)
                }, onMessage = { handleMessage(live, it) })
            apply(snapshot, restored.snapshotBytes)
            mutableFrame.value = renderer.render(terminal).scrolled(pointer.scrolled)
            bracketedPaste = terminal.bracketedPaste
            mutableStatus.value = Status.Live

            var sleptAt = 0L
            var askedSleep = 0L
            while (true) {
                var output = false
                if (measuring) {
                    turn.output = false; turn.commands = 0; turn.timer = false
                    ScrollTelemetry.terminalBusySince = 0
                }
                val more = select {
                    stream.frames.onReceiveCatching { result ->
                        val frame = result.getOrNull() ?: return@onReceiveCatching false
                        output = true
                        val began = if (measuring) ScrollTelemetry.micros().also { ScrollTelemetry.terminalBusySince = it } else 0L
                        var chunks = 0
                        var fed = 0
                        if (frame is Frame.Binary) {
                            val bytes = frame.readBytes()
                            apply(bytes, 0)
                            chunks++
                            fed += bytes.size
                        }
                        // Take whatever else has already arrived, so a burst of output is drawn once.
                        var budget = DRAIN_BYTES
                        while (budget > 0) {
                            val next = stream.frames.tryReceive().getOrNull() as? Frame.Binary ?: break
                            val bytes = next.readBytes()
                            apply(bytes, 0)
                            budget -= bytes.size
                            chunks++
                            fed += bytes.size
                        }
                        if (measuring) {
                            turn.wokeAt = began
                            turn.output = true
                            ScrollTelemetry.fed(this@TerminalSession, stream, chunks, fed, ScrollTelemetry.micros() - began, marks.syncBegins, marks.syncEnds, marks.drawn)
                            marks.clear()
                        }
                        true
                    }
                    commands.onReceive { command ->
                        val began = if (measuring) ScrollTelemetry.micros().also { ScrollTelemetry.terminalBusySince = it } else 0L
                        var done = 1
                        handleCommand(live, renderer, pointer, selection, searching, command)
                        // And whatever else was asked for meanwhile, so that it is all drawn once. A wheel
                        // turned fast asks many times in the time one frame takes: taken one to a frame,
                        // thirty turns went on scrolling for a fifth of a second after the wheel had stopped.
                        var budget = DRAIN_COMMANDS
                        while (budget-- > 0) {
                            val next = commands.tryReceive().getOrNull() ?: break
                            handleCommand(live, renderer, pointer, selection, searching, next)
                            done++
                        }
                        if (measuring) {
                            turn.wokeAt = began
                            turn.commands = done
                            ScrollTelemetry.commandsDone(this@TerminalSession, done, ScrollTelemetry.micros() - began)
                        }
                        true
                    }
                    // A long history is searched a little at a time, between frames.
                    if (searching.behind) onTimeout(0) { true }
                    // Held past the top or bottom edge while selecting: a row at a time, until it isn't.
                    if (pointer.scrollingAt != null) {
                        onTimeout((scrollNextMillis - nowMillis()).coerceAtLeast(0)) {
                            scrollNextMillis = nowMillis() + AUTOSCROLL_MILLIS
                            pointer.scroll(live)
                            true
                        }
                    }
                }
                if (!more) break
                // Whatever just happened may have changed what there is to find, or where it is in the view.
                searching.refresh()
                if (measuring) {
                    val before = ScrollTelemetry.micros()
                    if (!turn.output && turn.commands == 0) {
                        turn.timer = true
                        turn.wokeAt = before
                    }
                    turn.sleptMicros = if (sleptAt == 0L) 0 else turn.wokeAt - sleptAt
                    turn.askedSleepMicros = askedSleep
                    turn.sync = terminal.mode(MODE_SYNCHRONIZED_UPDATE)
                    val next = renderer.render(terminal).scrolled(pointer.scrolled)
                    ScrollTelemetry.frame(this, next, ScrollTelemetry.micros() - before, output, turn)
                    mutableFrame.value = next
                } else {
                    mutableFrame.value = renderer.render(terminal).scrolled(pointer.scrolled)
                }
                // Asked again every time, since what is under a pointer that hasn't moved may have changed.
                mutableLink.value = pointingAt?.let { pointer.linkUnder(live, it) }
                bracketedPaste = terminal.bracketedPaste
                if (measuring) {
                    ScrollTelemetry.terminalBusySince = 0
                    sleptAt = ScrollTelemetry.micros()
                    askedSleep = FRAME_INTERVAL_MILLIS * 1000
                }
                delay(FRAME_INTERVAL_MILLIS)
            }
            val reason = stream.closeReason()
            val ended = Status.Ended(
                reason = reason?.message?.ifEmpty { null } ?: "The terminal stream ended",
                blockClosed = reason?.code == CLOSE_BLOCK_CLOSED,
            )
            log("stream ended: ${ended.reason}")
            mutableStatus.value = ended
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log("stream failed: ${failure.message ?: failure}")
            mutableStatus.value = Status.Ended(failure.message ?: failure.toString())
        } finally {
            mutableLink.value = null
            withContext(NonCancellable) { stream?.close() }
            renderer?.close()
            mouse?.close()
            searching.close() // before the terminal, like the selection
            selection?.close() // before the terminal, which it lets go of
            terminal?.close()
        }
    }

    /**
     * Opens the stream, and asks again while the server refuses it for a reason that passes
     * ([RexStreamRefused.passes]): for as long as this terminal is wanted, at longer and
     * longer intervals. Each try takes a new ticket. The first few are made quietly.
     */
    private suspend fun openPatiently(): RexTerminalStream {
        var tries = 0
        while (true) {
            try {
                return open().also {
                    if (tries > 0) log("stream opened on try ${tries + 1}")
                    mutableStatus.value = Status.Connecting // its snapshot is still to come
                }
            } catch (refused: RexStreamRefused) {
                if (!refused.passes) throw refused
                val wait = RETRY_MILLIS[minOf(tries, RETRY_MILLIS.lastIndex)]
                // Said once, and again when it stops being quiet: it may go on for as long as the tab is shown.
                if (tries == 0 || tries == QUIET_TRIES) log("stream refused: ${refused.message}. Trying again")
                if (++tries > QUIET_TRIES) mutableStatus.value = Status.Retrying(refused.message.orEmpty())
                delay(wait)
            }
        }
    }

    /** Collects stream bytes until the whole snapshot has arrived. More may follow it in the result. */
    private suspend fun receiveSnapshot(stream: RexTerminalStream): ByteArray {
        val received = ByteCollector()
        var chunks = 0
        while (true) {
            val frame = withTimeoutOrNull(SNAPSHOT_TIMEOUT_MILLIS) { stream.frames.receiveCatching().getOrNull() }
                ?: error("The terminal's snapshot didn't arrive")
            if (frame !is Frame.Binary) continue
            ScrollTelemetry.taken(stream)
            received.write(frame.readBytes())
            // Walking the records from the start is too slow to do for every chunk of a large
            // snapshot, so only look every so often and whenever the stream pauses.
            if (++chunks % 64 == 0 || received.size < 1_000_000 || stream.idle) {
                val bytes = received.toByteArray()
                if (snapshotLength(bytes, bytes.size) != null) return bytes
            }
        }
    }

    private fun handleMessage(terminal: GhosttyTerminal, message: JsonObject) {
        when (message.string("type")) {
            "resize" -> {
                val columns = message["cols"]?.jsonPrimitive?.int ?: return
                val rows = message["rows"]?.jsonPrimitive?.int ?: return
                val cellWidth = message["cell_width_px"]?.jsonPrimitive?.int ?: 0
                val cellHeight = message["cell_height_px"]?.jsonPrimitive?.int ?: 0
                terminal.resize(columns, rows, cellWidth, cellHeight)
                mutableGrid.value = GridSize(columns, rows, cellWidth, cellHeight)
                log("is now $columns×$rows, sized by ${message.string("owner")?.let { "client …" + it.takeLast(6) } ?: "no client"}")
            }
            "child_exited" -> {
                val code = message["exit_code"]?.jsonPrimitive?.int ?: 0
                mutableExitCode.value = code
                log("its program exited with $code")
            }
            // "theme" carries the block owner's colors. This client draws with its own.
        }
    }

    private fun handleCommand(terminal: GhosttyTerminal, renderer: GhosttyRenderer, pointer: TerminalPointer, selection: GhosttySelection, searching: TerminalSearching, command: Command) {
        when (command) {
            is Command.SetColors -> {
                colors = command.colors
                terminal.setColors(command.colors)
                renderer.invalidate()
            }
            is Command.Scroll -> terminal.scrollViewport(command.rows)
            is Command.Mouse -> {
                val input = command.input
                val wasScrolling = pointer.scrollingAt != null
                val before = pointer.scrolled
                val report = pointer.handle(terminal, input, command.scrollRows, command.timeMillis, command.middlePastes)
                val moved = (pointer.scrolled - before).toInt()
                if (!wasScrolling) scrollNextMillis = nowMillis() + AUTOSCROLL_MILLIS
                ScrollTelemetry.wheelHandled(command.wheel, moved, report.size)
                // A click claims the terminal as a key press does; passing over it doesn't.
                if (report.isNotEmpty()) onInput(report.latin1(), input.action == MouseAction.Press && input.button != MouseButton.WheelUp && input.button != MouseButton.WheelDown)
            }
            is Command.PointAt -> pointingAt = command.at
            is Command.Overscan -> renderer.overscan = command.rows
            Command.ScrollToBottom -> terminal.scrollToBottom()
            Command.SelectAll -> selection.selectAll(terminal)
            Command.ClearSelection -> selection.clear(terminal)
            is Command.ReadSelection -> command.text.complete(runCatching { selection.text(terminal) }.getOrDefault(""))
            is Command.Search -> searching.find(terminal, command.query)
            is Command.SearchStep -> searching.step(command.forward)
            is Command.JumpToPrompt -> terminal.scrollByPrompts(if (command.older) -1 else 1)
        }
    }

    private companion object {
        const val SNAPSHOT_TIMEOUT_MILLIS = 15_000L
        /** While it is on, a program is in the middle of drawing and says not to show what it has drawn so far. */
        const val MODE_SYNCHRONIZED_UPDATE = 2026
        /** A short pause after each frame lets output that arrives in pieces be drawn together. */
        const val FRAME_INTERVAL_MILLIS = 4L
        const val DRAIN_BYTES = 4 shl 20
        /** How many things asked of the terminal are done before a frame is drawn, when that many are waiting. */
        const val DRAIN_COMMANDS = 256
        const val CLOSE_BLOCK_CLOSED: Short = 4000
        /** How long between rows while a selection is dragged past the top or bottom edge. */
        const val AUTOSCROLL_MILLIS = 30L
        const val SELECTION_TIMEOUT_MILLIS = 1_000L
        /** How long to wait before asking again for a stream the server refused: the last one from then on. */
        val RETRY_MILLIS = longArrayOf(50, 100, 200, 400, 800, 1_600, 3_000, 5_000)
        /** How many refusals in a row go by, about a second and a half, before the pane says so. */
        const val QUIET_TRIES = 5
    }
}
