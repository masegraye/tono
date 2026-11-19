package org.example.kotlintest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.mg.omarchy.theme.bundledOmarchyPalettes
import dev.mg.omarchy.theme.compose.omarchyColorScheme
import dev.mg.omarchy.theme.compose.omarchyShapes
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * Every dialog's buttons as the desktop draws them, offscreen, in two themes: a question
 * with the keyboard on the button that does it and then, after Tab, on Cancel; a question
 * that destroys something and one that doesn't; a form. The ring around the button with the
 * keyboard is measured against that button. The pictures are left in
 * `<project>/build/dialog-renders/` to be looked at.
 */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
class DialogButtonsRenderTest {
    private val outputDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "project.yaml").isFile }
        .resolve("build/dialog-renders")
        .apply { mkdirs() }

    /** Retro 82 is gold on blue, and the ring is its gold. Tokyo Night is the other. */
    private val themes = listOf("retro-82", "tokyo-night")

    private val closePane = Confirmation("Close this pane?", "The program running in it will end.", "Close Pane", onConfirm = { confirmed++ })
    private val replace = Confirmation("Ctrl+Shift+K is already a shortcut", "It is the shortcut for “Clear Scrollback”. Setting it for “Close Pane” leaves that without it.", "Replace", destructive = false, onConfirm = { confirmed++ })
    private var confirmed = 0
    private var dismissed = 0

    private inner class Picture(theme: String, val width: Int = 900, val height: Int = 420, content: @Composable () -> Unit) : AutoCloseable {
        val scheme = omarchyColorScheme(bundledOmarchyPalettes.first { it.slug == theme })
        private val source = object : java.awt.Component() {}
        private var millis = 0L
        val scene = ImageComposeScene(width, height, Density(1f)) {
            MaterialTheme(colorScheme = scheme, shapes = omarchyShapes()) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Box { content() } } }
        }

        fun draw(name: String? = null): Bitmap {
            repeat(30) {
                millis += 16
                scene.render(millis * 1_000_000)
                Snapshot.sendApplyNotifications()
            }
            val image = scene.render(millis * 1_000_000)
            if (name != null) File(outputDir, "$name.png").apply { delete() }.writeBytes(image.encodeToData()!!.bytes)
            return Bitmap.makeFromImage(image)
        }

        fun press(key: Key, code: Int) {
            for ((type, awt) in listOf(KeyEventType.KeyDown to AwtKeyEvent.KEY_PRESSED, KeyEventType.KeyUp to AwtKeyEvent.KEY_RELEASED)) {
                scene.sendKeyEvent(KeyEvent(key = key, type = type, nativeEvent = AwtKeyEvent(source, awt, 0L, 0, code, AwtKeyEvent.CHAR_UNDEFINED)))
            }
        }

        override fun close() = scene.close()
    }

    /** Whether a pixel is [color], give or take the smoothing of an edge. */
    private fun near(pixel: Int, color: Color, by: Int): Boolean {
        val want = color.toArgb()
        return (0..2).all { abs(((pixel shr (it * 8)) and 0xff) - ((want shr (it * 8)) and 0xff)) <= by }
    }

    /** The smallest box around every pixel of [color], or null if there is none: left, top, right, bottom, the last two past the end. */
    private fun Bitmap.boxOf(color: Color, within: IntArray? = null): IntArray? {
        fun near(pixel: Int) = near(pixel, color, 2)
        var box: IntArray? = null
        for (y in (within?.get(1) ?: 0) until (within?.get(3) ?: height)) for (x in (within?.get(0) ?: 0) until (within?.get(2) ?: width)) {
            if (!near(getColor(x, y))) continue
            val b = box ?: intArrayOf(x, y, x + 1, y + 1).also { box = it }
            if (x < b[0]) b[0] = x
            if (y < b[1]) b[1] = y
            if (x >= b[2]) b[2] = x + 1
            if (y >= b[3]) b[3] = y + 1
        }
        return box
    }

    /** Where two pictures differ: the smallest box around every pixel that changed. */
    private fun changed(before: Bitmap, after: Bitmap): IntArray? {
        var box: IntArray? = null
        for (y in 0 until before.height) for (x in 0 until before.width) {
            if (before.getColor(x, y) == after.getColor(x, y)) continue
            val b = box ?: intArrayOf(x, y, x + 1, y + 1).also { box = it }
            if (x < b[0]) b[0] = x
            if (y < b[1]) b[1] = y
            if (x >= b[2]) b[2] = x + 1
            if (y >= b[3]) b[3] = y + 1
        }
        return box
    }

    @Test
    fun `the ring is its button's outline, the same gap all round, and Tab moves it to Cancel without moving anything`() {
        for (theme in themes) for ((name, question) in listOf("close-pane" to closePane, "replace" to replace)) {
            confirmed = 0; dismissed = 0
            Picture(theme) { ConfirmationDialog(question, onDismiss = { dismissed++ }) }.use { picture ->
                val scheme = picture.scheme
                val fill = if (question.destructive) scheme.error else scheme.primary
                // The action has the keyboard as the question opens, and Tab takes it to Cancel.
                val onConfirm = picture.draw("$theme-$name-on-confirm")
                picture.press(Key.Tab, AwtKeyEvent.VK_TAB)
                val onCancel = picture.draw("$theme-$name-on-cancel")
                // The filled button, found by its color while the keyboard is elsewhere: with it, the color is tinted.
                val button = assertNotNull(onCancel.boxOf(fill), "$theme $name: the filled button")
                assertEquals(40, button[3] - button[1], "$theme $name: the button is the height it is drawn at")

                // What changed when the keyboard moved on is two rings, one gone and one come, and the tint.
                // Around the filled button the ring stood 2 off and was 2 thick: 4 out on every side.
                val around = intArrayOf(button[0] - 12, 0, picture.width, picture.height)
                // (Its round ends are smoothed, which may tint one pixel more to the left and right.)
                val ring = assertNotNull(changedWithin(onConfirm, onCancel, around), "$theme $name: a ring around the action")
                assertEquals(listOf(button[1] - 4, button[3] + 4), listOf(ring[1], ring[3]), "$theme $name: the ring hugs the filled button, above and below")
                assertTrue(button[0] - ring[0] in 4..5 && ring[2] - button[2] in 4..5, "$theme $name: the ring hugs the filled button, left and right: $ring around ${button.toList()}")
                // Around Cancel it is as tall, on the same line, and left of the filled button.
                val cancelRing = assertNotNull(changedWithin(onConfirm, onCancel, intArrayOf(0, 0, around[0], picture.height)), "$theme $name: a ring around Cancel")
                assertEquals(listOf(button[1] - 4, button[3] + 4), listOf(cancelRing[1], cancelRing[3]), "$theme $name: Cancel's ring is on the same line, the same height")
                assertTrue(cancelRing[2] <= button[0] - 4, "$theme $name: Cancel is left of the action, their rings apart")
                // The ring is drawn in the theme's text color, and is a line: 2 thick, then 2 of the dialog, then the button.
                val middle = (button[0] + button[2]) / 2
                val ringPixels = (button[1] - 8 until button[1]).filter { near(onConfirm.getColor(middle, it), scheme.onSurface, 2) }
                assertEquals(listOf(button[1] - 4, button[1] - 3), ringPixels, "$theme $name: the ring's line above the button")
                assertEquals(onConfirm.getColor(middle, button[1] - 8), onConfirm.getColor(middle, button[1] - 1), "$theme $name: between the ring and the button is the dialog")

                // Enter goes where the ring is: it cancels now, and confirms nothing.
                picture.press(Key.Enter, AwtKeyEvent.VK_ENTER)
                picture.draw()
                assertEquals(0 to 1, confirmed to dismissed, "$theme $name: Enter on Cancel cancels")
                // Back on the action, it confirms.
                picture.press(Key.Tab, AwtKeyEvent.VK_TAB)
                assertEquals(null, changed(onConfirm, picture.draw()), "$theme $name: Tab again, and it is as it opened")
                picture.press(Key.Enter, AwtKeyEvent.VK_ENTER)
                picture.draw()
                assertEquals(1, confirmed, "$theme $name: Enter on the action does it")
            }
        }
    }

    private fun changedWithin(before: Bitmap, after: Bitmap, within: IntArray): List<Int>? {
        var box: IntArray? = null
        for (y in maxOf(0, within[1]) until minOf(before.height, within[3])) for (x in maxOf(0, within[0]) until minOf(before.width, within[2])) {
            if (before.getColor(x, y) == after.getColor(x, y)) continue
            val b = box ?: intArrayOf(x, y, x + 1, y + 1).also { box = it }
            if (x < b[0]) b[0] = x
            if (y < b[1]) b[1] = y
            if (x >= b[2]) b[2] = x + 1
            if (y >= b[3]) b[3] = y + 1
        }
        return box?.toList()
    }

    private fun <T : Any> assertNotNull(value: T?, message: String): T = value ?: throw AssertionError(message)

    @Test
    fun `a form's buttons are the same two, in the same place`() {
        for (theme in themes) {
            Picture(theme, height = 480) {
                FormDialog("Rename Tab", saveLabel = "Rename", onSave = {}, onClose = {}) { first -> FormField(selected("build"), {}, placeholder = "zsh", hint = "Leave it empty to name the tab after the program running in it.", focus = first) }
            }.use { picture ->
                // The field has the keyboard as it opens, and no button has a ring.
                val form = picture.draw("$theme-form-rename")
                val button = assertNotNull(form.boxOf(picture.scheme.primary, intArrayOf(0, picture.height * 55 / 100, picture.width, picture.height)), "$theme: the filled button")
                assertEquals(40, button[3] - button[1], "$theme: the form's button is the height a question's is")
                // Tab goes to the buttons, and each has the ring in its turn.
                picture.press(Key.Tab, AwtKeyEvent.VK_TAB)
                val first = picture.draw("$theme-form-rename-tab")
                picture.press(Key.Tab, AwtKeyEvent.VK_TAB)
                val second = picture.draw("$theme-form-rename-tab-tab")
                val rings = listOf(first, second).map { assertNotNull(changedWithin(form, it, intArrayOf(0, button[1] - 12, picture.width, picture.height)), "$theme: a ring") }
                assertTrue(rings.all { it[1] == button[1] - 4 && it[3] == button[3] + 4 }, "$theme: each ring is around a button on the buttons' line: $rings")
            }
            // Changed, and asked whether to discard: the destructive pair.
            Picture(theme, height = 480) {
                FormDialog("Rename Tab", saveLabel = "Rename", changed = true, cancelRequests = requests.intValue, onSave = {}, onClose = {}) { first -> FormField(selected("build"), {}, focus = first) }
            }.use { picture ->
                picture.draw()
                requests.intValue++
                picture.draw("$theme-form-discard")
                requests.intValue = 0
            }
        }
    }

    private val requests = androidx.compose.runtime.mutableIntStateOf(0)

    @Test
    fun `a ring's corners grow with it`() {
        val density = Density(1f)
        val size = Size(120f, 40f)
        // A pill stays a pill: the corner is half the larger height.
        val pill = ringOutline(CircleShape, size, 3f, LayoutDirection.Ltr, density) as Outline.Rounded
        assertEquals(Size(126f, 46f), Size(pill.roundRect.width, pill.roundRect.height))
        assertEquals(CornerRadius(23f), pill.roundRect.topLeftCornerRadius)
        // A corner of 8 becomes 11, so the gap is 3 around it as along the edges.
        val rounded = ringOutline(RoundedCornerShape(8.dp), size, 3f, LayoutDirection.Ltr, density) as Outline.Rounded
        assertEquals(CornerRadius(11f), rounded.roundRect.bottomRightCornerRadius)
        // A square corner stays square.
        assertTrue(ringOutline(RectangleShape, size, 3f, LayoutDirection.Ltr, density) is Outline.Rectangle)
    }
}
