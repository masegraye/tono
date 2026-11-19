package org.example.kotlintest

import org.example.kotlintest.rex.ActivityState
import org.example.kotlintest.rex.ForegroundProgram
import org.example.kotlintest.rex.HostState
import org.example.kotlintest.rex.NotificationSwitches
import org.example.kotlintest.rex.PaneRect
import org.example.kotlintest.rex.PaneView
import org.example.kotlintest.rex.SessionSummary
import org.example.kotlintest.rex.SessionView
import org.example.kotlintest.rex.TabNews
import org.example.kotlintest.rex.TabPlace
import org.example.kotlintest.rex.WindowView
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun tab(id: String, label: String) = WindowView(
    id, label, focusedBlockId = "$id-a", zoomedBlockId = null,
    panes = listOf(PaneView("$id-a", PaneRect.Full, label = null, ownerClientId = null, isTerminal = true, floating = false)),
)

/** A session called "work" with two tabs, "build" and "agent". */
private val state = HostState(
    sessions = listOf(SessionSummary("s1", "work")),
    selectedSessionId = "s1",
    views = mapOf("s1" to SessionView("s1", "work", revision = 1, activeWindowId = "w1", windows = listOf(tab("w1", "build"), tab("w2", "agent")))),
)
private val agent = TabPlace("studio", "s1", "w2")
private val on = NotificationSwitches()

private fun activity(state: ActivityState, place: TabPlace? = agent) = TabNews.Activity(place, state)

class NotificationSwitchTest {
    @Test
    fun `with every switch on, waiting, failing, finishing and a program's own message all notify`() {
        for (news in listOf(activity(ActivityState.Blocked), activity(ActivityState.Error), activity(ActivityState.Done), TabNews.Message(agent, "", "hi"))) {
            assertTrue(on.want(news), "$news")
        }
    }

    @Test
    fun `each kind has its own switch, and turns off nothing else`() {
        val blocked = activity(ActivityState.Blocked)
        val error = activity(ActivityState.Error)
        val done = activity(ActivityState.Done)
        val message = TabNews.Message(agent, "", "hi")
        val all = listOf(blocked, error, done, message)
        fun wanted(switches: NotificationSwitches) = all.filter(switches::want)
        assertEquals(all - blocked, wanted(on.copy(blocked = false)))
        assertEquals(all - error, wanted(on.copy(error = false)))
        assertEquals(all - done, wanted(on.copy(done = false)))
        assertEquals(all - message, wanted(on.copy(programMessages = false)))
    }

    @Test
    fun `the main switch turns off every kind`() {
        val off = NotificationSwitches(desktop = false)
        assertFalse(off.want(activity(ActivityState.Blocked)))
        assertFalse(off.want(TabNews.Message(agent, "t", "b")))
        assertNull(noteFor(activity(ActivityState.Blocked), off, state))
    }

    @Test
    fun `a program that is working or idle is not news`() {
        assertFalse(on.want(activity(ActivityState.Working)))
        assertFalse(on.want(activity(ActivityState.Idle)))
        assertNull(noteFor(activity(ActivityState.Working), on, state))
    }

    @Test
    fun `a muted tab's news is turned away before the switches are asked, and nothing else is`() {
        val muted: (TabPlace) -> Boolean = { it == agent }
        assertFalse(announces(activity(ActivityState.Done), muted))
        assertFalse(announces(TabNews.Message(agent, "t", "b"), muted), "what its program asks to say, too")
        assertTrue(announces(activity(ActivityState.Done, TabPlace("studio", "s1", "w1")), muted), "another tab of the session")
        assertTrue(announces(activity(ActivityState.Done, agent.copy(host = "other")), muted), "the same ids on another host")
        assertTrue(announces(activity(ActivityState.Done)) { false })
        // News from no known tab can't be from a muted one.
        assertTrue(announces(TabNews.Message(null, "t", "b")) { true })
    }
}

