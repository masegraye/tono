package org.example.kotlintest

import java.nio.file.Files
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

    /** A stand-in for the desktop's opener, which writes down what it was given and exits as told. */
    private fun opener(directory: Path, exit: Int): Path = directory.resolve("fake-open").also {
        val d = '$'
        Files.writeString(it, "#!/bin/sh\nfor argument in \"$d@\"; do printf '%s\\n' \"${d}argument\" >> \"${d}0.given\"; done\nexit $exit\n")
        it.toFile().setExecutable(true)
    }

    @Test
    fun `the opener is run with the address whole, and its success is logged`() {
        val directory = Files.createTempDirectory("open-link")
        try {
            val url = "https://example.com/a?b=c&d=e;f#\$(g)'h"
            val said = ArrayList<Pair<String, Boolean>>()
            val command = openCommand(url, "Linux") { opener(directory, exit = 0) }!!

            runOpener(command, url) { message, isError -> said += message to isError }

            assertEquals(listOf(url), Files.readAllLines(directory.resolve("fake-open.given")))
            assertEquals(listOf("opened $url with fake-open" to false), said)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an opener that fails, or isn't there, is logged as an error`() {
        val directory = Files.createTempDirectory("open-link")
        try {
            val said = ArrayList<Pair<String, Boolean>>()
            runOpener(listOf(opener(directory, exit = 3).toString(), "https://example.com/"), "https://example.com/") { message, isError -> said += message to isError }
            runOpener(listOf(directory.resolve("missing").toString(), "https://example.com/"), "https://example.com/") { message, isError -> said += message to isError }

            assertEquals("couldn't open https://example.com/: fake-open exited with 3" to true, said[0])
            assertTrue(said[1].second && said[1].first.startsWith("couldn't open https://example.com/"), said[1].toString())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
