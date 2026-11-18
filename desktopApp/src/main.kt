package org.example.kotlintest

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.mg.omarchy.theme.compose.OmarchyWindowScale
import dev.mg.omarchy.theme.compose.detectOmarchyUiScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.example.kotlintest.rex.RexClient
import org.example.kotlintest.rex.Workspace
import org.example.kotlintest.rex.load
import org.jetbrains.skia.Image
import java.nio.file.Path

/**
 * Options:
 * - `--workspace <file>`: keep saved hosts and the last selection in this file instead of
 *   `~/.config/kotlin-test/workspace.json`.
 */
fun main(args: Array<String>) {
    // Some Java runtimes scale the window themselves from desktop settings, in whole steps,
    // and others don't scale at all. The theme library scales to the monitor's real factor
    // (1.6, say), so have the runtime stand aside, or the two disagree on the size of
    // everything. This has to be said before the first window class loads.
    if (System.getProperty("sun.java2d.uiScale") == null && detectOmarchyUiScale() != null) System.setProperty("sun.java2d.uiScale", "1")

    nameWindowClass("tono")

    val workspaceFile = args.indexOf("--workspace").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val workspace = workspaceFile?.let { Workspace.load(Path.of(it)) } ?: Workspace.load()

    // Connecting starts here, before the window exists: every configured server at once.
    val connections = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val client = RexClient(connections, workspace)

    application {
        Window(
            onCloseRequest = {
                client.close()
                connections.cancel()
                exitApplication()
            },
            title = "Tono",
            icon = remember { windowIcon() },
            state = rememberWindowState(size = DpSize(1280.dp, 820.dp)),
        ) {
            // Renders at the Hyprland monitor's scale, which the JVM doesn't see under XWayland.
            OmarchyWindowScale {
                TonoApp(client)
            }
        }
    }
}

/**
 * The app's icon, for window lists and title bars that show one. A launcher takes its icon
 * from the installed entry instead; this is for the window itself. Null if the picture isn't
 * in the jar, which leaves the toolkit's own.
 */
private fun windowIcon(): Painter? = runCatching {
    val bytes = object {}.javaClass.getResourceAsStream("/icon.png")?.use { it.readBytes() } ?: return null
    BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
}.getOrNull()

/**
 * Gives the app's windows a class of their own. A window manager knows a window by its class
 * (it is what a Hyprland window rule matches, and how a launcher ties a window to its entry),
 * and Java names it after whichever class started the program, which for a packaged jar is
 * the jar's loader. X11 only, and only when the launch options open the toolkit to it
 * (`--add-opens java.desktop/sun.awt.X11=ALL-UNNAMED`); otherwise the name stays as it was.
 */
private fun nameWindowClass(name: String) {
    runCatching {
        val toolkit = java.awt.Toolkit.getDefaultToolkit()
        val field = toolkit.javaClass.getDeclaredField("awtAppClassName")
        field.isAccessible = true
        field.set(toolkit, name)
    }
}

