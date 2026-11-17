package org.example.kotlintest

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Omarchy theme integration.
 *
 * Omarchy (the Linux desktop this app is developed on) stores its active theme in
 * `~/.local/state/omarchy/current/theme.name` (a slug like `retro-82`), and every theme
 * ships a machine-readable `colors.toml` with a full palette.
 *
 * On the desktop target the app reads that file and builds a Material 3 [ColorScheme]
 * from it, so the app looks native to the active Omarchy theme. On other platforms
 * (or non-Omarchy machines) [readOmarchyPalette] returns null and the app falls back
 * to one of the bundled palettes below.
 */
data class OmarchyPalette(
    val slug: String,
    val mode: String,
    val raw: Map<String, String>,
) {
    val displayName: String
        get() = slug.split('-').joinToString(" ") { part ->
            part.replaceFirstChar { it.uppercase() }
        }

    val isDark: Boolean get() = mode != "light"

    /** Every color key in the palette, in colors.toml order, for swatch displays. */
    val colorKeys: List<String> get() = raw.filterValues { isHexColor(it) }.keys.toList()

    /** The color for [key]; a loud magenta if the theme doesn't define it. */
    fun color(key: String): Color =
        raw[key]?.takeIf { isHexColor(it) }?.let(::hexToColor) ?: Color.Magenta
}

private val HEX_COLOR = Regex("^#?[0-9a-fA-F]{6}$")

internal fun isHexColor(value: String): Boolean = HEX_COLOR.matches(value)

/** Parse `#rrggbb` into a Compose [Color] (opaque alpha). */
fun hexToColor(hex: String): Color {
    val rgb = hex.removePrefix("#").lowercase()
    val argb = ("ff$rgb").toLong(16)
    return Color(argb)
}

/** Minimal parser for Omarchy's flat `key = "#hex"` colors.toml format. */
internal fun parseColorsToml(text: String): Map<String, String> =
    text.lineSequence().mapNotNull { line ->
        Regex("""^\s*([a-z_]+)\s*=\s*"([^"]*)"\s*$""")
            .find(line)?.let { it.groupValues[1] to it.groupValues[2] }
    }.toMap()

internal fun omarchyPaletteFromToml(slug: String, text: String): OmarchyPalette? {
    val raw = parseColorsToml(text)
    if (!raw.containsKey("background") || !raw.containsKey("accent")) return null
    return OmarchyPalette(slug = slug, mode = raw["mode"] ?: "dark", raw = raw)
}

/** Reads the active Omarchy palette, or null when not running on an Omarchy desktop. */
expect fun readOmarchyPalette(): OmarchyPalette?

/**
 * The desktop's Omarchy font. Omarchy uses one monospace font for its whole shell (bar,
 * menus, terminals), and fontconfig's `monospace` alias is its source of truth.
 */
class OmarchyFont(val name: String, val family: FontFamily)

/** Reads the active Omarchy font, or null when not running on an Omarchy desktop. */
expect fun readOmarchyFont(): OmarchyFont?

/**
 * Suspends until the desktop's Omarchy theme or font changes, then returns. Never returns
 * on platforms without Omarchy.
 */
expect suspend fun awaitOmarchyAppearanceChange()

/** Theme slugs installed on this machine (empty except on desktop). */
expect fun listOmarchyThemeSlugs(): List<String>

/**
 * Bundled fallback palettes, embedded from Omarchy's stock themes so the app still
 * feels Omarchy-native on Android, iOS and web. `retro-82` matches the dev machine.
 */
val bundledOmarchyPalettes: List<OmarchyPalette> by lazy {
    listOfNotNull(
        omarchyPaletteFromToml("retro-82", RETRO_82_TOML),
        omarchyPaletteFromToml("catppuccin", CATPPUCCIN_TOML),
        omarchyPaletteFromToml("catppuccin-latte", CATPPUCCIN_LATTE_TOML),
        omarchyPaletteFromToml("tokyo-night", TOKYO_NIGHT_TOML),
    )
}

/**
 * Maps an Omarchy palette onto Material 3 color roles. The same mapping works for
 * dark and light themes because Omarchy's colors.toml is mode-aware.
 */
