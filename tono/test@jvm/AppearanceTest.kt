package org.example.kotlintest

import dev.mg.omarchy.theme.OmarchyAppearance
import dev.mg.omarchy.theme.bundledOmarchyPalettes
import dev.mg.omarchy.theme.terminalColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.example.kotlintest.rex.AppearanceChoice
import org.example.kotlintest.rex.Keymap
import org.example.kotlintest.rex.RexClient
import org.example.kotlintest.rex.SavedHost
import org.example.kotlintest.rex.Workspace
import org.example.kotlintest.rex.WorkspaceFile
import org.example.kotlintest.rex.load
import java.nio.file.Files
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which theme and font are in effect, given what was chosen and what the desktop says. */
class AppearanceTest {
    private val gruvbox = bundledOmarchyPalettes.first { it.slug == "gruvbox" }
    private val installed = listOf(TerminalFontInfo("Adwaita Mono", listOf(400, 700)), TerminalFontInfo("Caskaydia", listOf(200, 300, 400, 600, 700)))

    // --- themes ---

    @Test
    fun `with Omarchy and nothing chosen, the desktop's theme is followed`() {
        assertEquals(gruvbox, themeInEffect(null, gruvbox))
        val options = themeOptions(null, gruvbox)
        assertEquals(FOLLOW_OMARCHY, options.first().title)
        assertTrue(options.first().available)
        assertEquals("now Gruvbox", options.first().detail)
        assertEquals(listOf<String?>(null), options.filter { it.current }.map { it.slug })
    }

    @Test
    fun `with Omarchy, a theme chosen wins over the desktop's`() {
        assertEquals("nord", themeInEffect("nord", gruvbox).slug)
        assertEquals(listOf<String?>("nord"), themeOptions("nord", gruvbox).filter { it.current }.map { it.slug })
    }

    @Test
    fun `without Omarchy, the default is a dark theme of the app's own, and following isn't on offer`() {
        val default = themeInEffect(null, null)
        assertEquals(DEFAULT_THEME, default.slug)
        assertTrue(default.isDark)
        val options = themeOptions(null, null)
        assertFalse(options.first().available)
        assertEquals("Omarchy isn't running here", options.first().detail)
        assertEquals(listOf<String?>(DEFAULT_THEME), options.filter { it.current }.map { it.slug })
        assertEquals("Dark · the default", options.first { it.slug == DEFAULT_THEME }.detail)
    }

    @Test
    fun `without Omarchy, a theme chosen is the one in effect`() {
        assertEquals("catppuccin-latte", themeInEffect("catppuccin-latte", null).slug)
        assertEquals(listOf<String?>("catppuccin-latte"), themeOptions("catppuccin-latte", null).filter { it.current }.map { it.slug })
    }

    @Test
    fun `a theme the app doesn't have counts as none chosen`() {
        assertEquals(gruvbox, themeInEffect("no-such-theme", gruvbox))
        assertEquals(DEFAULT_THEME, themeInEffect("no-such-theme", null).slug)
        assertEquals(listOf<String?>(null), themeOptions("no-such-theme", gruvbox).filter { it.current }.map { it.slug })
    }

    @Test
    fun `there are dark and light themes, the dark first, and every one can color a terminal`() {
        assertTrue(builtInThemes.count { it.isDark } >= 5)
        assertTrue(builtInThemes.count { !it.isDark } >= 3)
        assertEquals(builtInThemes.map { it.isDark }, builtInThemes.map { it.isDark }.sortedDescending())
        for (theme in builtInThemes) {
            val terminal = theme.terminalColors()
            assertEquals(16, terminal.ansi.size, theme.slug)
            // Text that can be read on its background, and a dark theme that is dark.
            assertTrue(contrast(terminal.foreground, terminal.background) >= 4.5, "${theme.slug}: ${contrast(terminal.foreground, terminal.background)}")
            assertEquals(theme.isDark, luminance(terminal.background) < luminance(terminal.foreground), theme.slug)
            assertTrue(terminal.selection != terminal.background, theme.slug)
        }
    }

    // --- fonts ---

    @Test
    fun `with Omarchy and nothing chosen, the font is the desktop's`() {
        assertNull(fontInEffect(null, desktopHasFont = true, installed))
        val options = fontOptions(null, true, "Caskaydia", installed)
        assertEquals(listOf("Omarchy's Font", BUNDLED_FONT_TITLE, "Adwaita Mono", "Caskaydia"), options.map { it.title })
        assertEquals("now Caskaydia", options.first().detail)
        assertEquals(listOf(200, 300, 400, 600), options.first().weights)
        assertEquals(listOf("Omarchy's Font"), options.filter { it.current }.map { it.title })
    }

