package org.example.kotlintest.rex

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Arranging the hosts, and changing one this app keeps itself. What the home server keeps is checked against a server. */
class HostChangesTest {
    private val directory = Files.createTempDirectory("workspace")
    private val file = directory.resolve("workspace.json")

    // Hosts that aren't there, so no server is asked. The first, on a socket, is the home server.
    private val hosts = listOf(
        SavedHost("Local", "unix://${directory.resolve("absent.sock")}"),
        SavedHost("attic", "https://attic.invalid"),
        SavedHost("studio", "https://studio.invalid"),
    )

    @Test
    fun `a changed host is sent back whole, with what this app doesn't know of it`() {
        val raw = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"id":"host:1","label":"studio","endpoints":["studio.ts.net"],"icon":"macstudio","host_id":"h","added_from":"laptop","source":"init.lua:4","updated_at":"2026-10-03T12:00:00Z","color":"teal"}""",
        ) as kotlinx.serialization.json.JsonObject
        val listing = HostListing("host:1", "studio", listOf("studio.ts.net"), "macstudio", "init.lua:4", "h", "laptop", raw)
        assertEquals(
            """{"id":"host:1","host_id":"h","added_from":"laptop","color":"teal","label":"den","endpoints":["den.ts.net","10.0.0.5:7297"],"icon":"cloud"}""",
            changedHostEntry(listing, "den", listOf("den.ts.net", "10.0.0.5:7297"), "cloud").toString(),
        )
        // No picture is the field left out, and the server sets where it was declared and when it changed.
        val plain = changedHostEntry(listing, "studio", listOf("studio.ts.net"), null)
        assertEquals(setOf("id", "host_id", "added_from", "color", "label", "endpoints"), plain.keys)
    }

    @Test
    fun `a host moves to stand before another, or to the end`() {
        val labels = listOf("a", "b", "c", "d")
        assertEquals(listOf("c", "a", "b", "d"), hostOrderAfterMove(labels, "c", before = "a"))
        assertEquals(listOf("b", "c", "a", "d"), hostOrderAfterMove(labels, "a", before = "d"))
        assertEquals(listOf("b", "c", "d", "a"), hostOrderAfterMove(labels, "a", before = null))
        assertEquals(labels, hostOrderAfterMove(labels, "b", before = "b"))
        assertEquals(labels, hostOrderAfterMove(labels, "z", before = "a"))
        assertEquals(listOf("a", "c", "d", "b"), hostOrderAfterMove(labels, "b", before = "gone"))
    }

    @Test
    fun `a host moves up or down one place, and stays put at either end`() {
        val labels = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), hostOrderAfterStep(labels, "b", up = true))
        assertEquals(listOf("a", "c", "b"), hostOrderAfterStep(labels, "b", up = false))
        assertEquals(labels, hostOrderAfterStep(labels, "a", up = true))
        assertEquals(labels, hostOrderAfterStep(labels, "c", up = false))
        assertEquals(labels, hostOrderAfterStep(labels, "z", up = true))
    }

    @Test
    fun `the order someone arranged is heard at once, kept, and read back`() = runBlocking {
        RexClient(this, Workspace(WorkspaceFile(file), hosts)).use { client ->
            assertEquals(emptyList(), client.hostOrder.value)
            client.setHostOrder(listOf("studio", "attic", "studio"))
            assertEquals(listOf("studio", "attic"), client.hostOrder.value)
        }
        assertEquals(listOf("studio", "attic"), Workspace.load(file).hostOrder)
        RexClient(this, Workspace.load(file)).use { client -> assertEquals(listOf("studio", "attic"), client.hostOrder.value) }
    }

    @Test
    fun `each host says where it is kept`() = runBlocking {
        RexClient(this, Workspace(WorkspaceFile(file), hosts)).use { client ->
            assertEquals(HostSource.Home, client.sourceOf("Local"))
            assertEquals(HostSource.ThisApp, client.sourceOf("attic"))
            assertNull(client.sourceOf("nowhere"))
        }
    }

    @Test
    fun `a host this app keeps is renamed and readdressed in the workspace, and keeps its place`() = runBlocking {
        RexClient(this, Workspace(WorkspaceFile(file), hosts)).use { client ->
            client.setHostOrder(listOf("studio", "attic"))
            client.selectHost("attic")

            assertNull(client.changeHost("attic", "loft", listOf(" https://loft.invalid "), icon = null))
            assertEquals(listOf("Local", "loft", "studio"), client.hosts.value.map { it.saved.label })
            assertEquals("https://loft.invalid", client.hosts.value.first { it.saved.label == "loft" }.saved.endpoint)
            assertEquals(listOf("studio", "loft"), client.hostOrder.value, "its place in the order goes with it")
            assertEquals("loft", client.selectedHost.value, "and it goes on showing")
        }
        val kept = Workspace.load(file)
        assertEquals(listOf(SavedHost("Local", hosts[0].endpoint), SavedHost("loft", "https://loft.invalid"), SavedHost("studio", "https://studio.invalid")), kept.hosts)
        assertEquals("loft", kept.lastHost)
    }

    @Test
    fun `a change that can't be made says why, and changes nothing`() = runBlocking {
        RexClient(this, Workspace(WorkspaceFile(file), hosts)).use { client ->
            assertNotNull(client.changeHost("Local", "Home", listOf(hosts[0].endpoint), null), "the home server stays as it is")
            assertEquals("A host needs a name.", client.changeHost("attic", "  ", listOf("https://attic.invalid"), null))
            assertEquals("A host needs an address.", client.changeHost("attic", "attic", listOf(" ", ""), null))
            assertEquals("There is a host called STUDIO already.", client.changeHost("attic", "STUDIO", listOf("https://attic.invalid"), null))
            assertTrue(client.changeHost("gone", "gone", listOf("https://x.invalid"), null)!!.contains("isn't a host"))
            assertEquals(hosts, client.hosts.value.map { it.saved })

            // Adding says why too.
            assertEquals("A host needs a name.", client.addHostNow(" ", "https://new.invalid"))
            assertEquals("There is a host called Attic already.", client.addHostNow("Attic", "https://new.invalid"))
            assertEquals("That address is a host already.", client.addHostNow("other", "https://studio.invalid"))
            // With no home server to keep it, a new host is this app's own.
            assertNull(client.addHostNow("new", "https://new.invalid", icon = "cloud"))
            assertEquals(HostSource.ThisApp, client.sourceOf("new"))
        }
    }
}
