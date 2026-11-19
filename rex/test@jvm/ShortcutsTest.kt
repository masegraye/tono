package org.example.kotlintest.rex

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShortcutsTest {
    private fun chord(keys: String) = KeyChord.parse(keys)!!
    private fun bound(keys: String, action: String, index: Int? = null) =
        KeyBinding(listOf(chord(keys)), action, index?.let { buildJsonObject { put("index", it) } })

    private val defaults = listOf(
        bound("ctrl+k", "session.switch"),
        bound("ctrl+shift+n", "session.new"),
        bound("ctrl+alt+n", "session.new"),
        bound("ctrl+shift+f", "client.find.open"),
        bound("ctrl+1", "client.tab.goto", 1),
        bound("ctrl+2", "client.tab.goto", 2),
    )
    private val shipped = resolveLocally(defaults, ShortcutOverrides())
    private val index1 = buildJsonObject { put("index", 1) }

    /** What Mase asked for: Change Session… on Control+Shift+K, and Control+K back with the shell. */
    private val moved = ShortcutOverrides().set("session.switch", null, chord("ctrl+shift+k"), shipped)

    @Test
    fun `setting a shortcut binds the new key and takes the old one off`() {
        assertEquals(listOf(ShortcutBinding(listOf("ctrl+shift+k"), "session.switch")), moved.bindings)
        assertEquals(listOf(ShortcutRemoval(listOf("ctrl+k"), "session.switch")), moved.removals)

        val keymap = resolveLocally(defaults, moved)
        assertEquals(emptyList(), keymap.bindingsFor(chord("ctrl+k")), "nothing is left on Control+K, so it goes to the terminal")
        assertEquals(listOf("session.switch"), keymap.bindingsFor(chord("ctrl+shift+k")).map { it.action })
        assertEquals("Ctrl+Shift+K", keymap.shortcutFor("session.switch")?.display())
        assertEquals(SHORTCUT_LAYER, keymap.bindingsOf("session.switch").single().source)
    }

    @Test
    fun `the layer is sent as the one layer of overrides, named for this app`() {
        val request = keymapRequest(listOf(RexAction("session.switch", "Change Session…", "Session")), defaults, moved)
        assertEquals(
            """[{"name":"tono","bindings":[{"keys":["ctrl+shift+k"],"action":"session.switch"}],"removals":[{"keys":["ctrl+k"],"action":"session.switch"}]}]""",
            request["overrides"]!!.jsonArray.toString(),
        )
        assertEquals("default", request["defaults"]!!.jsonArray.single().jsonObject.string("name"), "the shipped defaults stay as they are")
        assertEquals(6, request["defaults"]!!.jsonArray.single().jsonObject["bindings"]!!.jsonArray.size)
    }

    @Test
    fun `with nothing changed no layer is sent`() {
        assertNull(keymapRequest(emptyList(), defaults)["overrides"])
        assertNull(keymapRequest(emptyList(), defaults, moved.reset("session.switch", null, defaults))["overrides"])
    }

    @Test
    fun `a removal's arguments are remembered and not sent, and an action bound with arguments is sent with them`() {
        val changed = ShortcutOverrides().set("client.tab.goto", index1, chord("alt+1"), shipped)
        assertEquals(listOf(ShortcutRemoval(listOf("ctrl+1"), "client.tab.goto", index1)), changed.removals)
        assertEquals(
            """{"name":"tono","bindings":[{"keys":["alt+1"],"action":"client.tab.goto","args":{"index":1}}],"removals":[{"keys":["ctrl+1"],"action":"client.tab.goto"}]}""",
            changed.toLayer().toString(),
        )
        // Tab 2's key is another shortcut, and stays.
        assertEquals("Ctrl+2", resolveLocally(defaults, changed).shortcutFor("client.tab.goto", buildJsonObject { put("index", 2) })?.display())
        assertTrue(changed.changes("client.tab.goto", index1))
        assertFalse(changed.changes("client.tab.goto", buildJsonObject { put("index", 2) }))
    }

    @Test
    fun `removing a shortcut takes every key off the action`() {
        val removed = ShortcutOverrides().remove("session.new", null, shipped)
        assertEquals(emptyList(), removed.bindings)
        assertEquals(setOf(listOf("ctrl+shift+n"), listOf("ctrl+alt+n")), removed.removals.map { it.keys }.toSet())
        val keymap = resolveLocally(defaults, removed)
        assertNull(keymap.shortcutFor("session.new"))
        assertEquals(emptyList(), keymap.bindingsFor(chord("ctrl+shift+n")))
    }

    @Test
    fun `removing a shortcut set here leaves the action with none`() {
        val removed = moved.remove("session.switch", null, resolveLocally(defaults, moved))
        assertEquals(emptyList(), removed.bindings)
        assertEquals(moved.removals, removed.removals)
        assertNull(resolveLocally(defaults, removed).shortcutFor("session.switch"))
    }

    @Test
    fun `reset forgets the change, so the default is back`() {
        val reset = moved.reset("session.switch", null, defaults)
        assertTrue(reset.isEmpty)
        assertEquals("Ctrl+K", resolveLocally(defaults, reset).shortcutFor("session.switch")?.display())
        // Resetting one action leaves another's change alone.
        val both = moved.remove("session.new", null, shipped)
        assertEquals(both.removals.filter { it.action == "session.new" }, both.reset("session.switch", null, defaults).removals)
    }

    @Test
    fun `a key that is taken is reported, and replaced only by setting it`() {
        val taken = chord("ctrl+shift+f")
        val conflicts = ShortcutOverrides().conflicts("session.switch", null, taken, shipped)
        assertEquals(listOf("client.find.open"), conflicts.map { it.action })
        assertEquals(emptyList(), ShortcutOverrides().conflicts("client.find.open", null, taken, shipped), "an action's own key is no conflict")
        assertEquals(emptyList(), ShortcutOverrides().conflicts("session.switch", null, chord("ctrl+shift+k"), shipped))

        // Asking changes nothing. Setting takes the key from Find as well as moving Change Session….
        val replaced = ShortcutOverrides().set("session.switch", null, taken, shipped)
        val keymap = resolveLocally(defaults, replaced)
        assertEquals(listOf("session.switch"), keymap.bindingsFor(taken).map { it.action })
        assertNull(keymap.shortcutFor("client.find.open"))
        assertEquals(emptyList(), keymap.bindingsFor(chord("ctrl+k")))
        // Find lost its key to a change made here, so it can be reset, on its own.
        assertTrue(replaced.changes("client.find.open", null))
    }

    @Test
    fun `a key set here for one action and then for another moves`() {
        val keymap = resolveLocally(defaults, moved)
        assertEquals(listOf("session.switch"), moved.conflicts("client.find.open", null, chord("ctrl+shift+k"), keymap).map { it.action })
        val again = moved.set("client.find.open", null, chord("ctrl+shift+k"), keymap)
        assertEquals(listOf(ShortcutBinding(listOf("ctrl+shift+k"), "client.find.open")), again.bindings)
        val after = resolveLocally(defaults, again)
        assertEquals(listOf("client.find.open"), after.bindingsFor(chord("ctrl+shift+k")).map { it.action })
        assertNull(after.shortcutFor("session.switch"), "Control+K was taken off it before, and stays off")
    }

    @Test
    fun `setting one of an action's own default keys keeps that one and drops the other`() {
        val one = ShortcutOverrides().set("session.new", null, chord("ctrl+alt+n"), shipped)
        assertEquals(emptyList(), one.bindings, "it is bound beneath already")
        assertEquals(listOf(ShortcutRemoval(listOf("ctrl+shift+n"), "session.new")), one.removals)
        assertEquals(listOf("default"), resolveLocally(defaults, one).bindingsOf("session.new").map { it.source })
    }

    @Test
    fun `resetting takes back a default key given to something else here`() {
        // Find is given Control+K, which takes it from Change Session…; then Change Session… is reset.
        val given = ShortcutOverrides().set("client.find.open", null, chord("ctrl+k"), shipped)
        assertEquals(listOf(ShortcutBinding(listOf("ctrl+k"), "client.find.open")), given.resetConflicts("session.switch", null, defaults))
        val reset = given.reset("session.switch", null, defaults)
        val keymap = resolveLocally(defaults, reset)
        assertEquals(listOf("session.switch"), keymap.bindingsFor(chord("ctrl+k")).map { it.action })
        assertEquals(emptyList(), moved.resetConflicts("session.switch", null, defaults))
    }

    @Test
    fun `a key bound to a function in the configuration file is cleared outright, for as long as the binding that replaced it stands`() {
        val configured = Keymap(listOf(KeyBinding(listOf(chord("ctrl+shift+k")), FORWARDED_KEY, source = "init.lua:3")) + shipped.bindings)
        val set = ShortcutOverrides().set("session.switch", null, chord("ctrl+shift+k"), configured)
        assertTrue(ShortcutRemoval(listOf("ctrl+shift+k")) in set.removals)
        assertTrue(set.reset("session.switch", null, defaults).isEmpty)
    }

    @Test
    fun `the workspace keeps the layer between runs`() {
        val file = Files.createTempDirectory("workspace").resolve("workspace.json")
        val workspace = Workspace(WorkspaceFile(file))
        workspace.save()
        assertFalse("shortcuts" in Files.readString(file), "nothing is written until something is changed")
        assertTrue(Workspace.load(file).shortcuts.isEmpty)

        val changed = moved.set("client.tab.goto", index1, chord("alt+1"), shipped)
        workspace.shortcuts = changed
        workspace.save()
        assertEquals(changed, Workspace.load(file).shortcuts)
        assertEquals(
            """{"bindings":[{"keys":["ctrl+shift+k"],"action":"session.switch"},{"keys":["alt+1"],"action":"client.tab.goto","args":{"index":1}}],"removals":[{"keys":["ctrl+k"],"action":"session.switch"},{"keys":["ctrl+1"],"action":"client.tab.goto","args":{"index":1}}]}""",
            Json.parseToJsonElement(Files.readString(file)).jsonObject["shortcuts"].toString(),
        )
    }

    @Test
    fun `a layer written by hand is read as far as it can be`() {
        val parsed = ShortcutOverrides.parse(Json.parseToJsonElement(
            """{"bindings":[{"keys":["Shift+Ctrl+K"],"action":"session.switch"},{"keys":[],"action":"x"},{"action":"y"},{"keys":["ctrl+[KeyJ]"],"action":"z"},"nonsense"],"removals":[{"keys":["control+k"]},{"keys":[7]}]}""",
        ).jsonObject)
        assertEquals(listOf(ShortcutBinding(listOf("ctrl+shift+k"), "session.switch"), ShortcutBinding(listOf("ctrl+[KeyJ]"), "z")), parsed.bindings)
        assertEquals(listOf(ShortcutRemoval(listOf("ctrl+k"))), parsed.removals)
        assertTrue(ShortcutOverrides.parse(null).isEmpty)
    }

    // What the server answered for the request above, on 2026-11-19, cut down, with two things wrong added to the layer.
    private val answered = parseKeymap(Json.parseToJsonElement(
        """
        {"bindings":[
          {"keys":["ctrl+shift+k"],"action":"session.switch","source":"tono"},
          {"keys":["ctrl+[KeyL]"],"action":"client.find.open","source":"default"},
          {"keys":["ctrl+shift+n"],"action":"session.new","source":"init.lua:4"}],
         "diagnostics":[
          {"severity":"warning","source":"tono","message":"nothing is bound to \"ctrl+shift+z\"","key":"ctrl+shift+z"},
          {"severity":"warning","source":"init.lua:9","message":"unknown action \"nope\"","action":"nope"}],
         "config":{"path":"/home/me/.config/rex/init.lua","exists":true,"generation":1,"instance_id":"i","hash":"h"}}
        """,
    ).jsonObject)

    @Test
    fun `the server's answer leaves a removed key with nothing on it`() {
        assertEquals(emptyList(), answered.bindingsFor(chord("ctrl+k")))
        assertEquals("Ctrl+Shift+K", answered.shortcutFor("session.switch")?.display())
    }

    @Test
    fun `the server's warnings are read, with the layer each is about`() {
        assertEquals(
            listOf(KeymapDiagnostic("warning", "nothing is bound to \"ctrl+shift+z\"", "tono", key = "ctrl+shift+z")),
            answered.diagnostics.filter { it.source == SHORTCUT_LAYER },
        )
        assertEquals(2, answered.diagnostics.size)
    }

    @Test
    fun `a key the server named by position is removed by that spelling`() {
        val find = answered.bindingsOf("client.find.open").single()
        assertEquals(listOf(chord("ctrl+l")), find.keys)
        assertEquals(listOf("ctrl+[KeyL]"), find.wireKeys)
        assertEquals(listOf(ShortcutRemoval(listOf("ctrl+[KeyL]"), "client.find.open")), ShortcutOverrides().remove("client.find.open", null, answered).removals)
    }

    @Test
    fun `each shortcut is listed with its keys and where they come from`() {
        val targets = listOf(
            ShortcutTarget("session.switch", null, "Change Session…"),
            ShortcutTarget("session.new", null, "New Session"),
            ShortcutTarget("client.find.open", null, "Find…"),
            ShortcutTarget("pane.zoom", null, "Zoom Pane"),
        )
        val overrides = moved.remove("pane.zoom", null, Keymap(listOf(bound("ctrl+shift+enter", "pane.zoom").copy(source = "default"))))
        val listed = shortcutList(targets, answered, overrides)
        assertEquals(listOf("Ctrl+Shift+K", "Ctrl+Shift+N", "Ctrl+L", null), listed.map { it.keysShown() })
        assertEquals(listOf("Tono", "configuration file (init.lua:4)", "default", "removed in Tono"), listed.map { it.sourceShown() })
        assertEquals(listOf(true, false, false, true), listed.map { it.changed })
        assertEquals("no shortcut", shortcutList(targets.takeLast(1), answered, ShortcutOverrides()).single().sourceShown())
        // Two keys, both shown.
        assertEquals("Ctrl+Alt+N, Ctrl+Shift+N", shortcutList(targets, shipped, ShortcutOverrides())[1].keysShown())
    }

    @Test
    fun `a change made in the app applies at once, is saved, and is there after a restart`() = runBlocking {
        val directory = Files.createTempDirectory("workspace")
        val file = directory.resolve("workspace.json")
        // A host that isn't there, so no server is asked: the app goes by its defaults and its own layer.
        val hosts = listOf(SavedHost("Local", "unix://${directory.resolve("absent.sock")}"))
        suspend fun RexClient.waitFor(what: (Keymap) -> Boolean) = withTimeout(5_000) { keymap.first(what) }

        RexClient(this, Workspace(WorkspaceFile(file), hosts)).use { client ->
            client.declareKeymap(listOf(RexAction("session.switch", "Change Session…", "Session")), defaults)
            assertEquals("Ctrl+K", client.keymap.value.shortcutFor("session.switch")?.display())
            assertEquals(listOf("client.find.open"), client.shortcutConflicts("session.switch", null, chord("ctrl+shift+f")).map { it.action })

            client.setShortcut("session.switch", null, chord("ctrl+shift+k"))
            client.waitFor { it.bindingsFor(chord("ctrl+k")).isEmpty() }
            assertEquals("Ctrl+Shift+K", client.keymap.value.shortcutFor("session.switch")?.display())
            assertEquals(moved, client.shortcuts.value)
            assertTrue(client.log.entries.value.any { it.source == "keys" && "session.switch set to ctrl+shift+k" in it.message })
        }
        assertEquals(moved, Workspace.load(file).shortcuts)

        RexClient(this, Workspace.load(file)).use { client ->
            client.declareKeymap(listOf(RexAction("session.switch", "Change Session…", "Session")), defaults)
            assertEquals(emptyList(), client.keymap.value.bindingsFor(chord("ctrl+k")), "before any server has answered")
            assertEquals("Ctrl+Shift+K", client.keymap.value.shortcutFor("session.switch")?.display())
            assertEquals("Tono", client.shortcutList(listOf(ShortcutTarget("session.switch", null, "Change Session…"))).single().sourceShown())

            client.resetShortcut("session.switch", null)
            client.waitFor { it.shortcutFor("session.switch") == chord("ctrl+k") }
            assertTrue(client.shortcuts.value.isEmpty)
        }
        assertTrue(Workspace.load(file).shortcuts.isEmpty)
    }
}
