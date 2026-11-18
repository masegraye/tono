package org.example.kotlintest

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

/**
 * Dragging one of a column of siblings to a new place among them: the tabs of a session, or
 * the sessions of a host. The dragged one follows the pointer, kept within the column, and
 * the others step aside to show where it would land.
 *
 * The order itself belongs to the server. Letting go asks for the move through [onMove], and
 * everything holds where it was dropped until the server's new order arrives.
 */
@Stable
class ReorderState(private val onMove: (key: String, beforeKey: String?) -> Unit) {
    /** The siblings, in order, as last composed. */
    internal var keys: List<String> = emptyList()

    /** The order when the drag began. A drag only means anything while the order is still that one. */
    private var keysAtStart: List<String> = emptyList()

    // Where each sibling sits in the column when nothing is being dragged.
    private val tops = HashMap<String, Float>()
    private val heights = HashMap<String, Float>()

    private var dragging: String? by mutableStateOf(null)
    private var offset by mutableFloatStateOf(0f)

    /** True from letting go until the new order arrives. */
    internal var settling by mutableStateOf(false)
        private set

    /** Whether siblings should glide to their places (during a drag) or jump (when it is over). */
    internal val active: Boolean get() = dragging != null && keys == keysAtStart

    fun isDragging(key: String): Boolean = active && dragging == key

    internal fun place(key: String, top: Float, height: Float) {
        // Positions are read at rest. Mid-drag they are the ones the drag is measured against.
        if (dragging == null) {
            tops[key] = top
            heights[key] = height
        }
    }

    internal fun start(key: String) {
        if (settling || key !in keys || keys.any { it !in tops }) return
        keysAtStart = keys
        dragging = key
        offset = 0f
    }

    internal fun dragBy(delta: Float) {
        val key = dragging?.takeIf { active && !settling } ?: return
        val first = keysAtStart.first()
        val last = keysAtStart.last()
        val lowest = tops.getValue(first) - tops.getValue(key)
        val highest = tops.getValue(last) + heights.getValue(last) - tops.getValue(key) - heights.getValue(key)
        offset = (offset + delta).coerceIn(lowest, highest)
    }

    /**
     * Where the dragged sibling would land: its place among the others, counting how many of
     * them have their middle above its own.
     */
    private fun target(): Int {
        val key = dragging ?: return -1
        val middle = tops.getValue(key) + heights.getValue(key) / 2 + offset
        return keysAtStart.count { it != key && tops.getValue(it) + heights.getValue(it) / 2 < middle }
    }

    /** How far a sibling is moved from its resting place right now. */
    internal fun translationOf(key: String): Float {
        val dragged = dragging?.takeIf { active } ?: return 0f
        if (key == dragged) return offset
        val from = keysAtStart.indexOf(dragged)
        val index = keysAtStart.indexOf(key)
        val to = target()
        val room = heights.getValue(dragged)
        return when {
            index in (from + 1)..to -> -room // the dragged one passed it going down
            index in to until from -> room // or going up
            else -> 0f
        }
    }

    internal fun drop() {
        val key = dragging?.takeIf { active && !settling } ?: return reset()
        val from = keysAtStart.indexOf(key)
        val to = target()
        if (to == from) return reset()
        // Rest exactly in the gap the others opened.
        offset = if (to > from) (from + 1..to).sumOf { heights.getValue(keysAtStart[it]).toDouble() }.toFloat()
        else -(to until from).sumOf { heights.getValue(keysAtStart[it]).toDouble() }.toFloat()
        settling = true
        onMove(key, (keysAtStart - key).getOrNull(to))
    }

    internal fun reset() {
        dragging = null
        offset = 0f
        settling = false
    }
}

/** A [ReorderState] for the siblings with these [keys], which must be what the column shows, in order. */
@Composable
fun rememberReorder(keys: List<String>, onMove: (key: String, beforeKey: String?) -> Unit): ReorderState {
    val move by rememberUpdatedState(onMove)
    val state = remember { ReorderState { key, before -> move(key, before) } }
    state.keys = keys
    // A new order ends any drag: the one just made has arrived, or someone else moved things.
    LaunchedEffect(keys) { state.reset() }
    // If the server never answers, don't leave things hanging where they were dropped.
    LaunchedEffect(state.settling) {
        if (state.settling) {
            delay(SETTLE_TIMEOUT_MILLIS)
            state.reset()
        }
    }
    return state
}

/** Makes this one of the siblings: it moves with the drag, or out of the dragged one's way. */
@Composable
fun Modifier.reorderItem(state: ReorderState, key: String): Modifier {
    val dragged = state.isDragging(key)
    val aside by animateFloatAsState(
        targetValue = if (dragged) 0f else state.translationOf(key),
        // Gliding while something is dragged; jumping when it ends, since by then the new
        // order has been laid out and there is nowhere left to glide from.
        animationSpec = if (state.active) spring(stiffness = Spring.StiffnessMedium) else snap(),
        label = "reorder",
    )
    val y = if (dragged) state.translationOf(key) else aside
    return this
        // Before the layer, so that it reports the resting place and not the moved one.
        .onGloballyPositioned { state.place(key, it.positionInParent().y, it.size.height.toFloat()) }
        .zIndex(if (dragged) 1f else 0f)
        .graphicsLayer { translationY = y }
}

/** Makes this what the sibling is dragged by. A press that doesn't move is still a click. */
fun Modifier.reorderHandle(state: ReorderState, key: String): Modifier = pointerInput(state, key) {
    detectDragGestures(
        onDragStart = { state.start(key) },
        onDrag = { change, amount ->
            change.consume()
            state.dragBy(amount.y)
        },
        onDragEnd = state::drop,
        onDragCancel = state::reset,
    )
}

private const val SETTLE_TIMEOUT_MILLIS = 1_500L