class NotificationTextTest {
    @Test
    fun `what a program is doing is headed by its tab and the word for it, over where the tab is`() {
        val note = noteFor(activity(ActivityState.Blocked), on, state)!!
        assertEquals("agent · Needs you", note.title)
        assertEquals("work · studio", note.body)
        assertEquals("agent · Failed", noteFor(activity(ActivityState.Error), on, state)!!.title)
        assertEquals("agent · Done", noteFor(activity(ActivityState.Done), on, state)!!.title)
    }

    @Test
    fun `a tab nobody named is called by its program and directory`() {
        val unnamed = state.copy(
            views = mapOf("s1" to state.views.getValue("s1").let { it.copy(windows = it.windows.map { tab -> tab.copy(label = "") }) }),
            programs = mapOf("w2-a" to ForegroundProgram("claude", "~/rex")),
        )
        assertEquals("claude - ~/rex · Done", noteFor(activity(ActivityState.Done), on, unnamed)!!.title)
    }

    @Test
    fun `waiting and failing stay up until dismissed, finishing doesn't`() {
        assertTrue(noteFor(activity(ActivityState.Blocked), on, state)!!.urgent)
        assertTrue(noteFor(activity(ActivityState.Error), on, state)!!.urgent)
        assertFalse(noteFor(activity(ActivityState.Done), on, state)!!.urgent)
        assertFalse(noteFor(TabNews.Message(agent, "t", "b"), on, state)!!.urgent)
    }

    @Test
    fun `a program's message with a title keeps it, and says the whole of where it came from`() {
        val note = noteFor(TabNews.Message(agent, "Tests", "42 passed"), on, state)!!
        assertEquals("Tests", note.title)
        assertEquals("42 passed\nagent - work · studio", note.body)
    }

    @Test
    fun `a program's message with no title is headed by its tab`() {
        val note = noteFor(TabNews.Message(agent, "", "all done"), on, state)!!
        assertEquals("agent", note.title)
        assertEquals("all done\nwork · studio", note.body)
    }

    @Test
    fun `a program's message with a title and no text is the title over where it came from`() {
        assertEquals("agent - work · studio", noteFor(TabNews.Message(agent, "Ping", ""), on, state)!!.body)
    }

    @Test
    fun `news from a terminal in no known tab makes no notification`() {
        assertNull(noteFor(activity(ActivityState.Done, place = null), on, state))
        assertNull(noteFor(TabNews.Message(null, "t", "b"), on, state))
    }

    @Test
    fun `a tab that has closed since, or a host with no state, is still named somehow`() {
        val gone = noteFor(activity(ActivityState.Done, TabPlace("studio", "s1", "closed")), on, state)!!
        assertEquals("Terminal · Done", gone.title)
        assertEquals("work · studio", gone.body)
        assertEquals("studio", noteFor(activity(ActivityState.Done), on, null)!!.body)
    }

    @Test
    fun `what a program says is cleaned of control characters, and cut when it goes on`() {
        val note = noteFor(TabNews.Message(agent, "a\u001b[31mb\u0007\tc\nd", "one\r\n\n  two\u0000 \u009b three  "), on, state)!!
        assertEquals("a [31mb c d", note.title)
        assertEquals("one\ntwo three\nagent - work · studio", note.body)

        val long = noteFor(TabNews.Message(agent, "t".repeat(500), "b".repeat(5_000)), on, state)!!
        assertEquals(120, long.title.length)
        assertTrue(long.title.endsWith("…"))
        assertEquals(600, long.body.lineSequence().first().length)
    }
}

class NotificationReplacingTest {
    @Test
    fun `a tab's news takes the place of its last, whatever the state`() {
        val tags = listOf(ActivityState.Blocked, ActivityState.Error, ActivityState.Done).map { noteFor(activity(it), on, state)!!.tag }
        assertEquals(1, tags.toSet().size)
    }

