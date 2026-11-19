package org.example.kotlintest

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.mg.omarchy.theme.bundledOmarchyPalettes
import dev.mg.omarchy.theme.compose.omarchyColorScheme
import dev.mg.omarchy.theme.compose.omarchyShapes
import org.example.kotlintest.rex.ActivityState
import org.example.kotlintest.rex.TabName
import org.example.kotlintest.rex.TerminalProgress
import org.example.kotlintest.rex.TerminalProgress.Kind
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Draws the sidebar's tab rows offscreen, as the desktop draws them, with a bell and with
 * each kind of progress, and looks at the pixels. The picture is left in
 * `<project>/build/tab-renders/` to be looked at.
 */
class TabRowRenderTest {
    private val outputDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "project.yaml").isFile }
        .resolve("build/tab-renders")
        .apply { mkdirs() }

    private val scheme = omarchyColorScheme(bundledOmarchyPalettes.first { it.slug == "retro-82" })

    /** What each row shows, top to bottom. */
    private val rows: List<Pair<Boolean, TerminalProgress?>> = listOf(
        false to null,
        true to null,
        false to TerminalProgress(Kind.Normal, 50),
        false to TerminalProgress(Kind.Error, 50),
        false to TerminalProgress(Kind.Paused, 50),
        false to TerminalProgress(Kind.Indeterminate),
        true to TerminalProgress(Kind.Normal, 100),
        false to TerminalProgress(Kind.Error),
    )

    private val rowHeight = 30
    private val width = 240

    /** The rows drawn at one pixel to the dp, [atMillis] into their animations. */
    private fun draw(atMillis: Long, name: String): Bitmap {
        val scene = ImageComposeScene(width = width, height = rowHeight * rows.size, density = Density(1f)) {
            MaterialTheme(colorScheme = scheme, shapes = omarchyShapes()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.width(width.dp)) {
                        rows.forEachIndexed { index, (bell, progress) ->
                            TabRow(
                                name = TabName("tab $index", "~/dev"), digit = null, digitAlpha = 0f, showing = false,
                                activity = ActivityState.Idle, bell = bell, progress = progress, dragging = false, onClick = {},
                                modifier = Modifier.height(rowHeight.dp),
                            )
                        }
                    }
                }
            }
        }
        try {
            scene.render(0)
            val image: Image = scene.render(atMillis * 1_000_000)
            File(outputDir, "$name.png").writeBytes(image.encodeToData()!!.bytes)
            return Bitmap.makeFromImage(image)
        } finally {
            scene.close()
        }
    }

    private fun near(pixel: Int, color: Color): Boolean {
        val want = color.toArgb()
        return (0..16 step 8).all { shift -> abs(((pixel shr shift) and 0xFF) - ((want shr shift) and 0xFF)) <= 6 }
    }

    /** The columns of a row in which some pixel is [color], within the strip the bar is drawn in or, with [everywhere], the whole row. */
    private fun Bitmap.columnsOf(row: Int, color: Color, everywhere: Boolean = false): List<Int> {
        val ys = if (everywhere) (row * rowHeight) until (row + 1) * rowHeight else ((row + 1) * rowHeight - 6) until (row + 1) * rowHeight
        return (0 until width).filter { x -> ys.any { y -> near(getColor(x, y), color) } }
    }

    @Test
    fun `a row draws its bell and its progress, and a plain row draws neither`() {
        val image = draw(atMillis = 700, name = "tab-rows")
        val accent = scheme.primary
        val failed = scheme.error
        val paused = scheme.onSurfaceVariant

        assertEquals(emptyList(), image.columnsOf(0, accent, everywhere = true), "a plain row has nothing in the accent color")
        assertEquals(emptyList(), image.columnsOf(0, failed, everywhere = true))

        // The bell is a small mark at the row's right, by the dots and the digit.
        val bell = image.columnsOf(1, accent, everywhere = true)
        assertTrue(bell.isNotEmpty() && bell.first() > width / 2 && bell.last() - bell.first() < 14, "the bell: $bell")
        assertEquals(emptyList(), image.columnsOf(1, accent), "and no bar under it")

        // Half done: the bar starts under the text and stops halfway along its track.
        val track = (14 + 6) until (width - 6 - 8) // the row is inset 6 from the sidebar's edges
        val half = image.columnsOf(2, accent)
        val middle = (track.first + track.last) / 2
        assertTrue(abs(half.first() - track.first) <= 1 && abs(half.last() - middle) <= 2, "50%: ${half.first()}..${half.last()} of $track")
        assertTrue(abs(image.columnsOf(3, failed).last() - middle) <= 2, "a failure at 50% is as long, in the error color")
        assertEquals(emptyList(), image.columnsOf(3, accent))
        assertTrue(abs(image.columnsOf(4, paused).last() - middle) <= 2, "and so is a pause, in a muted one")

        // Busy with no percentage: a piece of the track, somewhere along it.
        val busy = image.columnsOf(5, accent)
        assertTrue(busy.isNotEmpty() && busy.size < track.count() / 2 && busy.first() >= track.first && busy.last() <= track.last + 1, "busy: $busy")

        // Done, with a bell: the whole track, and the bell above it.
        val whole = image.columnsOf(6, accent)
        assertTrue(abs(whole.first() - track.first) <= 1 && abs(whole.last() - track.last) <= 2, "100%: ${whole.first()}..${whole.last()} of $track")
        assertTrue(image.columnsOf(6, accent, everywhere = true).size > whole.size - 2)
        val broken = image.columnsOf(7, failed)
        assertTrue(abs(broken.first() - track.first) <= 1 && abs(broken.last() - track.last) <= 2, "a failure that names no percentage fills the track")
    }

    @Test
    fun `the piece that says only busy moves along the track`() {
        val early = draw(atMillis = 300, name = "tab-rows-busy-early").columnsOf(5, scheme.primary)
        val late = draw(atMillis = 900, name = "tab-rows-busy-late").columnsOf(5, scheme.primary)
        assertTrue(early.isNotEmpty() && late.isNotEmpty() && late.first() > early.first(), "from $early to $late")
    }
}
