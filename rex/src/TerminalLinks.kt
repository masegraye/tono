package org.example.kotlintest.rex

import org.example.kotlintest.ghostty.GhosttyTerminal
import org.example.kotlintest.ghostty.TerminalFrame

/*
 * Links in a terminal's output. A program can make any text a link (OSC 8), and an address
 * written out in the output is one too. Either is followed with Control and a click, and
 * only if it is the kind of address that is safe to hand to a browser: what a terminal shows
 * may come from another machine, or from a file someone else wrote.
 */

/**
 * A link on screen: where it leads, and the cells it covers, top to bottom. [url] has been
 * through [openableLink], and is what to show as the link's target, since the text in the
 * cells of a link a program made can say anything.
 */
data class TerminalLink(val url: String, val cells: List<Cells>) {
    /** Columns [firstColumn] to [lastColumn] of one visible row, both included. */
    data class Cells(val row: Int, val firstColumn: Int, val lastColumn: Int)
}

/**
 * [target] if it is an address that may be opened, and null if it isn't. Web pages, FTP
 * and mail are; nothing else is, and in particular not `file:`, which would open a file of
 * this machine's at the say of whatever wrote the output, nor the schemes that start
 * programs. An address with blanks or control characters in it is refused whole, as is one
 * with characters that change the direction of text, which can make an address read as
 * another when it is shown.
 */
fun openableLink(target: String): String? {
    if (target.length > MAX_LINK_LENGTH) return null
    if (target.any { it.isWhitespace() || it.isISOControl() || it.category == CharCategory.FORMAT }) return null
    val colon = target.indexOf(':')
    if (colon <= 0) return null
    val rest = target.substring(colon + 1)
    return when (target.substring(0, colon).lowercase()) {
        // With somewhere to go: "http://" alone, or "http:///etc", names no host.
        "http", "https", "ftp" -> target.takeIf { rest.startsWith("//") && rest.length > 2 && rest[2] != '/' }
        "mailto" -> target.takeIf { rest.isNotEmpty() }
        else -> null
    }
}

private const val MAX_LINK_LENGTH = 4096

/** How written-out addresses start. The same schemes as [openableLink] takes. */
private val WRITTEN_SCHEMES = listOf("https://", "http://", "ftp://", "mailto:")

/**
 * Where the addresses written out in [text] are. One runs from its scheme to the first
 * character that can't be part of an address, less what is more likely the sentence's than
 * the address's: punctuation at the end, and a closing bracket whose opening one came
 * before the address did.
 */
internal fun urlsIn(text: CharSequence): List<IntRange> {
    val found = ArrayList<IntRange>()
    var at = 0
    while (at < text.length) {
        val scheme = WRITTEN_SCHEMES.firstOrNull { text.startsWith(it, at, ignoreCase = true) }
        // "xhttp://" is some other scheme, not this one with a letter before it.
        if (scheme == null || (at > 0 && text[at - 1].isLetterOrDigit())) {
            at++
            continue
        }
        var end = at + scheme.length
        while (end < text.length && isAddressCharacter(text[end])) end++
        end = withoutTrailingPunctuation(text, at, end)
        if (end > at + scheme.length) {
            found += at until end
            at = end
        } else {
            at += scheme.length
        }
    }
    return found
}

/**
 * Letters and digits of any alphabet, and the ASCII punctuation addresses use. Not the
 * line-drawing characters that interfaces put right against their text, and not quotes,
 * angle brackets, braces or bars, which are much more often around an address than in one.
 */
private fun isAddressCharacter(char: Char): Boolean = when {
    char <= ' ' || char == '\u007f' -> false
    char.code < 0x80 -> char !in "\"<>`{}|\\^"
    else -> char.isLetterOrDigit()
}

