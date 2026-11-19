package org.example.kotlintest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import org.example.kotlintest.rex.GridSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.mg.omarchy.theme.OmarchyAppearance
import dev.mg.omarchy.theme.bundledOmarchyPalettes
import dev.mg.omarchy.theme.compose.hexToColor
import dev.mg.omarchy.theme.compose.omarchyColorScheme
import dev.mg.omarchy.theme.terminalColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.example.kotlintest.rex.AppearanceChoice
import org.example.kotlintest.rex.RexClient
import org.example.kotlintest.rex.SavedHost
import org.example.kotlintest.rex.Workspace
import org.example.kotlintest.rex.WorkspaceFile
import org.jetbrains.skia.Bitmap
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tono drawn offscreen as on a desktop without Omarchy: the app itself, its theme picker
 * and the Appearance settings, in several of its own themes and fonts. The pictures are
 * left in `<project>/build/appearance-renders/` to be looked at.
 */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class) // KeyEvent, to press keys in a scene
class AppearanceRenderTest {
    private val outputDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "project.yaml").isFile }
        .resolve("build/appearance-renders")
        .apply { mkdirs() }

    init {
        // As if Omarchy weren't here, whatever this machine is: nothing of the desktop is read or followed.
        System.setProperty("omarchy.absent", "true")
    }

    private val directory = Files.createTempDirectory("workspace")
    private val connections = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clients = mutableListOf<RexClient>()

    @AfterTest
    fun close() {
        clients.forEach { it.close() }
        connections.cancel()
    }

    /** An app with a workspace of its own and a host that isn't there, so no server is asked. */
    private fun app(chosen: AppearanceChoice = AppearanceChoice()): RexUiState {
        val file = Files.createTempFile(directory, "workspace", ".json")
        val client = RexClient(connections, Workspace(WorkspaceFile(file), listOf(SavedHost("Local", "unix://${directory.resolve("absent.sock")}")), appearance = chosen))
        clients += client
        return RexUiState(client).also { it.appearance.installedFonts = emptyList() }
    }

    private class Drawn(val bitmap: Bitmap) {
        fun near(x: Int, y: Int, color: Color, within: Int = 6): Boolean {
            val pixel = bitmap.getColor(x, y)
            val want = color.toArgb()
            return (0..16 step 8).all { shift -> abs(((pixel shr shift) and 0xFF) - ((want shr shift) and 0xFF)) <= within }
        }

        fun count(color: Color): Int = (0 until bitmap.width step 2).sumOf { x -> (0 until bitmap.height step 2).count { y -> near(x, y, color) } }

        /** How much of the picture is neither of two colors: the ink, roughly. */
        fun ink(background: Color): Int = (0 until bitmap.width).sumOf { x -> (0 until bitmap.height).count { y -> !near(x, y, background, within = 24) } }
    }

    private fun draw(name: String, width: Int = 1100, height: Int = 720, content: @Composable () -> Unit): Drawn {
        val scene = ImageComposeScene(width, height, Density(1f)) { content() }
        try {
            var millis = 0L
            repeat(20) {
                millis += 16
                scene.render(millis * 1_000_000)
                // What a coroutine changed is told to the picture by the toolkit's thread in the app; here it is done for it.
                Snapshot.sendApplyNotifications()
                Thread.sleep(5)
            }
            val image = scene.render(millis * 1_000_000)
            File(outputDir, "$name.png").writeBytes(image.encodeToData()!!.bytes)
            return Drawn(Bitmap.makeFromImage(image))
        } finally {
            scene.close()
        }
    }

    @Composable
    private fun Settings(ui: RexUiState) {
        TonoTheme(ui) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box { AppearanceSettings(ui) }
            }
        }
    }

    @Test
    fun `without Omarchy and with nothing chosen, the app is in its default theme and its own font`() {
        val ui = app()
        val commands = RexCommands(ui).also { it.declare() }
        val scheme = omarchyColorScheme(bundledOmarchyPalettes.first { it.slug == DEFAULT_THEME })
        val drawn = draw("app-default") { TonoTheme(ui) { RexApp(ui, commands) } }
        assertEquals(DEFAULT_THEME, ui.appearance.palette.slug)
        assertEquals(BUNDLED_FONT, ui.appearance.fontFamily)
        // The window's own surface at its edge.
        assertTrue(drawn.near(2, 400, scheme.surfaceContainerLow), "the sidebar is the default theme's")
        assertTrue(drawn.count(scheme.background) > 10_000, "the panel is the default theme's background")
    }

    @Test
    fun `the app in each of several themes is in that theme's colors`() {
        for (slug in listOf("catppuccin-latte", "gruvbox", "white", "nord", "rose-pine")) {
            val ui = app(AppearanceChoice(theme = slug))
            val commands = RexCommands(ui).also { it.declare() }
            val scheme = omarchyColorScheme(bundledOmarchyPalettes.first { it.slug == slug })
            val drawn = draw("app-$slug") { TonoTheme(ui) { RexApp(ui, commands) } }
            assertTrue(drawn.near(2, 400, scheme.surfaceContainerLow), slug)
            assertTrue(drawn.count(scheme.background) > 10_000, slug)
        }
    }

    @Test
    fun `the theme picker shows the theme under the highlight, and the one chosen when it closes`() {
        val ui = app(AppearanceChoice(theme = "nord"))
        val commands = RexCommands(ui).also { it.declare() }
        ui.palette = PaletteMode.Themes
        fun scheme(slug: String) = omarchyColorScheme(bundledOmarchyPalettes.first { it.slug == slug })
        val scene = ImageComposeScene(1100, 720, Density(1f)) { TonoTheme(ui) { RexApp(ui, commands) } }
        var millis = 0L
        fun draw(name: String): Drawn {
            repeat(20) {
                millis += 16
                scene.render(millis * 1_000_000)
                Snapshot.sendApplyNotifications()
                Thread.sleep(5)
            }
            val image = scene.render(millis * 1_000_000)
            File(outputDir, "$name.png").writeBytes(image.encodeToData()!!.bytes)
            return Drawn(Bitmap.makeFromImage(image))
        }
        fun press(key: Key) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
        }
        try {
            // It opens on the theme in effect, so nothing changes by opening it.
            val open = draw("picker-nord")
            assertEquals("nord", ui.appearance.palette.slug)
            assertTrue(open.count(scheme("nord").surfaceContainerHigh) > 5_000, "the picker is drawn in the theme in effect")

            // Down the list: each theme shows as its line is reached, and nothing is kept.
            val after = builtInThemes.map { it.slug }.let { it[it.indexOf("nord") + 1] }
            press(Key.DirectionDown)
            val tried = draw("picker-trying-$after")
            assertEquals(after, ui.appearance.palette.slug)
            assertTrue(tried.count(scheme(after).surfaceContainerHigh) > 5_000, "the picker is drawn in the theme tried")
            assertEquals("nord", ui.client.appearance.value.theme)

            // Escape: what was chosen shows again.
            press(Key.Escape)
            draw("picker-closed")
            assertEquals(null, ui.palette)
            assertEquals("nord", ui.appearance.palette.slug)

            // Opened again, two down and Enter: that theme is chosen.
            ui.palette = PaletteMode.Themes
            draw("picker-again")
            press(Key.DirectionDown)
            press(Key.DirectionDown)
            draw("picker-two-down")
            press(Key.Enter)
            val chosen = draw("picker-chosen")
            val second = builtInThemes.map { it.slug }.let { it[it.indexOf("nord") + 2] }
            assertEquals(second, ui.client.appearance.value.theme)
            assertEquals(second, ui.appearance.palette.slug)
            assertTrue(chosen.near(2, 400, scheme(second).surfaceContainerLow))
        } finally {
            scene.close()
        }
    }

    @Test
    fun `the Appearance settings in several themes and fonts`() {
        val themes = listOf(null, "catppuccin-latte", "kanagawa", "white")
        val pictures = themes.map { slug ->
            val ui = app(AppearanceChoice(theme = slug))
            val palette = bundledOmarchyPalettes.first { it.slug == (slug ?: DEFAULT_THEME) }
            val drawn = draw("settings-${slug ?: "default"}", width = 760, height = 900) { Settings(ui) }
            assertTrue(drawn.near(3, 3, hexToColor(palette.colors.getValue("background"))), "$slug: the section is on the theme's background")
            // The sample is on the terminal's background, in the terminal's colors.
            assertTrue(drawn.count(hexToColor(palette.terminalColors().ansi[2])) > 0, "$slug: the sample has the theme's green")
            drawn
        }
        assertEquals(themes.size, pictures.map { it.bitmap.getColor(3, 3) }.distinct().size)
    }

    @Test
    fun `a heavier weight puts more ink in a terminal's text, and a bigger size bigger cells`() {
        /** How much darker than white a line of letters comes out in the terminals' font, and how wide a cell is. */
        fun ink(name: String, chosen: AppearanceChoice): Pair<Long, Float> {
            val ui = app(chosen)
            var cell = 0f
            val drawn = draw(name, width = 900, height = 80) {
                TonoTheme(ui) {
                    val font = LocalTerminalFont.current!!
                    val style = TextStyle(fontFamily = font.family, fontWeight = font.weight, fontSize = ui.fontSize.sp, color = Color.Black)
                    cell = measureCell(rememberTextMeasurer(cacheSize = 0), style, LocalDensity.current, LocalFontFamilyResolver.current).width
                    Box(Modifier.fillMaxSize().background(Color.White)) { BasicText("The quick brown fox jumps over the lazy dog 0123456789", style = style.copy(fontSize = 28.sp)) }
                }
            }
            val bitmap = drawn.bitmap
            return (0 until bitmap.width).sumOf { x -> (0 until bitmap.height).sumOf { y -> 255L - (bitmap.getColor(x, y) and 0xFF) } } to cell
        }
        val (light, cell) = ink("font-own-light", AppearanceChoice(fontWeight = 300))
        val (regular, _) = ink("font-own-regular", AppearanceChoice())
        val (medium, _) = ink("font-own-medium", AppearanceChoice(fontWeight = 500))
        val (_, bigCell) = ink("font-own-18", AppearanceChoice(fontSize = 18))
        assertTrue(light * 1.05 < regular && regular * 1.05 < medium, "light $light, regular $regular, medium $medium")
        assertEquals(cell * 18 / 13, bigCell, 0.05f)
    }

    @Test
    fun `an installed font chosen is the one terminals are drawn in, and one that isn't installed is not`() {
        val installed = dev.mg.omarchy.theme.readInstalledMonospaceFonts().firstOrNull() ?: return // a machine with no fontconfig has nothing to choose
        var found: TerminalFont? = null
        val ui = app(AppearanceChoice(fontFamily = installed.family))
        ui.appearance.installedFonts = listOf(TerminalFontInfo(installed.family, installed.weights))
        draw("font-installed", width = 760, height = 900) {
            TonoTheme(ui) {
                found = LocalTerminalFont.current
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Box { AppearanceSettings(ui) } }
            }
        }
        assertEquals(installed.family, ui.appearance.fontFamily)
        assertNotNull(installedFontIfReady(installed.family, 400))
        assertEquals(installedFontIfReady(installed.family, 400), found?.family)

        // A font that was chosen and has since gone: the app's own stands in, not the toolkit's default.
        assertEquals(null, installedFontIfReady("No Such Font Anywhere", 400))
        var fallback: TerminalFont? = null
        var own: FontFamily? = null
        val gone = app(AppearanceChoice(fontFamily = "No Such Font Anywhere"))
        draw("font-gone", width = 760, height = 400) {
            TonoTheme(gone) {
                fallback = LocalTerminalFont.current
                own = bundledFontFamily()
                Box {}
            }
        }
        assertEquals(BUNDLED_FONT, gone.appearance.fontFamily)
        assertEquals(own, fallback?.family)
    }

    /** backlog-040: the platform's monospace has a blank a third as wide as its letters. The app's own font must not. */
    @Test
    fun `in the app's own font a blank is as wide as a letter, at every weight and size`() {
        for (weight in BUNDLED_FONT_WEIGHTS) for (size in listOf(11, 13, 18)) {
            val ui = app(AppearanceChoice(fontWeight = weight, fontSize = size))
            var letters = 0f
            var blanks = 0f
            var cell = 0f
            draw("cells-$weight-$size", width = 200, height = 60) {
                TonoTheme(ui) {
                    val font = LocalTerminalFont.current!!
                    assertEquals(weight, font.weight.weight)
                    val style = TextStyle(fontFamily = font.family, fontWeight = font.weight, fontSize = ui.fontSize.sp)
                    val measurer = rememberTextMeasurer(cacheSize = 0)
                    letters = measurer.measure("M".repeat(100), style, softWrap = false, maxLines = 1).getHorizontalPosition(100, true)
                    blanks = measurer.measure(" ".repeat(100) + "|", style, softWrap = false, maxLines = 1).getHorizontalPosition(100, true)
                    cell = measureCell(measurer, style, LocalDensity.current, LocalFontFamilyResolver.current).width
                }
            }
            assertTrue(letters > 0f)
            assertEquals(letters, blanks, 0.01f, "weight $weight, size $size")
            assertEquals(letters / 100, cell, 0.01f)
        }
    }

    /** A scene that is kept, and drawn on for long enough that a size waiting to be said is said. */
    private class Kept(content: @Composable () -> Unit) : AutoCloseable {
        private val scene = ImageComposeScene(600, 300, Density(1f)) { content() }
        private var millis = 0L

        // A size is said 150 ms after the cells last changed, by the clock on the wall.
        fun settle() = repeat(25) {
            millis += 16
            scene.render(millis * 1_000_000)
            Snapshot.sendApplyNotifications()
            Thread.sleep(12)
        }

        override fun close() = scene.close()
    }

    @Test
    fun `no size is asked for while cells change under a hold, one when it ends changed, none when it ends as it was`() {
        var width by mutableStateOf(8f)
        var hold by mutableStateOf(false)
        val asked = mutableListOf<GridSize>()
        Kept { DesiredGrid("terminal", IntSize(800, 400), remember(width) { CellSize(width, 20f) }, hold) { asked += it } }.use { scene ->
            scene.settle()
            assertEquals(listOf(GridSize(100, 20, 8, 20)), asked)

            // A picker opens and three fonts are gone through.
            hold = true
            for (tried in listOf(8f, 10f, 7f, 9f)) {
                width = tried
                scene.settle()
            }
            assertEquals(1, asked.size, "nothing is asked while trying")
            // Escape: the cells are as they were, and nothing is asked.
            width = 8f
            hold = false
            scene.settle()
            assertEquals(1, asked.size, "nothing is asked when what was tried is dropped")

            // Again, and this time the font tried is kept.
            hold = true
            width = 10f
            scene.settle()
            width = 16f
            scene.settle()
            assertEquals(1, asked.size)
            hold = false
            scene.settle()
            assertEquals(listOf(GridSize(100, 20, 8, 20), GridSize(50, 20, 16, 20)), asked, "the size is asked for once, when the font is chosen")

            // Without a hold a change of cells is asked for as before: the font size keys.
            width = 20f
            scene.settle()
            assertEquals(3, asked.size)
        }
    }

    @Test
    fun `going through the font picker asks no terminal size of the server, choosing a font asks one, and Escape none`() {
        val installed = dev.mg.omarchy.theme.readInstalledMonospaceFonts().map { TerminalFontInfo(it.family, it.weights) }
        val ui = app()
        ui.appearance.installedFonts = installed
        val commands = RexCommands(ui)
        val asked = mutableListOf<GridSize>()
        val cells = mutableMapOf<String?, Float>()
        Kept {
            TonoTheme(ui) {
                val font = LocalTerminalFont.current!!
                val style = TextStyle(fontFamily = font.family, fontWeight = font.weight, fontSize = ui.fontSize.sp)
                val measurer = rememberTextMeasurer(cacheSize = 0)
                val cell = remember(style) { measureCell(measurer, style, Density(1f)) }
                cells[ui.appearance.fontFamily] = cell.width
                DesiredGrid("terminal", IntSize(900, 500), cell, ui.appearance.tryingFont) { asked += it }
            }
        }.use { scene ->
            fun fonts() = paletteItems(ui, commands, org.example.kotlintest.rex.Keymap(emptyList()), PaletteMode.Fonts)
            scene.settle()
            assertEquals(1, asked.size, "the size is said once at the start")

            // Down the whole list, as the arrow keys go: every font is drawn, and none is asked for.
            for (item in fonts()) {
                item.onHighlight!!()
                assertTrue(ui.appearance.tryingFont)
                scene.settle()
            }
            assertEquals(1, asked.size, "nothing is asked while going through the list")
            // Escape.
            ui.appearance.endTrying()
            scene.settle()
            assertEquals(1, asked.size, "nothing is asked when the list is closed without choosing")
            assertEquals(BUNDLED_FONT, ui.appearance.fontFamily)

            // A font with cells of another size, gone to and chosen. A machine with none has no more to show.
            val other = fonts().firstOrNull { item -> cells.entries.any { (family, width) -> family == item.title && width != cells[BUNDLED_FONT] } } ?: return
            other.onHighlight!!()
            scene.settle()
            assertEquals(1, asked.size)
            other.run()
            scene.settle()
            assertEquals(2, asked.size, "the size is asked for once, when the font is chosen")
            assertEquals(other.title, ui.client.appearance.value.fontFamily)

            // The weights of the font now chosen, tried and dropped.
            for (item in paletteItems(ui, commands, org.example.kotlintest.rex.Keymap(emptyList()), PaletteMode.FontWeights)) {
                item.onHighlight!!()
                scene.settle()
            }
            ui.appearance.endTrying()
            scene.settle()
            assertEquals(2, asked.size, "nothing is asked for weights tried and dropped")
        }
    }

    @Test
    fun `with Omarchy, nothing chosen follows the desktop, and a theme chosen replaces it`() {
        val retro = bundledOmarchyPalettes.first { it.slug == "retro-82" }
        val ui = app()
        ui.appearance.desktop = OmarchyAppearance(retro, fontName = null)
        val followed = draw("omarchy-followed", width = 760, height = 900) { Settings(ui) }
        assertTrue(followed.near(3, 3, hexToColor(retro.colors.getValue("background"))))
        ui.appearance.chooseTheme("white")
        val chosen = draw("omarchy-overridden", width = 760, height = 900) { Settings(ui) }
        assertTrue(chosen.near(3, 3, Color.White))
        assertNotEquals(followed.bitmap.getColor(3, 3), chosen.bitmap.getColor(3, 3))
    }
}
