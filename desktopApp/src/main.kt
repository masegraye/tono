package org.example.kotlintest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.mg.omarchy.theme.OmarchyPalette
import dev.mg.omarchy.theme.compose.OmarchyTheme
import dev.mg.omarchy.theme.compose.OmarchyWindowScale
import dev.mg.omarchy.theme.compose.rememberOmarchyThemeState
import dev.mg.omarchy.theme.terminalColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.example.kotlintest.ghostty.TerminalColors
import org.example.kotlintest.rex.RexClient
import org.example.kotlintest.rex.Workspace
import java.nio.file.Path

/**
 * Options:
 * - `--workspace <file>`: keep saved hosts and the last selection in this file instead of
 *   `~/.config/kotlin-test/workspace.json`.
 */
fun main(args: Array<String>) {
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
            title = "Rex",
            state = rememberWindowState(size = DpSize(1280.dp, 820.dp)),
        ) {
            // Renders at the Hyprland monitor's scale, which the JVM doesn't see under XWayland.
            OmarchyWindowScale {
                val ui = remember { RexUiState(client) }
                // Tells Rex what this app can do and its default keys; the keymap comes back resolved.
                val commands = remember { RexCommands(ui).also { it.declare() } }
                if (ui.showcase) {
                    Showcase(onBack = { ui.showcase = false })
                } else {
                    val theme = rememberOmarchyThemeState()
                    // Terminals are drawn in the desktop's colors too, and follow a theme change.
                    LaunchedEffect(theme.palette) { client.setTerminalColors(theme.palette.toTerminalColors()) }
                    OmarchyTheme(theme) {
                        RexApp(ui, commands)
                    }
                }
            }
        }
    }
}

/** The original UI showcase, kept reachable from the Rex client's menu. */
@androidx.compose.runtime.Composable
private fun Showcase(onBack: () -> Unit) {
    Box {
        App()
        OmarchyTheme {
            FilledTonalButton(onClick = onBack, modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Text("Back to Rex")
            }
        }
    }
}

private fun OmarchyPalette.toTerminalColors(): TerminalColors {
    val colors = terminalColors()
    fun rgb(hex: String) = hex.removePrefix("#").toInt(16)
    return TerminalColors(rgb(colors.foreground), rgb(colors.background), rgb(colors.cursor), colors.ansi.map(::rgb))
}
