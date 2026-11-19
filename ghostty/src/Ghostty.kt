package org.example.kotlintest.ghostty

/*
 * What each platform provides: a terminal, something that reads what it shows, and the two
 * encoders (mouse, paste) that need to know the terminal's modes. On the JVM these call
 * libghostty-vt as a shared library; in a browser, as a WebAssembly module.
 */

/**
 * Loads the terminal library. Nothing else here may be used before this returns. On the JVM
 * the library loads itself on first use and this only does it early; in a browser it has to
 * be fetched, which can't be done on the spot.
 */
expect suspend fun loadGhostty()

/**
 * A terminal emulated by libghostty-vt, Ghostty's terminal core. It turns a program's
 * output (text and escape sequences) into a grid of styled cells with scrollback.
 *
 * Not thread-safe: use a terminal, and any [GhosttyRenderer] reading it, from one thread
 * at a time.
 */
expect class GhosttyTerminal : AutoCloseable {
    /** A terminal restored from a snapshot, and how many bytes the snapshot occupied. */
    class Restored {
        val terminal: GhosttyTerminal
        val snapshotBytes: Int
    }

    companion object {
        /** An empty terminal. */
        fun create(columns: Int, rows: Int): GhosttyTerminal

        /**
         * Restores a terminal from the Ghostty snapshot at the start of [bytes]: its screen,
         * scrollback, modes, and any half-received escape sequence. Bytes after the snapshot
         * are left alone; [Restored.snapshotBytes] says where they start.
         */
        fun restore(bytes: ByteArray, length: Int = bytes.size): Restored
    }

    val columns: Int
    val rows: Int

    /** Feeds program output to the terminal. */
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset)

    /** Changes the grid's size, reflowing wrapped lines. Pixel sizes are 0 when unknown. */
    fun resize(columns: Int, rows: Int, cellWidthPx: Int = 0, cellHeightPx: Int = 0)

    /** Sets the colors used where a program hasn't chosen one. */
    fun setColors(colors: TerminalColors)

    /** Scrolls the view through the scrollback: negative is up, towards older output. */
    fun scrollViewport(rows: Int)

    /** Returns the view to the live screen. */
    fun scrollToBottom()

    /**
     * Moves the view by [count] shell prompts: negative is up, to the prompts of older
     * commands, and the prompt gone to becomes the view's first row, or as near it as the
     * end of the history allows. A shell says where its prompts are (OSC 133); in a
     * terminal whose shell hasn't, and when there is no prompt that way, nothing moves.
     */
    fun scrollByPrompts(count: Int)

    /**
     * Whether the terminal's program asked for pasted text to be bracketed (mode 2004),
     * which lets it tell a paste from typing.
     */
    val bracketedPaste: Boolean

    /**
     * Where the cell at [column] in the view's row [row] leads, if its program made it a
     * link (OSC 8): the address as the program gave it, which is not to be trusted. Null
     * when the cell is no link, or there is no such cell.
     */
    fun hyperlinkAt(column: Int, row: Int): String?

    /** Whether the view's row [row] ran out of room and goes on in the row below it. */
    fun rowWraps(row: Int): Boolean

    override fun close()
}

/**
 * Reads what a [GhosttyTerminal] currently shows, as a [TerminalFrame].
 *
 * libghostty-vt tracks which rows changed since the last read, so [render] only rebuilds
 * those and reuses the [TerminalRow] objects of the rest. Use one renderer per terminal,
 * on the terminal's thread.
 */
expect class GhosttyRenderer() : AutoCloseable {
    /** Forgets the cached rows, so the next [render] rebuilds every one. Call it after changing colors. */
    fun invalidate()

    fun render(terminal: GhosttyTerminal): TerminalFrame

    override fun close()
}

/**
 * Turns pointer events into the bytes a terminal's program asked for. A program says whether
 * it wants the mouse at all, which events, and in what form, by setting terminal modes;
 * Neovim, htop and tmux all do. libghostty-vt keeps those modes, so it does the encoding.
 *
 * One of these serves one terminal: it remembers the cell last moved into, so that moving
 * within a cell says nothing twice. Not thread-safe, like the terminal it reads.
 */
expect class GhosttyMouse() : AutoCloseable {
    /**
     * What to send the program in [terminal] for [input]. Empty when it wants nothing: it
     * isn't tracking the mouse, or not this kind of event, or the pointer is still in the
     * cell last reported.
     */
    fun encode(terminal: GhosttyTerminal, input: MouseInput): ByteArray

    override fun close()
}

