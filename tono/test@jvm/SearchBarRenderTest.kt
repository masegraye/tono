package org.example.kotlintest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import dev.mg.omarchy.theme.bundledOmarchyPalettes
import dev.mg.omarchy.theme.compose.omarchyColorScheme
import dev.mg.omarchy.theme.compose.omarchyShapes
import org.example.kotlintest.rex.TerminalSearch
import org.jetbrains.skia.Bitmap
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * The search bar as the desktop draws it, offscreen, with keys sent to its field. The
 * pictures are left in `<project>/build/search-bar-renders/` to be looked at.
 */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
class SearchBarRenderTest {
    private val outputDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "project.yaml").isFile }
        .resolve("build/search-bar-renders")
        .apply { mkdirs() }

    private val scheme = omarchyColorScheme(bundledOmarchyPalettes.first { it.slug == "retro-82" })
    private val width = 480
    private val height = 120

    /** What the bar asked for, in order. */
    private val asked = ArrayList<String>()

    private class Bar(val scene: ImageComposeScene, val outputDir: File) : AutoCloseable {
        private val source = object : java.awt.Component() {}
        private var time = 0L

        fun draw(name: String): Bitmap {
            time += 100_000_000
            val image = scene.render(time)
            File(outputDir, "$name.png").writeBytes(image.encodeToData()!!.bytes)
            return Bitmap.makeFromImage(image)
        }

        private fun send(awtType: Int, code: Int, char: Char, shift: Boolean = false) {
            val native = AwtKeyEvent(source, awtType, 0, if (shift) AwtKeyEvent.SHIFT_DOWN_MASK else 0, code, char)
            scene.sendKeyEvent(
                KeyEvent(
                    key = Key(code),
                    type = when (awtType) {
                        AwtKeyEvent.KEY_PRESSED -> KeyEventType.KeyDown
                        AwtKeyEvent.KEY_RELEASED -> KeyEventType.KeyUp
                        else -> KeyEventType.Unknown
                    },
                    codePoint = if (char == AwtKeyEvent.CHAR_UNDEFINED) 0 else char.code,
                    isShiftPressed = shift,
                    nativeEvent = native,
                ),
            )
        }

        fun press(code: Int, shift: Boolean = false) {
            send(AwtKeyEvent.KEY_PRESSED, code, AwtKeyEvent.CHAR_UNDEFINED, shift)
            send(AwtKeyEvent.KEY_RELEASED, code, AwtKeyEvent.CHAR_UNDEFINED, shift)
            scene.render(++time)
        }

        fun type(text: String) {
            for (char in text) {
                val code = AwtKeyEvent.getExtendedKeyCodeForChar(char.code)
                send(AwtKeyEvent.KEY_PRESSED, code, char)
                send(AwtKeyEvent.KEY_TYPED, AwtKeyEvent.VK_UNDEFINED, char)
                send(AwtKeyEvent.KEY_RELEASED, code, char)
                scene.render(++time)
            }
        }

        override fun close() = scene.close()
    }

    /** The bar over a terminal-colored surface, its field holding the keyboard, showing what [found] says of the text typed. */
    private fun bar(initial: TerminalSearch, found: (String) -> TerminalSearch = { initial.copy(query = it) }): Bar {
        val scene = ImageComposeScene(width, height, Density(1f)) {
            MaterialTheme(colorScheme = scheme, shapes = omarchyShapes()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        var search by remember { mutableStateOf(initial) }
                        val field = remember { FocusRequester() }
                        LaunchedEffect(Unit) { field.requestFocus() }
                        SearchBar(
                            search = search,
                            field = field,
                            onQuery = { asked += "find $it"; search = found(it) },
                            onStep = { asked += if (it) "next" else "previous" },
                            onClose = { asked += "close" },
                            onTyping = { if (it) asked += "typing" },
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }
                }
            }
        }
        return Bar(scene, outputDir).also { it.draw("warm-up"); it.draw("warm-up") }
    }

    private fun near(pixel: Int, color: Color): Boolean {
        val want = color.toArgb()
        return (0..16 step 8).all { shift -> abs(((pixel shr shift) and 0xFF) - ((want shr shift) and 0xFF)) <= 6 }
    }

    private fun Bitmap.has(color: Color) = (0 until width).any { x -> (0 until height).any { y -> near(getColor(x, y), color) } }

    @Test
    fun `typing in the field asks for the text, a letter at a time`() = bar(TerminalSearch("")).use { bar ->
        bar.type("fish")
        bar.draw("typed")

        assertEquals(listOf("typing", "find f", "find fi", "find fis", "find fish"), asked)
    }

    @Test
    fun `Enter asks for the next match, Shift and Enter for the previous, and Escape closes`() = bar(TerminalSearch("fish", total = 17, selected = 2)).use { bar ->
        bar.press(AwtKeyEvent.VK_ENTER)
        bar.press(AwtKeyEvent.VK_ENTER, shift = true)
        bar.press(AwtKeyEvent.VK_ESCAPE)

        assertEquals(listOf("typing", "next", "previous", "close"), asked)
    }

    @Test
    fun `opened on text already there, typing replaces it`() = bar(TerminalSearch("fish", total = 17, selected = 2)).use { bar ->
        bar.type("x")

        assertEquals(listOf("typing", "find x"), asked)
    }

    @Test
    fun `it sits in the top right corner, and says in the error color when nothing matches`() {
        val found = bar(TerminalSearch("fish", total = 17, selected = 2)).use { it.draw("3-of-17") }
        val edge = scheme.outlineVariant
        val columns = (0 until width).filter { x -> (0 until height).any { y -> near(found.getColor(x, y), edge) } }
        val rows = (0 until height).filter { y -> (0 until width).any { x -> near(found.getColor(x, y), edge) } }
        assertTrue(columns.first() > width / 4 && columns.last() >= width - 6, "its outline spans columns ${columns.first()}..${columns.last()} of $width")
        assertTrue(rows.first() <= 6 && rows.last() < height / 2, "and rows ${rows.first()}..${rows.last()} of $height")
        assertTrue(!found.has(scheme.error), "a count isn't in the error color")

        val none = bar(TerminalSearch("fowl")).use { it.draw("no-matches") }
        assertTrue(none.has(scheme.error), "\"No matches\" is")
    }
}
