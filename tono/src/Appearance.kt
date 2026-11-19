package org.example.kotlintest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import dev.mg.omarchy.theme.compose.hexToColor
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import dev.mg.omarchy.theme.OmarchyAppearance
import dev.mg.omarchy.theme.OmarchyPalette
import dev.mg.omarchy.theme.bundledOmarchyPalettes
import dev.mg.omarchy.theme.compose.LocalOmarchyFont
import dev.mg.omarchy.theme.compose.omarchyColorScheme
import dev.mg.omarchy.theme.compose.omarchyShapes
import dev.mg.omarchy.theme.compose.omarchyTypography
import dev.mg.omarchy.theme.compose.systemFontFamily
import dev.mg.omarchy.theme.omarchyAppearanceFlow
import dev.mg.omarchy.theme.readOmarchyAppearance
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.example.kotlintest.rex.AppearanceChoice
import org.example.kotlintest.rex.RexClient
import org.example.kotlintest.tono.resources.Res
import org.example.kotlintest.tono.resources.jetbrains_mono_bold
import org.example.kotlintest.tono.resources.jetbrains_mono_bold_italic
import org.example.kotlintest.tono.resources.jetbrains_mono_italic
import org.example.kotlintest.tono.resources.jetbrains_mono_light
import org.example.kotlintest.tono.resources.jetbrains_mono_medium
import org.example.kotlintest.tono.resources.jetbrains_mono_regular
import androidx.compose.ui.text.font.Font
import org.jetbrains.compose.resources.FontResource

/*
 * How Tono looks, and who says.
 *
 * Tono follows the Omarchy desktop's theme and font, which is a difference from Rex.app
 * that is meant: on Omarchy an app that looks like the desktop, and changes with it, is the
 * point. Where there is no Omarchy (another Linux desktop, a page whose server has none,
 * later a phone) it has themes and a font of its own: Omarchy's stock themes, so that it
 * looks as it would there, and JetBrains Mono. And on Omarchy a person can still choose one
 * of those over the desktop's.
 *
 * What was chosen is the client's (RexClient.appearance), kept in the workspace. Nothing
 * chosen means: the desktop's where there is one, the defaults here where there isn't.
 * The theme colors everything; the font chosen is the terminals', and the rest of the app
 * stays in the desktop's font, or in the app's own where the desktop gives none.
 */

/** The theme where the desktop has none to follow and nobody has chosen one. */
internal const val DEFAULT_THEME = "tokyo-night"

/** The app's own font, as a choice of font family is written down. No installed font has a colon in its name. */
internal const val BUNDLED_FONT = "tono:jetbrains-mono"
internal const val BUNDLED_FONT_TITLE = "JetBrains Mono"

/** How heavy the app's own font comes, for text that isn't bold. */
internal val BUNDLED_FONT_WEIGHTS = listOf(300, 400, 500)

internal const val DEFAULT_FONT_WEIGHT = 400

/** What is said of a font that is loaded by name. */
internal expect val INSTALLED_FONTS_ARE: String

/** The app's own themes: Omarchy's stock ones, the dark before the light, each by name. */
internal val builtInThemes: List<OmarchyPalette> by lazy {
    bundledOmarchyPalettes.sortedWith(compareBy({ !it.isDark }, { it.displayName }))
}

/** The theme of that name, if the app has one. */
internal fun builtInTheme(slug: String?): OmarchyPalette? = slug?.let { name -> bundledOmarchyPalettes.firstOrNull { it.slug == name } }

/**
 * The palette to draw with: the theme chosen if the app has it, else the desktop's, else
 * the default. A name the app doesn't know (a theme from a later version, a typing mistake
 * in the file) counts as no choice.
 */
internal fun themeInEffect(chosen: String?, desktop: OmarchyPalette?): OmarchyPalette =
    builtInTheme(chosen) ?: desktop ?: builtInTheme(DEFAULT_THEME) ?: bundledOmarchyPalettes.first()

