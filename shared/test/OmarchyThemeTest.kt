package org.example.kotlintest

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class OmarchyThemeTest {
    private val retro = bundledOmarchyPalettes.first { it.slug == "retro-82" }

    @Test
    fun `hex colors convert to opaque compose colors`() {
        assertEquals(Color(0xFFFAA968), hexToColor("#faa968"))
        assertEquals(Color(0xFF05182E), hexToColor("#05182E"))
    }

    @Test
    fun `palette maps onto material color roles`() {
        val scheme = omarchyColorScheme(retro)
        assertEquals(Color(0xFFFAA968), scheme.primary)
        assertEquals(Color(0xFF05182E), scheme.background)
        assertEquals(Color(0xFFF6DCAC), scheme.onBackground)
        assertEquals(Color(0xFFF85525), scheme.error)
    }

    @Test
    fun `a minimal theme still produces a full color scheme`() {
        val minimal = OmarchyPalette(
            slug = "minimal",
            mode = "dark",
            colors = mapOf("background" to "#101010", "foreground" to "#eeeeee", "accent" to "#33aaff"),
        )
        val background = Color(0xFF101010)
        val foreground = Color(0xFFEEEEEE)
        val accent = Color(0xFF33AAFF)

        assertEquals(background, minimal.color("darker_background")) // -> dark_background -> background
        assertEquals(foreground, minimal.color("muted"))             // -> dark_foreground -> foreground
        assertEquals(accent, minimal.color("magenta"))               // -> blue -> accent

        val scheme = omarchyColorScheme(minimal)
        assertEquals(accent, scheme.primary)
        assertEquals(accent, scheme.error)
        assertEquals(background, scheme.surfaceVariant)
        assertEquals(foreground, scheme.onSurfaceVariant)
    }

    @Test
    fun `an unknown color name is loud`() {
        assertEquals(Color.Magenta, retro.color("no_such_color"))
    }
}
