package dev.mg.omarchy.theme

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

// Omarchy is a Linux desktop, and a phone has no way to see it. Apps fall back to
// bundledOmarchyPalettes here.
actual fun readOmarchyAppearance(): OmarchyAppearance? = null

actual fun omarchyAppearanceFlow(): Flow<OmarchyAppearance> = flow { awaitCancellation() }

actual fun readInstalledOmarchyPalettes(): List<OmarchyPalette> = emptyList()
