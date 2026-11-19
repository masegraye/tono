package dev.mg.omarchy.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OmarchyAbsentTest {
    @Test
    fun `declared absent, nothing of the desktop is read`() {
        val before = System.getProperty("omarchy.absent")
        System.setProperty("omarchy.absent", "true")
        try {
            assertTrue(omarchyDeclaredAbsent())
            assertFalse(omarchyIsPresent())
            assertNull(readOmarchyAppearance())
            assertEquals(emptyList(), readInstalledOmarchyPalettes())
        } finally {
            if (before == null) System.clearProperty("omarchy.absent") else System.setProperty("omarchy.absent", before)
        }
    }

    @Test
    fun `false and 0 do not declare it absent`() {
        val before = System.getProperty("omarchy.absent")
        try {
            for (value in listOf("false", "0", "")) {
                System.setProperty("omarchy.absent", value)
                assertEquals(System.getenv("OMARCHY_ABSENT")?.lowercase()?.let { it !in setOf("", "false", "0") } ?: false, omarchyDeclaredAbsent(), value)
            }
        } finally {
            if (before == null) System.clearProperty("omarchy.absent") else System.setProperty("omarchy.absent", before)
        }
    }
}

class InstalledFontsTest {
    @Test
    fun `monospace families are listed once, with their weights, and others left out`() {
        val listed = parseFontList(
            listOf(
                "JetBrainsMono Nerd Font,JetBrainsMono NF\t100\t80",
                "JetBrainsMono Nerd Font,JetBrainsMono NF\t100\t200",
                "JetBrainsMono Nerd Font,JetBrainsMono NF\t100\t80", // its italic
                "CaskaydiaMono Nerd Font,CaskaydiaMono NF,CaskaydiaMono NF Light\t90\t50",
                "Adwaita Mono\t100\t[50 200]",
                "Noto Sans\t\t80",
                "Liberation Mono\t100\t80",
                "",
            ).joinToString("\n"),
        )
        assertEquals(
            listOf(
                InstalledFont("Adwaita Mono", listOf(300, 400, 500, 600, 700)),
                InstalledFont("CaskaydiaMono Nerd Font", listOf(300)),
                InstalledFont("JetBrainsMono Nerd Font", listOf(400, 700)),
                InstalledFont("Liberation Mono", listOf(400)),
            ),
            listed,
        )
    }

    @Test
    fun `weights go between fontconfig's numbers and the usual ones`() {
        assertEquals(400, cssWeight(80.0))
        assertEquals(700, cssWeight(200.0))
        assertEquals(300, cssWeight(50.0))
        assertEquals(400, cssWeight(75.0)) // book
        assertEquals(80, fontconfigWeight(400))
        assertEquals(100, fontconfigWeight(500))
        assertEquals(200, fontconfigWeight(700))
    }

    @Test
    fun `a font that is not installed has no file`() {
        assertNull(installedFontFile("No Such Font Anywhere"))
        assertNull(installedFontFile("../../etc/passwd"))
    }
}
