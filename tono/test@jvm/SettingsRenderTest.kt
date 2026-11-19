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
                // Nothing chosen yet: the first section shows, marked in the list down the left.
                assertTrue(settings.draw("first-$width").has(scheme.secondaryContainer, 0 until 200), "$width: the first section is marked")
                // The second line of the list: Notifications.
                settings.click(Offset(100f, 52f + 48f + 24f))
                assertEquals(SettingsSection.Notifications, ui.settings.section)
                val first = settings.draw()
                assertEquals(6, first.switchesOn().size, "$width: Notifications has six switches, all on")
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

            // The third line of the list: Terminal.
            settings.click(Offset(120f, 52f + 2 * 54f + 27f))
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

    /** Presses a key with Control, as the toolkit reports it. */
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    private fun Settings.press(code: Int, control: Boolean = false) {
        val source = object : java.awt.Component() {}
        for (type in listOf(java.awt.event.KeyEvent.KEY_PRESSED, java.awt.event.KeyEvent.KEY_RELEASED)) {
            val modifiers = if (control) java.awt.event.InputEvent.CTRL_DOWN_MASK else 0
            scene.sendKeyEvent(
                androidx.compose.ui.input.key.KeyEvent(
                    key = androidx.compose.ui.input.key.Key(code),
                    type = if (type == java.awt.event.KeyEvent.KEY_PRESSED) androidx.compose.ui.input.key.KeyEventType.KeyDown else androidx.compose.ui.input.key.KeyEventType.KeyUp,
                    isCtrlPressed = control,
                    nativeEvent = java.awt.event.KeyEvent(source, type, 0L, modifiers, code, java.awt.event.KeyEvent.CHAR_UNDEFINED, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD),
                ),
            )
        }
    }

    @Test
    fun `choosing an action in Shortcuts records a key for it, and its line then offers to put it back`() {
        ui.settings.section = SettingsSection.Shortcuts
        settings(700, height = 900).use { settings ->
            settings.draw("shortcuts-700-before")
            // The first action listed is the first of the app's: the command palette.
            val first = commands.shortcutTargets(client.keymap.value).first()
            assertEquals("client.palette.toggle", first.action)
            // Its line: under the search field, the note, the line with Reset All, and the group's name.
            val line = Offset(400f, 300f)
            settings.click(line)
            assertEquals(first, ui.settings.shortcutEdit, "a click on a line starts recording for its action")
            settings.draw("shortcuts-700-recording")

            // Control+J is nothing's, so it is set without a question. Enter chooses Set.
            settings.press(java.awt.event.KeyEvent.VK_J, control = true)
            settings.draw("shortcuts-700-recorded")
            settings.press(java.awt.event.KeyEvent.VK_ENTER)
            assertEquals(null, ui.settings.shortcutEdit)
            assertEquals(null, ui.settings.confirmation)
            assertEquals(listOf("ctrl+j"), client.shortcuts.value.bindings.single { it.action == "client.palette.toggle" }.keys)
            assertFalse(Workspace.load(file).shortcuts.isEmpty, "and it is kept")
            settings.draw("shortcuts-700-changed")

            // Reset All asks first, and does nothing until answered.
            ui.settings.confirmation = resetAllShortcutsQuestion(1) { client.resetAllShortcuts() }
            assertFalse(client.shortcuts.value.isEmpty)
            ui.settings.confirmation!!.onConfirm()
            ui.settings.confirmation = null
            assertTrue(client.shortcuts.value.isEmpty)
            assertTrue(Workspace.load(file).shortcuts.isEmpty)
        }
    }

    @Test
    fun `where no keyboard has been seen the shortcuts can be read and not changed`() {
        ui.settings.section = SettingsSection.Shortcuts
        ui.keyboardSeen = false
        settings(420, height = 844).use { settings ->
            settings.draw("shortcuts-420-read-only")
            for (y in 200..800 step 40) settings.click(Offset(200f, y.toFloat()))
            assertEquals(null, ui.settings.shortcutEdit, "no line starts recording")
            assertEquals(null, ui.settings.confirmation)
            assertTrue(client.shortcuts.value.isEmpty)
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
