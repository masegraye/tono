package org.example.kotlintest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One of the small buttons along the top of the sidebar, or the smaller ones under the
 * terminals. Resting the pointer on it says what it does and which key does the same.
 *
 * @param active true while the thing it switches is on, which tints it
 * @param enabled false when there is nothing for it to act on, which fades it
 * @param size how large the button is; its icon is drawn to suit
 */
@Composable
fun ToolbarButton(
    description: String,
    shortcut: String?,
    onClick: () -> Unit,
    active: Boolean = false,
    enabled: Boolean = true,
    size: Dp = TOOLBAR_BUTTON,
    icon: @Composable (tint: Color) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Tooltip(
        tip = {
            Surface(color = colors.surfaceContainerHighest, shape = MaterialTheme.shapes.extraSmall, shadowElevation = 4.dp) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(description, style = MaterialTheme.typography.labelMedium)
                    if (shortcut != null) Text(shortcut, style = MaterialTheme.typography.labelMedium, color = colors.primary, modifier = Modifier.padding(start = 10.dp))
                }
            }
        },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(size)
                .clip(MaterialTheme.shapes.small)
                .background(if (active) colors.secondaryContainer else Color.Transparent)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { contentDescription = description },
        ) {
            icon((if (active) colors.onSecondaryContainer else colors.onSurfaceVariant).copy(alpha = if (enabled) 1f else 0.35f))
        }
    }
}

/** The buttons along the top of the sidebar, and the smaller ones in the line under the terminals. */
val TOOLBAR_BUTTON = 28.dp
val STATUS_BUTTON = 22.dp
val STATUS_ICON = 14.dp

/** A window with a column down its left side: the sidebar. */
@Composable
fun SidebarIcon(tint: Color, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        val unit = this.size.width / 16f
        val line = 1.5f * unit
        drawRoundRect(tint, Offset(1.5f * unit, 2.5f * unit), Size(13f * unit, 11f * unit), CornerRadius(2f * unit), Stroke(line))
        drawLine(tint, Offset(6f * unit, 2.5f * unit), Offset(6f * unit, 13.5f * unit), line)
    }
}

/** A pulse: what is going on right now. */
@Composable
fun ActivityIcon(tint: Color) {
    Canvas(Modifier.size(16.dp)) {
        val unit = size.width / 16f
        val pulse = Path().apply {
            moveTo(1.5f * unit, 8.5f * unit)
            lineTo(4.5f * unit, 8.5f * unit)
            lineTo(6.5f * unit, 3.5f * unit)
            lineTo(9.5f * unit, 13f * unit)
            lineTo(11.5f * unit, 8.5f * unit)
            lineTo(14.5f * unit, 8.5f * unit)
        }
        drawPath(pulse, tint, style = Stroke(1.5f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A magnifying glass: find a command, a session, a tab or a host. */
@Composable
fun PaletteIcon(tint: Color) {
    Canvas(Modifier.size(16.dp)) {
        val unit = size.width / 16f
        val line = 1.5f * unit
        drawCircle(tint, 4.5f * unit, Offset(7f * unit, 7f * unit), style = Stroke(line))
        drawLine(tint, Offset(10.4f * unit, 10.4f * unit), Offset(14f * unit, 14f * unit), line, StrokeCap.Round)
    }
}

/** A pane with a line down its middle: a new pane beside this one. */
@Composable
fun SplitRightIcon(tint: Color, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        val unit = this.size.width / 16f
        val line = 1.5f * unit
        drawRoundRect(tint, Offset(1.5f * unit, 2.5f * unit), Size(13f * unit, 11f * unit), CornerRadius(2f * unit), Stroke(line))
        drawLine(tint, Offset(8f * unit, 2.5f * unit), Offset(8f * unit, 13.5f * unit), line)
    }
}

/** A pane with a line across its middle: a new pane under this one. */
@Composable
fun SplitDownIcon(tint: Color, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        val unit = this.size.width / 16f
        val line = 1.5f * unit
        drawRoundRect(tint, Offset(1.5f * unit, 2.5f * unit), Size(13f * unit, 11f * unit), CornerRadius(2f * unit), Stroke(line))
        drawLine(tint, Offset(1.5f * unit, 8f * unit), Offset(14.5f * unit, 8f * unit), line)
    }
}

/** Two arrows pulling apart, corner to corner: one pane given the whole tab. */
@Composable
fun ZoomIcon(tint: Color, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        val unit = this.size.width / 16f
        val stroke = Stroke(1.5f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val arrows = Path().apply {
            // Up and to the right.
            moveTo(9.5f * unit, 6.5f * unit)
            lineTo(13.5f * unit, 2.5f * unit)
            moveTo(9.5f * unit, 2.5f * unit)
            lineTo(13.5f * unit, 2.5f * unit)
            lineTo(13.5f * unit, 6.5f * unit)
            // Down and to the left.
            moveTo(6.5f * unit, 9.5f * unit)
            lineTo(2.5f * unit, 13.5f * unit)
            moveTo(2.5f * unit, 9.5f * unit)
            lineTo(2.5f * unit, 13.5f * unit)
            lineTo(6.5f * unit, 13.5f * unit)
        }
        drawPath(arrows, tint, style = stroke)
    }
}

