package org.example.kotlintest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.mg.omarchy.theme.bundledOmarchyPalettes
import dev.mg.omarchy.theme.compose.omarchyColorScheme
import dev.mg.omarchy.theme.compose.omarchyShapes
import org.example.kotlintest.rex.ActivityState
import org.example.kotlintest.rex.HostConnection
import org.example.kotlintest.rex.HostState
import org.example.kotlintest.rex.SessionSummary
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Draws the sidebar's host headings offscreen, as the desktop draws them, one for each thing
 * a host's connection can be, and looks at the pixels. The picture is left in
 * `<project>/build/host-renders/` to be looked at.
 */
class HostHeaderRenderTest {
    private val outputDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "project.yaml").isFile }
        .resolve("build/host-renders")
        .apply { mkdirs() }

    private val scheme = omarchyColorScheme(bundledOmarchyPalettes.first { it.slug == "retro-82" })

    private val connected = HostState(connection = HostConnection.Connected, reached = true)
    private val connecting = HostState()
    private val reconnecting = HostState(connection = HostConnection.Connecting, failed = true, reached = true)
    private val down = HostState(connection = HostConnection.Connecting, failed = true)

    /** What each heading shows, top to bottom. All but the first are remote machines. */
    private val rows = listOf(connected, connecting, reconnecting, down)

    private val rowHeight = 56
    private val width = 240

    /** [hover] is the row the pointer rests on, if any, long enough for its button to have faded in. */
    private fun draw(atMillis: Long, name: String, hover: Int? = null): Bitmap {
        val scene = ImageComposeScene(width = width, height = rowHeight * rows.size, density = Density(1f)) {
            MaterialTheme(colorScheme = scheme, shapes = omarchyShapes()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.width(width.dp)) {
                        rows.forEachIndexed { index, state ->
                            Box(Modifier.height(rowHeight.dp)) {
                                HostHeader(
                                    label = "host $index", state = state, isHome = false, collapsed = false,
                                    activity = ActivityState.Idle, bell = false, onClick = {}, onNewSession = {}, onRetry = {},
                                )
                            }
                        }
                    }
                }
            }
        }
        try {
            scene.render(0)
            if (hover != null) {
                scene.sendPointerEvent(PointerEventType.Move, Offset(width / 2f, hover * rowHeight + 20f))
                // The heading hears of the pointer in one frame and its button fades in over the next ones.
                for (millis in 16 until atMillis step 16) scene.render(millis * 1_000_000)
            }
            val image: Image = scene.render(atMillis * 1_000_000)
            File(outputDir, "$name.png").writeBytes(image.encodeToData()!!.bytes)
            return Bitmap.makeFromImage(image)
        } finally {
            scene.close()
        }
    }

    private fun far(pixel: Int, color: Color): Int {
        val want = color.toArgb()
        return (0..16 step 8).maxOf { shift -> abs(((pixel shr shift) and 0xFF) - ((want shr shift) and 0xFF)) }
    }

    /** The picture of a machine is 16 across, 12 in from the left; the heading's row starts 12 down. */
    private fun Bitmap.picture(row: Int): List<Int> =
        (row * rowHeight + 12 until row * rowHeight + 34).flatMap { y -> (12 until 28).map { x -> getColor(x, y) } }

    private fun Bitmap.pictureHas(row: Int, color: Color) = picture(row).any { far(it, color) <= 6 }

    /** How far the picture's strongest pixel is from the sidebar's own color: how plainly it is drawn. */
    private fun Bitmap.pictureStrength(row: Int) = picture(row).maxOf { far(it, scheme.surfaceContainerLow) }

    /** Whether anything is drawn in the line under the heading's name, where "No sessions" goes. */
    private fun Bitmap.hasWordsUnder(row: Int): Boolean =
        (row * rowHeight + 34 until (row + 1) * rowHeight).any { y -> (12 until width / 2).any { x -> far(getColor(x, y), scheme.surfaceContainerLow) > 24 } }

    @Test
    fun `each state of a host's connection has a heading of its own`() {
        val image = draw(atMillis = 0, name = "host-headings")
        val quiet = scheme.onSurfaceVariant
        val error = scheme.error.copy(alpha = 0.85f).compositeOver(scheme.surfaceContainerLow)

        assertTrue(image.pictureHas(0, quiet), "a connected host's picture is in the quiet color")
        assertTrue(image.hasWordsUnder(0), "and with no sessions it says so")

        // Being reached for the first time: the picture is there, dimmer than a connected host's
        // at its brightest, never in the error color, with a word under the name.
        assertTrue(!image.pictureHas(1, quiet) && !image.pictureHas(1, error), "connecting is neither connected nor down")
        assertTrue(image.pictureStrength(1) in 20 until image.pictureStrength(0), "dimmed, and still to be seen: ${image.pictureStrength(1)} of ${image.pictureStrength(0)}")
        assertTrue(image.hasWordsUnder(1), "Connecting…")

        // Tried again after being connected: the error color, and a word.
        assertTrue(image.pictureHas(2, error), "reconnecting is in the error color")
        assertTrue(image.hasWordsUnder(2), "Reconnecting…")

        // Never answered: the error color and nothing more, as before.
        assertTrue(image.pictureHas(3, error), "down is in the error color")
        assertTrue(!image.hasWordsUnder(3), "and takes no line to say so")
    }

    @Test
    fun `the picture of a host being connected to breathes, and no other does`() {
        val dim = draw(atMillis = 0, name = "host-headings-breath-out")
        val bright = draw(atMillis = CONNECTING_BREATH_MILLIS.toLong(), name = "host-headings-breath-in")
        assertTrue(bright.pictureStrength(1) > dim.pictureStrength(1) + 20, "from ${dim.pictureStrength(1)} to ${bright.pictureStrength(1)}")
        assertTrue(bright.pictureStrength(1) < bright.pictureStrength(0), "at its brightest it is still dimmer than a connected host's")
        for (row in listOf(0, 2, 3)) assertEquals(dim.pictureStrength(row), bright.pictureStrength(row), "row $row is steady")
        // A host that has failed once doesn't go back to breathing while it is tried again.
        assertEquals(dim.picture(3), bright.picture(3))
    }

    /** Whether anything is drawn at the right end of a heading's row, where its buttons are. */
    private fun Bitmap.hasButton(row: Int): Boolean =
        (row * rowHeight + 12 until row * rowHeight + 34).any { y -> (width - 30 until width - 6).any { x -> far(getColor(x, y), scheme.surfaceContainerLow) > 24 } }

    @Test
    fun `under the pointer, a host that failed offers another try and one still on its first does not`() {
        for (row in rows.indices) assertTrue(!draw(atMillis = 0, name = "host-headings").hasButton(row), "row $row, with no pointer")
        fun hovered(row: Int) = draw(atMillis = 600, name = "host-headings-hover-$row", hover = row).hasButton(row)
        assertTrue(hovered(0), "a connected host offers a new session")
        assertTrue(!hovered(1), "a first attempt has nothing to try again")
        assertTrue(hovered(2), "a lost host can be tried now")
        assertTrue(hovered(3), "and so can one that never answered")
        assertTrue(!offersRetry(HostState(connection = HostConnection.Disconnected("Invalid endpoint", permanent = true), failed = true)), "not an address that can't be read")
    }

    @Test
    fun `sessions are listed only under a host that is connected`() {
        val sessions = listOf(SessionSummary("s", "work"))
        val up = connected.copy(sessions = sessions)
        assertTrue(listsSessions(up, collapsed = false))
        assertTrue(!listsSessions(up, collapsed = true))
        // Lost: what it last said is kept in its state and not shown, between tries and during them.
        val lost = up.copy(connection = HostConnection.Disconnected("it went away"), failed = true)
        assertEquals(sessions, lost.sessions)
        assertTrue(!listsSessions(lost, collapsed = false))
        assertTrue(!listsSessions(lost.copy(connection = HostConnection.Connecting), collapsed = false))
        assertTrue(!listsSessions(down.copy(sessions = sessions), collapsed = false))
        // Back: listed again.
        assertTrue(listsSessions(lost.copy(connection = HostConnection.Connected, failed = false), collapsed = false))
    }

    @Test
    fun `the states are told apart in words too`() {
        assertNull(hostReachWords(connected.reach))
        assertEquals("connecting", hostReachWords(connecting.reach))
        assertEquals("reconnecting", hostReachWords(reconnecting.reach))
        assertEquals("not connected", hostReachWords(down.reach))
    }
}
