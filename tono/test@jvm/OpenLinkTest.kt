package org.example.kotlintest

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenLinkTest {
    private fun programs(vararg present: String): (String) -> Path? = { name -> if (name in present) Path.of("/usr/bin/$name") else null }

    @Test
    fun `a link is opened with the desktop's own opener, as one argument`() {
        assertEquals(listOf("/usr/bin/xdg-open", "https://example.com/a?b=c&d=e;f"), openCommand("https://example.com/a?b=c&d=e;f", "Linux", programs("xdg-open")))
        assertEquals(listOf("/usr/bin/open", "https://example.com/"), openCommand("https://example.com/", "Mac OS X", programs()))
    }

    @Test
    fun `without an opener there is no command`() {
        assertNull(openCommand("https://example.com/", "Linux", programs("firefox")))
    }

    @Test
    fun `an address that isn't a web page, ftp or mail is refused before anything is run`() {
        for (url in listOf("file:///etc/passwd", "javascript:alert(1)", "--version", "https://example.com/ --new-window", "")) {
            val said = ArrayList<Pair<String, Boolean>>()
            openLink(url) { message, isError -> said += message to isError }

            assertEquals(1, said.size, url)
            assertTrue(said.single().second && said.single().first.startsWith("didn't open"), "$url: $said")
        }
    }
}
