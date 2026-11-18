package dev.mg.omarchy.theme.compose

import androidx.compose.ui.text.font.FontFamily

// A browser page cannot load the desktop's fonts by name.
actual fun systemFontFamily(name: String): FontFamily? = null
