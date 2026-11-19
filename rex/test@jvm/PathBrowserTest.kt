package org.example.kotlintest.rex

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Names a filesystem may hold that a shell would read as something else if they weren't quoted. */
private val AWKWARD_PATHS = listOf(
    "/tmp/a",
    "/tmp/a b",
    "/tmp/ leading and trailing ",
    "/tmp/two  spaces",
    "/tmp/Alice's",
    "'",
    "''",
    "'quoted'",
    "it's \"both\"",
    "\"",
    "\$HOME",
    "/tmp/\$HOME/\${HOME}",
    "\$(id)",
    "\$(touch /tmp/insert-path-must-not-exist)",
    "`id`",
    "`touch /tmp/insert-path-must-not-exist`",
    "!",
    "!!",
    "a!b",
    "!\$",
    "*",
    "/tmp/*.txt",
    "?",
    "[abc]",
    "{a,b}",
    "a{1..3}",
    "~",
    "~/x",
    "~root",
    "-rf",
    "-",
    "--",
    "--help",
    "-a b",
    "-n",
    "/tmp/-rf",
    "a;b",
    "a&b",
    "a&&b",
    "a|b",
    "a>b",
    "a<b",
    "(a)",
    "a#b",
    "#a",
    "a=b",
    "a%b",
    "a,b",
    "a:b",
    "a@b",
    "a+b",
    "a^b",
    "a\\b",
    "a\\\\b",
    "a\\",
    "\\",
    "\\\\",
    "a\\'b",
    "a'\\b",
    "\\'",
    "'\\''",
    "\\n",
    "\\x41",
    "/tmp/é雪",
    "/tmp/日本語/ファイル.txt",
    "/tmp/Ünïcödé",
    "/tmp/emoji 👩‍👩‍👧/🇸🇪", // joined with the zero-width joiner, which is let through
    "/tmp/नमस्ते",
    "/tmp/مرحبا",
    "/tmp/a\u00A0b", // a no-break space: odd, but visible as a space and not refused
    "/tmp/a\u3000b", // an ideographic space
    "/a//b/",
    "/",
    ".",
    "..",
    "...",
    "name.with.dots-and_underscores/OK123",
)

class PathQuotingTest {
    @Test
    fun `a path is quoted only when it needs it, the way a POSIX shell reads`() {
        for ((path, quoted) in listOf(
            "/tmp/a" to "/tmp/a",
            "/tmp/a b" to "'/tmp/a b'",
            "/tmp/Alice's" to "'/tmp/Alice'\\''s'",
            "'" to "\\'",
            "\$HOME" to "'\$HOME'",
            "`id`" to "'`id`'",
            "!" to "'!'",
            "*" to "'*'",
            "-rf" to "./-rf",
            "-a b" to "'./-a b'",
            "/tmp/-rf" to "/tmp/-rf",
            "/tmp/é雪" to "'/tmp/é雪'",
            "" to "''",
            "/a//b/" to "/a//b/",
            "a;b" to "'a;b'",
            "\$(id)" to "'\$(id)'",
            "~" to "'~'",
            "a=b" to "'a=b'",
            "a#b" to "'a#b'",
            // A backslash goes outside the quotes, where fish reads it as sh does.
            "a\\b" to "'a'\\\\'b'",
            "a\\" to "'a'\\\\",
            "\\'" to "\\\\\\'",
        )) assertEquals(quoted, quotePath(path), path)
    }

    @Test
    fun `what is typed has no space or newline after it, and cd comes before a folder`() {
        assertEquals("'/home/dev/my dir'", insertPathText("/home/dev/my dir"))
        assertEquals("cd '/home/dev/my dir'", goToDirectoryText("/home/dev/my dir"))
        assertEquals("cd /tmp", goToDirectoryText("/tmp"))
        for (path in AWKWARD_PATHS) {
            val typed = assertNotNull(goToDirectoryText(path), path)
            assertFalse(typed.any { it == '\n' || it == '\r' || it.code < 0x20 || it.code == 0x7F }, path)
            assertFalse(typed.endsWith(" ") && !path.endsWith(" "), path)
        }
    }

