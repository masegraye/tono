package org.example.kotlintest

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OmarchyThemeTest {
    /** Every colors.toml key that omarchyColorScheme() reads. */
    private val requiredKeys = listOf(
        "accent", "selection", "muted",
        "background", "dark_background", "darker_background", "lighter_background",
        "foreground", "dark_foreground", "light_foreground", "bright_foreground",
        "red", "green", "blue", "magenta",
    )

    @Test
    fun `parses omarchy colors toml`() {
        val raw = parseColorsToml(
            """
            mode = "light"

            accent = "#1e66f5"
            # a comment
            background = "#eff1f5"
            """.trimIndent(),
        )
        assertEquals(mapOf("mode" to "light", "accent" to "#1e66f5", "background" to "#eff1f5"), raw)
    }

    @Test
    fun `hex colors convert to opaque compose colors`() {
        assertEquals(Color(0xFFFAA968), hexToColor("#faa968"))
        assertEquals(Color(0xFF05182E), hexToColor("05182E"))
    }

    @Test
    fun `incomplete palettes are rejected`() {
        assertNull(omarchyPaletteFromToml("broken", "accent = \"#ffffff\""))
    }

    @Test
    fun `bundled palettes are complete`() {
        assertEquals(
            listOf("retro-82", "catppuccin", "catppuccin-latte", "tokyo-night"),
            bundledOmarchyPalettes.map { it.slug },
        )
        for (palette in bundledOmarchyPalettes) {
            for (key in requiredKeys) {
                assertNotNull(palette.raw[key], "${palette.slug} is missing '$key'")
            }
        }
    }

    @Test
    fun `non-color entries are not treated as colors`() {
        val retro = bundledOmarchyPalettes.first { it.slug == "retro-82" }
        assertFalse("mode" in retro.colorKeys)
        assertTrue("accent" in retro.colorKeys)
        assertEquals(Color.Magenta, retro.color("mode"))
        assertEquals(Color.Magenta, retro.color("no_such_key"))
    }

    @Test
    fun `palette mode and display name`() {
        val bySlug = bundledOmarchyPalettes.associateBy { it.slug }
        assertTrue(bySlug.getValue("retro-82").isDark)
        assertFalse(bySlug.getValue("catppuccin-latte").isDark)
        assertEquals("Retro 82", bySlug.getValue("retro-82").displayName)
        assertEquals("Catppuccin Latte", bySlug.getValue("catppuccin-latte").displayName)
    }

    @Test
    fun `palette maps onto material color roles`() {
        val retro = bundledOmarchyPalettes.first { it.slug == "retro-82" }
        val scheme = omarchyColorScheme(retro)
        assertEquals(Color(0xFFFAA968), scheme.primary)
        assertEquals(Color(0xFF05182E), scheme.background)
        assertEquals(Color(0xFFF6DCAC), scheme.onBackground)
        assertEquals(Color(0xFFF85525), scheme.error)
    }
}
