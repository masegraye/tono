package org.example.kotlintest.rex

import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
import org.example.kotlintest.ghostty.MouseAction
import org.example.kotlintest.ghostty.MouseButton
import org.example.kotlintest.ghostty.MouseInput
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

    /** Whether the terminal's program currently wants pasted text bracketed. */
    @Volatile
    var bracketedPaste: Boolean = false
        private set

    private sealed interface Command {
        class SetColors(val colors: TerminalColors) : Command
        class Scroll(val rows: Int) : Command
        class Mouse(val input: MouseInput, val scrollRows: Int) : Command
        data object ScrollToBottom : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var colors: TerminalColors? = colors
    private val job = scope.launch(terminalDispatcher) { run() }

    /** Sets the colors the terminal is drawn with where its program hasn't chosen one. */
    fun setColors(colors: TerminalColors) { commands.trySend(Command.SetColors(colors)) }

    /** Scrolls through the history: negative is up, towards older output. */
    fun scroll(rows: Int) { commands.trySend(Command.Scroll(rows)) }

    fun scrollToBottom() { commands.trySend(Command.ScrollToBottom) }

    /**
     * Tells the terminal's program what the pointer did, if it asked to know. A turn of the
     * wheel over a program that hasn't asked scrolls through the history instead, by
     * [scrollRows]: negative is up, towards older output.
     */
    fun mouse(input: MouseInput, scrollRows: Int = 0) { commands.trySend(Command.Mouse(input, scrollRows)) }

    fun close() = job.cancel()

    private suspend fun run() {
        var terminal: GhosttyTerminal? = null
        var renderer: GhosttyRenderer? = null
        var mouse: GhosttyMouse? = null
        var stream: RexTerminalStream? = null
        try {
            loadGhostty() // in a browser the terminal library has to be fetched before its first use
            stream = open()
            val snapshot = receiveSnapshot(stream)
            val restored = GhosttyTerminal.restore(snapshot)
            terminal = restored.terminal
            renderer = GhosttyRenderer()
            mouse = GhosttyMouse()
            colors?.let(terminal::setColors)
            mutableGrid.value = GridSize(terminal.columns, terminal.rows)
            log("restored from a ${restored.snapshotBytes}-byte snapshot at ${terminal.columns}×${terminal.rows}")

            val live = terminal
            val scanner = RexStreamScanner(stream.nonce)
            fun apply(bytes: ByteArray, offset: Int) =
                scanner.feed(bytes, offset, onOutput = live::write, onMessage = { handleMessage(live, it) })
            apply(snapshot, restored.snapshotBytes)
            mutableFrame.value = renderer.render(terminal)
            bracketedPaste = terminal.bracketedPaste
            mutableStatus.value = Status.Live

            while (true) {
                val more = select {
                    stream.frames.onReceiveCatching { result ->
                        val frame = result.getOrNull() ?: return@onReceiveCatching false
                        if (frame is Frame.Binary) apply(frame.readBytes(), 0)
                        // Take whatever else has already arrived, so a burst of output is drawn once.
                        var budget = DRAIN_BYTES
                        while (budget > 0) {
                            val next = stream.frames.tryReceive().getOrNull() as? Frame.Binary ?: break
                            val bytes = next.readBytes()
                            apply(bytes, 0)
                            budget -= bytes.size
                        }
                        true
                    }
                    commands.onReceive { command ->
                        handleCommand(live, renderer, mouse, command)
                        true
                    }
                }
                if (!more) break
                mutableFrame.value = renderer.render(terminal)
                bracketedPaste = terminal.bracketedPaste
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
            withContext(NonCancellable) { stream?.close() }
            renderer?.close()
            mouse?.close()
            terminal?.close()
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

    private fun handleCommand(terminal: GhosttyTerminal, renderer: GhosttyRenderer, mouse: GhosttyMouse, command: Command) {
        when (command) {
            is Command.SetColors -> {
                colors = command.colors
                terminal.setColors(command.colors)
                renderer.invalidate()
            }
            is Command.Scroll -> terminal.scrollViewport(command.rows)
            is Command.Mouse -> {
                val input = command.input
                val report = mouse.encode(terminal, input)
                when {
                    // A click claims the terminal as a key press does; passing over it doesn't.
                    report.isNotEmpty() -> onInput(report.latin1(), input.action == MouseAction.Press && input.button != MouseButton.WheelUp && input.button != MouseButton.WheelDown)
                    command.scrollRows != 0 -> terminal.scrollViewport(command.scrollRows)
                }
            }
            Command.ScrollToBottom -> terminal.scrollToBottom()
        }
    }

    private companion object {
        const val SNAPSHOT_TIMEOUT_MILLIS = 15_000L
        /** A short pause after each frame lets output that arrives in pieces be drawn together. */
        const val FRAME_INTERVAL_MILLIS = 4L
        const val DRAIN_BYTES = 4 shl 20
        const val CLOSE_BLOCK_CLOSED: Short = 4000
    }
}
