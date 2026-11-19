package org.example.kotlintest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import org.example.kotlintest.rex.LayoutPane
import org.example.kotlintest.rex.LayoutSplit
import org.example.kotlintest.rex.PaneDivider
import org.example.kotlintest.rex.PaneRect
import org.example.kotlintest.rex.WindowView
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drags the lines between panes with a mouse, offscreen, as the desktop takes it: what is
 * asked of the server, and where the line is held meanwhile.
 */
class PaneDividerDragTest {
    private val width = 1000
    private val height = 500

    /** `a` on the left half; on the right, `b` over `c`. */
    private val tab = WindowView(
        "w", "w", focusedBlockId = "a", zoomedBlockId = null, panes = emptyList(),
        layout = LayoutSplit(
            horizontal = true, ratio = 0.5f, rect = PaneRect.Full,
            before = LayoutPane("a", PaneRect(0f, 0f, 0.5f, 1f)),
            after = LayoutSplit(
                horizontal = false, ratio = 0.5f, rect = PaneRect(0.5f, 0f, 0.5f, 1f),
                before = LayoutPane("b", PaneRect(0.5f, 0f, 0.5f, 0.5f)),
                after = LayoutPane("c", PaneRect(0.5f, 0.5f, 0.5f, 0.5f)),
            ),
        ),
    )

    private class Seen {
        var drag: DividerDrag? by mutableStateOf(null)
        val asked = ArrayList<Pair<PaneDivider, Float>>()

        /** The drag as it stood at the end, before the window went away (which lets go of anything held). */
        var atEnd: DividerDrag? = null
    }

    /** Presses at [from], moves to [to] in steps, and lets go unless [hold]. Returns what happened. */
    private fun drag(from: Offset, to: Offset, window: WindowView = tab, hold: Boolean = false): Seen {
        val seen = Seen()
        ImageComposeScene(width, height, Density(1f)) {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    PaneDividerHandles(window, width.dp, height.dp, width, height, seen.drag, onDrag = { seen.drag = it }, onResize = { divider, ratio -> seen.asked += divider to ratio })
                }
            }
        }.use { scene ->
            var now = 0L
            fun frame() { now += 16; scene.render(now * 1_000_000) }
            frame()
            scene.sendPointerEvent(PointerEventType.Move, from); frame()
            scene.sendPointerEvent(PointerEventType.Press, from); frame()
            for (step in 1..10) {
                scene.sendPointerEvent(PointerEventType.Move, from + (to - from) * (step / 10f)); frame()
            }
            if (!hold) { scene.sendPointerEvent(PointerEventType.Release, to); frame() }
            seen.atEnd = seen.drag
        }
        return seen
    }

    private fun assertNear(expected: Float, actual: Float, within: Float = 0.02f) = assertTrue(abs(expected - actual) < within, "expected about $expected, got $actual")

    @Test
    fun `dragging the upright line sideways asks for it to follow the pointer`() {
        val seen = drag(Offset(500f, 250f), Offset(700f, 260f))
        val (divider, ratio) = seen.asked.last()
        assertEquals("a:b" to "horizontal", divider.key to divider.axis)
        // Less the little the pointer travels before a press counts as a drag.
        assertNear(0.7f, ratio)
        assertTrue(seen.asked.size > 1, "asked as it went, not only at the end")
        assertTrue(seen.asked.zipWithNext().all { (a, b) -> b.second > a.second }, "each ask further on than the last")
        assertEquals(DividerDrag("a:b", ratio, released = true), seen.atEnd)
    }

    @Test
    fun `dragging the level line moves only that one, measured against its own split`() {
        // Along the right half, the line between b and c is at half the height.
        val seen = drag(Offset(750f, 250f), Offset(760f, 150f))
        val (divider, ratio) = seen.asked.last()
        assertEquals("b:c" to "vertical", divider.key to divider.axis)
        assertNear(0.3f, ratio)
        assertTrue(seen.asked.all { it.first.key == "b:c" })
    }

    @Test
    fun `a line held is not yet let go, and one dragged past the edge stops short`() {
        val seen = drag(Offset(500f, 100f), Offset(999f, 100f), hold = true)
        assertEquals(0.9f, seen.asked.last().second)
        assertEquals(DividerDrag("a:b", 0.9f, released = false), seen.atEnd)
    }

    @Test
    fun `a press away from any line drags nothing`() {
        val seen = drag(Offset(200f, 250f), Offset(400f, 250f))
        assertTrue(seen.asked.isEmpty())
        assertNull(seen.atEnd)
    }

    @Test
    fun `the left half has no level line to take hold of`() {
        // The line between b and c stops at the middle; a press at its height on the left is in pane a.
        val seen = drag(Offset(250f, 250f), Offset(250f, 100f))
        assertTrue(seen.asked.isEmpty())
    }

    @Test
    fun `where the level line ends against the upright one, the upright one is taken`() {
        val seen = drag(Offset(501f, 250f), Offset(401f, 250f))
        assertEquals(setOf("a:b"), seen.asked.map { it.first.key }.toSet())
    }

    @Test
    fun `a zoomed tab has no lines to drag`() {
        val seen = drag(Offset(500f, 250f), Offset(700f, 250f), window = tab.copy(zoomedBlockId = "a"))
        assertTrue(seen.asked.isEmpty())
        assertNull(seen.atEnd)
        assertNotNull(tab.layout)
    }
}
