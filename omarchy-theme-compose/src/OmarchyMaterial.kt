package dev.mg.omarchy.theme.compose

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
import dev.mg.omarchy.theme.OmarchyPalette
import dev.mg.omarchy.theme.isHexColor

// Turns an Omarchy palette into the pieces of a Material 3 theme. Most apps only need
// OmarchyTheme (see OmarchyTheme.kt); these are here for apps that assemble their own.

/** Parses `#rrggbb` into an opaque Compose [Color]. */
fun hexToColor(hex: String): Color = Color(("ff" + hex.removePrefix("#")).toLong(16))

/**
 * What to use when a theme leaves a color out. Every stock Omarchy theme defines all of
 * these, but a hand-written theme only has to define background, foreground and accent.
 */
private val COLOR_FALLBACKS = mapOf(
    "selection" to "lighter_background",
    "muted" to "dark_foreground",
    "lighter_background" to "background",
    "dark_background" to "background",
    "darker_background" to "dark_background",
    "dark_foreground" to "foreground",
    "light_foreground" to "foreground",
    "bright_foreground" to "foreground",
    "red" to "accent",
    "green" to "accent",
    "blue" to "accent",
    "magenta" to "blue",
)

/** The palette's color named [key], following [COLOR_FALLBACKS] if the theme omits it. */
fun OmarchyPalette.color(key: String): Color {
    var name: String? = key
    while (name != null) {
        colors[name]?.takeIf(::isHexColor)?.let { return hexToColor(it) }
        name = COLOR_FALLBACKS[name]
    }
    return Color.Magenta // unknown name: make the mistake visible
}

/** The font family for an installed font, where the platform can load one by name. */
expect fun systemFontFamily(name: String): FontFamily?

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
 * Omarchy's visual language leans into rounded corners, so every component gets
 * more generous corner radii than Material's defaults.
 */
fun omarchyShapes(): Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Material's type scale set in the Omarchy font, so an app's text matches the desktop's
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
