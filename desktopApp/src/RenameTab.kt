package org.example.kotlintest

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Asks for a tab's name. The field starts with the name the tab was given, selected so that
 * typing replaces it, or empty for a tab called by its program, with that program's name as
 * the hint. Enter saves and Escape cancels. Saving an empty name clears it.
 */
@Composable
fun RenameTabDialog(renaming: TabRename, onDismiss: () -> Unit) {
    var name by remember(renaming) { mutableStateOf(TextFieldValue(renaming.givenName, TextRange(0, renaming.givenName.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(renaming) { focus.requestFocus() }
    val save = {
        onDismiss()
        if (name.text.trim() != renaming.givenName) renaming.host.renameTab(renaming.sessionId, renaming.windowId, name.text)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Tab") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(renaming.automaticName) },
                supportingText = { Text("Leave it empty to name the tab after the program running in it.") },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) { save(); true } else false
                },
            )
        },
        confirmButton = { PrimaryDialogButton("Rename", save) },
        dismissButton = { SecondaryDialogButton("Cancel", onDismiss) },
    )
}
