package org.example.kotlintest.rex

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A storage that counts what is written to it. */
internal class CountingStorage(var text: String? = null, override val location: String? = null) : WorkspaceStorage {
    var writes = 0
    override fun read(): String? = text
    override fun write(text: String) {
        writes++
        this.text = text
    }
}

internal fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
internal fun json(file: Path): JsonObject = json(Files.readString(file))

/** The workspace kept as two files, settings and state, and what it promises about each: backlog-049. */
class WorkspaceFilesTest {
    private val directory = Files.createTempDirectory("workspace-files")
    private val settingsFile = directory.resolve("config/settings.json")
    private val stateFile = directory.resolve("state/state.json")
    private val hosts = listOf(SavedHost("Local", "unix://${directory.resolve("absent.sock")}"), SavedHost("studio", "https://studio.invalid"))

    private fun everything(settings: WorkspaceStorage?, state: WorkspaceStorage? = settings) = Workspace(
        settings, hosts, lastHost = "studio", sessions = mapOf("Local" to "session:1"), collapsedHosts = setOf("studio"),
        collapsedSessions = setOf("session:2"), tabs = mapOf("session:1" to "window:3"), sounds = false,
        notifications = NotificationSwitches(done = false), selection = SelectionSwitches(copyOnSelection = true),
        mutedTabs = listOf(MutedTab("unix:///run/rex.sock", "session:1", "window:4")),
        appearance = AppearanceChoice(theme = "nord", fontSize = 15), hostOrder = listOf("studio", "Local"), clientLabel = "desk",
        stateStorage = state,
    )

    @Test
    fun `settings go in one file and state in the other, and both are read back`() {
        everything(WorkspaceFile(settingsFile), WorkspaceFile(stateFile)).save()

        assertEquals(setOf("hosts", "sounds", "notifications", "terminal", "appearance", "client_label", "host_order"), json(settingsFile).keys)
        assertEquals(setOf("last_host", "sessions", "tabs", "collapsed_hosts", "collapsed_sessions", "muted_tabs"), json(stateFile).keys)
        // Every key is one half's or the other's, and font size is a setting (adr-001).
        assertTrue(Workspace.SETTINGS_KEYS.intersect(Workspace.STATE_KEYS).isEmpty())
        assertEquals("15", json(settingsFile).obj("appearance")?.get("font_size").toString())

        val loaded = Workspace.load(WorkspaceFile(settingsFile), WorkspaceFile(stateFile))
        assertEquals(hosts, loaded.hosts)
        assertEquals("studio", loaded.lastHost)
        assertEquals(mapOf("Local" to "session:1"), loaded.sessions)
        assertEquals(mapOf("session:1" to "window:3"), loaded.tabs)
        assertEquals(setOf("studio"), loaded.collapsedHosts)
        assertEquals(setOf("session:2"), loaded.collapsedSessions)
        assertEquals(listOf(MutedTab("unix:///run/rex.sock", "session:1", "window:4")), loaded.mutedTabs)
        assertFalse(loaded.sounds)
        assertEquals(NotificationSwitches(done = false), loaded.notifications)
        assertEquals(SelectionSwitches(copyOnSelection = true), loaded.selection)
        assertEquals(AppearanceChoice(theme = "nord", fontSize = 15), loaded.appearance)
        assertEquals(listOf("studio", "Local"), loaded.hostOrder)
        assertEquals("desk", loaded.clientLabel)
        assertEquals(settingsFile.toString(), loaded.location)
        assertEquals(stateFile.toString(), loaded.stateLocation)
    }

    @Test
    fun `one storage keeps everything together, in the order it always had`() {
        val storage = CountingStorage()
        everything(storage).save()
        assertEquals(
            listOf("hosts", "last_host", "sessions", "sounds", "notifications", "terminal", "tabs", "collapsed_hosts", "collapsed_sessions", "muted_tabs", "appearance", "client_label", "host_order"),
            json(storage.text!!).keys.toList(),
        )
        val loaded = Workspace.load(storage)
        assertEquals("studio", loaded.lastHost)
        assertEquals("desk", loaded.clientLabel)
        assertEquals(loaded.location, loaded.stateLocation)
    }

