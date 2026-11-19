package dev.mg.omarchy.theme

import kotlin.test.Test
import kotlin.test.assertEquals

class OmarchyTerminalColorsTest {
    @Test
    fun `a theme maps onto terminal colors the way omarchy's own terminal configs do`() {
        // Retro 82's colors.toml; the expected values are what its generated ghostty.conf holds.
        val terminal = bundledOmarchyPalettes.first { it.slug == "retro-82" }.terminalColors()

        assertEquals("#f6dcac", terminal.foreground)
        assertEquals("#05182e", terminal.background)
        assertEquals("#f6dcac", terminal.cursor)
        assertEquals("#134e5a", terminal.selection)
        assertEquals(
            listOf(
                "#05182e", "#f85525", "#028391", "#e97b3c", "#3f8f8a", "#3f8f8a", "#8cbfb8", "#f6dcac",
                "#2a6b78", "#f85525", "#028391", "#e97b3c", "#faa968", "#3f8f8a", "#8cbfb8", "#f6dcac",
            ),
            terminal.ansi,
        )
    }

    @Test
    fun `a minimal theme still fills all sixteen colors`() {
        val minimal = OmarchyPalette("minimal", "dark", mapOf("background" to "#101010", "foreground" to "#eeeeee", "accent" to "#33aaff"))

        val terminal = minimal.terminalColors()

        assertEquals(16, terminal.ansi.size)
        assertEquals("#101010", terminal.ansi[0])
        assertEquals("#33aaff", terminal.ansi[1])   // red -> accent
        assertEquals("#eeeeee", terminal.ansi[7])
        assertEquals("#eeeeee", terminal.ansi[8])   // muted -> foreground
        assertEquals("#33aaff", terminal.ansi[9])   // bright red -> red -> accent
        assertEquals("#eeeeee", terminal.cursor)
        assertEquals("#eeeeee", terminal.selection) // selection -> ... -> foreground
    }
}
