package dev.mg.omarchy.theme.compose

import androidx.compose.ui.text.font.FontFamily

/**
 * The font to use as the desktop's, in a browser. A page cannot load the desktop's fonts by
 * name; it has only the fonts it is given. An app that can get the desktop's font another
 * way, such as from the server that serves it, sets it here before it shows anything, and
 * [OmarchyTheme] then uses it whenever the desktop names a font.
 */
var omarchyWebFont: FontFamily? = null

actual fun systemFontFamily(name: String): FontFamily? = omarchyWebFont