    @Test
    fun `nothing quoted can be taken for an option`() {
        for (path in AWKWARD_PATHS) assertFalse(quotePath(path)!!.startsWith("-"), path)
    }

    @Test
    fun `control characters are refused, never typed`() {
        val controls = (0x00..0x1F).map { it.toChar() } + '\u007F' + (0x80..0x9F).map { it.toChar() }
        for (char in controls) {
            val path = "/tmp/a${char}b"
            assertNull(quotePath(path), "U+%04X".format(char.code))
            assertNull(insertPathText(path))
            assertNull(goToDirectoryText(path))
            assertEquals(REFUSED_CHARACTERS, pathFieldRefusal(path))
        }
        // The ones that would do harm at a prompt: a newline runs the line, an escape starts a sequence.
        for (path in listOf("a\nrm -rf ~", "a\rb", "a\u001B[2Jb", "\u0003", "tab\there", "nul\u0000")) assertNull(quotePath(path), path)
    }

    @Test
    fun `line separators and invisible formatting characters are refused`() {
        val invisible = listOf(
            0x00AD, 0x034F, 0x061C, 0x115F, 0x1160, 0x180E, 0x3164, 0xFFA0, 0x200B, 0x200E, 0x200F,
            0x2028, 0x2029, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2060, 0x2061, 0x2064,
            0x2066, 0x2067, 0x2068, 0x2069, 0x206F, 0xFEFF, 0xFFF9, 0xFFFB, 0xE0000, 0xE0001, 0xE0041, 0xE007F,
        )
        for (codePoint in invisible) {
            assertTrue(isRefusedInPath(codePoint), "U+%04X".format(codePoint))
            assertNull(quotePath("/tmp/a" + String(Character.toChars(codePoint)) + "b"), "U+%04X".format(codePoint))
        }
        // A right-to-left override makes this read as invoicehs.pdf.
        assertNull(quotePath("invoice\u202Efdp.sh"))
    }

    @Test
    fun `half a surrogate pair is refused, and a whole one is not`() {
        assertNull(quotePath("a\uD83Db"))
        assertNull(quotePath("a\uDE00"))
        assertNull(quotePath("\uDE00\uD83D"))
        assertEquals("'😀'", quotePath("😀"))
    }

    @Test
    fun `joiners that scripts and emoji need, and ordinary unicode, are let through`() {
        for (codePoint in listOf(0x200C, 0x200D, 0x00A0, 0x3000, 0xE9, 0x96EA, 0x1F600, 0x0301, 0xFE0F)) {
            assertFalse(isRefusedInPath(codePoint), "U+%04X".format(codePoint))
        }
        for (path in AWKWARD_PATHS) assertNotNull(quotePath(path), path)
    }

    /**
     * The real test of quoting: hand each quoted path to the shells on this machine and see
     * that the one word they read is the path. Shells that aren't installed are skipped;
     * `sh` and `bash` are everywhere this builds.
     */
    @Test
    fun `every shell reads a quoted path back as the path`() {
        val shells = listOf("sh", "bash", "dash", "zsh", "ksh", "fish").mapNotNull { name ->
            System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, name) }.firstOrNull { it.canExecute() }?.let { name to it.path }
        }
        assertTrue(shells.any { it.first == "sh" || it.first == "bash" }, "no shell found to test with")
        println("quoting checked with: ${shells.joinToString { it.second }}")
        val marker = File("/tmp/insert-path-must-not-exist")
        marker.delete()
        for ((name, shell) in shells) {
            // One script for all the paths: each printed between markers, as one argument.
            val paths = AWKWARD_PATHS + ""
            val script = paths.joinToString("\n") { "printf '<%s>\\n' ${quotePath(it)!!}" }
            val flags = if (name == "fish") listOf("--no-config") else emptyList()
            val process = ProcessBuilder(listOf(shell) + flags + listOf("-c", script)).redirectErrorStream(true).start()
            val output = process.inputStream.readBytes().decodeToString()
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "$name didn't finish")
            assertEquals(paths.joinToString("") { "<${if (it.startsWith("-")) "./$it" else it}>\n" }, output, "read by $name")
            assertEquals(0, process.exitValue(), name)
        }
        assertFalse(marker.exists(), "something in a path was run")
    }

    /** And typed after `cd`, the way Go to Directory types it, a shell ends up in that folder. */
    @Test
    fun `a shell that runs what Go to Directory types ends up in the folder`() {
        val root = java.nio.file.Files.createTempDirectory("insert-path-test").toFile()
        try {
            for (name in listOf("plain", "a b", "Alice's", "\$HOME", "`id`", "*", "-rf", "back\\slash", "é雪", "semi;colon", "~", "!bang")) {
                val folder = File(root, name).apply { mkdir() }
                val process = ProcessBuilder("bash", "-c", goToDirectoryText(folder.path)!! + " && pwd -P").redirectErrorStream(true).start()
                val output = process.inputStream.readBytes().decodeToString()
                process.waitFor(20, TimeUnit.SECONDS)
                assertEquals(folder.canonicalPath + "\n", output, name)
            }
        } finally {
            root.deleteRecursively()
        }
    }
}

