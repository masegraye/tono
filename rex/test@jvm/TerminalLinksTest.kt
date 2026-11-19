package org.example.kotlintest.rex

import org.example.kotlintest.ghostty.GhosttyRenderer
import org.example.kotlintest.ghostty.GhosttyTerminal
import org.example.kotlintest.rex.TerminalLink.Cells
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val ESC = "\u001b"

/** What a program writes to make [text] a link to [target] (OSC 8). */
internal fun marked(target: String, text: String) = "$ESC]8;;$target$ESC\\$text$ESC]8;;$ESC\\"

class TerminalLinksTest {
    private fun found(text: String): List<String> = urlsIn(text).map { text.substring(it.first, it.last + 1) }

    // --- Which addresses are opened ---

    @Test
    fun `web pages, ftp and mail are opened`() {
        for (target in listOf("http://example.com", "https://example.com/a?b=c#d", "ftp://ftp.example.com/pub", "mailto:mase@example.com", "HTTPS://EXAMPLE.COM")) {
            assertEquals(target, openableLink(target))
        }
    }

    @Test
    fun `nothing else is, and above all not a file`() {
        val refused = listOf(
            "file:///etc/passwd", "file://remote/etc/passwd", "FILE:///etc/passwd",
            "javascript:alert(1)", "data:text/html,hi", "ssh://host", "vscode://file/etc/passwd", "x-man-page://ls", "smb://server/share",
            "", "example.com", "/etc/passwd", ":", "://example.com", "http", "mailto:",
        )
        for (target in refused) assertNull(openableLink(target), target)
    }

    @Test
    fun `a web address has to name a host`() {
        for (target in listOf("http://", "https:", "http:///etc/passwd", "https:/example.com", "http:example.com")) assertNull(openableLink(target), target)
    }

    @Test
    fun `an address with blanks, control characters or characters that reverse text is refused whole`() {
        val refused = listOf(
            "https://example.com/a b", " https://example.com", "https://example.com\n", "https://example.com/\u0007", "https://example.com/\u001b[0m",
            "https://example.com/\u202Egpj.exe", "https://example.com/\u2066x", "https://exam\u200Bple.com", "https://example.com/\u0085",
        )
        for (target in refused) assertNull(openableLink(target), target)
    }

    @Test
    fun `an address too long to be one is refused`() {
        assertNull(openableLink("https://example.com/" + "a".repeat(5000)))
    }

    // --- Addresses written out in the text ---

    @Test
    fun `an address is found among other words`() {
        assertEquals(listOf("https://example.com/docs"), found("see https://example.com/docs for more"))
        assertEquals(listOf("http://a.example", "ftp://b.example/x", "mailto:c@d.example"), found("http://a.example ftp://b.example/x and mailto:c@d.example"))
    }

    @Test
    fun `the scheme may be in capitals, and has to start a word`() {
        assertEquals(listOf("HTTPS://EXAMPLE.COM"), found("HTTPS://EXAMPLE.COM"))
        assertEquals(emptyList(), found("xhttp://example.com"))
        assertEquals(listOf("https://example.com"), found("url=https://example.com"))
    }

    @Test
    fun `a scheme with nothing after it is no address`() {
        assertEquals(emptyList(), found("use https:// or http://."))
        assertEquals(emptyList(), found("mailto: someone"))
    }

    @Test
    fun `punctuation after an address belongs to the sentence`() {
        assertEquals(listOf("https://example.com"), found("Go to https://example.com."))
        assertEquals(listOf("https://example.com/a"), found("https://example.com/a, then"))
        assertEquals(listOf("https://example.com/a"), found("Is it https://example.com/a?!"))
        assertEquals(listOf("https://example.com/a"), found("at https://example.com/a: yes"))
        assertEquals(listOf("https://example.com/a"), found("'https://example.com/a';"))
        assertEquals(listOf("https://example.com/a"), found("**https://example.com/a**"))
    }

    @Test
    fun `punctuation inside an address is kept, and so is a slash at the end`() {
        assertEquals(listOf("https://example.com/a.b,c;d:e!f?g=h's"), found("https://example.com/a.b,c;d:e!f?g=h's"))
        assertEquals(listOf("https://example.com/"), found("https://example.com/"))
        assertEquals(listOf("https://example.com:8080/x?a=1&b=2#top"), found("https://example.com:8080/x?a=1&b=2#top"))
    }

    @Test
    fun `a closing parenthesis is the address's only when its opening one is`() {
        assertEquals(listOf("https://example.com"), found("(https://example.com)"))
        assertEquals(listOf("https://example.com/a"), found("[docs](https://example.com/a)."))
        assertEquals(listOf("https://en.wikipedia.org/wiki/Rust_(programming_language)"), found("https://en.wikipedia.org/wiki/Rust_(programming_language)"))
        assertEquals(listOf("https://en.wikipedia.org/wiki/Rust_(programming_language)"), found("(see https://en.wikipedia.org/wiki/Rust_(programming_language))."))
        assertEquals(listOf("https://example.com/a"), found("[https://example.com/a]"))
        assertEquals(listOf("http://[::1]:8080/"), found("http://[::1]:8080/"))
    }

    @Test
    fun `quotes, angle brackets, bars and line drawing end an address`() {
        assertEquals(listOf("https://example.com/a"), found("\"https://example.com/a\""))
        assertEquals(listOf("https://example.com/a"), found("<https://example.com/a>"))
        assertEquals(listOf("https://example.com/a"), found("| https://example.com/a|"))
        assertEquals(listOf("https://example.com/a"), found("│https://example.com/a│"))
        assertEquals(listOf("https://example.com/a"), found("`https://example.com/a`"))
        assertEquals(listOf("https://example.com/a"), found("https://example.com/a\u0000b"))
    }

