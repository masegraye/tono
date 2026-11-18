package org.example.kotlintest.ghostty

/**
 * Everything needed to draw one terminal at one moment. Immutable, so it can be handed
 * from the thread that owns the terminal to the one that draws.
 *
 * Colors are `0xRRGGBB`.
 */
class TerminalFrame(
    val columns: Int,
    /** The visible rows, top to bottom. */
    val rows: List<TerminalRow>,
    val background: Int,
    val foreground: Int,
    /** Null when the cursor is hidden or scrolled out of view. */
    val cursor: TerminalCursor?,
    /** The images on screen, in the order to draw them within each layer. */
    val images: List<TerminalImagePlacement> = emptyList(),
    /** The size of a cell in the terminal's own pixels, which image sizes are given in. Zero if it was never told one. */
    val cellWidthPx: Int = 0,
    val cellHeightPx: Int = 0,
)

/**
 * One row, as the runs of cells that have something to draw. A row object is reused from
 * frame to frame while its content doesn't change, so a renderer can cache work per row.
 */
class TerminalRow(val runs: List<TerminalRun>) {
    /** The row's text, for tests and debugging. Cells with nothing in them read as spaces. */
    fun text(): String = buildString {
        for (run in runs) {
            while (length < run.column) append(' ')
            append(run.text)
        }
    }
}

/**
 * Cells next to each other that are drawn the same way.
 *
 * A run holds either plain narrow characters, which a monospace font lays out one per
 * cell, or exactly one character that needs placing by hand (wide, combined, or from a
 * fallback font).
 */
class TerminalRun(
    /** The column of the run's first cell. */
    val column: Int,
    /** How many cells the run covers. */
    val cells: Int,
    val text: String,
    val foreground: Int,
    /** Null to leave the terminal's background showing. */
    val background: Int?,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
)

class TerminalCursor(
    val column: Int,
    val row: Int,
    val shape: Shape,
    val blinking: Boolean,
    /** Null to draw the cursor in the foreground color. */
    val color: Int?,
) {
    enum class Shape { Block, HollowBlock, Bar, Underline }
}

/** The colors a terminal draws with when a program hasn't asked for a specific one. */
class TerminalColors(
    val foreground: Int,
    val background: Int,
    /** Null to draw the cursor in the foreground color. */
    val cursor: Int? = null,
    /** The 16 ANSI colors: black, red, green, yellow, blue, magenta, cyan, white, then the bright ones. */
    val ansi: List<Int> = emptyList(),
) {
    init {
        require(ansi.isEmpty() || ansi.size == 16) { "ansi must hold all 16 colors or none, got ${ansi.size}" }
    }
}
