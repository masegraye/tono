package org.example.kotlintest.rex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Insert Path and Go to Directory: browsing the files and folders of a terminal's machine, and
 * turning the one chosen into text for its shell. The rules are Rex Rune's (its
 * `docs/terminal.md` and `src/app/directory.rs`), with one addition to the quoting, said there.
 * Nothing here draws or connects; the browser itself is in the app.
 */

/** The longest path, in bytes, that is asked about or typed. No real path is longer, and a pasted megabyte must not become a request. */
const val PATH_MAX_BYTES = 4096

/**
 * Whether a character keeps a path from being listed or typed: the control characters
 * (a newline in a name would run what is before it), the line and paragraph separators, and
 * the invisible formatting characters that can disguise a name, such as a right-to-left
 * override (`invoice<RLO>fdp.sh` reads as `invoicehs.pdf`). Half of a split surrogate pair
 * is refused too, as it can't be sent.
 *
 * The zero-width joiner and non-joiner are let through, since scripts and emoji need them.
 * Letters that merely look like others are not detected.
 */
fun isRefusedInPath(codePoint: Int): Boolean = when (codePoint) {
    in 0x00..0x1F, in 0x7F..0x9F -> true // C0, DEL and C1
    in 0xD800..0xDFFF -> true // a surrogate on its own
    0x00AD, 0x034F, 0x061C, 0x115F, 0x1160, 0x180E, 0x3164, 0xFFA0, 0x200B, 0x200E, 0x200F, 0xFEFF -> true
    in 0x2028..0x202E, in 0x2060..0x2064, in 0x2066..0x206F, in 0xFFF9..0xFFFB, in 0xE0000..0xE007F -> true
    else -> false
}

/** Whether [text] has a character that [isRefusedInPath]. */
fun hasRefusedCharacter(text: String): Boolean {
    var index = 0
    while (index < text.length) {
        val char = text[index]
        val next = text.getOrNull(index + 1)
        if (char.isHighSurrogate() && next != null && next.isLowSurrogate()) {
            if (isRefusedInPath(0x10000 + ((char.code - 0xD800) shl 10) + (next.code - 0xDC00))) return true
            index += 2
        } else {
            if (isRefusedInPath(char.code)) return true
            index++
        }
    }
    return false
}

/**
 * A path as one word for a shell, or null if it has a character that is refused.
 *
 * A path of only ASCII letters, digits, `/`, `_`, `-` and `.` goes as it is. Anything else is
 * put in single quotes, where a POSIX shell reads every character as itself: spaces, `$`,
 * backticks, `*`, `!`, `~` and the rest. A single quote can't be inside them, so it is
 * written `\'` between two quoted stretches, as Rex Rune writes it.
 *
 * A backslash is written `\\`, outside the quotes too. Rune leaves it inside, which sh, bash
 * and zsh read rightly; fish reads `\\` and `\'` inside single quotes as one character, so a
 * name with two backslashes together, or one before a quote, would come out wrong there.
 * Outside the quotes all four agree. csh, which looks for `!` even in single quotes, is not
 * provided for.
 *
 * A name starting with `-` gets `./` in front, so that no command takes it for an option.
 * The empty path is `''`.
 */
fun quotePath(path: String): String? {
    if (hasRefusedCharacter(path)) return null
    val word = if (path.startsWith('-')) "./$path" else path
    if (word.isNotEmpty() && word.all { it in SAFE_UNQUOTED }) return word
    if (word.isEmpty()) return "''"
    val quoted = StringBuilder()
    var open = false
    for (char in word) {
        if (char == '\'' || char == '\\') {
            if (open) quoted.append('\'')
            open = false
            quoted.append('\\').append(char)
        } else {
            if (!open) quoted.append('\'')
            open = true
            quoted.append(char)
        }
    }
    if (open) quoted.append('\'')
    return quoted.toString()
}

private val SAFE_UNQUOTED: Set<Char> = (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('/', '_', '-', '.')).toSet()

/** What Insert Path types for a path: the path quoted, with no space or Enter after it. Null if it is refused. */
fun insertPathText(path: String): String? = quotePath(path)

/**
 * What Go to Directory types for a folder: `cd` and the folder quoted, and no Enter. The app
 * can't see what the prompt already holds, so running the line is left to the person.
 * Null if the path is refused.
 */
fun goToDirectoryText(path: String): String? = quotePath(path)?.let { "cd $it" }

/** What is typed in the browser's field, taken apart: the directory to list, and the start of the names wanted in it. */
data class PathQuery(val directory: String, val prefix: String)

/** The part after the last slash filters the directory before it. A slash at the end lists the directory itself, as does `~` alone. */
fun splitPath(text: String): PathQuery {
    if (text == "~") return PathQuery("~", "")
    val slash = text.lastIndexOf('/')
    return if (slash < 0) PathQuery("", text) else PathQuery(text.substring(0, slash + 1), text.substring(slash + 1))
}

/** A path as the browser shows it, with `~` for the home directory. What is typed into the terminal is always the full path. */
fun shownPath(path: String, home: String): String {
    val base = home.trimEnd('/')
    return when {
        home.isEmpty() || base.isEmpty() -> path
        path == home || path == base -> "~"
        path.startsWith("$base/") -> "~/" + path.substring(base.length + 1)
        else -> path
    }
}

/** What goes in the field to list the inside of a folder. */
fun fieldTextFor(directory: String, home: String): String = shownPath(directory, home).trimEnd('/') + "/"

/** One entry of a directory, as the server lists it. */
data class DirectoryEntry(val name: String, val isFile: Boolean = false)