    @Test
    fun `each file is written only when what it holds changed`() {
        val settings = CountingStorage()
        val state = CountingStorage()
        val workspace = everything(settings, state)
        workspace.save()
        assertEquals(1 to 1, settings.writes to state.writes)
        workspace.save()
        assertEquals(1 to 1, settings.writes to state.writes, "nothing changed")

        // Where the person is: state alone.
        workspace.tabs["session:1"] = "window:9"
        workspace.save()
        workspace.lastHost = "Local"
        workspace.save()
        workspace.collapsedSessions = emptySet()
        workspace.save()
        assertEquals(1 to 4, settings.writes to state.writes)

        // What they chose: settings alone.
        workspace.sounds = true
        workspace.save()
        assertEquals(2 to 4, settings.writes to state.writes)

        // And what was read is as good as written: starting again and looking around rewrites no settings.
        val again = Workspace.load(settings, state)
        again.save()
        again.tabs["session:1"] = "window:1"
        again.save()
        assertEquals(2 to 5, settings.writes to state.writes)
    }

    @Test
    fun `a file kept by hand is not rewritten until a setting changes`() {
        val byHand = """{"hosts":[{"label":"Local","endpoint":"unix:///run/rex.sock"}],
            "sounds": false}"""
        val settings = CountingStorage(byHand)
        val state = CountingStorage()
        val workspace = Workspace.load(settings, state)
        workspace.lastHost = "Local"
        workspace.save()
        assertEquals(byHand, settings.text)
        assertEquals(0 to 1, settings.writes to state.writes)
    }

    @Test
    fun `switching host in the app writes the state and leaves the settings file alone`() = runBlocking {
        val settings = CountingStorage()
        val state = CountingStorage()
        RexClient(this, Workspace(settings, hosts, stateStorage = state)).use { client ->
            client.setSounds(false)
            val written = settings.writes
            val before = state.writes
            client.selectHost("studio")
            client.selectHost("Local")
            assertEquals(written, settings.writes)
            assertEquals(before + 2, state.writes)
            assertEquals("Local", json(state.text!!).string("last_host"))
        }
    }

    @Test
    fun `a settings file that can't be read is kept, said, and never saved over`() = runBlocking {
        val broken = "{ \"hosts\": [ {\"label\": \"studio\", \"endpoint\": \"https://studio.invalid\"} ],, }"
        Files.createDirectories(settingsFile.parent)
        Files.writeString(settingsFile, broken)

        val workspace = Workspace.load(WorkspaceFile(settingsFile), WorkspaceFile(stateFile))
        assertTrue(workspace.hosts.isEmpty(), "it runs on defaults")
        assertTrue(workspace.settingsProblem!!.contains(settingsFile.toString()))
        assertNull(workspace.stateProblem)

        // The client's first act with no hosts is to add the local one and save.
        RexClient(this, workspace).use { client ->
            assertEquals(listOf("Local"), client.hosts.value.map { it.saved.label })
            assertTrue(client.log.entries.value.any { it.isError && settingsFile.toString() in it.message }, "the debug log says so")
            client.setSounds(false)
            client.setAppearance(AppearanceChoice(theme = "nord"))
            client.selectHost("Local")
        }
        assertEquals(broken, Files.readString(settingsFile))
        assertEquals(listOf("settings.json"), Files.list(settingsFile.parent).use { files -> files.map { it.fileName.toString() }.toList() })
        // The state could be read (there was none), so where the person was is still kept.
        assertEquals("Local", json(stateFile).string("last_host"))
    }

    @Test
    fun `a single file that can't be read is kept too`() = runBlocking {
        val file = directory.resolve("workspace.json")
        for (broken in listOf("{ not json", "[1, 2]", "\"text\"")) {
            Files.writeString(file, broken)
            val workspace = Workspace.load(file)
            RexClient(this, workspace).use { client -> client.setSounds(false) }
            workspace.save()
            assertEquals(broken, Files.readString(file))
            assertEquals(1, workspace.notices.size)
        }
        // A file that is there and can't be opened is not a missing file.
        Files.delete(file)
        Files.createDirectory(file)
        val workspace = Workspace.load(file)
        assertTrue(workspace.settingsProblem != null)
        workspace.sounds = false
        workspace.save()
        assertTrue(Files.isDirectory(file))
    }