class PathBrowsingTest {
    private fun listing(json: String) = DirectoryListing.parse(Json.parseToJsonElement(json).jsonObject)

    private val home = listing(
        """{"path":"/home/dev","home":"/home/dev","status":"ok","truncated":false,"entries":[
            {"name":"notes.txt","file":true},{"name":"Work"},{"name":".config"},{"name":"apps"},{"name":"work","symlink":true},
            {"name":"a\nb"},{"name":"evil\u202Efdp.sh","file":true},{"name":""},{"name":"."},{"name":".."},{"name":"x/y"},{"name":"Zed","file":true}]}""",
    )

    @Test
    fun `what is typed splits at the last slash`() {
        assertEquals(PathQuery("", ""), splitPath(""))
        assertEquals(PathQuery("", "src"), splitPath("src"))
        assertEquals(PathQuery("~", ""), splitPath("~"))
        assertEquals(PathQuery("~/", ""), splitPath("~/"))
        assertEquals(PathQuery("~/dev/", "ko"), splitPath("~/dev/ko"))
        assertEquals(PathQuery("/", "et"), splitPath("/et"))
        assertEquals(PathQuery("/etc/", ""), splitPath("/etc/"))
        assertEquals(PathQuery("a b/", " c"), splitPath("a b/ c")) // spaces are part of names
    }

    @Test
    fun `the home directory shows as a tilde`() {
        assertEquals("~", shownPath("/home/dev", "/home/dev"))
        assertEquals("~/a/b", shownPath("/home/dev/a/b", "/home/dev"))
        assertEquals("/home/developer", shownPath("/home/developer", "/home/dev"))
        assertEquals("/etc", shownPath("/etc", "/home/dev"))
        assertEquals("/etc", shownPath("/etc", ""))
        assertEquals("/etc", shownPath("/etc", "/")) // root's home, on some machines
        assertEquals("~/", fieldTextFor("/home/dev", "/home/dev"))
        assertEquals("~/a b/", fieldTextFor("/home/dev/a b", "/home/dev"))
        assertEquals("/", fieldTextFor("/", "/home/dev"))
        assertEquals("/etc/", fieldTextFor("/etc", ""))
    }

    @Test
    fun `the typed path comes first, then the entries sorted without regard to case`() {
        val rows = pathRows(home, prefix = "", foldersOnly = false)
        assertEquals(listOf("~", "apps", "notes.txt", "Work", "work", "Zed"), rows.map { it.label })
        assertEquals(PathRow("~", "/home/dev", PathRow.Kind.Typed, isFolder = true), rows[0])
        assertEquals(PathRow("apps", "/home/dev/apps", PathRow.Kind.Folder), rows[1])
        assertEquals(PathRow("notes.txt", "/home/dev/notes.txt", PathRow.Kind.File), rows[2])
        assertTrue(rows[1].isFolder)
        assertFalse(rows[2].isFolder)
    }

