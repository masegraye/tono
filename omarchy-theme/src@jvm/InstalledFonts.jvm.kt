package dev.mg.omarchy.theme

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A monospace font family installed on this machine.
 *
 * @property family the name fontconfig knows it by, which is the name to load it with
 * @property weights how heavy its styles are, lightest first, as numbers from 100 to 900
 *   where 400 is regular and 700 bold
 */
data class InstalledFont(val family: String, val weights: List<Int>)

/**
 * The monospace fonts installed here that have Latin letters, by name, as fontconfig lists
 * them. Empty where there is no fontconfig. This is the list a terminal can choose a font
 * from; it runs `fc-list`, so ask off the thread that draws.
 */
fun readInstalledMonospaceFonts(): List<InstalledFont> =
    run("fc-list", ":lang=en", "-f", "%{family}\t%{spacing}\t%{weight}\n")?.let(::parseFontList).orEmpty()

/**
 * Reads `fc-list -f "%{family}\t%{spacing}\t%{weight}\n"`: one style of one font to a line,
 * with fontconfig's own numbers. A font's spacing is 100 for monospace and 110 for one made
 * of character cells, 90 for one with two widths (which is what some terminal fonts with
 * wide symbols say), and missing for everything else.
 */
internal fun parseFontList(text: String): List<InstalledFont> {
    val weights = LinkedHashMap<String, MutableSet<Int>>()
    for (line in text.lineSequence()) {
        val fields = line.split('\t')
        if (fields.size < 3) continue
        // A font may go by several names ("JetBrainsMono Nerd Font,JetBrainsMono NF"): the first is its own.
        val family = fields[0].substringBefore(',').trim().ifEmpty { null } ?: continue
        if (fields[1].trim().toIntOrNull() !in MONOSPACE_SPACINGS) continue
        val found = weights.getOrPut(family) { sortedSetOf() }
        // A font that varies gives the range it covers: "[50 200]".
        val numbers = Regex("""[0-9.]+""").findAll(fields[2]).mapNotNull { it.value.toDoubleOrNull() }.toList()
        when (numbers.size) {
            1 -> found += cssWeight(numbers[0])
            2 -> found += (cssWeight(numbers[0])..cssWeight(numbers[1]) step 100)
        }
    }
    return weights.map { (family, found) -> InstalledFont(family, found.toList().ifEmpty { listOf(400) }) }.sortedBy { it.family.lowercase() }
}

/**
 * The file of an installed font's style, for handing the font to something that can't load
 * it by name (a web page). Null if [family] isn't one of [readInstalledMonospaceFonts], or
 * its file is a collection of several fonts, which a page's text engine can't pick from.
 */
fun installedFontFile(family: String, weight: Int = 400, italic: Boolean = false): File? {
    if (readInstalledMonospaceFonts().none { it.family == family }) return null
    // In a pattern a backslash goes before what would otherwise end the name.
    val name = family.replace(Regex("""([\\:,\-=])"""), """\\$1""")
    val found = run("fc-match", "-f", "%{file}\t%{family}", "$name:weight=${fontconfigWeight(weight)}:slant=${if (italic) 100 else 0}") ?: return null
    val (path, families) = found.split('\t').let { it.first() to it.getOrNull(1).orEmpty() }
    // fontconfig always answers with something: make sure it is the font asked for.
    if (families.split(',').none { it.trim() == family }) return null
    return File(path).takeIf { it.isFile && it.extension.lowercase() in setOf("ttf", "otf") }
}

private val MONOSPACE_SPACINGS = setOf(90, 100, 110)

/** fontconfig's weights beside the ones everything else uses. */
private val WEIGHTS = listOf(0 to 100, 40 to 200, 50 to 300, 80 to 400, 100 to 500, 180 to 600, 200 to 700, 205 to 800, 210 to 900)

/** fontconfig's weight (80 for regular, 200 for bold) as the usual hundred (400, 700). */
internal fun cssWeight(fontconfig: Double): Int = WEIGHTS.minBy { kotlin.math.abs(it.first - fontconfig) }.second

internal fun fontconfigWeight(css: Int): Int = WEIGHTS.minBy { kotlin.math.abs(it.second - css) }.first

private fun run(vararg command: String): String? = runCatching {
    val process = ProcessBuilder(*command).redirectErrorStream(false).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
    output
}.getOrNull()