    @Test
    fun `without Omarchy, the font is the app's own, and Omarchy's isn't offered`() {
        assertEquals(BUNDLED_FONT, fontInEffect(null, desktopHasFont = false, installed))
        val options = fontOptions(null, false, null, installed)
        assertEquals(listOf(BUNDLED_FONT_TITLE, "Adwaita Mono", "Caskaydia"), options.map { it.title })
        assertEquals("Built in · the default", options.first().detail)
        assertEquals(listOf(BUNDLED_FONT_TITLE), options.filter { it.current }.map { it.title })
    }

    @Test
    fun `a font chosen is used if it is there, with or without Omarchy`() {
        assertEquals("Adwaita Mono", fontInEffect("Adwaita Mono", true, installed))
        assertEquals("Adwaita Mono", fontInEffect("Adwaita Mono", false, installed))
        assertEquals(BUNDLED_FONT, fontInEffect(BUNDLED_FONT, true, installed))
        // Until the fonts have been listed, it is taken to be there.
        assertEquals("Adwaita Mono", fontInEffect("Adwaita Mono", true, null))
    }

    @Test
    fun `a font chosen that is gone falls back to the desktop's, or the app's own`() {
        assertNull(fontInEffect("Gone Mono", true, installed))
        assertEquals(BUNDLED_FONT, fontInEffect("Gone Mono", false, installed))
        assertEquals(BUNDLED_FONT, fontInEffect("Gone Mono", false, emptyList()))
    }

    @Test
    fun `text that isn't bold can be regular and whatever the family has short of bold`() {
        assertEquals(listOf(400), regularWeights(null))
        assertEquals(listOf(400), regularWeights(listOf(700)))
        assertEquals(listOf(300, 400, 500), regularWeights(listOf(300, 500, 700, 800)))
        assertEquals(700, boldWeightFor(400))
        assertEquals(700, boldWeightFor(500))
        assertEquals(900, boldWeightFor(600))
    }

    // --- the state the app draws from, and what it keeps ---

    private val directory = Files.createTempDirectory("workspace")
    private val file = directory.resolve("workspace.json")
    private val connections = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = RexClient(connections, Workspace(WorkspaceFile(file), listOf(SavedHost("Local", "unix://${directory.resolve("absent.sock")}"))))
    private val ui = RexUiState(client)
    private val look = ui.appearance.also { it.desktop = null; it.installedFonts = installed }

    @AfterTest
    fun close() {
        client.close()
        connections.cancel()
    }

    @Test
    fun `choosing a theme applies it and keeps it, and following again keeps nothing`() {
        look.desktop = OmarchyAppearance(gruvbox, "Caskaydia")
        assertEquals("gruvbox", look.palette.slug)
        look.chooseTheme("nord")
        assertEquals("nord", look.palette.slug)
        assertEquals("Nord", look.themeTitle)
        assertEquals(AppearanceChoice(theme = "nord"), Workspace.load(file).appearance)
        look.chooseTheme(null)
        assertEquals("gruvbox", look.palette.slug)
        assertEquals("Follow Omarchy (Gruvbox)", look.themeTitle)
        assertTrue(Workspace.load(file).appearance.isDefault)
    }

    @Test
    fun `a theme being tried shows until it is chosen or dropped, and is never kept`() {
        look.triedTheme = Tried("white")
        assertEquals("white", look.palette.slug)
        assertTrue(Workspace.load(file).appearance.isDefault)
        look.endTrying()
        assertEquals(DEFAULT_THEME, look.palette.slug)
        // Trying "follow the desktop" over a theme that was chosen.
        look.desktop = OmarchyAppearance(gruvbox, null)
        look.chooseTheme("nord")
        look.triedTheme = Tried(null)
        assertEquals("gruvbox", look.palette.slug)
        look.endTrying()
        assertEquals("nord", look.palette.slug)
    }

    @Test
    fun `the font size keys change the size that is kept, within limits, and the default isn't written`() {
        val commands = RexCommands(ui)
        assertEquals(RexUiState.DEFAULT_FONT_SIZE, ui.fontSize)
        commands.run("client.font.increase", origin = "test")
        commands.run("client.font.increase", origin = "test")
        assertEquals(RexUiState.DEFAULT_FONT_SIZE + 2, ui.fontSize)
        assertEquals(RexUiState.DEFAULT_FONT_SIZE + 2, Workspace.load(file).appearance.fontSize)
        commands.run("client.font.decrease", origin = "test")
        assertEquals(RexUiState.DEFAULT_FONT_SIZE + 1, Workspace.load(file).appearance.fontSize)
        commands.run("client.font.reset", origin = "test")
        assertEquals(RexUiState.DEFAULT_FONT_SIZE, ui.fontSize)
        assertTrue(Workspace.load(file).appearance.isDefault)
        repeat(60) { commands.run("client.font.increase", origin = "test") }
        assertEquals(RexUiState.FONT_SIZES.last, ui.fontSize)
    }

