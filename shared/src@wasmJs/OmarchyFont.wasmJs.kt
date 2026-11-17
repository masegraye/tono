package org.example.kotlintest

import androidx.compose.ui.text.font.FontFamily

// A browser page cannot load the desktop's fonts by name.
actual fun systemFontFamily(name: String): FontFamily? = null
