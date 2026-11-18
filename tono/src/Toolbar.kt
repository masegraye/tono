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
import androidx.compose.ui.unit.dp

/**
 * One of the small buttons along the top of the sidebar. Resting the pointer on it says
 * what it does and which key does the same.
 *
 * @param active true while the thing it switches is on, which tints it
 */
@Composable
fun ToolbarButton(description: String, shortcut: String?, onClick: () -> Unit, active: Boolean = false, icon: @Composable (tint: Color) -> Unit) {
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
            modifier = Modifier.size(28.dp)
                .clip(MaterialTheme.shapes.small)
                .background(if (active) colors.secondaryContainer else Color.Transparent)
                .clickable(onClick = onClick)
                .semantics { contentDescription = description },
        ) {
            icon(if (active) colors.onSecondaryContainer else colors.onSurfaceVariant)
        }
    }
}

/** A window with a column down its left side: the sidebar. */
@Composable
fun SidebarIcon(tint: Color) {
    Canvas(Modifier.size(16.dp)) {
        val unit = size.width / 16f
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
