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
    /** The selected cells that are on screen, top to bottom. In a row of [above] the row is negative, and in one of [below] it is past the last of [rows]. */
    val selection: List<SelectedCells> = emptyList(),
    /**
     * The rows just above the view, top to bottom, the last being the one next to the
     * view, and those just [below] it. Only where the renderer was asked for them
     * ([GhosttyRenderer.overscan]) and there are any: there is nothing below the live
     * screen. They are what shows at an edge while the picture is drawn part of a row
     * away from where it rests, which is how scrolling is made smooth.
     */
    val above: List<TerminalRow> = emptyList(),
    val below: List<TerminalRow> = emptyList(),
    /**
     * How many rows the wheel has scrolled the view in all, down counting up and up
     * counting down. Whoever draws compares it with the frame drawn before, to tell that
     * the view moved and how far, which the rows themselves don't say.
     */
    val scrolled: Long = 0,
) {
    /** This frame, said to be after the wheel has scrolled the view [scrolled] rows in all. */
    fun scrolled(scrolled: Long): TerminalFrame =
        if (scrolled == this.scrolled) this
        else TerminalFrame(columns, rows, background, foreground, cursor, images, cellWidthPx, cellHeightPx, selection, above, below, scrolled)
}

/** Cells of one visible row that are selected, or that a search matched: columns [firstColumn] to [lastColumn], both included. */
data class SelectedCells(val row: Int, val firstColumn: Int, val lastColumn: Int)

/**
 * One row, as the runs of cells that have something to draw. A row object is reused from
 * frame to frame while its content doesn't change, so a renderer can cache work per row.
 *
 * Two rows that draw the same are equal, whichever frame each is from and wherever in it.
 * That matters because the terminal library reads every row again after the view scrolls,
 * and after a full-screen program draws its screen again a few lines on: the rows are new
 * objects then, and most of them are rows that were already on screen. Work kept by row
 * (the text's layout, which is most of what a frame costs) is found again for those.
 */
class TerminalRow(val runs: List<TerminalRun>) {
    private var hash = 0

    override fun equals(other: Any?): Boolean = this === other || (other is TerminalRow && hashCode() == other.hashCode() && runs == other.runs)

    override fun hashCode(): Int {
        if (hash == 0) hash = runs.hashCode().let { if (it == 0) 1 else it }
        return hash
    }

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
    val underline: TerminalUnderline = TerminalUnderline.None,
    val strikethrough: Boolean = false,
    /** The color of the line under the text, when the program asked for one of its own. Null to draw it in [foreground]. */
    val underlineColor: Int? = null,
    /** A line over the text, along the top of its cells. */
    val overline: Boolean = false,
) {
    /** Whether this is plain characters, one to a cell, and not one character that takes all the run's cells. */
    val oneToACell: Boolean get() = text.length == cells && text.all { isPlain(it.code) }

    override fun equals(other: Any?): Boolean = this === other || (
        other is TerminalRun && column == other.column && cells == other.cells && foreground == other.foreground &&
            background == other.background && bold == other.bold && italic == other.italic && underline == other.underline &&
            strikethrough == other.strikethrough && underlineColor == other.underlineColor && overline == other.overline && text == other.text
        )

    override fun hashCode(): Int {
        var hash = text.hashCode()
        hash = hash * 31 + column
        hash = hash * 31 + cells
        hash = hash * 31 + foreground
        hash = hash * 31 + (background ?: -1)
        hash = hash * 31 + (underlineColor ?: -1)
        hash = hash * 31 + underline.ordinal
        return hash * 31 + ((if (bold) 1 else 0) or (if (italic) 2 else 0) or (if (strikethrough) 4 else 0) or (if (overline) 8 else 0))
    }
}

/**
 * The line under a run's text, as its program asked for it (SGR 4:1 to 4:5). In the order
 * the terminal library and the browser's glue number them, from zero.
 */
enum class TerminalUnderline { None, Single, Double, Curly, Dotted, Dashed }

/**
 * Whether a monospace font can be trusted to draw this character exactly one cell wide, so
 * that it can share a run with its neighbors: printable ASCII, Latin letters, and the
 * box-drawing and block characters that terminal interfaces are built from. The browser's
 * glue (`ghostty-glue.js`) builds its runs by the same rule.
 */
internal fun isPlain(codepoint: Int): Boolean =
    codepoint in 0x20..0x7E || codepoint in 0xA0..0x17F || codepoint in 0x2500..0x259F

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