    @Test
    fun `a state file that can't be read is kept, while settings are still saved`() {
        val settings = CountingStorage("""{"hosts":[{"label":"Local","endpoint":"unix:///run/rex.sock"}]}""")
        val state = CountingStorage("{{{", location = "state.json")
        val workspace = Workspace.load(settings, state)
        assertNull(workspace.settingsProblem)
        assertTrue(workspace.stateProblem!!.startsWith("state.json"))
        workspace.lastHost = "Local"
        workspace.sounds = false
        workspace.save()
        assertEquals("{{{", state.text)
        assertEquals(0 to 1, state.writes to settings.writes)
    }

    @Test
    fun `an empty file is nothing to lose, and is written`() {
        val settings = CountingStorage("  \n")
        val workspace = Workspace.load(settings)
        assertTrue(workspace.notices.isEmpty())
        workspace.save()
        assertEquals(1, settings.writes)
    }

    @Test
    fun `keys this app doesn't know survive a save, in each file`() {
        val settings = CountingStorage(
            """{"hosts":[{"label":"Local","endpoint":"unix:///run/rex.sock","icon":"desktop"}],
               "notifications":{"done":false,"quiet_hours":"22-7"},
               "terminal":{"copy_on_selection":true,"scrollback":9000},
               "appearance":{"theme":"nord","cursor":"bar"},
               "sync":{"with":"icloud"},"last_host":"not read from here"}""",
        )
        val state = CountingStorage("""{"last_host":"Local","window":{"width":1400},"hosts":"not read from here"}""")
        val workspace = Workspace.load(settings, state)
        assertEquals("Local", workspace.lastHost)
        assertEquals(1, workspace.hosts.size)

        workspace.sounds = false
        workspace.appearance = AppearanceChoice()
        workspace.tabs["session:1"] = "window:2"
        workspace.save()

        val kept = json(settings.text!!)
        assertEquals("""{"with":"icloud"}""", kept["sync"].toString())
        assertEquals("\"not read from here\"", kept["last_host"].toString())
        assertEquals("\"desktop\"", kept.jsonArrayOf("hosts")[0].jsonObject["icon"].toString())
        assertEquals("\"22-7\"", kept.obj("notifications")!!["quiet_hours"].toString())
        assertEquals("false", kept.obj("notifications")!!["done"].toString())
        assertEquals("9000", kept.obj("terminal")!!["scrollback"].toString())
        // The theme went back to the default, and what this app doesn't know of the look stayed.
        assertEquals("""{"cursor":"bar"}""", kept["appearance"].toString())
        assertEquals("false", kept["sounds"].toString())

        val state2 = json(state.text!!)
        assertEquals("""{"width":1400}""", state2["window"].toString())
        assertEquals("\"not read from here\"", state2["hosts"].toString())
        assertEquals("""{"session:1":"window:2"}""", state2["tabs"].toString())

        // And again after reading what was written.
        val again = Workspace.load(settings, state)
        again.sounds = true
        again.lastHost = null
        again.save()
        assertEquals("""{"with":"icloud"}""", json(settings.text!!)["sync"].toString())
        assertEquals("""{"width":1400}""", json(state.text!!)["window"].toString())
    }

    @Test
    fun `keys this app doesn't know survive a save in a single file`() {
        val file = directory.resolve("workspace.json")
        Files.writeString(file, """{"hosts":[{"label":"Local","endpoint":"unix:///run/rex.sock"}],"last_host":"Local","sync":{"with":"icloud"},"window":{"width":1400}}""")
        val workspace = Workspace.load(file)
        workspace.sounds = false
        workspace.save()
        val kept = json(file)
        assertEquals("""{"with":"icloud"}""", kept["sync"].toString())
        assertEquals("""{"width":1400}""", kept["window"].toString())
        assertEquals("\"Local\"", kept["last_host"].toString())
    }

    private fun JsonObject.jsonArrayOf(key: String) = this[key] as kotlinx.serialization.json.JsonArray
}
