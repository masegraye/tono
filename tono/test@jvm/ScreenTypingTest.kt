package org.example.kotlintest

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a keyboard on a screen does to its field, and what that should do to a terminal. */
class ScreenTypingTest {
    private val typing = ScreenTyping()

    /** The field after the keyboard has made it read [content] after the mark, still composing a word or not. */
    private fun keyboard(content: String, composing: Boolean = false): Pair<Int, String> {
        val text = ScreenTyping.MARK + content
        val edit = typing.changed(TextFieldValue(text, TextRange(text.length), composition = if (composing) TextRange(ScreenTyping.MARK.length, text.length) else null))
        return edit.backspaces to edit.text
    }

    @Test
    fun `a letter put in the field is typed, and the field is emptied for the next`() {
        assertEquals(0 to "l", keyboard("l"))
        assertEquals(0 to "s", keyboard("s"))
        assertEquals(ScreenTyping.MARK, typing.field.text)
    }

    @Test
    fun `a word put in at once is typed at once`() {
        assertEquals(0 to "docker ", keyboard("docker "))
    }

    @Test
    fun `a word still being put together is typed as it grows`() {
        assertEquals(0 to "g", keyboard("g", composing = true))
        assertEquals(0 to "i", keyboard("gi", composing = true))
        assertEquals(0 to "t", keyboard("git", composing = true))
        // Settled as it stood: nothing more to type.
        assertEquals(0 to "", keyboard("git"))
        assertEquals(ScreenTyping.MARK, typing.field.text)
    }

    @Test
    fun `a word the keyboard changes its mind about is backspaced over and typed again`() {
        keyboard("teh", composing = true)
        assertEquals(2 to "he ", keyboard("the "))
    }

    @Test
    fun `a character taken back while a word is being put together is one backspace`() {
        keyboard("gir", composing = true)
        assertEquals(1 to "", keyboard("gi", composing = true))
    }

    @Test
    fun `backspace in an empty field deletes the mark, which is how it is noticed`() {
        val edit = typing.changed(TextFieldValue("", TextRange(0)))
        assertEquals(1 to "", edit.backspaces to edit.text)
        assertEquals(ScreenTyping.MARK, typing.field.text)
    }

    @Test
    fun `a new line comes through as text for the terminal to turn into Enter`() {
        assertEquals(0 to "ls\n", keyboard("ls\n"))
    }

    @Test
    fun `the keys Control goes with have names`() {
        assertEquals("key_c", controlKeyNamed('c'))
        assertEquals("key_d", controlKeyNamed('D'))
        assertEquals("bracket_left", controlKeyNamed('['))
        assertEquals(null, controlKeyNamed('1'))
    }
}
