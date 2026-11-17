package org.example.kotlintest

import kotlinx.coroutines.awaitCancellation

// Omarchy is a Linux desktop; iOS falls back to the bundled palettes.
actual fun readOmarchyPalette(): OmarchyPalette? = null

actual fun readOmarchyFont(): OmarchyFont? = null

actual fun listOmarchyThemeSlugs(): List<String> = emptyList()

actual suspend fun awaitOmarchyAppearanceChange(): Unit = awaitCancellation()
