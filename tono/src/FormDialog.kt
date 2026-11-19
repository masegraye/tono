package org.example.kotlintest

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * The one modal that anything typed goes in (design-001): a tab's, a pane's or a session's
 * name, a host's label and addresses, the label this client has. A switch or a choice from a
 * list applies where it is, at once; what is typed is a form, which is saved whole or not at
 * all, and may be refused.
 *
 * - Enter saves and Escape cancels, from any field.
 * - The first field has the keyboard when it opens.
 * - Save can't be pressed until the form can be saved.
 * - What is wrong is said beside the field it is about, and the form stays open.
 * - Cancelling, or a click beside the form, asks first if anything was changed. The form
 *   asks in itself, where Cancel and Save were: Enter discards, Escape goes on editing.
 *
 * Where there is room it is a dialog over the window. Where there isn't (a phone, a third of
 * a screen) it is a sheet over everything, with Cancel at its top left and Save at its top
 * right, where thumbs and the keyboard on the screen leave them in reach.
 */

/**
 * @param saveLabel what saving is called here: Save, Rename, Add Host
 * @param canSave whether the form can be saved as it stands. False greys Save out, and Enter does nothing.
 * @param changed whether anything in it differs from what it opened with, which is when cancelling asks first
 * @param busy true while a save is on its way to a server: nothing can be pressed twice
 * @param sheet whether to fill the window instead of standing over it. A sheet has to be drawn somewhere that fills the window.
 * @param cancelRequests goes up when something outside asks to cancel, as the browser's back does on a phone
 * @param onSave saves. The form stays until its caller takes it down, so a refusal can be shown in it.
 * @param onClose takes the form down, with nothing saved
 * @param content the fields. [FocusRequester] is for the first of them, which has the keyboard when the form opens.
 */
@Composable
internal fun FormDialog(
    title: String,
    saveLabel: String = "Save",
    canSave: Boolean = true,
    changed: Boolean = false,
    busy: Boolean = false,
    sheet: Boolean = false,
    cancelRequests: Int = 0,
    onSave: () -> Unit,
    onClose: () -> Unit,
    content: @Composable ColumnScope.(first: FocusRequester) -> Unit,
) {
    val first = remember { FocusRequester() }
    var asking by remember { mutableStateOf(false) }
    val cancel = { if (changed && !busy) asking = true else if (!busy) onClose() }
    val save = { if (canSave && !busy) onSave() }
    // Something outside asked to cancel since this opened.
    var heard by remember { mutableStateOf(cancelRequests) }
    LaunchedEffect(cancelRequests) {
        if (cancelRequests != heard) { heard = cancelRequests; cancel() }
    }
    val keep = { asking = false }
    val discard = { asking = false; onClose() }
    // The form's keys, from whichever of its fields has the keyboard. While it is asking
    // whether to discard, they answer that: Enter discards, Escape goes on editing.
    val keys = Modifier.onPreviewKeyEvent { event ->
        val down = event.type == KeyEventType.KeyDown
        when {
            event.key == Key.Escape -> true.also { if (down) { if (asking) keep() else cancel() } }
            event.key == Key.Enter || event.key == Key.NumPadEnter -> true.also { if (down) { if (asking) discard() else save() } }
            else -> false
        }
    }
    if (sheet) {
        Surface(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }.then(keys), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (asking) {
                        // The question takes the place of Cancel and Save, where the answer is in reach.
                        Text("Discard what you changed?", style = MaterialTheme.typography.titleSmall, maxLines = 2, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                        TextButton(onClick = keep, modifier = Modifier.heightIn(min = TOUCH_TARGET)) { Text("Keep Editing", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(onClick = discard, modifier = Modifier.heightIn(min = TOUCH_TARGET)) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                    } else {
                        TextButton(onClick = cancel, enabled = !busy, modifier = Modifier.heightIn(min = TOUCH_TARGET)) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                        TextButton(onClick = save, enabled = canSave && !busy, modifier = Modifier.heightIn(min = TOUCH_TARGET)) { Text(saveLabel) }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) { content(first) }
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = { if (asking) keep() else cancel() },
            title = { Text(title) },
            text = {
                Column(Modifier.widthIn(max = FORM_WIDTH).fillMaxWidth().verticalScroll(rememberScrollState()).then(keys)) {
                    content(first)
                    // Asked for from in here, once the dialog has put the field there to be given the keyboard.
                    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                    if (asking) Text("Discard what you changed? Nothing of it has been saved.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                }
            },
            // The question is answered where Cancel and Save were, so that it is the form's own and its keys answer it.
            // Escape from a button is the form's Escape too: a browser doesn't always hand it to the dialog.
            confirmButton = {
                if (asking) PrimaryDialogButton("Discard", discard, Modifier.onEscape(keep), destructive = true)
                else PrimaryDialogButton(saveLabel, save, Modifier.onEscape(cancel), enabled = canSave && !busy)
            },
            dismissButton = { if (asking) SecondaryDialogButton("Keep Editing", keep, Modifier.onEscape(keep)) else SecondaryDialogButton("Cancel", cancel, Modifier.onEscape(cancel)) },
        )
    }
}

/** About how wide the form is as a dialog. */
internal val FORM_WIDTH = 480.dp

/**
 * One line of a form to type in.
 *
 * @param error what is wrong with what was typed, shown under it in place of [hint]
 * @param focus for the first field of a form
 * @param plain typed as it is, nothing capitalized or corrected: an address, a name
 */
@Composable
internal fun FormField(
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    label: String? = null,
    placeholder: String = "",
    hint: String? = null,
    error: String? = null,
    focus: FocusRequester? = null,
    plain: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        // A form has no line breaks: Enter saves it.
        onValueChange = { typed -> onChange(if ('\n' in typed.text) typed.copy(text = typed.text.replace("\n", "")) else typed) },
        singleLine = true,
        isError = error != null,
        label = label?.let { { Text(it) } },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingText = (error ?: hint)?.let { { Text(it) } },
        keyboardOptions = if (plain) KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = keyboard) else KeyboardOptions.Default,
        modifier = modifier.fillMaxWidth().then(if (focus != null) Modifier.focusRequester(focus) else Modifier),
    )
}

/** A field's text as it opens: everything selected, so that typing replaces it. */
internal fun selected(text: String) = TextFieldValue(text, TextRange(0, text.length))
