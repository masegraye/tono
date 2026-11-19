package org.example.kotlintest.rex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun pane(blockId: String) = PaneView(blockId, PaneRect.Full, label = null, ownerClientId = null, isTerminal = true, floating = false)

private fun tab(id: String, vararg blocks: String) = WindowView(id, id, focusedBlockId = blocks.first(), zoomedBlockId = null, panes = blocks.map(::pane))

private fun session(id: String, vararg tabs: WindowView) = id to SessionView(id, id, revision = 1, activeWindowId = tabs.firstOrNull()?.id, windows = tabs.toList())

private fun fields(text: String) = Json.parseToJsonElement(text).jsonObject

/** Two sessions. "work" is the one selected, showing its first tab. */
private val host = HostState(
    selectedSessionId = "work",
    views = mapOf(session("work", tab("w1", "a"), tab("w2", "b", "c")), session("notes", tab("n1", "d"))),
)

class TabNewsTest {
    @Test
    fun `a terminal's tab is found in whichever session has it`() {
        assertEquals(TabPlace("studio", "work", "w2"), host.placeOf("studio", "c"))
        assertEquals(TabPlace("studio", "notes", "n1"), host.placeOf("studio", "d"))
    }

    @Test
    fun `a terminal in no known tab has no place`() {
        assertNull(host.placeOf("studio", "gone"))
    }

    @Test
    fun `the tab on screen is the selected session's shown one`() {
        assertEquals(TabPlace("studio", "work", "w1"), host.shownPlace("studio"))
        assertEquals(TabPlace("studio", "work", "w2"), host.copy(shownWindows = mapOf("work" to "w2")).shownPlace("studio"))
        assertNull(host.copy(selectedSessionId = null).shownPlace("studio"))
    }

    @Test
    fun `a program's notification is read from the event, OSC 9 with a body only and OSC 777 with a title too`() {
        val place = TabPlace("studio", "work", "w2")
        assertEquals(TabNews.Message(place, "", "hello there"), TabNews.message(place, fields("""{"title":"","body":"hello there"}""")))
        assertEquals(TabNews.Message(place, "Title", "Body text"), TabNews.message(place, fields("""{"title":"Title","body":" Body text\n"}""")))
        assertEquals(TabNews.Message(null, "Only a title", ""), TabNews.message(null, fields("""{"title":"Only a title"}""")))
    }

    @Test
    fun `a program's notification that says nothing is no news`() {
        assertNull(TabNews.message(null, fields("""{"title":"","body":"  "}""")))
        assertNull(TabNews.message(null, fields("""{"title":7}""")))
        assertNull(TabNews.message(null, null))
    }
}

class NotificationSwitchesTest {
    @Test
    fun `every switch is on until it is turned off`() {
        assertEquals(NotificationSwitches(), NotificationSwitches.parse(null))
        assertEquals(NotificationSwitches(), NotificationSwitches.parse(fields("""{"done":"sometimes","other":false}""")))
    }

    @Test
    fun `switches are written under Rex Rune's names and read back`() {
        val switches = NotificationSwitches(desktop = true, blocked = true, error = false, done = false, programMessages = true)
        assertEquals(fields("""{"system_notifications":true,"blocked":true,"error":false,"done":false,"program_messages":true}"""), switches.toJson())
        for (each in listOf(switches, NotificationSwitches(desktop = false), NotificationSwitches(blocked = false, programMessages = false))) {
            assertEquals(each, NotificationSwitches.parse(each.toJson()))
        }
    }

    @Test
    fun `the workspace keeps the switches, and shows them all even when none has been touched`() {
        val file = Files.createTempDirectory("workspace").resolve("workspace.json")
        Workspace(WorkspaceFile(file)).save()
        assertEquals(NotificationSwitches().toJson(), fields(Files.readString(file))["notifications"])
        assertEquals(NotificationSwitches(), Workspace.load(file).notifications)

        val workspace = Workspace.load(file)
        workspace.notifications = NotificationSwitches(done = false)
        workspace.save()
        assertEquals(NotificationSwitches(done = false), Workspace.load(file).notifications)
        file.parent.toFile().deleteRecursively()
    }

    @Test
    fun `the selection switches are off until turned on, and are written under Rex Rune's names`() {
        assertEquals(SelectionSwitches(copyOnSelection = false, primarySelection = false), SelectionSwitches.parse(null))
        assertEquals(SelectionSwitches(), SelectionSwitches.parse(fields("""{"copy_on_selection":"sometimes","primary_selection":false}""")))
        val both = SelectionSwitches(copyOnSelection = true, primarySelection = true)
        assertEquals(fields("""{"copy_on_selection":true,"primary_selection":true}"""), both.toJson())
        for (each in listOf(both, SelectionSwitches(copyOnSelection = true), SelectionSwitches(primarySelection = true))) {
            assertEquals(each, SelectionSwitches.parse(each.toJson()))
        }
    }

    @Test
    fun `the workspace keeps the selection switches, and shows them even when none has been touched`() {
        val file = Files.createTempDirectory("workspace").resolve("workspace.json")
        Workspace(WorkspaceFile(file)).save()
        assertEquals(SelectionSwitches().toJson(), fields(Files.readString(file))["terminal"])
        assertEquals(SelectionSwitches(), Workspace.load(file).selection)

        val workspace = Workspace.load(file)
        workspace.selection = SelectionSwitches(primarySelection = true)
        workspace.save()
        assertEquals(SelectionSwitches(primarySelection = true), Workspace.load(file).selection)
        assertEquals(NotificationSwitches(), Workspace.load(file).notifications)
        file.parent.toFile().deleteRecursively()
    }
}