private fun withoutTrailingPunctuation(text: CharSequence, start: Int, end: Int): Int {
    var stop = end
    while (stop > start) {
        val last = text[stop - 1]
        val opening = when (last) {
            ')' -> '('
            ']' -> '['
            else -> null
        }
        val drop = when {
            opening != null -> (start until stop).count { text[it] == last } > (start until stop).count { text[it] == opening }
            else -> last in ".,;:!?'*"
        }
        if (!drop) break
        stop--
    }
    return stop
}

/** What finding a link reads off a terminal's view. */
internal interface LinkScreen {
    val columns: Int
    val rows: Int

    /**
     * A row's characters, one for each column. A column with nothing in it is a space, and
     * so is one holding something that isn't a single character a column wide.
     */
    fun text(row: Int): String

    /** Whether the row ran out of room and goes on in the row below. */
    fun wraps(row: Int): Boolean

    /** Where the program said the cell leads (OSC 8), exactly as it said it, or null. */
    fun marked(column: Int, row: Int): String?
}

/**
 * The link at a cell of the view, or null. A cell its program made a link leads where the
 * program said and nowhere else: if that is somewhere that isn't opened, the cell is no
 * link, whatever its text reads. Otherwise it is a link if an address is written across it,
 * which may run over the end of a row the terminal wrapped.
 */
internal fun linkAt(screen: LinkScreen, column: Int, row: Int): TerminalLink? {
    val columns = screen.columns
    if (column !in 0 until columns || row !in 0 until screen.rows) return null
    val position = row * columns + column
    val marked = screen.marked(column, row)
    if (marked != null) {
        val url = openableLink(marked) ?: return null
        // The same link in the cells before and after, across the ends of rows: a program
        // that draws its own lines marks each part of a link it broke in two.
        fun same(at: Int) = screen.marked(at % columns, at / columns) == marked
        var first = position
        while (first > 0 && position - first < MARKED_REACH && same(first - 1)) first--
        var last = position
        while (last < columns * screen.rows - 1 && last - position < MARKED_REACH && same(last + 1)) last++
        return TerminalLink(url, cells(first, last, columns))
    }

    var firstRow = row
    while (firstRow > 0 && screen.wraps(firstRow - 1)) firstRow--
    var lastRow = row
    while (lastRow < screen.rows - 1 && screen.wraps(lastRow)) lastRow++
    val line = buildString { for (each in firstRow..lastRow) append(screen.text(each).padEnd(columns).substring(0, columns)) }
    val within = position - firstRow * columns
    val found = urlsIn(line).firstOrNull { within in it } ?: return null
    val url = openableLink(line.substring(found.first, found.last + 1)) ?: return null
    return TerminalLink(url, cells(firstRow * columns + found.first, firstRow * columns + found.last, columns))
}

/** How many cells either side of the pointer a link made by a program is followed for, to underline it. */
private const val MARKED_REACH = 2000

/** The cells from one position to another, counted along the rows, as a stretch of each row. */
private fun cells(first: Int, last: Int, columns: Int): List<TerminalLink.Cells> = (first / columns..last / columns).map { row ->
    TerminalLink.Cells(
        row = row,
        firstColumn = if (row == first / columns) first % columns else 0,
        lastColumn = if (row == last / columns) last % columns else columns - 1,
    )
}

/** A terminal's view as it was last drawn, for finding links in. */
internal class DrawnScreen(private val frame: TerminalFrame, private val terminal: GhosttyTerminal) : LinkScreen {
    override val columns: Int get() = frame.columns
    override val rows: Int get() = frame.rows.size

    override fun text(row: Int): String {
        val text = CharArray(columns) { ' ' }
        for (run in frame.rows[row].runs) {
            // A run is either plain characters, one to a column, or one character placed by hand.
            if (run.text.length != run.cells) continue
            for (index in run.text.indices) if (run.column + index < columns) text[run.column + index] = run.text[index]
        }
        return text.concatToString()
    }

    override fun wraps(row: Int): Boolean = terminal.rowWraps(row)

    override fun marked(column: Int, row: Int): String? = terminal.hyperlinkAt(column, row)
}
