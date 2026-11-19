package org.example.kotlintest.rex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaneLayoutTest {
    private fun pane(id: String, x: Float, y: Float, w: Float, h: Float) = LayoutPane(id, PaneRect(x, y, w, h))

    private fun window(layout: LayoutNode?, zoomed: String? = null) =
        WindowView("w", "w", focusedBlockId = null, zoomedBlockId = zoomed, panes = emptyList(), layout = layout)

    /**
     * `a` on the left 60%; on the right, `b` (the top 40%) over `c` beside `d`:
     *
     *     a | b
     *       |-----
     *       | c | d
     */
    private val four = window(
        LayoutSplit(
            horizontal = true, ratio = 0.6f, rect = PaneRect.Full,
            before = pane("a", 0f, 0f, 0.6f, 1f),
            after = LayoutSplit(
                horizontal = false, ratio = 0.4f, rect = PaneRect(0.6f, 0f, 0.4f, 1f),
                before = pane("b", 0.6f, 0f, 0.4f, 0.4f),
                after = LayoutSplit(
                    horizontal = true, ratio = 0.5f, rect = PaneRect(0.6f, 0.4f, 0.4f, 0.6f),
                    before = pane("c", 0.6f, 0.4f, 0.2f, 0.6f),
                    after = pane("d", 0.8f, 0.4f, 0.2f, 0.6f),
                ),
            ),
        ),
    )

    /** Three in a row, as two splits: `a | (b | c)`, with `a` given half. */
    private val row = window(
        LayoutSplit(
            horizontal = true, ratio = 0.5f, rect = PaneRect.Full,
            before = pane("a", 0f, 0f, 0.5f, 1f),
            after = LayoutSplit(
                horizontal = true, ratio = 0.5f, rect = PaneRect(0.5f, 0f, 0.5f, 1f),
                before = pane("b", 0.5f, 0f, 0.25f, 1f),
                after = pane("c", 0.75f, 0f, 0.25f, 1f),
            ),
        ),
    )

    private fun assertNear(expected: Float, actual: Float) = assertTrue(kotlin.math.abs(expected - actual) < 1e-4f, "expected $expected, got $actual")

    @Test
    fun `the server's layout is read as a tree, with a name only for a pane someone named`() {
        val view = parseSessionView(
            Json.parseToJsonElement(
                """{"session_id":"s","label":"s","revision":5,"active_window_id":"window:1","windows":[{"window_id":"window:1","label":"Window 1",
                "focused_block_id":"block:aaaaaaaa","layers":[{"kind":"tiled","bounds":[0,0,1,1],"layout":{"rect":[0,0,1,1],"split":{"split_id":"split:1",
                "direction":"horizontal","ratio":0.5,"before":{"rect":[0,0,0.5,1],"block_id":"block:aaaaaaaa"},"after":{"rect":[0.5,0,0.5,1],"split":{
                "split_id":"split:2","direction":"vertical","ratio":0.25,"before":{"rect":[0.5,0,0.5,0.25],"block_id":"block:bbbbbbbb"},
                "after":{"rect":[0.5,0.25,0.5,0.75],"block_id":"block:cccccccc"}}}}},
                "blocks":[{"block_id":"block:aaaaaaaa","label":"shell","creator_name":"com.superlogical.terminal","flavor":"com.superlogical.terminal.shell"},
                {"block_id":"block:bbbbbbbb","label":"logs","creator_name":"com.superlogical.terminal","flavor":"com.superlogical.terminal.shell"},
                {"block_id":"block:cccccccc","label":"Block cccccccc","creator_name":"com.superlogical.terminal","flavor":"com.superlogical.terminal.shell"}]}]}]}""",
            ).jsonObject,
        )
        val tab = view.windows.single()
        assertEquals(listOf(null, "logs", null), tab.panes.map { it.name })
        val lines = tab.dividers()
        assertEquals(listOf("block:aaaaaaaa:block:bbbbbbbb", "block:bbbbbbbb:block:cccccccc"), lines.map { it.key })
        assertEquals(listOf("horizontal", "vertical"), lines.map { it.axis })
        assertNear(0.5f, lines[0].position)
        assertNear(0.25f, lines[1].position)
        assertEquals(PaneRect(0.5f, 0f, 0.5f, 1f), lines[1].rect)
    }

    @Test
    fun `a default label is not a name`() {
        assertNull(givenBlockName("block:1234-89abcdef", "shell", "com.superlogical.terminal.shell"))
        assertNull(givenBlockName("block:1234-89abcdef", "Block 89abcdef", "com.superlogical.terminal.shell"))
        assertNull(givenBlockName("block:1234-89abcdef", "", null))
        assertEquals("Block 12345678", givenBlockName("block:1234-89abcdef", "Block 12345678", null))
        assertEquals("server", givenBlockName("block:1234-89abcdef", "server", "com.superlogical.terminal.shell"))
    }

    @Test
    fun `a zoomed tab has no lines to drag and no neighbours`() {
        val zoomed = four.copy(zoomedBlockId = "a")
        assertEquals(emptyList(), zoomed.dividers())
        assertNull(zoomed.neighbourOf("a", PaneSide.Right))
        assertNull(zoomed.resizedBy("a", PaneSide.Right))
    }

    @Test
    fun `a neighbour is the pane sharing the most of an edge`() {
        // b and c both lie against a's right side; c shares more of it.
        assertEquals("c", four.neighbourOf("a", PaneSide.Right))
        assertEquals("a", four.neighbourOf("b", PaneSide.Left))
        assertEquals("a", four.neighbourOf("c", PaneSide.Left))
        assertEquals("d", four.neighbourOf("c", PaneSide.Right))
        // c and d share b's lower edge equally: the first in layout order.
        assertEquals("c", four.neighbourOf("b", PaneSide.Down))
        assertEquals("b", four.neighbourOf("d", PaneSide.Up))
        // d touches a only through c.
        assertEquals("c", four.neighbourOf("d", PaneSide.Left))
    }

    @Test
    fun `there is no neighbour at the tab's edge`() {
        assertNull(four.neighbourOf("a", PaneSide.Left))
        assertNull(four.neighbourOf("a", PaneSide.Up))
        assertNull(four.neighbourOf("d", PaneSide.Right))
        assertNull(four.neighbourOf("d", PaneSide.Down))
        assertNull(four.neighbourOf("nobody", PaneSide.Left))
        assertNull(window(pane("only", 0f, 0f, 1f, 1f)).neighbourOf("only", PaneSide.Left))
    }

    @Test
    fun `a keyboard resize moves the border on the pane's right or under it`() {
        val right = assertNotNull(four.resizedBy("a", PaneSide.Right))
        assertEquals("a:b", right.key)
        assertNear(0.65f, right.ratio)
        assertNear(0.55f, assertNotNull(four.resizedBy("a", PaneSide.Left)).ratio)
        // b's lower border is the split between it and the row of c and d.
        val down = assertNotNull(four.resizedBy("b", PaneSide.Down))
        assertEquals("b:c" to false, down.key to down.horizontal)
        assertNear(0.45f, down.ratio)
        // c's right border is the innermost split, not the one a is in.
        assertEquals("c:d", assertNotNull(four.resizedBy("c", PaneSide.Right)).key)
    }

    @Test
    fun `a pane with no border on its right or under it moves the one on its left or above it`() {
        val left = assertNotNull(four.resizedBy("d", PaneSide.Left))
        assertEquals("c:d", left.key)
        assertNear(0.45f, left.ratio)
        val up = assertNotNull(four.resizedBy("d", PaneSide.Up))
        assertEquals("b:c", up.key)
        assertNear(0.35f, up.ratio)
        // b has nothing on its right, so its left border moves: the one it shares with a.
        assertEquals("a:b", assertNotNull(four.resizedBy("b", PaneSide.Right)).key)
        // a has no border above or below at all.
        assertNull(four.resizedBy("a", PaneSide.Up))
        assertNull(four.resizedBy("a", PaneSide.Down))
    }

    @Test
    fun `a resize takes an amount, and stops at the end of the range`() {
        assertNear(0.75f, assertNotNull(four.resizedBy("a", PaneSide.Right, percent = 15.0)).ratio)
        assertNear(0.9f, assertNotNull(four.resizedBy("a", PaneSide.Right, percent = 80.0)).ratio)
        assertNear(0.1f, assertNotNull(four.resizedBy("a", PaneSide.Left, percent = 80.0)).ratio)
        assertNull(four.resizedBy("a", PaneSide.Right, percent = 0.0))
        assertNull(four.resizedBy("a", PaneSide.Right, percent = -5.0))
        assertNull(four.resizedBy("a", PaneSide.Right, percent = Double.NaN))
    }

    @Test
    fun `a border already past the end of the range is not pulled back`() {
        val squeezed = window(LayoutSplit(true, 0.95f, PaneRect.Full, pane("a", 0f, 0f, 0.95f, 1f), pane("b", 0.95f, 0f, 0.05f, 1f)))
        assertNull(squeezed.resizedBy("a", PaneSide.Right))
        assertNear(0.9f, assertNotNull(squeezed.resizedBy("a", PaneSide.Left)).ratio)
    }

    @Test
    fun `a drag is measured in the window and kept in range`() {
        val inner = four.dividers().first { it.key == "c:d" }
        // The split c and d share is 40% of the window wide, so a tenth of the window is a quarter of it.
        assertNear(0.75f, inner.draggedBy(0.1f))
        assertNear(0.25f, inner.draggedBy(-0.1f))
        assertNear(0.9f, inner.draggedBy(0.9f))
        assertNear(0.1f, inner.draggedBy(-0.9f))
        val level = four.dividers().first { it.key == "b:c" }
        assertNear(0.5f, level.draggedBy(0.1f))
    }

    @Test
    fun `panes are placed by a dragged line and the server's others`() {
        val rects = four.rectsWith("a:b", 0.5f)
        assertEquals(PaneRect(0f, 0f, 0.5f, 1f), rects["a"])
        assertNear(0.5f, rects.getValue("b").x)
        assertNear(0.4f, rects.getValue("b").height)
        assertNear(0.75f, rects.getValue("d").x)
        assertNear(0.25f, rects.getValue("d").width)
        assertNear(1f, rects.values.sumOf { (it.width * it.height).toDouble() }.toFloat())
        // A line that isn't there changes nothing.
        assertEquals(PaneRect(0f, 0f, 0.6f, 1f), four.rectsWith("x:y", 0.5f)["a"])
    }

    @Test
    fun `balancing gives the panes of a row the same width`() {
        val moves = row.balanced()
        // a gets a third; b and c already share the rest evenly, so that border stays.
        assertEquals(listOf("a:b"), moves.map { it.key })
        assertNear(1f / 3, moves.single().ratio)
    }

    @Test
    fun `balancing counts a column in a row once, and evens it out on its own`() {
        val moves = four.balanced().associate { it.key to it.ratio }
        // a beside a column: half each. The column is b over the row of c and d: half each.
        assertEquals(setOf("a:b", "b:c"), moves.keys)
        assertNear(0.5f, moves.getValue("a:b"))
        assertNear(0.5f, moves.getValue("b:c"))
    }

    @Test
    fun `a balanced tab has nothing to move`() {
        assertEquals(emptyList(), window(pane("only", 0f, 0f, 1f, 1f)).balanced())
        assertEquals(emptyList(), window(null).balanced())
        val even = window(LayoutSplit(false, 0.5f, PaneRect.Full, pane("a", 0f, 0f, 1f, 0.5f), pane("b", 0f, 0.5f, 1f, 0.5f)))
        assertEquals(emptyList(), even.balanced())
    }

    @Test
    fun `directions are read as actions spell them`() {
        assertEquals(PaneSide.Left, PaneSide.parse("left"))
        assertEquals(PaneSide.Down, PaneSide.parse("Down"))
        assertNull(PaneSide.parse("sideways"))
        assertNull(PaneSide.parse(null))
    }
}
