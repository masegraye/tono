package dev.mg.omarchy.theme.compose

import androidx.compose.ui.text.font.FontFamily

/**
 * A page cannot load the desktop's fonts by name; it has only the fonts it is given. An app
 * that can get the desktop's font another way provides it through [LocalOmarchyFont].
 */
actual fun systemFontFamily(name: String): FontFamily? = null
