package org.example.kotlintest.rex

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun window(id: String) = WindowView(id, id, focusedBlockId = null, zoomedBlockId = null, panes = emptyList())

private fun view(id: String, vararg tabs: String) = id to SessionView(id, id, revision = 1, activeWindowId = tabs.firstOrNull(), windows = tabs.map(::window))

class TabMoveTest {
    private val order = listOf("a", "b", "c", "d")

    @Test
    fun `a tab moves backward to before the one ahead of it`() {
        assertEquals(TabMove("a"), tabMove(order, "b", forward = false))
        assertEquals(TabMove("c"), tabMove(order, "d", forward = false))
    }

    @Test
    fun `a tab moves forward to before the one after its neighbour, or to the end`() {
        assertEquals(TabMove("c"), tabMove(order, "a", forward = true))
        assertEquals(TabMove("d"), tabMove(order, "b", forward = true))
        assertEquals(TabMove(null), tabMove(order, "c", forward = true))
    }

    @Test
    fun `there is nowhere to go at the ends, alone, or from outside the session`() {
        assertNull(tabMove(order, "a", forward = false))
        assertNull(tabMove(order, "d", forward = true))
        assertNull(tabMove(listOf("a"), "a", forward = true))
        assertNull(tabMove(listOf("a"), "a", forward = false))
        assertNull(tabMove(order, "x", forward = true))
        assertNull(tabMove(emptyList(), "a", forward = false))
    }

    @Test
    fun `each move, applied as the server applies it, shifts the tab one place`() {
        fun apply(order: List<String>, id: String, move: TabMove): List<String> {
            val rest = order - id
            val at = move.beforeWindowId?.let(rest::indexOf) ?: rest.size
            return rest.take(at) + id + rest.drop(at)
        }
        for (id in order) for (forward in listOf(true, false)) {
            val move = tabMove(order, id, forward) ?: continue
            val moved = apply(order, id, move)
            assertEquals(order.indexOf(id) + if (forward) 1 else -1, moved.indexOf(id), "$id ${if (forward) "forward" else "backward"}: $moved")
            assertEquals(order - id, moved - id, "the others keep their order")
        }
    }

    @Test
    fun `a direction is read in Rex's words, Rex Rune's, and a sidebar's`() {
        for (word in listOf("forward", "right", "down", "Forward")) assertEquals(true, tabMoveIsForward(word), word)
        for (word in listOf("backward", "left", "up", "LEFT")) assertEquals(false, tabMoveIsForward(word), word)
        assertNull(tabMoveIsForward("sideways"))
        assertNull(tabMoveIsForward(null))
    }
}

class CloseOtherTabsTest {
    @Test
    fun `every tab named closes, and the one kept does not`() {
        assertEquals(listOf("a", "c"), tabsToClose(open = listOf("a", "b", "c"), keep = "b", named = listOf("a", "c")))
        assertEquals(listOf("a"), tabsToClose(open = listOf("a", "b"), keep = "b", named = listOf("a", "b", "a")))
    }

    @Test
    fun `a tab opened since the question was asked stays, and one closed since is skipped`() {
        assertEquals(listOf("c"), tabsToClose(open = listOf("b", "c", "new"), keep = "b", named = listOf("a", "c")))
    }

    @Test
    fun `nothing closes when the tab that was to stay has gone`() {
        assertEquals(emptyList(), tabsToClose(open = listOf("a", "c"), keep = "b", named = listOf("a", "c")))
        assertEquals(emptyList(), tabsToClose(open = emptyList(), keep = "b", named = listOf("a")))
    }
}

class MutedTabsTest {
    private val studio = "https://studio.ts.net"
    private val build = MutedTab(studio, "work", "w1")
    private val agent = MutedTab(studio, "work", "w2")

    @Test
    fun `a tab is muted on its own server only`() {
        val muted = emptyList<MutedTab>().with(build, muted = true)
        assertTrue(muted.mutes(studio, "w1"))
        assertFalse(muted.mutes(studio, "w2"))
        assertFalse(muted.mutes("unix:///run/rex.sock", "w1"), "another server may have a tab with the same id")
    }

    @Test
    fun `muting twice keeps one entry, and unmuting takes it away`() {
        val muted = listOf(build, agent).with(build, muted = true)
        assertEquals(listOf(agent, build), muted)
        assertEquals(listOf(agent), muted.with(build, muted = false))
        assertEquals(listOf(agent), listOf(agent).with(build, muted = false))
    }

    @Test
    fun `the list is kept to a length, losing the oldest`() {
        var muted = emptyList<MutedTab>()
        for (index in 0..MUTED_TABS_LIMIT) muted = muted.with(MutedTab(studio, "s", "w$index"), muted = true)
        assertEquals(MUTED_TABS_LIMIT, muted.size)
        assertFalse(muted.mutes(studio, "w0"))
        assertTrue(muted.mutes(studio, "w$MUTED_TABS_LIMIT"))
    }

    @Test
    fun `a muted tab is forgotten when it closes or its session ends`() {
        val other = MutedTab("https://other.ts.net", "work", "w1")
        val muted = listOf(build, agent, other)
        val sessions = listOf(SessionSummary("work", "work"))
        val connected = HostState(connection = HostConnection.Connected, sessions = sessions, views = mapOf(view("work", "w1", "w2")))
        assertEquals(muted, muted.stillOpen(studio, connected))
        // The tab closed: its session's layout no longer lists it.
        assertEquals(listOf(build, other), muted.stillOpen(studio, connected.copy(views = mapOf(view("work", "w1")))))
        // The session ended.
        assertEquals(listOf(other), muted.stillOpen(studio, connected.copy(sessions = emptyList(), views = emptyMap())))
    }

    @Test
    fun `nothing is forgotten on the word of a server that isn't connected, or hasn't said yet`() {
        val muted = listOf(build, agent)
        assertEquals(muted, muted.stillOpen(studio, HostState()), "still connecting: no sessions listed yet")
        assertEquals(muted, muted.stillOpen(studio, HostState(connection = HostConnection.Connected, sessions = listOf(SessionSummary("work", "work")))), "connected, the layout not read yet")
    }

    @Test
    fun `the workspace keeps muted tabs between runs, in Rex Rune's shape`() {
        val file = Files.createTempDirectory("workspace").resolve("workspace.json")
        val workspace = Workspace(WorkspaceFile(file))
        workspace.save()
        assertFalse("muted_tabs" in Files.readString(file), "nothing is written while nothing is muted")
        workspace.mutedTabs = listOf(build, agent)
        workspace.save()
        val written = kotlinx.serialization.json.Json.parseToJsonElement(Files.readString(file))
        assertEquals(
            kotlinx.serialization.json.Json.parseToJsonElement("""[{"endpoint":"$studio","session":"work","window":"w1"},{"endpoint":"$studio","session":"work","window":"w2"}]"""),
            (written as kotlinx.serialization.json.JsonObject)["muted_tabs"],
        )
        assertEquals(listOf(build, agent), Workspace.load(file).mutedTabs)
        file.parent.toFile().deleteRecursively()
    }
}