/**
 * A theme that can be chosen.
 *
 * @param slug what choosing it writes down: a theme's name, or null to follow the desktop
 * @param palette what it looks like, for a swatch and a preview
 * @param available false for following a desktop that has no theme to follow
 * @param current whether it is the one in effect
 */
internal class ThemeOption(val slug: String?, val title: String, val detail: String, val palette: OmarchyPalette, val available: Boolean, val current: Boolean)

internal const val FOLLOW_OMARCHY = "Follow Omarchy"

/** Every theme there is to choose: following the desktop first, then the app's own. */
internal fun themeOptions(chosen: String?, desktop: OmarchyPalette?): List<ThemeOption> = buildList {
    val own = builtInTheme(chosen)?.slug
    // With no desktop to follow, no choice is the default theme, and that is the one marked.
    val marked = own ?: if (desktop == null) themeInEffect(null, null).slug else null
    add(
        ThemeOption(
            slug = null,
            title = FOLLOW_OMARCHY,
            detail = if (desktop != null) "now ${desktop.displayName}" else "Omarchy isn't running here",
            palette = desktop ?: themeInEffect(null, null),
            available = desktop != null,
            current = desktop != null && own == null,
        ),
    )
    for (theme in builtInThemes) {
        val detail = (if (theme.isDark) "Dark" else "Light") + if (desktop == null && theme.slug == DEFAULT_THEME) " · the default" else ""
        add(ThemeOption(theme.slug, theme.displayName, detail, theme, available = true, current = theme.slug == marked))
    }
}

/** A monospace font the platform can give the terminals, and how heavy its styles are (400 is regular). */
data class TerminalFontInfo(val family: String, val weights: List<Int>)

/**
 * A font that can be chosen for the terminals.
 *
 * @param family what choosing it writes down: a family's name, [BUNDLED_FONT], or null for
 *   the desktop's own
 * @param weights how heavy its text that isn't bold can be
 */
internal class FontOption(val family: String?, val title: String, val detail: String, val weights: List<Int>, val current: Boolean)

/**
 * The font family in effect, as [AppearanceChoice.fontFamily] names them: the one chosen if
 * it can be had, else the desktop's (null) if the desktop gives one, else the app's own.
 *
 * @param desktopHasFont whether the desktop supplies a font, which is to say Omarchy is there
 * @param installed the fonts the platform can load by name, or null while that isn't known:
 *   until it is, a font chosen is taken to be there
 */
internal fun fontInEffect(chosen: String?, desktopHasFont: Boolean, installed: List<TerminalFontInfo>?): String? = when {
    chosen == BUNDLED_FONT -> BUNDLED_FONT
    chosen != null && (installed == null || installed.any { it.family == chosen }) -> chosen
    desktopHasFont -> null
    else -> BUNDLED_FONT
}

/**
 * Every font there is to choose for the terminals: the desktop's where it gives one, the
 * app's own, then the monospace fonts the platform has.
 *
 * @param desktopFont the name of the desktop's font, where that is known
 */
internal fun fontOptions(chosen: String?, desktopHasFont: Boolean, desktopFont: String?, installed: List<TerminalFontInfo>?): List<FontOption> = buildList {
    val current = fontInEffect(chosen, desktopHasFont, installed)
    if (desktopHasFont) {
        val weights = installed.orEmpty().firstOrNull { it.family == desktopFont }?.weights
        add(FontOption(null, "Omarchy's Font", desktopFont?.let { "now $it" } ?: "the desktop's monospace font", regularWeights(weights), current == null))
    }
    add(FontOption(BUNDLED_FONT, BUNDLED_FONT_TITLE, if (desktopHasFont) "Built in" else "Built in · the default", BUNDLED_FONT_WEIGHTS, current == BUNDLED_FONT))
    for (font in installed.orEmpty()) add(FontOption(font.family, font.family, INSTALLED_FONTS_ARE, regularWeights(font.weights), current == font.family))
}

