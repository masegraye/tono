package dev.mg.omarchy.theme.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import dev.mg.omarchy.theme.OmarchyAppearance
import dev.mg.omarchy.theme.OmarchyPalette
import dev.mg.omarchy.theme.bundledOmarchyPalettes
import dev.mg.omarchy.theme.omarchyAppearanceFlow
import dev.mg.omarchy.theme.readOmarchyAppearance

/**
 * A Material 3 theme that looks like the Omarchy desktop and follows it: when the user
 * switches theme or font, everything inside restyles at runtime.
 *
 * ```
 * OmarchyTheme {
 *     MyApp()
 * }
 * ```
 *
 * Pass your own [state] (from [rememberOmarchyThemeState]) to read what is being shown, or
 * to let the user preview another palette or flip between dark and light.
 */
@Composable
fun OmarchyTheme(
    state: OmarchyThemeState = rememberOmarchyThemeState(),
    content: @Composable () -> Unit,
) {
    val typography = remember(state.fontFamily) { omarchyTypography(state.fontFamily) }
    MaterialTheme(
        colorScheme = omarchyColorScheme(state.palette, state.isDark),
        shapes = omarchyShapes(),
        typography = typography,
        content = content,
    )
}

/**
 * Creates the state behind [OmarchyTheme] and keeps it in step with the desktop for as long
 * as it stays in the composition.
 *
 * @param fallback the palette to show where the desktop's theme cannot be known: Android,
 *   iOS, a web page with no server feeding it, or a desktop that isn't running Omarchy
 */
@Composable
fun rememberOmarchyThemeState(
    fallback: OmarchyPalette = bundledOmarchyPalettes.first(),
): OmarchyThemeState {
    val state = remember(fallback) { OmarchyThemeState(readOmarchyAppearance(), fallback) }
    LaunchedEffect(state) {
        omarchyAppearanceFlow().collect(state::desktopChanged)
    }
    return state
}

/** What [OmarchyTheme] is showing, and the two ways an app can override it. */
@Stable
class OmarchyThemeState internal constructor(
    initial: OmarchyAppearance?,
    private val fallback: OmarchyPalette,
) {
    /** How the desktop looks, or null while (or where) that is unknown. */
    var desktop: OmarchyAppearance? by mutableStateOf(initial)
        private set

    /**
     * A palette to show instead of the desktop's, for example from a theme picker.
     * The desktop always wins: this goes back to null when the desktop's theme changes.
     */
    var preview: OmarchyPalette? by mutableStateOf(null)

    /**
     * Forces the dark (true) or light (false) color scheme. Null uses the palette's own
     * mode. Like [preview], this goes back to null when the desktop's theme changes.
     */
    var darkOverride: Boolean? by mutableStateOf(null)

    /** The desktop's font, on platforms that can load it; null means the default font. */
    var fontFamily: FontFamily? by mutableStateOf(initial?.fontName?.let(::systemFontFamily))
        private set

    /** The palette in effect: the [preview], else the desktop's, else the fallback. */
    val palette: OmarchyPalette get() = preview ?: desktop?.palette ?: fallback

    val isDark: Boolean get() = darkOverride ?: palette.isDark

    val isPreviewing: Boolean get() = preview != null

    internal fun desktopChanged(latest: OmarchyAppearance) {
        if (latest == desktop) return
        if (latest.fontName != desktop?.fontName) {
            fontFamily = latest.fontName?.let(::systemFontFamily)
        }
        desktop = latest
        preview = null
        darkOverride = null
    }
}
