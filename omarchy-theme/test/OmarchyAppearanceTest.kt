package dev.mg.omarchy.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OmarchyAppearanceTest {
    @Test
    fun `parses omarchy colors toml`() {
        val entries = parseColorsToml(
            """
            mode = "light"

            accent = "#1e66f5"
            # a comment
            background = "#eff1f5"
            """.trimIndent(),
        )
        assertEquals(mapOf("mode" to "light", "accent" to "#1e66f5", "background" to "#eff1f5"), entries)
    }

    @Test
    fun `a palette keeps the colors and drops other settings`() {
        // Matte Black's colors.toml also carries Hyprland border settings.
        val palette = omarchyPaletteFromToml(
            "matte-black",
            """
            mode = "dark"
            accent = "#e68e0d"
            background = "#121212"
            foreground = "#bebebe"
            hyprland_active_border = "rgba(8a8588ee) rgba(e2dddcee)"
            """.trimIndent(),
        )
        assertNotNull(palette)
        assertEquals(listOf("accent", "background", "foreground"), palette.colors.keys.toList())
        assertEquals("dark", palette.mode)
    }

    @Test
    fun `a palette needs background foreground and accent`() {
        assertNull(omarchyPaletteFromToml("broken", "accent = \"#ffffff\""))
    }

    @Test
    fun `mode defaults to dark`() {
        val palette = omarchyPaletteFromToml(
            "plain",
            "accent = \"#ffffff\"\nbackground = \"#000000\"\nforeground = \"#cccccc\"",
        )
        assertEquals(true, palette?.isDark)
    }

    @Test
    fun `bundled palettes parse`() {
        // The four that were here first keep their places; every stock theme is here.
        assertEquals(listOf("retro-82", "catppuccin", "catppuccin-latte", "tokyo-night"), bundledOmarchyPalettes.take(4).map { it.slug })
        assertEquals(22, bundledOmarchyPalettes.size)
        assertEquals(bundledOmarchyPalettes.size, bundledOmarchyPalettes.map { it.slug }.distinct().size)
        assertTrue(bundledOmarchyPalettes.count { !it.isDark } >= 4)
        val bySlug = bundledOmarchyPalettes.associateBy { it.slug }
        assertTrue(bySlug.getValue("retro-82").isDark)
        assertFalse(bySlug.getValue("catppuccin-latte").isDark)
        assertEquals("Retro 82", bySlug.getValue("retro-82").displayName)
        assertEquals("Catppuccin Latte", bySlug.getValue("catppuccin-latte").displayName)
        assertEquals("#faa968", bySlug.getValue("retro-82").colors["accent"])
        // Complete copies of the stock themes, bright colors included.
        for (palette in bundledOmarchyPalettes) {
            assertTrue(palette.colors.size >= 23, palette.slug)
            for (name in listOf("selection", "muted", "bright_blue", "lighter_background")) assertTrue(name in palette.colors, "${palette.slug} $name")
            assertEquals(16, palette.terminalColors().ansi.size, palette.slug)
        }
    }

    @Test
    fun `appearance survives the trip through json`() {
        for (palette in bundledOmarchyPalettes) {
            val appearance = OmarchyAppearance(palette, fontName = "JetBrainsMono Nerd Font")
            assertEquals(appearance, omarchyAppearanceFromJson(appearance.toJson()))
        }
        val noFont = OmarchyAppearance(bundledOmarchyPalettes.first(), fontName = null)
        assertEquals(noFont, omarchyAppearanceFromJson(noFont.toJson()))
    }

    @Test
    fun `json keeps the palette's color order`() {
        val palette = bundledOmarchyPalettes.first()
        val decoded = omarchyAppearanceFromJson(OmarchyAppearance(palette, null).toJson())
        assertEquals(palette.colors.keys.toList(), decoded?.palette?.colors?.keys?.toList())
    }

    @Test
    fun `unusable json is rejected`() {
        assertNull(omarchyAppearanceFromJson("not json"))
        assertNull(omarchyAppearanceFromJson("""{"theme":"x"}"""))
        assertNull(omarchyAppearanceFromJson("""{"theme":"x","colors":{"accent":"#ffffff"}}"""))
    }
}
