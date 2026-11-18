package org.example.kotlintest

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/*
 * Typing into a terminal on a touch screen.
 *
 * A keyboard on a screen comes up for a text field and nothing else, and it doesn't press
 * keys: it edits the field's text, a letter at a time or a word at a time, sometimes going
 * back to change what it put there. So a terminal that was touched keeps a text field no one
 * can see, and what the keyboard does to that field is done to the terminal: text added is
 * typed, text taken away is backspaced over.
 */

/**
 * What a screen keyboard did to a field, as what to do to a terminal: how many characters
 * to backspace over, then the text to type.
 */
internal class ScreenEdit(val backspaces: Int, val text: String)

/**
 * Follows a field a screen keyboard is editing. The field always holds one character the
 * user never sees, [MARK], so that a backspace into an empty field has something to remove
 * and can be told from nothing having happened.
 */
internal class ScreenTyping {
    /** What has been passed on of the field's text, after the mark. */
    private var passedOn = ""

    /** The field as it should be shown: the mark, and whatever is still being composed. */
    var field by mutableStateOf(EMPTY)
        private set

    /** Takes the field as the keyboard left it, and says what that did. */
    fun changed(now: TextFieldValue): ScreenEdit {
        val text = now.text
        if (!text.startsWith(MARK)) {
            // The mark itself was deleted: a backspace with nothing typed since the last.
            val edit = ScreenEdit(backspaces = 1 + passedOn.length, text = text)
            reset()
            return edit
        }
        val content = text.substring(MARK.length)
        val same = content.commonPrefixWith(passedOn).length
        val edit = ScreenEdit(backspaces = passedOn.length - same, text = content.substring(same))
        // A keyboard that is still putting a word together may yet change it, so the field
        // keeps the word until it is settled. Then it is emptied: there is nothing behind a
        // terminal's cursor for a keyboard to correct.
        if (now.composition == null) reset() else { passedOn = content; field = now }
        return edit
    }

    private fun reset() {
        passedOn = ""
        field = EMPTY
    }

    companion object {
        const val MARK = " "
        private val EMPTY = TextFieldValue(MARK, TextRange(MARK.length))
    }
}

/**
 * The field a screen keyboard edits. It is there to hold the keyboard and takes up no room.
 * Its modifier decides what else it does, such as taking the focus when the terminal is touched.
 */
@Composable
internal fun ScreenKeyboardField(typing: ScreenTyping, onEdit: (ScreenEdit) -> Unit, modifier: Modifier = Modifier) {
    BasicTextField(
        value = typing.field,
        onValueChange = { onEdit(typing.changed(it)) },
        modifier = modifier.size(1.dp).alpha(0f),
        // Nothing a keyboard does to prose helps at a prompt.
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.None,
        ),
    )
}

/** A key a screen keyboard doesn't have, by the name Rex has for it, or a modifier to hold for the next key. */
internal enum class ScreenKey(val label: String, val rexName: String?) {
    Escape("esc", "escape"), Tab("tab", "tab"), Control("ctrl", null),
    Left("←", "arrow_left"), Down("↓", "arrow_down"), Up("↑", "arrow_up"), Right("→", "arrow_right"),
    Hide("⌄", null),
}

/**
 * The keys a screen keyboard leaves out and a terminal can't do without, in a row over it.
 *
 * @param controlHeld whether Control is waiting for the next key, which lights its button
 */
@Composable
internal fun ScreenKeyRow(controlHeld: Boolean, onKey: (ScreenKey) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().background(colors.surfaceContainer).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (key in ScreenKey.entries) {
            val lit = key == ScreenKey.Control && controlHeld
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.weight(1f).widthIn(min = 32.dp).height(34.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(if (lit) colors.primary else colors.surfaceContainerHighest)
                    // A tap and nothing more: a button that took the focus would take the
                    // keyboard away from the field that is holding it up.
                    .pointerInput(key) { detectTapGestures { onKey(key) } },
            ) {
                Text(key.label, style = MaterialTheme.typography.labelLarge, color = if (lit) colors.onPrimary else colors.onSurface, maxLines = 1)
            }
        }
    }
}

/** The name Rex has for the key that types [char], for the keys Control is used with. Null for the rest. */
internal fun controlKeyNamed(char: Char): String? = when (val c = char.lowercaseChar()) {
    in 'a'..'z' -> "key_$c"
    '[' -> "bracket_left"
    ']' -> "bracket_right"
    '\\' -> "backslash"
    ' ' -> "space"
    '/' -> "slash"
    else -> null
}
