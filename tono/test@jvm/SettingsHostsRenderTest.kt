package org.example.kotlintest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.mg.omarchy.theme.bundledOmarchyPalettes
import dev.mg.omarchy.theme.compose.omarchyColorScheme
import dev.mg.omarchy.theme.compose.omarchyShapes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.example.kotlintest.rex.HostListing
import org.example.kotlintest.rex.HostReach
import org.example.kotlintest.rex.HostSource
import org.example.kotlintest.rex.RexClient
import org.example.kotlintest.rex.SavedHost
import org.example.kotlintest.rex.TailnetServer
import org.example.kotlintest.rex.Workspace
import org.example.kotlintest.rex.WorkspaceFile
import org.example.kotlintest.rex.load
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Remote Hosts as the desktop draws it, offscreen. The pictures are left in
 * `<project>/build/settings-renders/` to be looked at: a host's line in each state of
 * reaching it, what each source says of itself, and the section, a host's details and the
 * form that adds one at the widths a tiling window manager gives a window.
 */
class SettingsHostsRenderTest {
    private val outputDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "project.yaml").isFile }
        .resolve("build/settings-renders")
        .apply { mkdirs() }

    private val scheme = omarchyColorScheme(bundledOmarchyPalettes.first { it.slug == "retro-82" })
    private val directory = Files.createTempDirectory("workspace")
    private val file = directory.resolve("workspace.json")
    private val connections = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Hosts that aren't there: an absent socket for the home server, and names that can't be looked up.
    private val client = RexClient(
        connections,
        Workspace(WorkspaceFile(file), listOf(SavedHost("Local", "unix://${directory.resolve("absent.sock")}"), SavedHost("attic", "https://attic.invalid"), SavedHost("studio", "https://studio-rex.example.invalid:7297"))),
    )
    private val ui = RexUiState(client).also {
        // Nothing here asks the real tailnet what is on it.
        it.findTailnetServers = { listOf(TailnetServer("garage-rex", "garage-rex.tail1234.ts.net", online = true), TailnetServer("shed-rex", "shed-rex.tail1234.ts.net", online = false)) }
    }
    private val commands = RexCommands(ui).also { it.declare() }

    @AfterTest
    fun close() {
        client.close()
        connections.cancel()
    }

    private inner class Picture(val width: Int, val height: Int, content: @Composable () -> Unit) : AutoCloseable {
        val scene = ImageComposeScene(width, height, Density(1f)) { MaterialTheme(colorScheme = scheme, shapes = omarchyShapes(), content = content) }
        private var millis = 0L

        fun draw(name: String? = null) {
            repeat(20) {
                millis += 16
                scene.render(millis * 1_000_000)
                Snapshot.sendApplyNotifications()
            }
            val image = scene.render(millis * 1_000_000)
            if (name != null) File(outputDir, "$name.png").apply { delete() }.writeBytes(image.encodeToData()!!.bytes)
        }

        fun click(at: Offset) {
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at)
            scene.sendPointerEvent(PointerEventType.Release, at)
            draw()
        }

        override fun close() = scene.close()
    }

    private fun settings(width: Int, height: Int = 760) = Picture(width, height) { SettingsContent(ui, commands, Modifier.fillMaxSize()) }.also { it.draw() }

    @Test
    fun `a host's line in each state of reaching it, and what each source says of itself`() {
        val listed = HostListing("host:1", "studio", listOf("studio-rex.tail1234.ts.net"), "macstudio", source = null, addedFrom = "laptop")
        for ((width, wide) in listOf(420 to false, 700 to true)) {
            Picture(width, 760) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(14.dp)) {
                    SettingsGroup("Each state") {
                        HostRow("Local", "unix:///run/user/1000/rex/server.sock", HostIcon.Desktop, HostReach.Connected, handle = null, details = wide, onDetails = {})
                        RowDivider()
                        HostRow("studio", "studio-rex.tail1234.ts.net", HostIcon.Studio, HostReach.Connecting, handle = if (wide) Modifier else null, details = wide, onDetails = {})
                        RowDivider()
                        HostRow("omranchy", "omranchy-rex.drake-magellanic.ts.net", HostIcon.Server, HostReach.Reconnecting, handle = if (wide) Modifier else null, details = wide, onDetails = {})
                        RowDivider()
                        HostRow("a-laptop-with-a-long-name", "a-laptop-with-a-long-name-rex.drake-magellanic.ts.net", HostIcon.Laptop, HostReach.Down, handle = if (wide) Modifier else null, details = wide, onDetails = {})
                    }
                    for (source in HostSource.entries) {
                        SettingsGroup(source.name) {
                            InfoLine(hostSourceWords(source, "Local", if (source == HostSource.ConfigurationFile) listed.copy(source = "init.lua:4") else listed))
                        }
                    }
                }
            }.use { it.draw("hosts-states-and-sources-$width") }
            assertTrue(File(outputDir, "hosts-states-and-sources-$width.png").length() > 0)
        }
    }

    @Test
    fun `the section, a host's details and the form that adds one, at a third, a half and the whole of a screen`() {
        for (width in listOf(420, 700, 1100)) {
            for ((name, place) in listOf(
                "hosts" to SettingsPlace(SettingsSection.RemoteHosts),
                "hosts-details" to SettingsPlace(SettingsSection.RemoteHosts, hostDetail("studio")),
                "hosts-home" to SettingsPlace(SettingsSection.RemoteHosts, hostDetail("Local")),
                "hosts-add" to SettingsPlace(SettingsSection.RemoteHosts, ADD_HOST_DETAIL),
            )) {
                ui.settings.replace(place)
                settings(width, height = 900).use { it.draw("$name-$width") }
                assertTrue(File(outputDir, "$name-$width.png").length() > 0, "$name-$width")
            }
        }
    }

    @Test
    fun `a host's line leads to its details, and Move Down there moves it in the list and the sidebar`() {
        ui.settings.replace(SettingsPlace(SettingsSection.RemoteHosts))
        settings(420, height = 900).use { settings ->
            assertEquals(listOf("Local", "attic", "studio"), ui.orderedHosts().map { it.saved.label })
            // The second line of the card: attic. The whole line leads to its details where there is one column.
            settings.click(Offset(200f, 6f + 52f + 8f + 56f + 28f))
            assertEquals(SettingsPlace(SettingsSection.RemoteHosts, hostDetail("attic")), ui.settings.place)
            settings.draw("hosts-details-420-attic")

            // What the buttons under Place in the List do.
            client.setHostOrder(org.example.kotlintest.rex.hostOrderAfterStep(listOf("attic", "studio"), "attic", up = false))
            settings.draw("hosts-details-420-attic-moved")
            assertEquals(listOf("Local", "studio", "attic"), ui.orderedHosts().map { it.saved.label }, "the sidebar's order follows")
            assertEquals(listOf("studio", "attic"), Workspace.load(file).hostOrder)

            // Back is one step out: to the list of hosts.
            settings.click(Offset(30f, 36f))
            assertEquals(SettingsPlace(SettingsSection.RemoteHosts), ui.settings.place)
            settings.draw("hosts-420-reordered")
        }
    }
}
