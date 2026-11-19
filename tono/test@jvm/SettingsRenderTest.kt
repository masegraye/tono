package org.example.kotlintest

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import dev.mg.omarchy.theme.bundledOmarchyPalettes
import dev.mg.omarchy.theme.compose.omarchyColorScheme
import dev.mg.omarchy.theme.compose.omarchyShapes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.example.kotlintest.rex.RexClient
import org.example.kotlintest.rex.SavedHost
import org.example.kotlintest.rex.Workspace
import org.example.kotlintest.rex.WorkspaceFile
import org.example.kotlintest.rex.load
import org.jetbrains.skia.Bitmap
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The settings as the desktop draws them, offscreen, at the widths a tiling window manager
 * gives a window: a third of a screen, a half, and all of it. The pictures are left in
 * `<project>/build/settings-renders/` to be looked at, one for each section at each width.
 */
class SettingsRenderTest {
    private val outputDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "project.yaml").isFile }
        .resolve("build/settings-renders")
        .apply { mkdirs() }

    private val scheme = omarchyColorScheme(bundledOmarchyPalettes.first { it.slug == "retro-82" })
    private val directory = Files.createTempDirectory("workspace")
    private val file = directory.resolve("workspace.json")
    private val connections = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // A host that isn't there, so no server is asked and nothing of anyone's is touched.
    private val client = RexClient(connections, Workspace(WorkspaceFile(file), listOf(SavedHost("Local", "unix://${directory.resolve("absent.sock")}"))))
    private val ui = RexUiState(client)
    private val commands = RexCommands(ui).also { it.declare() }

    @AfterTest
    fun close() {
        client.close()
        connections.cancel()
    }

    private inner class Settings(val width: Int, val height: Int, closes: Boolean) : AutoCloseable {
        val scene = ImageComposeScene(width, height, Density(1f)) {
            MaterialTheme(colorScheme = scheme, shapes = omarchyShapes()) {
                // As the main window does while it is there.
                LaunchedEffect(Unit) { ui.followSettings() }
                SettingsContent(ui, commands, Modifier.fillMaxSize(), onClose = if (closes) ui::closeSettings else null)
            }
        }
        private var millis = 0L

        fun draw(name: String? = null): Bitmap {
            // A few frames, so that a switch has finished sliding.
            repeat(30) {
                millis += 16
                scene.render(millis * 1_000_000)
                // What something outside the picture changed is told to it by the toolkit's
                // thread in the app. Here that thread has had no time to, so it is done for it.
                Snapshot.sendApplyNotifications()
            }
            val image = scene.render(millis * 1_000_000)
            if (name != null) File(outputDir, "$name.png").writeBytes(image.encodeToData()!!.bytes)
            return Bitmap.makeFromImage(image)
        }

        fun click(at: Offset) {
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at)
            scene.sendPointerEvent(PointerEventType.Release, at)
            scene.sendPointerEvent(PointerEventType.Move, Offset(2f, height - 2f))
        }

        override fun close() = scene.close()
    }

    private fun settings(width: Int, height: Int = 680, closes: Boolean = false) = Settings(width, height, closes).also { it.draw() }

    private fun near(pixel: Int, color: Color, within: Int = 6): Boolean {
        val want = color.toArgb()
        return (0..16 step 8).all { shift -> abs(((pixel shr shift) and 0xFF) - ((want shr shift) and 0xFF)) <= within }
    }

    // The line last clicked keeps the keyboard, and is lit a little to show it, switch and all.
    private fun isSwitchOn(pixel: Int) = near(pixel, scheme.primary, within = 30)

    private fun Bitmap.has(color: Color, columns: IntRange = 0 until width) = columns.any { x -> (0 until height).any { y -> near(getColor(x, y), color) } }

    /** The rows where a switch that is on is drawn: each run of lines that have its color in them, as its middle. */
    private fun Bitmap.switchesOn(): List<Offset> {
        val lines = (0 until height).filter { y -> (0 until width).any { x -> isSwitchOn(getColor(x, y)) } }
        val runs = ArrayList<IntRange>()
        for (y in lines) if (runs.isNotEmpty() && runs.last().last == y - 1) runs[runs.lastIndex] = runs.last().first..y else runs += y..y
        // A switch is some twenty lines high; a line of text in the color would be less.
        return runs.filter { it.count() >= 20 }.map { run ->
            val y = (run.first + run.last) / 2
            val across = (0 until width).filter { x -> isSwitchOn(getColor(x, y)) }
            Offset((across.first() + across.last()) / 2f, y.toFloat())
        }
    }

    @Test
    fun `every section is drawn at a third, a half and the whole of a screen`() {
        for (width in listOf(420, 700, 1100)) {
            for (section in SettingsSection.entries) {
                ui.settings.section = section
                val name = "${section.name.lowercase()}-$width"
                File(outputDir, "$name.png").delete()
                settings(width).use { it.draw(name) }
                assertTrue(File(outputDir, "$name.png").length() > 0, name)
            }
        }
    }

    @Test
    fun `wide, the list of sections is beside the one chosen, which is marked in it`() {
        for (width in listOf(700, 1100)) {
            ui.settings.section = null
            settings(width).use { settings ->
                val first = settings.draw()
                // Nothing chosen yet: the first section shows, marked in the list down the left.
                assertTrue(first.has(scheme.secondaryContainer, 0 until 200), "$width: the first section is marked")
                assertEquals(6, first.switchesOn().size, "$width: Notifications shows first, with its six switches, all on")
                assertTrue(first.switchesOn().all { it.x > 200 && it.x < width }, "$width: the switches are beside the list, and within the window")
            }
        }
    }

    @Test
    fun `narrow, the list comes first, and choosing a section shows it with a way back`() {
        ui.settings.section = null
        settings(420).use { settings ->
            val list = settings.draw("list-420")
            assertFalse(list.has(scheme.secondaryContainer), "nothing is chosen in the list")
            assertTrue(list.switchesOn().isEmpty(), "and no section is showing")

            // The second line of the list: Terminal.
            settings.click(Offset(120f, 48f + 62f + 30f))
            assertEquals(SettingsSection.Terminal, ui.settings.section)
            val section = settings.draw("list-420-chosen")
            assertTrue(section.switchesOn().isEmpty(), "Terminal's two switches start off")

            // The way back, at the start of the heading.
            settings.click(Offset(30f, 36f))
            assertEquals(null, ui.settings.section)
        }
    }

    @Test
    fun `a click on a switch turns it for the app and keeps it, and a change from elsewhere shows`() {
        ui.settings.section = SettingsSection.Notifications
        settings(700).use { settings ->
            val before = settings.draw().switchesOn()
            assertEquals(6, before.size)
            assertTrue(client.sounds.value)

            // The first switch: Play Activity Sounds. (No sound is played: there is nothing here to play one.)
            settings.click(before.first())
            val after = settings.draw("notifications-700-sounds-off").switchesOn()
            assertFalse(client.sounds.value)
            assertFalse(ui.soundsEnabled)
            assertFalse(Workspace.load(file).sounds)
            assertEquals(before.drop(1), after)

            // Turned back on by something else with the same client: the palette in another window, say.
            // The app hears of it in one frame, and draws it in the ones after.
            client.setSounds(true)
            settings.draw()
            assertTrue(ui.soundsEnabled, "the app follows the client")
            assertEquals(before, settings.draw("notifications-700-sounds-on-again").switchesOn())

            // The kinds of news stay as set while notifications as a whole are off, and are faded.
            client.setNotifications(client.notifications.value.copy(desktop = false))
            settings.draw()
            val off = settings.draw("notifications-700-system-off").switchesOn()
            assertEquals(listOf(before.first()), off, "only the sounds' switch is still in the full color")
        }
    }

    @Test
    fun `a panel has a close button, and a window does not`() {
        ui.settings.section = SettingsSection.About
        ui.openSettings()
        settings(700, closes = true).use { settings ->
            settings.draw("about-700-panel")
            settings.click(Offset(700f - 6f - 8f - 16f, 6f + 6f + 24f))
            assertFalse(ui.settingsOpen)
        }
        ui.openSettings()
        settings(700, closes = false).use { settings ->
            settings.click(Offset(700f - 6f - 8f - 16f, 6f + 6f + 24f))
            assertTrue(ui.settingsOpen)
        }
    }
}