    @Test
    fun `names that can't be typed safely are never offered`() {
        val all = pathRows(home, "", foldersOnly = false) + pathRows(home, ".", foldersOnly = false) + pathRows(home, "e", foldersOnly = false) + pathRows(home, "a", foldersOnly = false)
        for (row in all) {
            assertFalse(hasRefusedCharacter(row.path), row.path)
            assertNotNull(quotePath(row.path))
        }
        assertEquals(listOf("~/e"), pathRows(home, "e", foldersOnly = false).map { it.label }) // not the disguised one
        assertEquals(listOf("~/a", "apps"), pathRows(home, "a", foldersOnly = false).map { it.label }) // not the one with a newline
        assertFalse(all.any { it.label == "x/y" || it.label == ".." })
    }

    @Test
    fun `hidden entries show once a dot is typed`() {
        assertEquals(listOf("~/.", ".config"), pathRows(home, ".", foldersOnly = false).map { it.label })
        assertEquals(listOf("~/.c", ".config"), pathRows(home, ".c", foldersOnly = false).map { it.label })
    }

    @Test
    fun `what is typed after the slash narrows the entries, whatever its case`() {
        val rows = pathRows(home, "wo", foldersOnly = false)
        assertEquals(listOf("~/wo", "Work", "work"), rows.map { it.label })
        assertEquals("/home/dev/wo", rows[0].path)
        assertFalse(rows[0].isFolder) // a part of a name isn't known to be a folder
        assertEquals(listOf("~/Z", "Zed"), pathRows(home, "Z", foldersOnly = false).map { it.label })
    }

    @Test
    fun `go to directory offers folders only`() {
        assertEquals(listOf("~", "apps", "Work", "work"), pathRows(home, "", foldersOnly = true).map { it.label })
        assertEquals(listOf("~/Z"), pathRows(home, "Z", foldersOnly = true).map { it.label })
    }

    @Test
    fun `entries of the root directory have one slash`() {
        val root = listing("""{"path":"/","home":"/root","status":"ok","truncated":true,"entries":[{"name":"etc"}]}""")
        assertTrue(root.truncated)
        assertEquals(listOf("/" to "/", "etc" to "/etc"), pathRows(root, "", foldersOnly = false).map { it.label to it.path })
        assertEquals("/e", pathRows(root, "e", foldersOnly = false)[0].path)
    }

    @Test
    fun `a listing that failed says why`() {
        fun status(status: String, path: String = "/x") = listingRefusal(listing("""{"path":"$path","home":"","status":"$status","entries":null,"truncated":false}"""))
        assertNull(status("ok"))
        assertEquals("That folder doesn't exist.", status("missing"))
        assertEquals("That path isn't a folder.", status("not_dir"))
        assertEquals("Rex isn't allowed to read that folder.", status("denied"))
        assertNotNull(status("something new"))
        // A path that couldn't be typed is not built on.
        assertNotNull(status("ok", path = "relative"))
        assertNotNull(listingRefusal(DirectoryListing("/a\nb", "", "ok", emptyList(), false)))
        assertEquals("This server can't list folders. Update Rex on it.", listingFailure(RexError("method_not_found", "no such method", 404)))
        assertEquals("Not connected to the server.", listingFailure(RexDisconnected("gone")))
        assertNotNull(listingFailure(RexError("internal", "boom", 500)))
    }

    @Test
    fun `a path too long to exist is not asked about`() {
        assertNull(pathFieldRefusal("~/dev/"))
        assertNull(pathFieldRefusal("a".repeat(PATH_MAX_BYTES)))
        assertEquals("That path is too long.", pathFieldRefusal("a".repeat(PATH_MAX_BYTES + 1)))
        assertEquals("That path is too long.", pathFieldRefusal("雪".repeat(PATH_MAX_BYTES / 3 + 1))) // counted in bytes
    }

    @Test
    fun `a reply with nothing in it is an empty listing`() {
        val empty = listing("""{"path":"/x","home":"","status":"ok","entries":null,"truncated":false}""")
        assertEquals(emptyList(), empty.entries)
        assertEquals(listOf("/x"), pathRows(empty, "", foldersOnly = false).map { it.label })
    }
}
