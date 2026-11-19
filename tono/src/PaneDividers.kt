package org.example.kotlintest

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.kotlintest.rex.PaneDivider
import org.example.kotlintest.rex.WindowView
import org.example.kotlintest.rex.dividers

/**
 * A line between panes being dragged: which one, and where it has been dragged to. The
 * panes are drawn there at once, and the server is asked to put it there; its answer comes a
 * moment later.
 *
 * @param released true once the pointer has let go. The line is then drawn here until the
 *   server's layout has it in the same place, so it doesn't jump back for a moment.
 */
internal data class DividerDrag(val key: String, val ratio: Float, val released: Boolean = false)

/** The pointer for a line that can be dragged sideways, or up and down. */
internal expect fun resizePointer(sideways: Boolean): PointerIcon

/**
 * Something to take hold of on each line between a tab's panes: drag it and the panes on
 * either side change size. Wider than the line it lies on, which stays as thin as it is
 * drawn; it lights up under the pointer and while it is dragged.
 *
 * @param width the size of the panel the panes are on
 * @param drag the drag under way, if any
 * @param onDrag called as a line is dragged with where it is now, and on letting go
 * @param onResize called with the line as the server has it and the ratio to ask for
 */
@Composable
internal fun PaneDividerHandles(
    window: WindowView,
    width: Dp,
    height: Dp,
    widthPx: Int,
    heightPx: Int,
    drag: DividerDrag?,
    onDrag: (DividerDrag) -> Unit,
    onResize: (PaneDivider, Float) -> Unit,
) {
    for (divider in window.dividers()) {
        key(divider.key, divider.horizontal) {
            val current by rememberUpdatedState(divider)
            val dragging = drag?.takeIf { it.key == divider.key }
            val ratio = dragging?.ratio ?: divider.ratio
            val rect = divider.rect
            val hover = remember { MutableInteractionSource() }
            val hovered by hover.collectIsHoveredAsState()
            val lit = hovered || dragging?.released == false
            val place = if (divider.horizontal) {
                Modifier.offset(x = width * (rect.x + rect.width * ratio) - DIVIDER_GRIP / 2, y = height * rect.y).size(DIVIDER_GRIP, height * rect.height)
            } else {
                Modifier.offset(x = width * rect.x, y = height * (rect.y + rect.height * ratio) - DIVIDER_GRIP / 2).size(width * rect.width, DIVIDER_GRIP)
            }
            Box(
                place
                    .testTag("pane-divider")
                    .hoverable(hover)
                    .pointerHoverIcon(resizePointer(divider.horizontal))
                    .pointerInput(divider.key, divider.horizontal, widthPx, heightPx) {
                        var from = current
                        var moved = 0f
                        var last = DividerDrag(from.key, from.ratio)
                        val finish = { onDrag(last.copy(released = true)) }
                        detectDragGestures(
                            onDragStart = {
                                // From where the server has the line now; the drag is measured from there.
                                from = current
                                moved = 0f
                                last = DividerDrag(from.key, from.ratio)
                            },
                            onDragEnd = finish,
                            onDragCancel = finish,
                        ) { change, amount ->
                            change.consume()
                            moved += if (from.horizontal) amount.x / widthPx.coerceAtLeast(1) else amount.y / heightPx.coerceAtLeast(1)
                            val next = from.draggedBy(moved)
                            if (next != last.ratio) {
                                last = DividerDrag(from.key, next)
                                onDrag(last)
                                onResize(from, next)
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (lit) {
                    val line = Modifier.background(MaterialTheme.colorScheme.primary)
                    Box(if (divider.horizontal) line.width(DIVIDER_LIT).fillMaxHeight() else line.height(DIVIDER_LIT).fillMaxWidth())
                }
            }
        }
    }
}

/** A pane's name, for the pane that was given one, small in its top right corner. */
@Composable
internal fun PaneNameChip(name: String, modifier: Modifier = Modifier) {
    Text(
        name,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag("pane-name"),
    )
}

/** How wide the part of a line between panes that can be taken hold of is. The line itself is one pixel. */
private val DIVIDER_GRIP = 9.dp

/** How wide a line is drawn under the pointer and while dragged. */
private val DIVIDER_LIT = 2.dp
