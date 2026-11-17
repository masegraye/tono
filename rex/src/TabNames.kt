package org.example.kotlintest.rex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/*
 * What a tab is called.
 *
 * The server stores one thing: a window's label. Until someone names the window, that label
 * is a placeholder the server makes from the window's ID, such as "Window 89abcdef". Showing
 * something better in its place is each client's own business: this one shows the program in
 * the foreground of the tab's focused terminal and the directory it is in, which the server
 * reports and keeps current.
 * A name someone gave the window is a label like any other, so it shows on every client and
 * stays until it is cleared.
 */

/**
 * Whether a window was never given a name: its label is missing, is its ID, or is the
 * placeholder the server makes from the last eight characters of its ID. The placeholder is
 * matched against this window's own ID, so a window someone really did name "Window 12345678"
 * keeps that name.
 */
fun WindowView.hasDefaultLabel(): Boolean {
    if (label.isBlank() || label == id) return true
    val suffix = label.removePrefix(DEFAULT_LABEL_PREFIX)
    return suffix !== label && suffix.length == DEFAULT_LABEL_ID_LENGTH && id.endsWith(suffix)
}

/**
 * What a tab is called.
 *
 * @param directory where its program is, such as `~/dev/rex`, shown quieter beside the name.
 *   Only a tab called by its program has one: a name someone gave says what they wanted said.
 */
data class TabName(val text: String, val directory: String? = null)

/**
 * The program in a terminal's foreground: the shell at its prompt, or the command it is running.
 *
 * @param cwd its working directory, when the server can read it
 * @param home the home directory of the terminal's user, for showing [cwd] with `~`
 */
data class ForegroundProgram(val name: String, val cwd: String? = null, val home: String? = null) {
    /** Where the program is, as a tab shows it. */
    val directory: String? get() = cwd?.let { shortDirectory(it, home) }
}

/**
 * Reads the foreground program from a `process` reply or a `process_changed` event. Null
 * when it names no program.
 *
 * The directory is the program's own, or when that can't be read (another user's process,
 * as under sudo), that of the nearest process it runs under. An event may leave the home
 * directory out; it is the terminal's, so what was [known] before still holds.
 */
fun foregroundProgram(process: JsonObject?, known: ForegroundProgram? = null): ForegroundProgram? {
    val name = programName(process) ?: return null
    val chain = listOfNotNull(process?.obj("foreground")) +
        (process?.get("ancestors") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject } +
        listOfNotNull(process?.obj("child"))
    fun path(value: String?) = value?.takeIf { it.startsWith("/") }
    return ForegroundProgram(name, cwd = chain.firstNotNullOfOrNull { path(it.string("cwd")) }, home = path(process?.string("home")) ?: known?.home)
}

/**
 * A directory as a tab shows it: the home directory as `~`, and of a long path only as many
 * of its last folders as fit in [room] characters, after `…/`. The last folder always shows,
 * however long: it is the part worth reading, and the tab cuts it short if it must.
 */
fun shortDirectory(cwd: String, home: String?, room: Int = DIRECTORY_ROOM): String {
    val base = home?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    val dir = when {
        base == null -> cwd
        cwd == base -> "~"
        cwd.startsWith("$base/") -> "~" + cwd.removePrefix(base)
        else -> cwd
    }
    if (dir.length <= room) return dir
    val kept = ArrayDeque<String>()
    var used = 1
    for (folder in dir.split('/').filter { it.isNotEmpty() }.asReversed()) {
        used += 1 + folder.length
        if (kept.isNotEmpty() && used > room) break
        kept.addFirst(folder)
    }
    return "…/" + kept.joinToString("/")
}

/**
 * The name to show for the program in a terminal's foreground, from a `process` reply or a
 * `process_changed` event: the shell at its prompt, or the command it is running. Following
 * the server's advice, it is the file name of the first of `script`, `argv0` and `name` that
 * is there, so a script is called by its own name and not its interpreter's. A login shell's
 * leading dash is dropped.
 */
fun programName(process: JsonObject?): String? {
    val foreground = process?.obj("foreground") ?: process?.obj("child") ?: return null
    return sequenceOf("script", "argv0", "name")
        .mapNotNull { foreground.string(it) }
        .firstOrNull { it.isNotEmpty() }
        ?.substringAfterLast('/')
        ?.trimStart('-')
        ?.takeIf { it.isNotEmpty() }
}

/** The most characters a tab gives a directory. The sidebar is narrow, and the program's name comes first. */
private const val DIRECTORY_ROOM = 18

private const val DEFAULT_LABEL_PREFIX = "Window "
private const val DEFAULT_LABEL_ID_LENGTH = 8
