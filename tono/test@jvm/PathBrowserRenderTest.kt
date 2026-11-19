package org.example.kotlintest

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import dev.mg.omarchy.theme.bundledOmarchyPalettes
import dev.mg.omarchy.theme.compose.omarchyColorScheme
import dev.mg.omarchy.theme.compose.omarchyShapes
import org.example.kotlintest.rex.DirectoryEntry
import org.example.kotlintest.rex.DirectoryListing
import org.example.kotlintest.rex.RexError
import org.example.kotlintest.rex.splitPath
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * The path browser as the desktop runs it, offscreen, over a made-up machine, with keys sent
 * to its field. What it asked the machine and what it typed into the terminal are recorded.
 * The pictures are left in `<project>/build/path-browser-renders/` to be looked at.
 */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
class PathBrowserRenderTest {
    private val outputDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "project.yaml").isFile }
        .resolve("build/path-browser-renders")
        .apply { mkdirs() }

    private val scheme = omarchyColorScheme(bundledOmarchyPalettes.first { it.slug == "retro-82" })

    /** The made-up machine: each folder's entries. The terminal is in /home/dev/work. */
    private val machine = mapOf(
        "/home/dev/work" to listOf(
            DirectoryEntry("notes.txt", isFile = true), DirectoryEntry("my project"), DirectoryEntry("Alice's"), DirectoryEntry(".git"),
            DirectoryEntry("\$HOME"), DirectoryEntry("new\nline"), DirectoryEntry("-rf", isFile = true),
        ),
        "/home/dev/work/my project" to listOf(DirectoryEntry("src"), DirectoryEntry("read me.md", isFile = true)),
        "/home/dev/work/my project/src" to emptyList(),
        "/home/dev" to listOf(DirectoryEntry("work")),
        "/etc" to listOf(DirectoryEntry("hosts", isFile = true)),
    )

    private val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val typed = java.util.concurrent.CopyOnWriteArrayList<String>()
    private var closed = 0
    private var terminalThere = true

    /** Answers as the server does: `~` and relative paths resolved, entries narrowed by the prefix, files only if asked for. */
    private fun answer(text: String, files: Boolean): DirectoryListing {
        asked += text
        val query = splitPath(text)
        val directory = query.directory.trimEnd('/').let { dir ->
            when {
                query.directory == "/" -> "/"
                dir.isEmpty() -> "/home/dev/work"
                dir == "~" -> "/home/dev"
                dir.startsWith("~/") -> "/home/dev/" + dir.removePrefix("~/")
                dir.startsWith("/") -> dir
                else -> "/home/dev/work/$dir"
            }
        }
        if (directory == "/old") throw RexError("method_not_found", "no such method", 404)
        val entries = machine[directory] ?: return DirectoryListing(directory, "/home/dev", "missing", emptyList(), false)
        return DirectoryListing(
            directory, "/home/dev", "ok",
            entries.filter { it.name.startsWith(query.prefix, ignoreCase = true) && (files || !it.isFile) }, truncated = directory == "/etc",
        )
    }

    private inner class Browser(foldersOnly: Boolean, hasTerminal: Boolean = true) : AutoCloseable {
        private val source = object : java.awt.Component() {}
        private var time = 0L
        val scene = ImageComposeScene(720, 520, Density(1f)) {
            MaterialTheme(colorScheme = scheme, shapes = omarchyShapes()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    PathBrowserPanel(
                        foldersOnly = foldersOnly,
                        list = if (hasTerminal) { text -> answer(text, files = !foldersOnly) } else null,
                        type = { text -> if (terminalThere) typed += text; terminalThere },
                        onClose = { closed++ },
                    )
                }
            }
        }

        /** Lets the listing that was asked for arrive, and draws. */
        fun settle(name: String? = null) {
            repeat(8) {
                Thread.sleep(12)
                time += 50_000_000
                scene.render(time)
            }
            if (name != null) File(outputDir, "$name.png").writeBytes(scene.render(time).encodeToData()!!.bytes)
        }

        private fun send(awtType: Int, code: Int, char: Char, shift: Boolean) {
            scene.sendKeyEvent(
                KeyEvent(
                    key = Key(code),
                    type = when (awtType) {
                        AwtKeyEvent.KEY_PRESSED -> KeyEventType.KeyDown
                        AwtKeyEvent.KEY_RELEASED -> KeyEventType.KeyUp
                        else -> KeyEventType.Unknown
                    },
                    codePoint = if (char == AwtKeyEvent.CHAR_UNDEFINED) 0 else char.code,
                    isShiftPressed = shift,
                    nativeEvent = AwtKeyEvent(source, awtType, 0, if (shift) AwtKeyEvent.SHIFT_DOWN_MASK else 0, code, char),
                ),
            )
        }

        fun press(code: Int, shift: Boolean = false) {
            send(AwtKeyEvent.KEY_PRESSED, code, AwtKeyEvent.CHAR_UNDEFINED, shift)
            send(AwtKeyEvent.KEY_RELEASED, code, AwtKeyEvent.CHAR_UNDEFINED, shift)
            settle()
        }

        fun type(text: String) {
            for (char in text) {
                val code = AwtKeyEvent.getExtendedKeyCodeForChar(char.code)
                send(AwtKeyEvent.KEY_PRESSED, code, char, false)
                send(AwtKeyEvent.KEY_TYPED, AwtKeyEvent.VK_UNDEFINED, char, false)
                send(AwtKeyEvent.KEY_RELEASED, code, char, false)
                scene.render(++time)
            }
            settle()
        }

        fun clear(characters: Int) = repeat(characters) { press(AwtKeyEvent.VK_BACK_SPACE) }

        override fun close() = scene.close()
    }

    private fun browser(foldersOnly: Boolean = false, hasTerminal: Boolean = true) = Browser(foldersOnly, hasTerminal).also { it.settle() }

    @Test
    fun `it opens on the terminal's working directory, and Enter types that path`() = browser().use { browser ->
        browser.settle("opened")
        assertEquals(listOf(""), asked, "one question, for where the terminal is")

        browser.press(AwtKeyEvent.VK_ENTER)
        assertEquals(listOf("/home/dev/work"), typed)
    }

    @Test
    fun `Down chooses an entry and Enter types it quoted, with nothing after it`() = browser().use { browser ->
        // Sorted without regard to case: $HOME, -rf, Alice's, my project, notes.txt. The dot name and the newline name aren't there.
        repeat(3) { browser.press(AwtKeyEvent.VK_DOWN) }
        browser.settle("alices")
        browser.press(AwtKeyEvent.VK_ENTER)

        assertEquals(listOf("'/home/dev/work/Alice'\\''s'"), typed)
    }

    @Test
    fun `every entry offered can be typed, and the one with a newline never is`() {
        val all = ArrayList<String>()
        for (row in 0..6) {
            typed.clear()
            browser().use { browser ->
                repeat(row) { browser.press(AwtKeyEvent.VK_DOWN) }
                browser.press(AwtKeyEvent.VK_ENTER)
            }
            all += typed
        }
        // Six rows; a sixth press of Down comes round to the first again.
        assertEquals(
            listOf("/home/dev/work", "'/home/dev/work/\$HOME'", "/home/dev/work/-rf", "'/home/dev/work/Alice'\\''s'", "'/home/dev/work/my project'", "/home/dev/work/notes.txt", "/home/dev/work"),
            all,
        )
        assertTrue(all.none { '\n' in it })
    }

    @Test
    fun `Tab goes into a folder, and again into the next`() = browser().use { browser ->
        repeat(4) { browser.press(AwtKeyEvent.VK_DOWN) }
        browser.press(AwtKeyEvent.VK_TAB)
        browser.settle("inside-my-project")
        assertEquals("~/work/my project/", asked.last())

        browser.press(AwtKeyEvent.VK_DOWN) // read me.md, a file: Tab does nothing
        browser.press(AwtKeyEvent.VK_TAB)
        assertEquals("~/work/my project/", asked.last())
        browser.press(AwtKeyEvent.VK_DOWN) // src
        browser.press(AwtKeyEvent.VK_TAB)
        assertEquals("~/work/my project/src/", asked.last())

        browser.press(AwtKeyEvent.VK_ENTER)
        assertEquals(listOf("'/home/dev/work/my project/src'"), typed, "the full path, not the one with a tilde")
    }

    @Test
    fun `typing narrows the folder`() = browser().use { browser ->
        browser.type("m")
        assertEquals("~/work/m", asked.last())
        browser.press(AwtKeyEvent.VK_DOWN)
        browser.press(AwtKeyEvent.VK_ENTER)
        assertEquals(listOf("'/home/dev/work/my project'"), typed)
    }

    @Test
    fun `hidden entries come with a dot`() = browser().use { browser ->
        browser.type(".")
        browser.settle("hidden")
        browser.press(AwtKeyEvent.VK_DOWN)
        browser.press(AwtKeyEvent.VK_ENTER)
        assertEquals(listOf("/home/dev/work/.git"), typed)
    }

    @Test
    fun `go to directory lists folders only and types cd before the folder, and no Enter`() = browser(foldersOnly = true).use { browser ->
        browser.settle("go-to-directory")
        // $HOME, Alice's, my project: no notes.txt and no -rf, which are files.
        repeat(3) { browser.press(AwtKeyEvent.VK_DOWN) }
        browser.press(AwtKeyEvent.VK_ENTER)
        assertEquals(listOf("cd '/home/dev/work/my project'"), typed)
        assertTrue(typed.none { it.endsWith("\n") || it.endsWith("\r") || it.endsWith(" ") })
    }

    @Test
    fun `a folder that isn't there says so, and Enter types nothing`() = browser().use { browser ->
        browser.type("nowhere/")
        browser.settle("missing")
        browser.press(AwtKeyEvent.VK_ENTER)
        assertEquals("~/work/nowhere/", asked.last())
        assertEquals(emptyList(), typed)
    }

    @Test
    fun `a server too old to list, and no terminal at all, type nothing`() {
        browser().use { browser ->
            browser.clear("~/work/".length)
            browser.type("/old/")
            browser.settle("old-server")
            browser.press(AwtKeyEvent.VK_ENTER)
        }
        browser(hasTerminal = false).use { browser ->
            browser.settle("no-terminal")
            browser.press(AwtKeyEvent.VK_ENTER)
        }
        assertEquals(emptyList(), typed)
    }

    @Test
    fun `a terminal that closed meanwhile is not typed into, and the browser stays to say so`() = browser().use { browser ->
        terminalThere = false
        browser.press(AwtKeyEvent.VK_ENTER)
        browser.settle("terminal-gone")
        assertEquals(emptyList(), typed)
        assertEquals(0, closed)
    }

    @Test
    fun `Escape closes it and types nothing`() = browser().use { browser ->
        browser.press(AwtKeyEvent.VK_DOWN)
        browser.press(AwtKeyEvent.VK_ESCAPE)
        assertEquals(1, closed)
        assertEquals(emptyList(), typed)
    }

    @Test
    fun `an absolute path can be typed in place of where the terminal is`() = browser().use { browser ->
        browser.clear("~/work/".length)
        browser.type("/etc/")
        browser.settle("etc-truncated")
        browser.press(AwtKeyEvent.VK_DOWN)
        browser.press(AwtKeyEvent.VK_ENTER)
        assertEquals(listOf("/etc/hosts"), typed)
    }
}
