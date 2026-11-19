package org.example.kotlintest

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import dev.mg.omarchy.theme.readInstalledMonospaceFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.FontMgr

/** The monospace fonts fontconfig lists. */
internal actual suspend fun installedTerminalFonts(): List<TerminalFontInfo> = withContext(Dispatchers.IO) {
    readInstalledMonospaceFonts().map { TerminalFontInfo(it.family, it.weights) }
}

/**
 * An installed font, by name. Each style is matched to the nearest the family has, so a
 * weight it lacks comes out as its neighbor. A name nothing answers to is null, and not
 * the default font that the toolkit would quietly hand back, which isn't monospace.
 */
@OptIn(ExperimentalTextApi::class) // SystemFont
internal actual fun installedFontIfReady(family: String, weight: Int): FontFamily? {
    if (family.lowercase() !in familyNames) return null
    return families.getOrPut(family to weight) {
        val regular = FontWeight(weight.coerceIn(1, 1000))
        val bold = FontWeight(boldWeightFor(weight))
        FontFamily(
            SystemFont(family, regular, FontStyle.Normal),
            SystemFont(family, regular, FontStyle.Italic),
            SystemFont(family, bold, FontStyle.Normal),
            SystemFont(family, bold, FontStyle.Italic),
        )
    }
}

internal actual suspend fun loadInstalledFont(family: String, weight: Int): FontFamily? = installedFontIfReady(family, weight)

internal actual fun themeApplied(background: String, dark: Boolean) {}

/** The name of every font family the toolkit can load, in lower case. Asking it for one by name always gets an answer, so this is how to tell. */
private val familyNames: Set<String> by lazy {
    runCatching { FontMgr.default.let { fonts -> (0 until fonts.familiesCount).map { fonts.getFamilyName(it).lowercase() }.toSet() } }.getOrDefault(emptySet())
}
private val families = java.util.concurrent.ConcurrentHashMap<Pair<String, Int>, FontFamily>()

internal actual val INSTALLED_FONTS_ARE: String = "Installed"

@androidx.compose.runtime.Composable
internal actual fun resourceFont(resource: org.jetbrains.compose.resources.FontResource, weight: FontWeight, style: FontStyle): androidx.compose.ui.text.font.Font? =
    org.jetbrains.compose.resources.Font(resource, weight, style)