    @Test
    fun `letters of other alphabets are part of an address`() {
        assertEquals(listOf("https://пример.example/страница"), found("https://пример.example/страница далее"))
    }

    @Test
    fun `an address inside another is part of it`() {
        assertEquals(listOf("https://web.archive.org/web/2020/http://example.com/"), found("https://web.archive.org/web/2020/http://example.com/"))
    }

    // --- Links on a screen ---

    /** A screen of plain rows. A row ending in "\" is wrapped into the next. */
    private class Rows(vararg lines: String, val marks: Map<Pair<Int, Int>, String> = emptyMap()) : LinkScreen {
        private val wrapped = lines.map { it.endsWith("\\") }
        private val text = lines.map { it.removeSuffix("\\") }
        override val columns = text.maxOf { it.length }
        override val rows = text.size
        override fun text(row: Int) = text[row]
        override fun wraps(row: Int) = wrapped[row]
        override fun marked(column: Int, row: Int) = marks[column to row]
    }

    @Test
    fun `an address is a link in each of its cells and in none beside it`() {
        val screen = Rows("see https://example.com/a now")
        val link = TerminalLink("https://example.com/a", listOf(Cells(row = 0, firstColumn = 4, lastColumn = 24)))

        assertNull(linkAt(screen, 3, 0))
        assertEquals(link, linkAt(screen, 4, 0))
        assertEquals(link, linkAt(screen, 24, 0))
        assertNull(linkAt(screen, 25, 0))
    }

    @Test
    fun `nowhere outside the screen is a link`() {
        val screen = Rows("https://example.com/a")

        assertNull(linkAt(screen, -1, 0))
        assertNull(linkAt(screen, 0, 1))
        assertNull(linkAt(screen, 21, 0))
    }

    @Test
    fun `an address the terminal wrapped is one link, from either row`() {
        val screen = Rows(
            "first line          ",
            "go to https://exampl\\",
            "e.com/a/long/path/th\\",
            "at/ends.            ",
            "https://other.exampl",
        )
        val link = TerminalLink(
            "https://example.com/a/long/path/that/ends",
            listOf(Cells(1, 6, 19), Cells(2, 0, 19), Cells(3, 0, 6)),
        )

        assertEquals(link, linkAt(screen, 10, 1))
        assertEquals(link, linkAt(screen, 0, 2))
        assertEquals(link, linkAt(screen, 6, 3))
        assertNull(linkAt(screen, 7, 3)) // the full stop
    }

    @Test
    fun `an address that reaches the end of a row the terminal didn't wrap stops there`() {
        val screen = Rows(
            "https://example.com/",
            "more                ",
        )

        assertEquals(TerminalLink("https://example.com/", listOf(Cells(0, 0, 19))), linkAt(screen, 5, 0))
        assertNull(linkAt(screen, 0, 1))
    }

    @Test
    fun `a link a program made leads where the program said, not where its text says`() {
        val text = "https://bank.example"
        val screen = Rows(text, marks = text.indices.associate { (it to 0) to "https://evil.example/" })

        assertEquals(TerminalLink("https://evil.example/", listOf(Cells(0, 0, 19))), linkAt(screen, 5, 0))
    }

    @Test
    fun `a link a program made to somewhere that isn't opened is no link, even over an address`() {
        val text = "https://example.com/"
        val screen = Rows(text, marks = text.indices.associate { (it to 0) to "file:///etc/passwd" })

        assertNull(linkAt(screen, 5, 0))
    }

    @Test
    fun `a link a program made covers the cells it marked, across rows`() {
        val screen = Rows(
            "read the docs",
            "here or there",
            marks = ((9..12).map { it to 0 } + (0..3).map { it to 1 }).associateWith { "https://example.com/docs" } + ((8 to 1) to "https://example.com/other"),
        )

        assertEquals(TerminalLink("https://example.com/docs", listOf(Cells(0, 9, 12), Cells(1, 0, 3))), linkAt(screen, 10, 0))
        assertEquals(TerminalLink("https://example.com/other", listOf(Cells(1, 8, 8))), linkAt(screen, 8, 1))
        assertNull(linkAt(screen, 5, 1))
    }

    // --- The same, read off a real terminal ---

    private fun terminal(columns: Int, rows: Int, output: String, body: (LinkScreen) -> Unit) = GhosttyTerminal.create(columns, rows).use { terminal ->
        GhosttyRenderer().use { renderer ->
            terminal.write(output)
            body(DrawnScreen(renderer.render(terminal), terminal))
        }
    }

    @Test
    fun `a terminal's wrapped address is one link`() = terminal(20, 4, "go to https://example.com/a/long/path/that/ends.\r\nnext") { screen ->
        val link = TerminalLink("https://example.com/a/long/path/that/ends", listOf(Cells(0, 6, 19), Cells(1, 0, 19), Cells(2, 0, 6)))

        assertEquals(link, linkAt(screen, 6, 0))
        assertEquals(link, linkAt(screen, 3, 2))
        assertNull(linkAt(screen, 0, 3))
    }

    @Test
    fun `a terminal's marked text is a link to what the program said`() = terminal(30, 2, "see ${marked("https://example.com/real", "the docs")} now") { screen ->
        val link = TerminalLink("https://example.com/real", listOf(Cells(0, 4, 11)))

        assertNull(linkAt(screen, 3, 0))
        assertEquals(link, linkAt(screen, 4, 0))
        assertEquals(link, linkAt(screen, 11, 0))
        assertNull(linkAt(screen, 12, 0))
    }

    @Test
    fun `a wide character ends an address in a terminal`() = terminal(30, 2, "https://example.com/a日本 x") { screen ->
        assertEquals("https://example.com/a", linkAt(screen, 0, 0)?.url)
    }
}