/** The weights of a family that text which isn't bold can have: regular always, and what it has short of bold. */
internal fun regularWeights(weights: List<Int>?): List<Int> = (weights.orEmpty().filter { it < 700 } + DEFAULT_FONT_WEIGHT).distinct().sorted()

/** How heavy bold text is beside text of a weight: bold, or heavier still where the text is nearly that already. */
internal fun boldWeightFor(regular: Int): Int = if (regular >= 600) 900 else 700

/** A weight in words, as font menus have them. */
internal fun fontWeightName(weight: Int): String = when (weight) {
    100 -> "Thin"; 200 -> "Extra Light"; 300 -> "Light"; 400 -> "Regular"; 500 -> "Medium"
    600 -> "Semibold"; 700 -> "Bold"; 800 -> "Extra Bold"; 900 -> "Black"
    else -> weight.toString()
}

/** Something being tried before it is chosen. A class of its own, since trying "follow the desktop" is trying null. */
internal class Tried(val value: String?)

/**
 * How the app looks: what was chosen, what the desktop says, and what is being tried in a
 * picker. One per app, shared by its windows.
 */
@Stable
class AppearanceState internal constructor(private val client: RexClient, desktop: OmarchyAppearance? = readOmarchyAppearance()) {
    /** What the person chose. The client's, mirrored here for drawing; [follow] keeps it in step. */
    var choice: AppearanceChoice by mutableStateOf(client.appearance.value)
        private set

    /** How the desktop looks, or null where there is no Omarchy (or, in a browser, until the server has said). */
    var desktop: OmarchyAppearance? by mutableStateOf(desktop)
        internal set

    /** Whether the page was handed the desktop's font by its server. A page can't load one by name. */
    internal var pageHasDesktopFont by mutableStateOf(false)

    /** The monospace fonts the platform can load by name. Null until they have been listed. */
    var installedFonts: List<TerminalFontInfo>? by mutableStateOf(null)
        internal set

    /** A theme being tried: a picker's highlighted line. It shows in place of the one chosen until it is chosen or the picker closes. */
    internal var triedTheme: Tried? by mutableStateOf(null)

    /** A font family being tried, the same way. */
    internal var triedFont: Tried? by mutableStateOf(null)

    /** A weight being tried, the same way. */
    internal var triedWeight: Int? by mutableStateOf(null)

    val desktopHasFont: Boolean get() = pageHasDesktopFont || desktop?.fontName != null

    /** The palette everything is drawn with. */
    val palette: OmarchyPalette get() = themeInEffect(triedTheme.let { if (it != null) it.value else choice.theme }, desktop?.palette)

    /** The terminals' font family, as a choice names them: null for the desktop's. */
    internal val fontFamily: String? get() = fontInEffect(triedFont.let { if (it != null) it.value else choice.fontFamily }, desktopHasFont, installedFonts)

    /** How heavy terminal text that isn't bold is. */
    val fontWeight: Int get() = triedWeight ?: choice.fontWeight ?: DEFAULT_FONT_WEIGHT

    /** The size of terminal text. */
    val fontSize: Int get() = (choice.fontSize ?: RexUiState.DEFAULT_FONT_SIZE).coerceIn(RexUiState.FONT_SIZES)

    internal val themes: List<ThemeOption> get() = themeOptions(choice.theme, desktop?.palette)
    internal val fonts: List<FontOption> get() = fontOptions(choice.fontFamily, desktopHasFont, desktop?.fontName, installedFonts)

    /** The weights the font in effect can be set to. */
    internal val weights: List<Int> get() = fonts.firstOrNull { it.current }?.weights ?: listOf(DEFAULT_FONT_WEIGHT)

    /** The name of the theme in effect, for showing beside the command that changes it. */
    val themeTitle: String get() = themes.firstOrNull { it.current }?.let { if (it.slug == null) "${it.title} (${it.palette.displayName})" else it.title }.orEmpty()

