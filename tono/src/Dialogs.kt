package org.example.kotlintest

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

/*
 * The two buttons every dialog has. One is what the dialog is there to do: it is filled, so
 * it is the first thing seen, and it says which key presses it. The other backs out, and is
 * plain text. A dialog never has two filled buttons, so there is never a question of which
 * one Enter means.
 */

/**
 * The button for what a dialog is there to do. Enter presses it.
 *
 * @param destructive true when it ends or deletes something that can't be brought back, which
 *   colors it as a warning
 */
@Composable
fun PrimaryDialogButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, destructive: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier.keyboardFocusRing(interaction),
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

/** The button that backs out of a dialog. Escape presses it. */
@Composable
fun SecondaryDialogButton(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    TextButton(
        onClick = onClick,
        modifier = Modifier.keyboardFocusRing(interaction),
        interactionSource = interaction,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
    ) {
        Text(label)
        KeyHint("esc")
    }
}

/**
 * A ring around whichever button has the keyboard. The filled button shows what Enter does
 * as a dialog opens; the ring shows where Enter and Space go once Tab has moved on from it.
 * It is drawn outside the button, so the filled one keeps its shape and nothing shifts.
 */
@Composable
private fun Modifier.keyboardFocusRing(interaction: MutableInteractionSource): Modifier {
    val focused by interaction.collectIsFocusedAsState()
    val color = if (focused) MaterialTheme.colorScheme.onSurface else Color.Transparent
    return padding(3.dp).border(2.dp, color, CircleShape).padding(3.dp)
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

