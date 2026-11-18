package org.example.kotlintest.rex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyChordTest {
    @Test
    fun `reads keys as rex spells them`() {
        assertEquals(KeyChord("t", ctrl = true, shift = true), KeyChord.parse("ctrl+shift+t"))
        assertEquals(KeyChord("pagedown", ctrl = true), KeyChord.parse("ctrl+pagedown"))
        assertEquals(KeyChord("`", ctrl = true), KeyChord.parse("ctrl+`"))
        assertEquals(KeyChord("k", cmd = true), KeyChord.parse("cmd+k"))
        assertEquals(KeyChord("a"), KeyChord.parse("a"))
    }

    @Test
    fun `spelling variations mean the same key`() {
        val expected = KeyChord("tab", ctrl = true, shift = true)
        assertEquals(expected, KeyChord.parse("shift+ctrl+tab"))
        assertEquals(expected, KeyChord.parse("Ctrl+Shift+Tab"))
        assertEquals(expected, KeyChord.parse("control+shift+tab"))
        assertEquals(KeyChord("k", cmd = true), KeyChord.parse("super+k"))
        assertEquals(KeyChord("left", alt = true), KeyChord.parse("opt+left"))
    }

    @Test
    fun `the plus key can be bound`() {
        assertEquals(KeyChord("+", ctrl = true), KeyChord.parse("ctrl++"))
        assertEquals(KeyChord("+"), KeyChord.parse("+"))
    }

    @Test
    fun `a key named by position is read as that key`() {
        assertEquals(KeyChord("k", cmd = true), KeyChord.parse("cmd+[KeyK]"))
        assertEquals(KeyChord("1", ctrl = true), KeyChord.parse("ctrl+[Digit1]"))
    }

    @Test
    fun `a spelling this client does not know is not guessed at`() {
        assertNull(KeyChord.parse("hyper+x"))
        assertNull(KeyChord.parse(""))
    }

    @Test
    fun `written back the way the server spells it`() {
        for (spelling in listOf("ctrl+shift+t", "ctrl+alt+left", "ctrl+shift+enter", "ctrl+1", "shift+pageup", "cmd+k", "ctrl++")) {
            assertEquals(spelling, KeyChord.parse(spelling)!!.toWire())
        }
    }

    @Test
    fun `shown to people with names they know`() {
        assertEquals("Ctrl+Shift+T", KeyChord.parse("ctrl+shift+t")!!.display())
        assertEquals("Ctrl+PageDown", KeyChord.parse("ctrl+pagedown")!!.display())
        assertEquals("Ctrl+Alt+←", KeyChord.parse("ctrl+alt+left")!!.display())
        assertEquals("Ctrl+`", KeyChord.parse("ctrl+`")!!.display())
        assertEquals("Ctrl+Shift+Enter", KeyChord.parse("ctrl+shift+enter")!!.display())
    }
}

class KeymapTest {
    // The shape client.keymap answers with: resolved bindings top-first, then the server's actions.
    private val resolved = parseKeymap(
        Json.parseToJsonElement(
            """
            {"bindings": [
               {"keys": ["ctrl+shift+t"], "action": "dev_layout", "args": null, "source": "init.lua:12", "mode": null},
               {"keys": ["ctrl+shift+t"], "action": "client.tab.new", "args": null, "source": "default", "mode": null},
               {"keys": ["ctrl+2"], "action": "client.tab.goto", "args": {"index": 2}, "source": "default", "mode": null},
               {"keys": ["ctrl+b", "c"], "action": "client.tab.new", "args": null, "source": "init.lua:3", "mode": null},
               {"keys": ["h"], "action": "pane.resize", "args": null, "source": "init.lua:9", "mode": "resize"},
               {"keys": ["hyper+x"], "action": "client.tab.new", "args": null, "source": "init.lua:4", "mode": null}],
             "actions": [
               {"name": "dev_layout", "kind": "lua", "title": "Dev Layout", "category": "Layouts"},
               {"name": "com.superlogical.terminal.clear", "kind": "block", "title": "Clear Terminal", "category": "Pane"},
               {"name": "com.superlogical.terminal.write", "kind": "block"}],
             "diagnostics": []}
            """,
        ).jsonObject,
    )

    @Test
    fun `bindings keep the order to try them in`() {
        val onKey = resolved.bindingsFor(KeyChord.parse("ctrl+shift+t")!!)
        // The configuration file's binding sits above this app's default on the same key.
        assertEquals(listOf("dev_layout", "client.tab.new"), onKey.map { it.action })
        assertEquals(listOf("init.lua:12", "default"), onKey.map { it.source })
    }

    @Test
    fun `bindings with arguments carry them`() {
        val binding = resolved.bindingsFor(KeyChord.parse("ctrl+2")!!).single()
        assertEquals("client.tab.goto", binding.action)
        assertEquals(2, binding.args!!["index"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `sequences, modes and unreadable keys are set aside`() {
        // A sequence is kept, but isn't offered for a single key press.
        assertEquals(1, resolved.bindings.count { it.keys.size == 2 })
        assertEquals(emptyList(), resolved.bindingsFor(KeyChord.parse("ctrl+b")!!))
        assertEquals(emptyList(), resolved.bindingsFor(KeyChord("h")))
        assertEquals(4, resolved.bindings.size)
    }

    @Test
    fun `only the server actions meant for people are listed`() {
        assertEquals(listOf("Dev Layout", "Clear Terminal"), resolved.serverActions.map { it.title })
        assertEquals(listOf("lua", "block"), resolved.serverActions.map { it.kind })
    }

    @Test
    fun `an action's shortcut is its topmost single key`() {
        assertEquals("Ctrl+Shift+T", resolved.shortcutFor("client.tab.new")?.display())
        assertEquals("Ctrl+2", resolved.shortcutFor("client.tab.goto", buildJsonObject { put("index", 2) })?.display())
        assertNull(resolved.shortcutFor("client.tab.goto", buildJsonObject { put("index", 3) }))
        assertNull(resolved.shortcutFor("pane.zoom"))
    }

    @Test
    fun `the request declares actions and a default layer`() {
        val request = keymapRequest(
            actions = listOf(RexAction("client.tab.new", "New Tab", "Tab", keywords = "create window")),
            defaults = listOf(
                KeyBinding(listOf(KeyChord.parse("ctrl+shift+t")!!), "client.tab.new"),
                KeyBinding(listOf(KeyChord.parse("ctrl+1")!!), "client.tab.goto", buildJsonObject { put("index", 1) }),
            ),
        )
        assertEquals(
            """{"name":"client.tab.new","kind":"client","title":"New Tab","category":"Tab","keywords":"create window"}""",
            request["actions"]!!.jsonArray.single().toString(),
        )
        val layer = request["defaults"]!!.jsonArray.single().jsonObject
        assertEquals("default", layer["name"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("""{"keys":["ctrl+shift+t"],"action":"client.tab.new"}""", """{"keys":["ctrl+1"],"action":"client.tab.goto","args":{"index":1}}"""),
            layer["bindings"]!!.jsonArray.map { it.toString() },
        )
    }
}

class RexLogTest {
    @Test
    fun `keeps the most recent entries`() {
        val log = RexLog(capacity = 3)
        for (number in 1..5) log.add("app", "entry $number")
        assertEquals(listOf("entry 3", "entry 4", "entry 5"), log.entries.value.map { it.message })
        log.clear()
        assertEquals(emptyList(), log.entries.value)
    }
}
