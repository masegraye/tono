package dev.mg.omarchy.theme.compose

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont

/** Loads an installed font through fontconfig. An unknown name falls back to the default font. */
@OptIn(ExperimentalTextApi::class) // SystemFont
actual fun systemFontFamily(name: String): FontFamily? = FontFamily(
    SystemFont(name, FontWeight.Normal, FontStyle.Normal),
    SystemFont(name, FontWeight.Normal, FontStyle.Italic),
    SystemFont(name, FontWeight.Bold, FontStyle.Normal),
    SystemFont(name, FontWeight.Bold, FontStyle.Italic),
)
