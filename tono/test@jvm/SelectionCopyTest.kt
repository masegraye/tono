package org.example.kotlintest

import org.example.kotlintest.rex.SelectionSwitches
import java.awt.datatransfer.StringSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SelectionCopyTest {
    private val off = SelectionSwitches()
    private val copy = SelectionSwitches(copyOnSelection = true)
    private val primary = SelectionSwitches(primarySelection = true)
    private val both = SelectionSwitches(copyOnSelection = true, primarySelection = true)

    @Test
    fun `with both switches off a selection goes nowhere and the middle button pastes nothing`() {
        for (kind in PrimarySelection.entries) {
            assertFalse(selectionTargets(off, kind).any, "$kind")
            assertFalse(middlePastes(off, kind), "$kind")
        }
    }

    @Test
    fun `copy on selection is the clipboard's alone, and gives the middle button nothing to do`() {
        for (kind in PrimarySelection.entries) {
            assertEquals(SelectionTargets(clipboard = true, primary = false), selectionTargets(copy, kind), "$kind")
            assertFalse(middlePastes(copy, kind), "$kind")
        }
    }

    @Test
    fun `on a desktop that has one, the primary selection is set and the clipboard left alone`() {
        assertEquals(SelectionTargets(clipboard = false, primary = true), selectionTargets(primary, PrimarySelection.Desktop))
        assertTrue(middlePastes(primary, PrimarySelection.Desktop))
        assertEquals(SelectionTargets(clipboard = true, primary = true), selectionTargets(both, PrimarySelection.Desktop))
    }

    @Test
    fun `in a browser the clipboard stands in for it`() {
        assertEquals(SelectionTargets(clipboard = true, primary = false), selectionTargets(primary, PrimarySelection.Clipboard))
        assertTrue(middlePastes(primary, PrimarySelection.Clipboard))
    }

    @Test
    fun `where there is none the switch does nothing`() {
        assertFalse(selectionTargets(primary, PrimarySelection.None).any)
        assertFalse(middlePastes(primary, PrimarySelection.None))
        assertEquals(SelectionTargets(clipboard = true, primary = false), selectionTargets(both, PrimarySelection.None))
    }

    @Test
    fun `what a selection holds is pasted as what a clipboard holds is`() {
        val content = clipboardContent(StringSelection("echo hi"))

        assertEquals("echo hi", assertIs<ClipboardContent.Text>(content).text)
    }
}
