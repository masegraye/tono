package org.example.kotlintest.rex

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppearanceChoiceTest {
    private val directory = Files.createTempDirectory("workspace")
    private val file = directory.resolve("workspace.json")
    private val hosts = listOf(SavedHost("Local", "unix://${directory.resolve("absent.sock")}"))

    private fun written() = Json.parseToJsonElement(Files.readString(file)).jsonObject

    @Test
    fun `nothing chosen, nothing is written, and a file without it follows the desktop`() {
        Workspace(WorkspaceFile(file), hosts).save()
        assertFalse("appearance" in written())
        assertEquals(AppearanceChoice(), Workspace.load(file).appearance)
        assertTrue(Workspace.load(file).appearance.isDefault)
    }

    @Test
    fun `a theme and a font survive being written and read`() {
        val chosen = AppearanceChoice(theme = "catppuccin-latte", fontFamily = "Liberation Mono", fontSize = 15, fontWeight = 300)
        Workspace(WorkspaceFile(file), hosts, appearance = chosen).save()
        assertEquals(
            """{"theme":"catppuccin-latte","font_family":"Liberation Mono","font_size":15,"font_weight":300}""",
            written().getValue("appearance").toString(),
        )
        assertEquals(chosen, Workspace.load(file).appearance)
    }

    @Test
    fun `only what was chosen is written`() {
        Workspace(WorkspaceFile(file), hosts, appearance = AppearanceChoice(fontSize = 16)).save()
        assertEquals("""{"font_size":16}""", written().getValue("appearance").toString())
        assertEquals(AppearanceChoice(fontSize = 16), Workspace.load(file).appearance)
    }

    @Test
    fun `what isn't of its kind is left to the app`() {
        Files.writeString(file, """{"hosts":[],"appearance":{"theme":7,"font_family":"  ","font_size":"big","font_weight":4000}}""")
        assertEquals(AppearanceChoice(), Workspace.load(file).appearance)
        Files.writeString(file, """{"hosts":[],"appearance":"dark"}""")
        assertEquals(AppearanceChoice(), Workspace.load(file).appearance)
        Files.writeString(file, """{"hosts":[],"appearance":{"theme":"nord","font_size":2}}""")
        assertEquals(AppearanceChoice(theme = "nord"), Workspace.load(file).appearance)
    }

    @Test
    fun `the client says what was chosen, applies a change at once and remembers it`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = RexClient(scope, Workspace(WorkspaceFile(file), hosts, appearance = AppearanceChoice(theme = "nord")))
        try {
            assertEquals(AppearanceChoice(theme = "nord"), client.appearance.value)
            val chosen = AppearanceChoice(theme = "gruvbox", fontWeight = 500)
            client.setAppearance(chosen)
            assertEquals(chosen, client.appearance.value)
            assertEquals(chosen, Workspace.load(file).appearance)
            // Back to nothing chosen: the file says nothing again.
            client.setAppearance(AppearanceChoice())
            assertFalse("appearance" in written())
        } finally {
            client.close()
            scope.cancel()
        }
    }
}
