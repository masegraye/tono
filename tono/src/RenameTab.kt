package org.example.kotlintest

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue

/*
 * Renaming: a tab, a pane, a session. Each is a form of one field, in the modal every form
 * is in (FormDialog.kt): a dialog where there is room, a sheet over a narrow window.
 */

/**
 * Asks for a tab's name. The field starts with the name the tab was given, selected so that
 * typing replaces it, or empty for a tab called by its program, with that program's name as
 * the hint. Enter saves and Escape cancels. Saving an empty name clears it.
 */
@Composable
fun RenameTabDialog(renaming: TabRename, onDismiss: () -> Unit, sheet: Boolean = false) {
    var name by remember(renaming) { mutableStateOf(selected(renaming.givenName)) }
    val save = {
        onDismiss()
        if (name.text.trim() != renaming.givenName) renaming.host.renameTab(renaming.sessionId, renaming.windowId, name.text)
    }
    RenameDialog("Rename Tab", name, { name = it }, renaming.automaticName, "Leave it empty to name the tab after the program running in it.", save, onDismiss, sheet)
}

/**
 * Asks for a pane's name, in the same way. The name is the block's label on the server; a
 * pane that has one shows it in its corner.
 */
@Composable
fun RenamePaneDialog(renaming: PaneRename, onDismiss: () -> Unit, sheet: Boolean = false) {
    var name by remember(renaming) { mutableStateOf(selected(renaming.givenName)) }
    val save = {
        onDismiss()
        if (name.text.trim() != renaming.givenName) renaming.host.renamePane(renaming.blockId, name.text)
    }
    RenameDialog("Rename Pane", name, { name = it }, "No name", "Leave it empty for a pane with no name.", save, onDismiss, sheet)
}

/**
 * Asks for a session's name, in the same way. The name is the session's label on the server.
 * A session always has one: the server makes one up for a session nobody named, and gives
 * that back when the name is cleared.
 */
@Composable
fun RenameSessionDialog(renaming: SessionRename, onDismiss: () -> Unit, sheet: Boolean = false) {
    var name by remember(renaming) { mutableStateOf(selected(renaming.name)) }
    val save = {
        onDismiss()
        if (name.text.trim() != renaming.name) renaming.host.renameSession(renaming.sessionId, name.text)
    }
    RenameDialog("Rename Session", name, { name = it }, "The server's name for it", "Leave it empty to go back to the name the server gave it.", save, onDismiss, sheet)
}

/** A name is one line, and backing out of one loses nothing worth asking about. */
@Composable
private fun RenameDialog(title: String, name: TextFieldValue, onChange: (TextFieldValue) -> Unit, placeholder: String, hint: String, save: () -> Unit, onDismiss: () -> Unit, sheet: Boolean) {
    FormDialog(title, saveLabel = "Rename", sheet = sheet, onSave = save, onClose = onDismiss) { first ->
        FormField(name, onChange, placeholder = placeholder, hint = hint, focus = first)
    }
}
