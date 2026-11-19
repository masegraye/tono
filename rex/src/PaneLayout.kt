package org.example.kotlintest.rex

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Reading the lines between a tab's panes out of the server's layout, and working out what
 * to ask the server for when one is moved.
 *
 * The server owns the layout: a tree of splits, each dividing its space in two by a ratio,
 * with a pane at every leaf. Nothing here changes it. Each function answers with the
 * borders to move and the ratios to give them, for `session.resize_pane`, or with the pane
 * to trade places with, for `session.swap_blocks`. The rules are Rex Rune's.
 */

/** A node of a tab's layout: a pane, or a split holding two more nodes. */
sealed interface LayoutNode {
    /** Where the node sits, as fractions of the window. */
    val rect: PaneRect
}

data class LayoutPane(val blockId: String, override val rect: PaneRect) : LayoutNode

/**
 * @param horizontal true when [before] is on the left of [after]; false when it is above it
 * @param ratio the fraction of the split's space that [before] has
 */
data class LayoutSplit(
    val horizontal: Boolean,
    val ratio: Float,
    override val rect: PaneRect,
    val before: LayoutNode,
    val after: LayoutNode,
) : LayoutNode

/** A side of a pane: where its neighbour is, or which way one of its borders moves. */
enum class PaneSide {
    Left, Right, Up, Down;

    val sideways: Boolean get() = this == Left || this == Right

    /** Whether moving a border this way gives the part before it more room. */
    internal val forward: Boolean get() = this == Right || this == Down

    companion object {
        /** Reads the `direction` argument of an action. */
        fun parse(text: String?): PaneSide? = entries.firstOrNull { it.name.equals(text, ignoreCase = true) }
    }
}

/**
 * The line between the two parts of a split.
 *
 * @param beforeBlockId a pane on the left of the line, or above it
 * @param afterBlockId a pane on its right, or below it. The two together name the split to the server.
 * @param horizontal true for an upright line between left and right
 * @param rect the space the split divides
 * @param ratio how much of that space is before the line
 */
data class PaneDivider(
    val beforeBlockId: String,
    val afterBlockId: String,
    val horizontal: Boolean,
    val rect: PaneRect,
    val ratio: Float,
) {
    /** Tells one line of a tab from another. */
    val key: String get() = "$beforeBlockId:$afterBlockId"

    /** The word the server has for the split's direction. */
    val axis: String get() = if (horizontal) "horizontal" else "vertical"

    /** How far across the window the line is (or down it, for a level line), as a fraction. */
    val position: Float get() = if (horizontal) rect.x + rect.width * ratio else rect.y + rect.height * ratio

    /**
     * The ratio that puts the line [moved] of the window further on than it is, kept within
     * what a drag may leave ([RATIO_RANGE]).
     */
    fun draggedBy(moved: Float): Float {
        val room = if (horizontal) rect.width else rect.height
        if (room <= 0f) return ratio
        return wholeSteps(ratio + moved / room).coerceIn(RATIO_RANGE)
    }

    companion object {
        /** The ratios a line can be dragged or stepped to. Neither side can be squeezed to nothing. */
        val RATIO_RANGE = 0.1f..0.9f
    }
}

/** How far `pane.resize` moves a border when it isn't told: this many hundredths of its split. */
const val RESIZE_STEP_PERCENT = 5.0

/** Rectangles are fractions of the window; two edges this close are the same edge. */
private const val SAME = 1e-4f

/** A ratio to four places, so that one stepped many times doesn't gather rounding noise. */
private fun wholeSteps(ratio: Float): Float = (ratio * 10_000).roundToInt() / 10_000f

private fun firstPane(node: LayoutNode): String = when (node) {
    is LayoutPane -> node.blockId
    is LayoutSplit -> firstPane(node.before)
}

private fun collectDividers(node: LayoutNode?, into: MutableList<PaneDivider>) {
    if (node !is LayoutSplit) return
    into += PaneDivider(firstPane(node.before), firstPane(node.after), node.horizontal, node.rect, node.ratio)
    collectDividers(node.before, into)
    collectDividers(node.after, into)
}

private fun collectPanes(node: LayoutNode?, into: MutableList<LayoutPane>) {
    when (node) {
        is LayoutPane -> into += node
        is LayoutSplit -> { collectPanes(node.before, into); collectPanes(node.after, into) }
        null -> {}
    }
}

/**
 * The lines between a tab's tiled panes, outermost first. None while a pane is zoomed, since
 * the others aren't beside it then.
 */
fun WindowView.dividers(): List<PaneDivider> {
    if (zoomedBlockId != null) return emptyList()
    return ArrayList<PaneDivider>().also { collectDividers(layout, it) }
}

/**
 * Where each tiled pane sits when the line [key] is at [ratio] and every other line is where
 * the server has it. For showing a drag as it happens, before the server has answered.
 */