fun omarchyColorScheme(p: OmarchyPalette, dark: Boolean = p.isDark): ColorScheme {
    fun c(key: String) = p.color(key)
    val common = mapOf(
        "primary" to c("accent"),
        "onPrimary" to c("darker_background"),
        "primaryContainer" to c("muted"),
        "onPrimaryContainer" to c("bright_foreground"),
        "secondary" to c("blue"),
        "onSecondary" to c("darker_background"),
        "secondaryContainer" to c("selection"),
        "onSecondaryContainer" to c("bright_foreground"),
        "tertiary" to c("magenta"),
        "onTertiary" to c("darker_background"),
        "tertiaryContainer" to c("green"),
        "onTertiaryContainer" to c("darker_background"),
        "background" to c("background"),
        "onBackground" to c("foreground"),
        "surface" to c("background"),
        "onSurface" to c("foreground"),
        "surfaceVariant" to c("lighter_background"),
        "onSurfaceVariant" to c("light_foreground"),
        "surfaceContainerLowest" to c("darker_background"),
        "surfaceContainerLow" to c("dark_background"),
        "surfaceContainer" to c("background"),
        "surfaceContainerHigh" to c("lighter_background"),
        "surfaceContainerHighest" to c("lighter_background"),
        "surfaceTint" to c("accent"),
        "inverseSurface" to c("foreground"),
        "inverseOnSurface" to c("background"),
        "error" to c("red"),
        "onError" to c("darker_background"),
        "errorContainer" to c("red"),
        "onErrorContainer" to c("darker_background"),
        "outline" to c("dark_foreground"),
        "outlineVariant" to c("muted"),
        "scrim" to c("darker_background"),
    )
    fun role(name: String): Color = common.getValue(name)
    // Build via darkColorScheme/lightColorScheme so unspecified roles get sane defaults.
    return if (dark) {
        darkColorScheme(
            primary = role("primary"), onPrimary = role("onPrimary"),
            primaryContainer = role("primaryContainer"), onPrimaryContainer = role("onPrimaryContainer"),
            secondary = role("secondary"), onSecondary = role("onSecondary"),
            secondaryContainer = role("secondaryContainer"), onSecondaryContainer = role("onSecondaryContainer"),
            tertiary = role("tertiary"), onTertiary = role("onTertiary"),
            tertiaryContainer = role("tertiaryContainer"), onTertiaryContainer = role("onTertiaryContainer"),
            background = role("background"), onBackground = role("onBackground"),
            surface = role("surface"), onSurface = role("onSurface"),
            surfaceVariant = role("surfaceVariant"), onSurfaceVariant = role("onSurfaceVariant"),
            surfaceContainerLowest = role("surfaceContainerLowest"),
            surfaceContainerLow = role("surfaceContainerLow"),
            surfaceContainer = role("surfaceContainer"),
            surfaceContainerHigh = role("surfaceContainerHigh"),
            surfaceContainerHighest = role("surfaceContainerHighest"),
            surfaceTint = role("surfaceTint"),
            inverseSurface = role("inverseSurface"), inverseOnSurface = role("inverseOnSurface"),
            error = role("error"), onError = role("onError"),
            errorContainer = role("errorContainer"), onErrorContainer = role("onErrorContainer"),
            outline = role("outline"), outlineVariant = role("outlineVariant"),
            scrim = role("scrim"),
        )
    } else {
        lightColorScheme(
            primary = role("primary"), onPrimary = role("onPrimary"),
            primaryContainer = role("primaryContainer"), onPrimaryContainer = role("onPrimaryContainer"),
            secondary = role("secondary"), onSecondary = role("onSecondary"),
            secondaryContainer = role("secondaryContainer"), onSecondaryContainer = role("onSecondaryContainer"),
            tertiary = role("tertiary"), onTertiary = role("onTertiary"),
            tertiaryContainer = role("tertiaryContainer"), onTertiaryContainer = role("onTertiaryContainer"),
            background = role("background"), onBackground = role("onBackground"),
            surface = role("surface"), onSurface = role("onSurface"),
            surfaceVariant = role("surfaceVariant"), onSurfaceVariant = role("onSurfaceVariant"),
            surfaceContainerLowest = role("surfaceContainerLowest"),
            surfaceContainerLow = role("surfaceContainerLow"),
            surfaceContainer = role("surfaceContainer"),
            surfaceContainerHigh = role("surfaceContainerHigh"),
            surfaceContainerHighest = role("surfaceContainerHighest"),
            surfaceTint = role("surfaceTint"),
            inverseSurface = role("inverseSurface"), inverseOnSurface = role("inverseOnSurface"),
            error = role("error"), onError = role("onError"),
            errorContainer = role("errorContainer"), onErrorContainer = role("onErrorContainer"),
            outline = role("outline"), outlineVariant = role("outlineVariant"),
            scrim = role("scrim"),
        )
    }
}