    @Test
    fun `a program's messages take each other's place, and leave what the program is doing alone`() {
        val first = noteFor(TabNews.Message(agent, "", "one"), on, state)!!.tag
        val second = noteFor(TabNews.Message(agent, "Title", "two"), on, state)!!.tag
        assertEquals(first, second)
        assertNotEquals(first, noteFor(activity(ActivityState.Done), on, state)!!.tag)
    }

    @Test
    fun `another tab, session or host has notifications of its own`() {
        val places = listOf(agent, agent.copy(windowId = "w1"), agent.copy(sessionId = "s2"), agent.copy(host = "laptop"))
        assertEquals(places.size, places.map { noteFor(activity(ActivityState.Done, it), on, state)!!.tag }.toSet().size)
    }

    @Test
    fun `places whose parts run together differently are told apart`() {
        assertNotEquals(tagsOf(TabPlace("a", "bc", "d")), tagsOf(TabPlace("ab", "c", "d")))
        assertNotEquals(tagsOf(TabPlace("a", "b", "cd")), tagsOf(TabPlace("a", "bc", "d")))
    }

    @Test
    fun `seeing a tab takes down both of its notifications`() {
        val both = setOf(noteFor(activity(ActivityState.Done), on, state)!!.tag, noteFor(TabNews.Message(agent, "", "hi"), on, state)!!.tag)
        assertEquals(both, tagsOf(agent).toSet())
    }
}

class NotifySendTest {
    private val program = Path.of("/usr/bin/notify-send")

    @Test
    fun `the command names the app, waits to hear of a click, and ends its options before the text`() {
        val command = notifyCommand(program, DesktopNote("tag", "agent · Done", "work · studio"), replaces = null)
        assertEquals(
            listOf(
                "/usr/bin/notify-send", "--app-name=Tono", "--icon=tono", "--hint=string:desktop-entry:tono", "--urgency=normal",
                "--print-id", "--action=default=Show", "--wait", "--", "agent · Done", "work · studio",
            ),
            command,
        )
    }

    @Test
    fun `one that takes another's place names it, and an urgent one says so`() {
        val command = notifyCommand(program, DesktopNote("tag", "agent · Needs you", "work · studio", urgent = true), replaces = 41)
        assertTrue("--replace-id=41" in command)
        assertTrue("--urgency=critical" in command)
        assertTrue(command.indexOf("--replace-id=41") < command.indexOf("--"))
    }

    @Test
    fun `text that looks like an option or like markup is shown as written`() {
        val command = notifyCommand(program, DesktopNote("tag", "--replace-id=1", "<b>bold</b> & <a href=\"x\">link</a>"), replaces = null)
        val text = command.drop(command.indexOf("--") + 1)
        assertEquals(listOf("--replace-id=1", "&lt;b&gt;bold&lt;/b&gt; &amp; &lt;a href=\"x\"&gt;link&lt;/a&gt;"), text)
    }

    @Test
    fun `a notification with nothing to say still has a heading, and no empty text`() {
        val command = notifyCommand(program, DesktopNote("tag", "", " "), replaces = null)
        assertEquals(listOf("--", "Tono"), command.takeLast(2))
    }

    @Test
    fun `closing uses GLib's tool, then systemd's, and gives up without either`() {
        fun programs(vararg names: String): (String) -> Path? = { name -> if (name in names) Path.of("/usr/bin/$name") else null }
        assertEquals(
            listOf("/usr/bin/gdbus", "call", "--session", "--dest", "org.freedesktop.Notifications", "--object-path", "/org/freedesktop/Notifications", "--method", "org.freedesktop.Notifications.CloseNotification", "7"),
            closeNotificationCommand(7, programs("gdbus", "busctl")),
        )
        assertEquals(
            listOf("/usr/bin/busctl", "--user", "call", "org.freedesktop.Notifications", "/org/freedesktop/Notifications", "org.freedesktop.Notifications", "CloseNotification", "u", "7"),
            closeNotificationCommand(7, programs("busctl")),
        )
        assertNull(closeNotificationCommand(7, programs()))
    }
}