fun WindowView.rectsWith(key: String, ratio: Float): Map<String, PaneRect> {
    val rects = HashMap<String, PaneRect>()
    fun place(node: LayoutNode, rect: PaneRect) {
        when (node) {
            is LayoutPane -> rects[node.blockId] = rect
            is LayoutSplit -> {
                val share = if ("${firstPane(node.before)}:${firstPane(node.after)}" == key) ratio else node.ratio
                if (node.horizontal) {
                    place(node.before, rect.copy(width = rect.width * share))
                    place(node.after, rect.copy(x = rect.x + rect.width * share, width = rect.width * (1 - share)))
                } else {
                    place(node.before, rect.copy(height = rect.height * share))
                    place(node.after, rect.copy(y = rect.y + rect.height * share, height = rect.height * (1 - share)))
                }
            }
        }
    }
    layout?.let { place(it, it.rect) }
    return rects
}

/**
 * The pane that lies against [side] of a pane: of several, the one that shares the most of
 * that edge, and of equals the first in layout order. Null at the tab's edge, for a pane
 * that isn't tiled, and while a pane is zoomed.
 */
fun WindowView.neighbourOf(blockId: String, side: PaneSide): String? {
    if (zoomedBlockId != null) return null
    val panes = ArrayList<LayoutPane>().also { collectPanes(layout, it) }
    val own = panes.firstOrNull { it.blockId == blockId }?.rect ?: return null
    var best: String? = null
    var most = 0f
    for (pane in panes) {
        if (pane.blockId == blockId) continue
        val rect = pane.rect
        val touches = when (side) {
            PaneSide.Left -> abs(rect.x + rect.width - own.x) < SAME
            PaneSide.Right -> abs(rect.x - (own.x + own.width)) < SAME
            PaneSide.Up -> abs(rect.y + rect.height - own.y) < SAME
            PaneSide.Down -> abs(rect.y - (own.y + own.height)) < SAME
        }
        val shared = if (side.sideways) min(rect.y + rect.height, own.y + own.height) - max(rect.y, own.y)
        else min(rect.x + rect.width, own.x + own.width) - max(rect.x, own.x)
        if (touches && shared > SAME && shared > most + SAME) {
            best = pane.blockId
            most = shared
        }
    }
    return best
}

/**
 * The border of a pane that a keyboard resize toward [side] moves, with the ratio that moves
 * it one step that way. It is the border on the pane's right (or under it), or for a pane
 * with none there, the one on its left (or above it); so in a pane at the left of its tab,
 * "right" makes it wider, and in one at the right, "right" makes it narrower.
 *
 * Null when the pane has no such border, while a pane is zoomed, or when the border is
 * already as far that way as it goes.
 *
 * @param percent how far to move it, in hundredths of the split it divides
 */
fun WindowView.resizedBy(blockId: String, side: PaneSide, percent: Double = RESIZE_STEP_PERCENT): PaneDivider? {
    if (percent.isNaN() || percent.isInfinite() || percent <= 0) return null
    val panes = ArrayList<LayoutPane>().also { collectPanes(layout, it) }
    val own = panes.firstOrNull { it.blockId == blockId }?.rect ?: return null
    val all = dividers()
    // A border of the pane: a split the right way round, as long as the pane or longer,
    // whose line is on the pane's edge at `at`. Of several, the innermost.
    fun border(at: Float): PaneDivider? = all.lastOrNull { divider ->
        val rect = divider.rect
        val (from, to) = if (side.sideways) rect.y to rect.y + rect.height else rect.x to rect.x + rect.width
        val (start, end) = if (side.sideways) own.y to own.y + own.height else own.x to own.x + own.width
        divider.horizontal == side.sideways && abs(divider.position - at) < SAME && from < start + SAME && to > end - SAME
    }
    val divider = (if (side.sideways) border(own.x + own.width) ?: border(own.x) else border(own.y + own.height) ?: border(own.y)) ?: return null
    val step = (percent / 100).toFloat() * if (side.forward) 1 else -1
    val ratio = wholeSteps(divider.ratio + step).coerceIn(PaneDivider.RATIO_RANGE)
    // A ratio the server holds outside the range (any client may set one) is left alone
    // rather than pulled back in, which would move the border the other way.
    if ((ratio - divider.ratio) * (if (side.forward) 1 else -1) < SAME) return null
    return divider.copy(ratio = ratio)
}

/**
 * The borders to move, and where to, so that the panes side by side in a row are all the
 * same width and the panes stacked in a column are all the same height. A row counts each
 * thing in it once, whether that is one pane or a column of several, and the column is
 * evened out within its own space. Borders already in place are left out.
 */
fun WindowView.balanced(): List<PaneDivider> {
    fun slots(node: LayoutNode, horizontal: Boolean): Int =
        if (node is LayoutSplit && node.horizontal == horizontal) slots(node.before, horizontal) + slots(node.after, horizontal) else 1

    val moves = ArrayList<PaneDivider>()
    fun balance(node: LayoutNode) {
        if (node !is LayoutSplit) return
        val before = slots(node.before, node.horizontal)
        val ratio = before.toFloat() / (before + slots(node.after, node.horizontal))
        if (abs(ratio - node.ratio) > SAME) moves += PaneDivider(firstPane(node.before), firstPane(node.after), node.horizontal, node.rect, ratio)
        balance(node.before)
        balance(node.after)
    }
    layout?.let(::balance)
    return moves
}
