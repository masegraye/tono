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
    RenameDialog("Rename Tab", name, { name = it }, renaming.automaticName, "Leave it empty to name the tab after the program running in it.", focus, save, onDismiss)
}

/**
 * Asks for a pane's name, in the same way. The name is the block's label on the server; a
 * pane that has one shows it in its corner.
 */
@Composable
fun RenamePaneDialog(renaming: PaneRename, onDismiss: () -> Unit) {
    var name by remember(renaming) { mutableStateOf(TextFieldValue(renaming.givenName, TextRange(0, renaming.givenName.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(renaming) { focus.requestFocus() }
    val save = {
        onDismiss()
        if (name.text.trim() != renaming.givenName) renaming.host.renamePane(renaming.blockId, name.text)
    }
    RenameDialog("Rename Pane", name, { name = it }, "No name", "Leave it empty for a pane with no name.", focus, save, onDismiss)
}

@Composable
private fun RenameDialog(
    title: String,
    name: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    placeholder: String,
    hint: String,
    focus: FocusRequester,
    save: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = onChange,
                singleLine = true,
                placeholder = { Text(placeholder) },
                supportingText = { Text(hint) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onEscape(onDismiss).onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) { save(); true } else false
                },
            )
        },
        confirmButton = { PrimaryDialogButton("Rename", save) },
        dismissButton = { SecondaryDialogButton("Cancel", onDismiss) },
    )
}
