package org.example.kotlintest

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import org.example.kotlintest.rex.TerminalProgress

/*
 * How a tab shows that its program rang the bell while nobody was looking, and the progress
 * its program reports with OSC 9;4. The sidebar is where tabs are, so that is where both go;
 * the status line says the progress of the tab on screen in words, for when the sidebar is
 * hidden.
 */

/** A small bell, for a tab whose program rang one while the tab was out of sight. Nothing when it didn't. */
@Composable
internal fun BellMark(rang: Boolean, modifier: Modifier = Modifier) {
    if (!rang) return
    Icon(Icons.Filled.Notifications, contentDescription = "Bell", tint = MaterialTheme.colorScheme.primary, modifier = modifier.padding(start = 6.dp).size(12.dp))
}

/**
 * Draws a tab's progress as a thin bar along the bottom of its row, inset to its text: the
 * part done over a faint track, in the accent color while the work goes on, the error color
 * when it failed, and a muted one while it is paused. Progress with no percentage is a short
 * piece that travels along the track if the program is working, and the whole track
 * otherwise. No progress, no bar.
 */
@Composable
internal fun Modifier.progressBar(progress: TerminalProgress?): Modifier {
    if (progress == null) return this
    val scheme = MaterialTheme.colorScheme
    val color = when (progress.kind) {
        TerminalProgress.Kind.Error -> scheme.error
        TerminalProgress.Kind.Paused -> scheme.onSurfaceVariant
        else -> scheme.primary
    }
    val track = scheme.onSurfaceVariant.copy(alpha = 0.18f)
    val span = progressSpan(progress)
    // Where a piece that says only "busy" has got to, from one end of the track to the other and around.
    val travel = if (span == null) {
        val position by rememberInfiniteTransition(label = "progress").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1400, easing = LinearEasing)),
            label = "position",
        )
        position
    } else 0f
    return drawWithContent {
        drawContent()
        val height = 2.dp.toPx()
        val left = 14.dp.toPx()
        val width = size.width - left - 8.dp.toPx()
        val top = size.height - height - 1.dp.toPx()
        if (width <= 0f) return@drawWithContent
        val corner = CornerRadius(height / 2)
        drawRoundRect(track, Offset(left, top), Size(width, height), corner)
        val (start, end) = span ?: travellingSpan(travel)
        if (end > start) drawRoundRect(color, Offset(left + width * start, top), Size(width * (end - start), height), corner)
    }
}

/**
 * The part of the track to fill for a report, as fractions of its length, or null for one
 * that only says the program is busy, which is drawn moving. A known percentage fills that
 * much, and never so little that it can't be seen. A pause or a failure that names no
 * percentage fills the track: there is no "how far", only that it stopped.
 */
internal fun progressSpan(progress: TerminalProgress): Pair<Float, Float>? {
    val percent = progress.percent
    return when {
        percent != null && progress.kind != TerminalProgress.Kind.Indeterminate -> 0f to (percent / 100f).coerceIn(MIN_FILL, 1f)
        progress.kind == TerminalProgress.Kind.Paused || progress.kind == TerminalProgress.Kind.Error -> 0f to 1f
        else -> null
    }
}

/**
 * The part of the track a travelling piece covers when it is [position] of the way through
 * its journey (0 to 1). It comes in from the left edge and leaves by the right, so the loop
 * has no jump in it.
 */
internal fun travellingSpan(position: Float): Pair<Float, Float> {
    val start = position + (position - 1f) * TRAVELLER // from a piece's length before the track to its far end
    return start.coerceIn(0f, 1f) to (start + TRAVELLER).coerceIn(0f, 1f)
}

/**
 * A tab's progress in a word or two, for the status line and for a screen reader: `42%`,
 * `working`, `paused 42%`, `failed`.
 */
internal fun progressWords(progress: TerminalProgress): String {
    val percent = progress.percent?.let { "$it%" }
    return when (progress.kind) {
        TerminalProgress.Kind.Indeterminate -> "working"
        TerminalProgress.Kind.Normal -> percent ?: "working"
        TerminalProgress.Kind.Paused -> listOfNotNull("paused", percent).joinToString(" ")
        TerminalProgress.Kind.Error -> listOfNotNull("failed", percent?.let { "at $it" }).joinToString(" ")
    }
}

/** What a tab's row says of its bell and progress to a screen reader, or null when there is neither. */
internal fun tabNews(bell: Boolean, progress: TerminalProgress?): String? =
    listOfNotNull("bell".takeIf { bell }, progress?.let(::progressWords)).joinToString(", ").takeIf { it.isNotEmpty() }

/** The least of the track a known percentage fills, so that 0% and 1% still show as started. */
private const val MIN_FILL = 0.03f

/** How long the travelling piece is, as a fraction of the track. */
private const val TRAVELLER = 0.3f