/**
 * Selecting text with the pointer. libghostty-vt knows what a press, a drag and a second or
 * third press select (cells, a word, a line), and each function here hands it one such
 * event and makes the answer the terminal's selection. From then on the terminal keeps the
 * selection itself: it stays on the same text as output scrolls it or the terminal is
 * resized, and a [GhosttyRenderer] reports which cells are in it.
 *
 * Positions are [MouseInput]s, of which only the position and cell size are read. One
 * outside the terminal counts as the nearest cell at its edge. One of these serves one
 * terminal, and is closed before it. Not thread-safe, like the terminal.
 */
expect class GhosttySelection() : AutoCloseable {
    /**
     * The left button went down. A press on its own selects nothing and drops what was
     * selected; a second soon after it, in the same place, selects the word there, and a
     * third the line. [timeMillis] is when, on any clock that only goes forward.
     */
    fun press(terminal: GhosttyTerminal, at: MouseInput, timeMillis: Long)

    /**
     * The pointer moved with the button held. Returns whether it is now at or past the top
     * or bottom edge, where [autoscroll] should be called every so often until it says to stop.
     */
    fun drag(terminal: GhosttyTerminal, at: MouseInput): Boolean

    fun release(terminal: GhosttyTerminal, at: MouseInput)

    /**
     * Scrolls a row towards the edge the pointer is held at, which is at [at], and extends
     * the selection to there. Returns whether to go on calling it.
     */
    fun autoscroll(terminal: GhosttyTerminal, at: MouseInput): Boolean

    /** Selects everything in the terminal, its scrollback included. */
    fun selectAll(terminal: GhosttyTerminal)

    fun clear(terminal: GhosttyTerminal)

    /**
     * The selected text, as it is copied: a line the terminal wrapped is one line, and the
     * blanks after the end of a line are left out. Empty when nothing is selected.
     */
    fun text(terminal: GhosttyTerminal): String

    override fun close()
}

/**
 * Looking for text in one terminal: its screen and everything that scrolled off it.
 * libghostty-vt does the looking, and keeps what it found in step with the terminal as
 * output arrives, the terminal is resized, or a full-screen program comes and goes.
 *
 * A letter of the English alphabet matches in either case; everything else has to be the
 * same. Matches are counted from the newest, at the bottom of the screen, back through the
 * history, and "next" steps that way, towards older output, as it does in Ghostty.
 *
 * The search only learns what the terminal holds when [advance] is called, so call it
 * after anything that changes the terminal or scrolls its view, and before reading
 * [total], [selected] or [highlights]. Closed before its terminal. Not thread-safe, like
 * the terminal.
 */
expect class GhosttySearch(terminal: GhosttyTerminal) : AutoCloseable {
    /** What to look for. Changing it starts over, with no match selected; empty looks for nothing. */
    fun setNeedle(text: String)

    /**
     * Takes in what the terminal holds now and looks through some more of it, a bounded
     * amount, so that a long history doesn't hold everything else up. Returns whether it
     * has been through all of it; call it again when not.
     */
    fun advance(): Boolean

    /**
     * Selects the match before the selected one, in older output, or the newest when none
     * is selected, going round from the oldest to the newest. The view scrolls to show it
     * if it doesn't already. False when there are no matches.
     */
    fun selectNext(): Boolean

    /** As [selectNext], the other way: towards newer output, and round from the newest to the oldest. */
    fun selectPrevious(): Boolean

    /** How many matches have been found so far. */
    val total: Int

    /** Which match is selected, counting from 0 for the newest, or null when none is. */
    val selected: Int?

    /** Where the matches are in the terminal's view, as of the last [advance]. */
    fun highlights(): SearchHighlights

    override fun close()
}

/**
 * The matches of a search that are on screen, as cells to mark: those of the [selected]
 * match, and those of all the [others]. A match that a row's end broke in two has cells
 * in both rows.
 */
data class SearchHighlights(val others: List<SelectedCells>, val selected: List<SelectedCells>) {
    companion object {
        val None = SearchHighlights(emptyList(), emptyList())
    }
}

/**
 * Makes pasted text safe to send to a terminal: characters that could end the paste early
 * are replaced with spaces, and the text is wrapped in bracketed-paste markers when
 * [bracketed] is true, or has its newlines turned into carriage returns when it isn't.
 */
expect fun encodePaste(text: String, bracketed: Boolean): ByteArray
