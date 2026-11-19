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
        val scene = ImageComposeScene(width, height, Density(1f)) {
            MaterialTheme(colorScheme = scheme, shapes = omarchyShapes()) { androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background, content = content) }
        }
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
            )) {
                ui.settings.replace(place)
                settings(width, height = 900).use { it.draw("$name-$width") }
                assertTrue(File(outputDir, "$name-$width.png").length() > 0, "$name-$width")
            }
            // The forms, over the place they are opened from: a dialog where there is room, a sheet where there isn't.
            for ((name, form) in listOf("hosts-edit" to SettingsForm.EditHost("studio"), "hosts-add" to SettingsForm.AddHost, "client-label" to SettingsForm.ClientLabel)) {
                ui.settings.replace(if (form == SettingsForm.ClientLabel) SettingsPlace(SettingsSection.Server) else SettingsPlace(SettingsSection.RemoteHosts, hostDetail("studio")))
                ui.settings.form = form
                settings(width, height = 900).use { it.draw("$name-$width") }
                ui.settings.form = null
                assertTrue(File(outputDir, "$name-$width.png").length() > 0, "$name-$width")
            }
        }
    }

    /** Presses a key, as the toolkit reports it. */
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    private fun Picture.press(code: Int, char: Char = java.awt.event.KeyEvent.CHAR_UNDEFINED) {
        val source = object : java.awt.Component() {}
        fun send(type: Int, keyCode: Int) = scene.sendKeyEvent(
            androidx.compose.ui.input.key.KeyEvent(
                key = androidx.compose.ui.input.key.Key(keyCode),
                type = when (type) {
                    java.awt.event.KeyEvent.KEY_PRESSED -> androidx.compose.ui.input.key.KeyEventType.KeyDown
                    java.awt.event.KeyEvent.KEY_RELEASED -> androidx.compose.ui.input.key.KeyEventType.KeyUp
                    else -> androidx.compose.ui.input.key.KeyEventType.Unknown
                },
                codePoint = if (char == java.awt.event.KeyEvent.CHAR_UNDEFINED) 0 else char.code,
                nativeEvent = java.awt.event.KeyEvent(source, type, 0L, 0, keyCode, char, if (type == java.awt.event.KeyEvent.KEY_TYPED) java.awt.event.KeyEvent.KEY_LOCATION_UNKNOWN else java.awt.event.KeyEvent.KEY_LOCATION_STANDARD),
            ),
        )
        send(java.awt.event.KeyEvent.KEY_PRESSED, code)
        if (char != java.awt.event.KeyEvent.CHAR_UNDEFINED) send(java.awt.event.KeyEvent.KEY_TYPED, java.awt.event.KeyEvent.VK_UNDEFINED)
        send(java.awt.event.KeyEvent.KEY_RELEASED, code)
        draw()
    }

    @Test
    fun `a host's form is saved by Enter, and Escape asks before throwing away what was typed`() {
        // As a sheet. As a dialog it is the same form with the same keys, but an offscreen picture
        // doesn't hand a dialog the keyboard: that one was pressed through in a browser.
        for (width in listOf(420)) {
            ui.settings.replace(SettingsPlace(SettingsSection.RemoteHosts, hostDetail("attic")))
            ui.settings.form = SettingsForm.EditHost("attic")
            settings(width, height = 900).use { settings ->
                // Nothing changed yet: Escape just closes it.
                settings.press(java.awt.event.KeyEvent.VK_ESCAPE)
                assertEquals(null, ui.settings.form, "$width: unchanged, it closes without asking")

                // The label has the keyboard, selected, so typing replaces it.
                ui.settings.form = SettingsForm.EditHost("attic")
                settings.draw()
                for (char in "loft") settings.press(java.awt.event.KeyEvent.getExtendedKeyCodeForChar(char.code), char)
                settings.draw("hosts-edit-$width-typed")
                settings.press(java.awt.event.KeyEvent.VK_ESCAPE)
                assertEquals(SettingsForm.EditHost("attic"), ui.settings.form, "$width: it asks first")
                settings.draw("hosts-edit-$width-asking")
                // Escape again goes on editing; Enter then saves what was typed.
                settings.press(java.awt.event.KeyEvent.VK_ESCAPE)
                assertEquals(SettingsForm.EditHost("attic"), ui.settings.form)
                settings.press(java.awt.event.KeyEvent.VK_ENTER, '\n')
                val saved = System.nanoTime()
                while (ui.settings.form != null && System.nanoTime() - saved < 5_000_000_000) { Thread.sleep(20); settings.draw() }
                assertEquals(null, ui.settings.form, "$width: saved, it closes")
                assertTrue(client.hosts.value.any { it.saved.label == "loft" }, "$width: the host has its new label")
                assertEquals(SettingsPlace(SettingsSection.RemoteHosts, hostDetail("loft")), ui.settings.place, "$width: and its details stay with it")

                // Changed and then thrown away: Escape, then Enter for Discard.
                ui.settings.form = SettingsForm.EditHost("loft")
                settings.draw()
                for (char in "attic") settings.press(java.awt.event.KeyEvent.getExtendedKeyCodeForChar(char.code), char)
                settings.press(java.awt.event.KeyEvent.VK_ESCAPE)
                settings.press(java.awt.event.KeyEvent.VK_ENTER, '\n')
                assertEquals(null, ui.settings.form)
                assertTrue(client.hosts.value.any { it.saved.label == "loft" }, "$width: nothing was saved")
            }
            // Put back for the next width.
            kotlinx.coroutines.runBlocking { client.changeHost("loft", "attic", listOf("https://attic.invalid"), null) }
        }
    }

    @Test
    fun `what a server says about itself, and what is offered when there is none on this machine`() {
        val info = org.example.kotlintest.rex.ServerInfo(
            version = "0.0.1794270332-g4ba7cc", platform = "linux_amd64", apiRevision = 12, pid = 1670719, startedAt = "2026-11-10T02:09:42.429Z", account = "mase", sessionCount = 2,
            listeners = listOf(
                org.example.kotlintest.rex.ServerListener("unix:///run/user/1000/rex/server.sock", "unix", primary = true),
                org.example.kotlintest.rex.ServerListener("login-server:///run/user/1000/rex/login-server.sock", "unix", primary = false),
            ),
            tailscale = org.example.kotlintest.rex.ServerTailscale("running", "work-rex.tail1234.ts.net", "https://work-rex.tail1234.ts.net", "me@example.com", "example.com"),
            configGeneration = 3,
        )
        for (width in listOf(420, 700)) {
            Picture(width, 760) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(14.dp)) {
                    SettingsGroup("Rex Server") { ServerInfoRows(info, configPath = "/home/mase/.config/rex/init.lua") }
                }
            }.use { it.draw("server-info-$width") }
        }
        // The home server here is a socket nobody listens on. Called this machine's usual one, it is a missing server.
        ui.usualLocalEndpoint = client.hosts.value.first().saved.endpoint
        var ran: LocalServerCommand? = null
        ui.runRexCommand = { command -> ran = command; "Error: the unit rex.socket could not be enabled\nrun it again as yourself" }
        ui.settings.replace(SettingsPlace(SettingsSection.Server))
        for ((name, setup) in listOf(
            "no-rex" to LocalServerSetup(rexPath = null),
            "not-enabled" to LocalServerSetup("/usr/bin/rex", socketEnabled = false, socketActive = false),
            "enabled" to LocalServerSetup("/usr/bin/rex", socketEnabled = true, socketActive = true),
        )) {
            ui.lookAtLocalServer = { setup }
            for (width in listOf(420, 700, 1100)) settings(width, height = 900).use { settings ->
                // The first attempt on the socket has to have failed before anything is offered.
                Thread.sleep(300)
                settings.draw("server-$name-$width")
                assertTrue(File(outputDir, "server-$name-$width.png").length() > 0)
            }
        }
        // Nothing has been run by looking.
        assertEquals(null, ran)
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