    @Test
    fun `a size kept from last time is the size the app starts with`() {
        val saved = directory.resolve("saved.json")
        Workspace(WorkspaceFile(saved), listOf(SavedHost("Local", "unix://${directory.resolve("absent.sock")}")), appearance = AppearanceChoice(fontSize = 17, fontWeight = 300)).save()
        val again = RexClient(connections, Workspace.load(saved))
        try {
            val state = RexUiState(again)
            assertEquals(17, state.fontSize)
            assertEquals(300, state.appearance.fontWeight)
        } finally {
            again.close()
        }
    }

    @Test
    fun `choosing a font keeps it, and drops a weight the new font doesn't have`() {
        look.chooseFont("Caskaydia")
        look.chooseFontWeight(300)
        assertEquals(AppearanceChoice(fontFamily = "Caskaydia", fontWeight = 300), Workspace.load(file).appearance)
        assertEquals(listOf(200, 300, 400, 600), look.weights)
        look.chooseFont(BUNDLED_FONT)
        assertEquals(AppearanceChoice(fontFamily = BUNDLED_FONT, fontWeight = 300), Workspace.load(file).appearance)
        look.chooseFont("Adwaita Mono")
        assertEquals(AppearanceChoice(fontFamily = "Adwaita Mono"), Workspace.load(file).appearance)
        assertEquals(DEFAULT_FONT_WEIGHT, look.fontWeight)
        look.chooseFontWeight(400)
        assertNull(Workspace.load(file).appearance.fontWeight)
    }

    @Test
    fun `a change made elsewhere to the client's choice is the one in the file`() {
        client.setAppearance(AppearanceChoice(theme = "kanagawa"))
        assertEquals(AppearanceChoice(theme = "kanagawa"), Workspace.load(file).appearance)
    }

    // --- the palette ---

    private fun themeItems() = paletteItems(ui, RexCommands(ui), Keymap(emptyList()), PaletteMode.Themes)

    @Test
    fun `the palette lists the app's themes, and following Omarchy only where there is one`() {
        assertEquals(builtInThemes.map { it.displayName }, themeItems().map { it.title })
        assertEquals(listOf("Tokyo Night"), themeItems().filter { it.current }.map { it.title })
        look.desktop = OmarchyAppearance(gruvbox, null)
        assertEquals(listOf(FOLLOW_OMARCHY) + builtInThemes.map { it.displayName }, themeItems().map { it.title })
        assertEquals(listOf(FOLLOW_OMARCHY), themeItems().filter { it.current }.map { it.title })
    }

    @Test
    fun `a theme's line shows the theme while it is highlighted, and choosing it keeps it`() {
        val white = themeItems().first { it.title == "White" }
        assertNotNull(white.swatch)
        white.onHighlight!!()
        assertEquals("white", look.palette.slug)
        assertTrue(Workspace.load(file).appearance.isDefault)
        white.run()
        look.endTrying()
        assertEquals("white", look.palette.slug)
        assertEquals("white", Workspace.load(file).appearance.theme)
    }

    @Test
    fun `the palette lists the fonts and the weights of the one in effect`() {
        val fonts = paletteItems(ui, RexCommands(ui), Keymap(emptyList()), PaletteMode.Fonts)
        assertEquals(listOf(BUNDLED_FONT_TITLE, "Adwaita Mono", "Caskaydia"), fonts.map { it.title })
        fonts.last().run()
        val weights = paletteItems(ui, RexCommands(ui), Keymap(emptyList()), PaletteMode.FontWeights)
        assertEquals(listOf("Extra Light", "Light", "Regular", "Semibold"), weights.map { it.title })
        assertEquals(listOf("Regular"), weights.filter { it.current }.map { it.title })
        weights.first().onHighlight!!()
        assertEquals(200, look.fontWeight)
        look.endTrying()
        assertEquals(400, look.fontWeight)
    }

    @Test
    fun `the commands that choose are in the palette`() {
        val names = clientActions().map { it.name }
        assertTrue("client.theme.select" in names && "client.font.select" in names && "client.font.weight" in names)
    }

    private fun luminance(hex: String): Double {
        val rgb = hex.removePrefix("#").toInt(16)
        fun channel(shift: Int) = ((rgb shr shift) and 0xFF) / 255.0
        fun linear(value: Double) = if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        return 0.2126 * linear(channel(16)) + 0.7152 * linear(channel(8)) + 0.0722 * linear(channel(0))
    }

    private fun contrast(a: String, b: String): Double = (max(luminance(a), luminance(b)) + 0.05) / (min(luminance(a), luminance(b)) + 0.05)
}