    val fontTitle: String get() = fonts.firstOrNull { it.current }?.let { if (it.family == null) desktop?.fontName ?: it.title else it.title }.orEmpty()

    /** Chooses a theme of the app's own by name, or null to follow the desktop. Applies at once, and is remembered. */
    fun chooseTheme(slug: String?) {
        triedTheme = null
        change(choice.copy(theme = slug))
        client.log.add("appearance", "theme: ${slug ?: "the desktop's"}")
    }

    /** Chooses the terminals' font family, or null for the desktop's. A weight the family doesn't have goes back to regular. */
    fun chooseFont(family: String?) {
        triedFont = null
        val weights = fontOptions(family, desktopHasFont, desktop?.fontName, installedFonts).firstOrNull { it.current }?.weights
        change(choice.copy(fontFamily = family, fontWeight = choice.fontWeight?.takeIf { weights == null || it in weights }))
        client.log.add("appearance", "terminal font: ${family ?: "the desktop's"}")
    }

    /** Sets how heavy terminal text is. Regular is the default, and isn't written down. */
    fun chooseFontWeight(weight: Int) {
        triedWeight = null
        change(choice.copy(fontWeight = weight.coerceIn(100, 900).takeIf { it != DEFAULT_FONT_WEIGHT }))
    }

    /** Sets the size of terminal text, within what is allowed. The default isn't written down. */
    fun chooseFontSize(size: Int) {
        change(choice.copy(fontSize = size.coerceIn(RexUiState.FONT_SIZES).takeIf { it != RexUiState.DEFAULT_FONT_SIZE }))
    }

    /** Nothing is being tried any longer: what was chosen shows again. */
    fun endTrying() {
        triedTheme = null
        triedFont = null
        triedWeight = null
    }

    private fun change(to: AppearanceChoice) {
        choice = to
        client.setAppearance(to)
    }

    /**
     * Keeps this in step with the client's choice, whoever changes it, and with the desktop
     * as its theme and font change; and lists the fonts there are. Runs until cancelled.
     */
    suspend fun follow(): Unit = coroutineScope {
        launch { client.appearance.collect { choice = it } }
        launch { omarchyAppearanceFlow().collect { desktop = it } }
        launch { if (installedFonts == null) installedFonts = runCatching { installedTerminalFonts() }.getOrDefault(emptyList()) }
    }
}

/** The monospace fonts that can be loaded by name here. On the desktop, the installed ones; in a browser, the ones its server has. */
internal expect suspend fun installedTerminalFonts(): List<TerminalFontInfo>

/**
 * A font family loaded by name, with its text that isn't bold at [weight], if it is to hand
 * without waiting: always on the desktop if it is installed, and in a browser once
 * [loadInstalledFont] has fetched it. Null if it isn't.
 */
internal expect fun installedFontIfReady(family: String, weight: Int): FontFamily?

/** Gets a font family by name, fetching it where it has to be fetched. Null if there is no such font. */
internal expect suspend fun loadInstalledFont(family: String, weight: Int): FontFamily?

/** A font from the app's resources, or null while it is still being read: in a browser it is fetched. */
@Composable
internal expect fun resourceFont(resource: FontResource, weight: FontWeight, style: FontStyle = FontStyle.Normal): Font?

/** Tells whoever shows the app's surroundings (a browser's own bars) what color the app is. Nothing on the desktop. */
internal expect fun themeApplied(background: String, dark: Boolean)

private const val FONT_WAIT_MILLIS = 5_000L

/** The font the terminals are drawn in. */
class TerminalFont(val family: FontFamily, val weight: FontWeight)

/** The terminals' font, where [TonoTheme] has chosen one. Null leaves a terminal the theme's own. */
val LocalTerminalFont = staticCompositionLocalOf<TerminalFont?> { null }

/**
 * The app's own font, JetBrains Mono (without ligatures, which a terminal's cells have no
 * room for), from the app's resources. Null until it has been read, which in a browser
 * takes a moment and on the desktop none.
 */
@Composable
internal fun bundledFontFamily(): FontFamily? {
    val fonts: List<Font?> = listOf(
        resourceFont(Res.font.jetbrains_mono_light, FontWeight.Light),
        resourceFont(Res.font.jetbrains_mono_regular, FontWeight.Normal),
        resourceFont(Res.font.jetbrains_mono_medium, FontWeight.Medium),
        resourceFont(Res.font.jetbrains_mono_bold, FontWeight.Bold),
        resourceFont(Res.font.jetbrains_mono_italic, FontWeight.Normal, FontStyle.Italic),
        resourceFont(Res.font.jetbrains_mono_bold_italic, FontWeight.Bold, FontStyle.Italic),
    )
    return remember(fonts) { if (fonts.any { it == null }) null else FontFamily(fonts.filterNotNull()) }
}

/**
 * Tono's Material theme: the desktop's colors and font where there is an Omarchy desktop
 * and nobody chose otherwise, and the theme and font chosen, or the app's defaults, where
 * not. It follows the desktop and the choice as they change. Everything Tono draws goes
 * inside one: the app, and its settings where they have a window of their own.
 */
@Composable
fun TonoTheme(ui: RexUiState, content: @Composable () -> Unit) {
    val look = ui.appearance
    LaunchedEffect(look) { look.follow() }

    // The desktop's font: handed to a page by its server, loaded by name on the desktop.
    val pageFont = LocalOmarchyFont.current
    SideEffect { look.pageHasDesktopFont = pageFont != null }
    val desktopFontName = look.desktop?.fontName
    val desktopFont = pageFont ?: remember(desktopFontName) { desktopFontName?.let(::systemFontFamily) }

    val chosenFamily = look.fontFamily
    val weight = look.fontWeight
    // The app's own font is read only where it is used: in a browser it is a megabyte to fetch.
    val bundled = if (desktopFont == null || chosenFamily == BUNDLED_FONT) bundledFontFamily() else null
    val appFont = desktopFont ?: bundled
    // Nothing is drawn in a font that is still on its way: terminals laid out in another and
    // then switched would jump, and tell their servers two sizes. Not for ever, though.
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(FONT_WAIT_MILLIS); waited = true }
    if (appFont == null && !waited) {
        Box(Modifier.fillMaxSize().background(hexToColor(look.palette.colors.getValue("background"))))
        return
    }

    // A family by name. In a browser it arrives after it is asked for, and the terminals
    // stay in the font they had until it has.
    val named = chosenFamily?.takeIf { it != BUNDLED_FONT } ?: desktopFontName?.takeIf { weight != DEFAULT_FONT_WEIGHT }
    var fetched by remember { mutableStateOf<Triple<String, Int, FontFamily>?>(null) }
    LaunchedEffect(named, weight) {
        if (named != null && installedFontIfReady(named, weight) == null) loadInstalledFont(named, weight)?.let { fetched = Triple(named, weight, it) }
    }
    val byName = named?.let { name -> installedFontIfReady(name, weight) ?: fetched?.takeIf { it.first == name && it.second == weight }?.third }
    val terminalFamily = when {
        chosenFamily == BUNDLED_FONT -> bundled
        else -> byName
    } ?: fetched?.third?.takeIf { named != null } ?: appFont

    val palette = look.palette
    LaunchedEffect(palette) { themeApplied(palette.colors.getValue("background"), palette.isDark) }
    val typography = remember(appFont) { omarchyTypography(appFont) }
    MaterialTheme(colorScheme = remember(palette) { omarchyColorScheme(palette) }, shapes = remember { omarchyShapes() }, typography = typography) {
        val terminalFont = remember(terminalFamily, weight) { terminalFamily?.let { TerminalFont(it, FontWeight(weight)) } }
        CompositionLocalProvider(LocalTerminalFont provides terminalFont, content = content)
    }
}