/** The server's answer to `com.superlogical.terminal.list_dir`. */
data class DirectoryListing(
    /** The directory that was listed, as an absolute path. */
    val path: String,
    /** The home directory of the terminal's program, or empty if the server can't tell. */
    val home: String,
    /** `ok`, `missing`, `not_dir` or `denied`. */
    val status: String,
    val entries: List<DirectoryEntry>,
    /** Whether there were more entries than the server sent. */
    val truncated: Boolean,
) {
    companion object {
        fun parse(reply: JsonObject): DirectoryListing = DirectoryListing(
            path = reply.string("path").orEmpty(),
            home = reply.string("home").orEmpty(),
            status = reply.string("status").orEmpty(),
            entries = (reply["entries"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { entry ->
                DirectoryEntry(entry.string("name") ?: return@mapNotNull null, isFile = (entry["file"] as? JsonPrimitive)?.booleanOrNull == true)
            },
            truncated = (reply["truncated"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }
}

/** A row of the browser: something that can be chosen. */
data class PathRow(
    /** What the row shows: an entry's name, or for the typed path the path itself. */
    val label: String,
    /** The full path, which is what gets typed. */
    val path: String,
    val kind: Kind,
    /** Whether Tab can go into it: a folder the server listed, or the listed folder itself. */
    val isFolder: Boolean = kind == Kind.Folder,
) {
    enum class Kind {
        /** The path as typed in the field. It may be a folder, a file, or nothing that exists. */
        Typed,
        Folder,
        File,
    }
}

/**
 * The rows for a listing, given the [prefix] that was typed after the last slash.
 *
 * First comes the typed path itself. Then the entries whose names start with the prefix,
 * whatever their case, sorted without regard to case. Names starting with a dot are listed
 * only once a dot is typed. Names that couldn't be typed safely are left out: see
 * [isRefusedInPath]. With [foldersOnly], files are left out too, even if the server sent them.
 */
fun pathRows(listing: DirectoryListing, prefix: String, foldersOnly: Boolean): List<PathRow> {
    val typed = joinPath(listing.path, prefix)
    val first = PathRow(shownPath(typed, listing.home), typed, PathRow.Kind.Typed, isFolder = prefix.isEmpty())
    val start = prefix.lowercase()
    val entries = listing.entries
        .filter { entry ->
            entry.name.isNotEmpty() && entry.name != "." && entry.name != ".." && '/' !in entry.name &&
                !hasRefusedCharacter(entry.name) &&
                (!entry.name.startsWith('.') || prefix.startsWith('.')) &&
                entry.name.lowercase().startsWith(start) &&
                !(foldersOnly && entry.isFile)
        }
        .sortedWith(compareBy<DirectoryEntry> { it.name.lowercase() }.thenBy { it.name })
    return listOf(first) + entries.map { PathRow(it.name, joinPath(listing.path, it.name), if (it.isFile) PathRow.Kind.File else PathRow.Kind.Folder) }
}

private fun joinPath(directory: String, name: String): String = if (name.isEmpty()) directory else directory.trimEnd('/') + "/" + name

/** Why the text in the field can't be asked about, or null if it can. */
fun pathFieldRefusal(text: String): String? = when {
    hasRefusedCharacter(text) -> REFUSED_CHARACTERS
    text.encodeToByteArray().size > PATH_MAX_BYTES -> "That path is too long."
    else -> null
}

/** Why a listing has nothing to choose from, or null if it has. */
fun listingRefusal(listing: DirectoryListing): String? = when (listing.status) {
    "ok" -> if (!listing.path.startsWith('/') || hasRefusedCharacter(listing.path)) "Rex returned a path that can't be typed." else null
    "missing" -> "That folder doesn't exist."
    "not_dir" -> "That path isn't a folder."
    "denied" -> "Rex isn't allowed to read that folder."
    else -> COULD_NOT_LIST
}

/** What to say when asking for a listing failed. */
fun listingFailure(failure: Exception): String = when {
    failure is RexError && failure.code in setOf("method_not_found", "revision_too_low", "not_supported") ->
        "This server can't list folders. Update Rex on it."
    failure is RexDisconnected -> "Not connected to the server."
    else -> COULD_NOT_LIST
}

const val REFUSED_CHARACTERS = "Paths with control or invisible formatting characters can't be typed."
const val LISTING_TIMED_OUT = "The server took too long to list that folder."
const val TRUNCATED_LISTING = "Some entries aren't shown. Type more of the name."
private const val COULD_NOT_LIST = "The folder couldn't be listed."

/** How long a listing may take. A slow filesystem on the other end shouldn't leave the browser waiting for ever. */
const val LISTING_TIMEOUT_MILLIS = 8_000L

/**
 * Asks the machine a terminal runs on for what is typed in the browser's field: the
 * directory before the last slash, narrowed to the names starting with what comes after it.
 * An empty field lists the working directory of the terminal's foreground program, which is
 * the server's to say. Reading a directory changes nothing and doesn't claim the terminal.
 *
 * @param files whether to list files as well as folders
 */
suspend fun RexHost.listDirectory(sessionId: String, blockId: String, text: String, files: Boolean): DirectoryListing {
    val query = splitPath(text)
    return DirectoryListing.parse(call("com.superlogical.terminal.list_dir", buildJsonObject {
        put("session_id", sessionId)
        put("block_id", blockId)
        put("args", buildJsonObject {
            put("path", query.directory)
            put("prefix", query.prefix)
            put("files", files)
        })
    }))
}