/**
 * Omarchy's visual language leans into rounded corners, so give every component
 * generous corner radii rather than Material's defaults.
 */
fun omarchyShapes(): Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Material's type scale set in the Omarchy font, so the app's text matches the desktop's
 * bar and menus. With a null [family] this is the stock Material typography.
 */
fun omarchyTypography(family: FontFamily?): Typography {
    val base = Typography()
    if (family == null) return base
    // Material's letter spacing is tuned for a proportional font; a monospace font
    // already has even spacing, and the extra tracking makes it look loose.
    fun TextStyle.inOmarchyFont() = copy(fontFamily = family, letterSpacing = 0.sp)
    return base.copy(
        displayLarge = base.displayLarge.inOmarchyFont(),
        displayMedium = base.displayMedium.inOmarchyFont(),
        displaySmall = base.displaySmall.inOmarchyFont(),
        headlineLarge = base.headlineLarge.inOmarchyFont(),
        headlineMedium = base.headlineMedium.inOmarchyFont(),
        headlineSmall = base.headlineSmall.inOmarchyFont(),
        titleLarge = base.titleLarge.inOmarchyFont(),
        titleMedium = base.titleMedium.inOmarchyFont(),
        titleSmall = base.titleSmall.inOmarchyFont(),
        bodyLarge = base.bodyLarge.inOmarchyFont(),
        bodyMedium = base.bodyMedium.inOmarchyFont(),
        bodySmall = base.bodySmall.inOmarchyFont(),
        labelLarge = base.labelLarge.inOmarchyFont(),
        labelMedium = base.labelMedium.inOmarchyFont(),
        labelSmall = base.labelSmall.inOmarchyFont(),
    )
}

// ---------------------------------------------------------------------------------------------
// Stock Omarchy palettes (verbatim from /usr/share/omarchy/themes/<slug>/colors.toml)
// ---------------------------------------------------------------------------------------------

private val RETRO_82_TOML = """
mode = "dark"

accent = "#faa968"
selection = "#134e5a"
muted = "#2a6b78"

background = "#05182e"
dark_background = "#031222"
darker_background = "#020c17"
lighter_background = "#0a2540"

foreground = "#f6dcac"
dark_foreground = "#3f8f8a"
light_foreground = "#a7c9c6"
bright_foreground = "#f6dcac"

red = "#f85525"
yellow = "#e97b3c"
orange = "#faa968"
green = "#028391"
cyan = "#8cbfb8"
blue = "#3f8f8a"
magenta = "#3f8f8a"
brown = "#743d1e"
""".trim()

private val CATPPUCCIN_TOML = """
mode = "dark"

accent = "#89b4fa"
selection = "#45475a"
muted = "#585b70"

background = "#1e1e2e"
dark_background = "#161622"
darker_background = "#101019"
lighter_background = "#313244"

foreground = "#cdd6f4"
dark_foreground = "#6c7086"
light_foreground = "#bac2de"
bright_foreground = "#cdd6f4"

red = "#f38ba8"
yellow = "#f9e2af"
orange = "#f6b6ab"
green = "#a6e3a1"
cyan = "#94e2d5"
blue = "#89b4fa"
magenta = "#f5c2e7"
brown = "#7b5b55"
""".trim()

private val CATPPUCCIN_LATTE_TOML = """
mode = "light"

accent = "#1e66f5"
selection = "#ccd0da"
muted = "#acb0be"

background = "#eff1f5"
dark_background = "#e3e4e8"
darker_background = "#d7d8dc"
lighter_background = "#dce0e8"

foreground = "#4c4f69"
dark_foreground = "#9ca0b0"
light_foreground = "#5c5f77"
bright_foreground = "#4c4f69"

red = "#d20f39"
yellow = "#df8e1d"
orange = "#d84e2b"
green = "#40a02b"
cyan = "#179299"
blue = "#1e66f5"
magenta = "#ea76cb"
brown = "#6c2715"
""".trim()

private val TOKYO_NIGHT_TOML = """
mode = "dark"

accent = "#7aa2f7"
selection = "#292e42"
muted = "#414868"

background = "#1a1b26"
dark_background = "#13141c"
darker_background = "#0e0e14"
lighter_background = "#24283b"

foreground = "#a9b1d6"
dark_foreground = "#565f89"
light_foreground = "#b4bee6"
bright_foreground = "#c0caf5"

red = "#f7768e"
yellow = "#e0af68"
orange = "#eb927b"
green = "#9ece6a"
cyan = "#449dab"
blue = "#7aa2f7"
magenta = "#ad8ee6"
brown = "#75493d"
""".trim()
