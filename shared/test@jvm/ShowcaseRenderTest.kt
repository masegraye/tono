package org.example.kotlintest

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders every showcase tab offscreen, in each bundled Omarchy palette where it matters.
 * This catches composition crashes on tabs nobody clicked, and leaves PNGs in
 * `<project>/build/showcase-renders/` for a visual check.
 */
class ShowcaseRenderTest {
    // Tests run from the module directory; write into the project's (git-ignored) build dir.
    private val outputDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "project.yaml").isFile }
        .resolve("build/showcase-renders")
        .apply { mkdirs() }

    private fun render(name: String, palette: OmarchyPalette, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = 1100, height = 1900, density = Density(1.5f)) {
            MaterialTheme(colorScheme = omarchyColorScheme(palette), shapes = omarchyShapes()) {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        try {
            scene.render(0)
            val image = scene.render(600_000_000L) // advance 600ms so enter animations settle
            val bytes = image.encodeToData()?.bytes
            assertTrue(bytes != null && bytes.size > 5_000, "$name rendered an empty image")
            File(outputDir, "$name.png").writeBytes(bytes)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `every section renders`() {
        val retro = bundledOmarchyPalettes.first { it.slug == "retro-82" }
        render("1-components", retro) { SectionBasics() }
        render("2-inputs", retro) { SectionInputs() }
        render("3-lists", retro) { SectionLists() }
        render("4-motion", retro) { SectionMotion() }
        render("5-canvas", retro) { SectionCanvas() }
        render("7-platform", retro) { SectionPlatform() }
    }

    @Test
    fun `theming section renders in every bundled palette`() {
        for (palette in bundledOmarchyPalettes) {
            render("6-theming-${palette.slug}", palette) {
                SectionTheming(
                    palette = palette,
                    live = false,
                    selectedSlug = palette.slug,
                    dark = palette.isDark,
                    onSelect = {},
                    onDarkToggle = {},
                )
            }
        }
    }
}
