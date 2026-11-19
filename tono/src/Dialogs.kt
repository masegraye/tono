package org.example.kotlintest

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/*
 * The two buttons every dialog has, and the dialog that only asks a question. One button is
 * what the dialog is there to do: it is filled, so it is the first thing seen, and it says
 * which key presses it. The other backs out, and is plain text. A dialog never has two filled
 * buttons, so there is never a question of which one Enter means.
 *
 * Every dialog that stands over the window uses these, in Material's dialog, which puts the
 * one that backs out left of the other at the bottom right and stacks them where both don't
 * fit on a line.
 */

/** The shape of both buttons, and so of the ring around the one that has the keyboard. */
private val DialogButtonShape: Shape @Composable get() = ButtonDefaults.shape

/**
 * The button for what a dialog is there to do. Enter presses it.
 *
 * @param modifier for the button itself: what gives it the keyboard, the keys it hears
 * @param destructive true when it ends or deletes something that can't be brought back, which
 *   colors it as a warning
 */
@Composable
fun PrimaryDialogButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, destructive: Boolean = false, enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val shape = DialogButtonShape
    DialogButtonPlace {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.keyboardFocusRing(interaction, shape),
            shape = shape,
            interactionSource = interaction,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (destructive) colors.error else colors.primary,
                contentColor = if (destructive) colors.onError else colors.onPrimary,
            ),
        ) {
            Text(label)
            KeyHint("↵")
        }
    }
}

/**
 * The button that backs out of a dialog. Escape presses it.
 *
 * @param modifier for the button itself, as [PrimaryDialogButton]'s is
 */
@Composable
fun SecondaryDialogButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val shape = DialogButtonShape
    DialogButtonPlace {
        TextButton(
            onClick = onClick,
            modifier = modifier.keyboardFocusRing(interaction, shape),
            shape = shape,
            interactionSource = interaction,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        ) {
            Text(label)
            KeyHint("esc")
        }
    }
}

/**
 * The room a dialog's button takes: what a finger needs, and a margin in which the ring is
 * drawn. The button within is the size it is drawn at and no larger, which is what lets the
 * ring be measured from it. Material would otherwise pad the button out to a finger's height
 * itself, unseen, and a ring around that stands off the button above and below.
 */
@Composable
private fun DialogButtonPlace(button: @Composable () -> Unit) {
    Box(Modifier.padding(RING_MARGIN).minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp, content = button)
    }
}

/** The room around a dialog's button, in which its ring is drawn. */
private val RING_MARGIN = 6.dp

/** How far the ring stands off its button, and how thick it is. */
private val RING_GAP = 2.dp
private val RING_WIDTH = 2.dp

/**
 * A ring around whichever button has the keyboard. The filled button shows what Enter does
 * as a dialog opens; the ring shows where Enter and Space go once Tab has moved on from it.
 *
 * It is the button's own outline, [shape], a little larger: the same corners, the same gap
 * all the way round. It is drawn over the margin the button has and takes no room of its
 * own, so nothing moves when it appears.
 */
@Composable
private fun Modifier.keyboardFocusRing(interaction: MutableInteractionSource, shape: Shape): Modifier {
    val focused by interaction.collectIsFocusedAsState()
    val color = MaterialTheme.colorScheme.onSurface
    return drawWithContent {
        drawContent()
        if (!focused) return@drawWithContent
        // The middle of the line is the gap and half the line's width out from the button.
        val grow = RING_GAP.toPx() + RING_WIDTH.toPx() / 2
        val ring = ringOutline(shape, size, grow, layoutDirection, this)
        translate(-grow, -grow) { drawOutline(ring, color, style = Stroke(RING_WIDTH.toPx())) }
    }
}

/**
 * [shape] at [size], grown by [grow] on every side, with its corner at the origin. Rounded
 * corners grow with it, so that the gap is as wide around a corner as along an edge.
 */
internal fun ringOutline(shape: Shape, size: Size, grow: Float, direction: LayoutDirection, density: Density): Outline {
    val grown = Size(size.width + 2 * grow, size.height + 2 * grow)
    return when (val outline = shape.createOutline(size, direction, density)) {
        is Outline.Rounded -> {
            fun corner(radius: CornerRadius) = if (radius.x <= 0f && radius.y <= 0f) radius else CornerRadius(radius.x + grow, radius.y + grow)
            val of = outline.roundRect
            Outline.Rounded(RoundRect(Rect(Offset.Zero, grown), corner(of.topLeftCornerRadius), corner(of.topRightCornerRadius), corner(of.bottomRightCornerRadius), corner(of.bottomLeftCornerRadius)))
        }
        is Outline.Rectangle -> Outline.Rectangle(Rect(Offset.Zero, grown))
        // A shape that is neither: itself at the larger size, which is as near as can be said.
        is Outline.Generic -> shape.createOutline(grown, direction, density)
    }
}

/**
 * A question that stands over the window until it is answered: closing a pane, removing a
 * host, taking a shortcut from another action. The button that does it has the keyboard as
 * it opens, so Enter does it; Escape, Cancel and a click beside it don't. Tab moves between
 * the two buttons, and Enter and Space then press the one with the ring.
 *
 * @param onDismiss takes the question down. Called before [Confirmation.onConfirm] too.
 */
@Composable
fun ConfirmationDialog(pending: Confirmation, onDismiss: () -> Unit) {
    val confirmFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pending.title) },
        text = { Text(pending.text) },
        confirmButton = {
            PrimaryDialogButton(pending.confirmLabel, onClick = { onDismiss(); pending.onConfirm() }, Modifier.focusRequester(confirmFocus).onEscape(onDismiss), destructive = pending.destructive)
            // Asked for from in here, once the button is there to be given it.
            LaunchedEffect(pending) { confirmFocus.requestFocus() }
        },
        dismissButton = { SecondaryDialogButton("Cancel", onDismiss, Modifier.onEscape(onDismiss)) },
    )
}

/** The key that presses a button, small and faint beside its label. */
@Composable
private fun KeyHint(key: String) {
    Text(key, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp), color = androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.65f))
}

/**
 * Backs out of a dialog on Escape, from whatever in it has the keyboard. A dialog does this
 * itself on the desktop; in a browser it doesn't always, so the dialogs here say so.
 */
fun Modifier.onEscape(dismiss: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.key != Key.Escape) return@onPreviewKeyEvent false
    if (event.type == KeyEventType.KeyDown) dismiss()
    true
}

