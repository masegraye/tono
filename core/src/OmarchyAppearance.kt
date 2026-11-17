package org.example.kotlintest

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The colors of one Omarchy theme, as found in its `colors.toml`.
 *
 * Omarchy (https://omarchy.org) is the Linux desktop this app is developed on. Every theme
 * ships a `colors.toml` with a `mode` and a set of named `#rrggbb` colors; [colors] holds
 * those by name (`accent`, `background`, `foreground`, `red`, ...), in file order.
 */
data class OmarchyPalette(
    val slug: String,
    val mode: String,
    val colors: Map<String, String>,
) {
    /** "retro-82" -> "Retro 82" */
    val displayName: String
        get() = slug.split('-').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

    val isDark: Boolean get() = mode != "light"
}

/** How the desktop currently looks: the applied palette and the Omarchy font, if known. */
data class OmarchyAppearance(
    val palette: OmarchyPalette,
    val fontName: String?,
)

/**
 * The desktop's appearance right now, or null where it cannot be read synchronously
 * (Android, iOS, and web, which only learns it from [omarchyAppearanceFlow]).
 */
expect fun readOmarchyAppearance(): OmarchyAppearance?

/**
 * Emits the desktop's appearance, and again every time the user switches theme or font.
 *
 * - JVM: reads the files Omarchy writes and watches them for changes.
 * - Web: subscribes to the server module's `api/theme/events` stream.
 * - Android, iOS: never emits.
 */
expect fun omarchyAppearanceFlow(): Flow<OmarchyAppearance>

/** The palette of every theme installed on this machine (empty where that is unknown). */
expect fun readInstalledOmarchyPalettes(): List<OmarchyPalette>

private val HEX_COLOR = Regex("^#[0-9a-fA-F]{6}$")

fun isHexColor(value: String): Boolean = HEX_COLOR.matches(value)

/** Parses the flat `key = "value"` format of Omarchy's colors.toml. */
fun parseColorsToml(text: String): Map<String, String> =
    text.lineSequence().mapNotNull { line ->
        Regex("""^\s*([a-z_]+)\s*=\s*"([^"]*)"\s*$""")
            .find(line)?.let { it.groupValues[1] to it.groupValues[2] }
    }.toMap()

/** Builds a palette from the text of a colors.toml, or null if it isn't a usable theme. */
fun omarchyPaletteFromToml(slug: String, text: String): OmarchyPalette? {
    val entries = parseColorsToml(text)
    // Themes may carry other settings (e.g. hyprland_active_border); keep only the colors.
    return omarchyPalette(slug, entries["mode"], entries.filterValues(::isHexColor))
}

private fun omarchyPalette(slug: String, mode: String?, colors: Map<String, String>): OmarchyPalette? {
    val usable = listOf("background", "foreground", "accent").all { it in colors }
    return if (usable) OmarchyPalette(slug, mode ?: "dark", colors) else null
}

/**
 * The wire format the server sends to web clients:
 * `{"theme":"retro-82","mode":"dark","font":"JetBrainsMono Nerd Font","colors":{"accent":"#faa968",...}}`
 */
fun OmarchyAppearance.toJson(): String = buildJsonObject {
    put("theme", palette.slug)
    put("mode", palette.mode)
    fontName?.let { put("font", it) }
    putJsonObject("colors") {
        for ((name, hex) in palette.colors) put(name, hex)
    }
}.toString()

/** Parses [toJson]'s output; null if the text isn't a usable appearance. */
fun omarchyAppearanceFromJson(json: String): OmarchyAppearance? = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    val colors = root.getValue("colors").jsonObject
        .mapValues { (_, value) -> value.jsonPrimitive.content }
        .filterValues(::isHexColor)
    val palette = omarchyPalette(
        slug = root.getValue("theme").jsonPrimitive.content,
        mode = root["mode"]?.jsonPrimitive?.contentOrNull,
        colors = colors,
    ) ?: return null
    OmarchyAppearance(palette, root["font"]?.jsonPrimitive?.contentOrNull)
}.getOrNull()
