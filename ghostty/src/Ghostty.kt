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
     * Whether the terminal's program asked for pasted text to be bracketed (mode 2004),
     * which lets it tell a paste from typing.
     */
    val bracketedPaste: Boolean

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
 * Makes pasted text safe to send to a terminal: characters that could end the paste early
 * are replaced with spaces, and the text is wrapped in bracketed-paste markers when
 * [bracketed] is true, or has its newlines turned into carriage returns when it isn't.
 */
expect fun encodePaste(text: String, bracketed: Boolean): ByteArray
