package org.example.kotlintest

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ApplicationScope
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
import org.example.kotlintest.rex.defaultWorkspace
import org.example.kotlintest.rex.load
import org.jetbrains.skia.Image
import java.nio.file.Path

/**
 * Options:
 * - `--workspace <file>`: keep settings and state together in this one file, and read and
 *   write nothing in the usual places (on Linux `~/.config/tono/settings.json` and
 *   `~/.local/state/tono/state.json`). For tests, demos and a second profile.
 */
fun main(args: Array<String>) {
    // Some Java runtimes scale the window themselves from desktop settings, in whole steps,
    // and others don't scale at all. The theme library scales to the monitor's real factor
    // (1.6, say), so have the runtime stand aside, or the two disagree on the size of
    // everything. This has to be said before the first window class loads.
    if (System.getProperty("sun.java2d.uiScale") == null && detectOmarchyUiScale() != null) System.setProperty("sun.java2d.uiScale", "1")

    nameWindowClass("tono")

    val workspaceFile = args.indexOf("--workspace").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val workspace = workspaceFile?.let { Workspace.load(Path.of(it)) } ?: defaultWorkspace()
    // Said here as well as in the app's debug log: a file left alone because it couldn't be read, or moved from the old place.
    for (notice in workspace.notices) System.err.println("tono: ${notice.message}")

    // Connecting starts here, before the window exists: every configured server at once.
    val connections = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val client = RexClient(connections, workspace)

    application {
        val tono = rememberTono(client, settingsInOwnWindow = true)
        val icon = remember { windowIcon() }
        Window(
            // Closing the main window ends the app, and the settings window with it.
            onCloseRequest = {
                client.close()
                connections.cancel()
                exitApplication()
            },
            // Says where the window is, for a window list or a workspace overview.
            title = currentWindowTitle(client),
            icon = icon,
            state = rememberWindowState(size = DpSize(1280.dp, 820.dp)),
        ) {
            // Renders at the Hyprland monitor's scale, which the JVM doesn't see under XWayland.
            OmarchyWindowScale {
                TonoApp(tono)
            }
        }
        if (tono.settingsOpen) SettingsWindow(tono, icon)
    }
}

/**
 * The settings, in a window of their own: an ordinary one, with no fixed size and nothing
 * that marks it a dialog, so that a tiling window manager places it as it does any other,
 * which on Hyprland is beside the main window. It is the same program and the same
 * connections as the main window. Closing it leaves the app running.
 */
@Composable
private fun ApplicationScope.SettingsWindow(tono: Tono, icon: Painter?) {
    // For a window manager that lets windows float. The window is measured in the toolkit's
    // pixels and drawn at the monitor's scale, so the size is scaled here to come out the same.
    val scale = remember { detectOmarchyUiScale() ?: 1f }
    Window(
        onCloseRequest = tono::closeSettings,
        title = "$APP_NAME Settings",
        icon = icon,
        state = rememberWindowState(size = DpSize(SETTINGS_WIDTH * scale, SETTINGS_HEIGHT * scale)),
        // Control+W closes it, from wherever in it the keyboard is.
        onPreviewKeyEvent = tono::settingsKey,
    ) {
        // Asked for again while open: it comes to the front, as far as the desktop lets a window do that.
        LaunchedEffect(tono.settingsRaises) {
            window.toFront()
            window.requestFocus()
        }
        OmarchyWindowScale {
            TonoSettings(tono)
        }
    }
}

private val SETTINGS_WIDTH = 760.dp
private val SETTINGS_HEIGHT = 640.dp

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

