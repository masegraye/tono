package org.example.kotlintest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mg.omarchy.theme.OmarchyPalette
import dev.mg.omarchy.theme.compose.OmarchyTheme
import dev.mg.omarchy.theme.compose.rememberOmarchyThemeState
import dev.mg.omarchy.theme.terminalColors
import org.example.kotlintest.ghostty.TerminalColors
import org.example.kotlintest.rex.RexClient

/**
 * Tono: the whole app, for a window on the desktop or a page in a browser. [client] holds
 * its connections, which the caller starts and ends.
 */
@Composable
fun TonoApp(client: RexClient) {
    val ui = remember(client) { RexUiState(client) }
    // Tells Rex what this app can do and its default keys; the keymap comes back resolved.
    val commands = remember(ui) { RexCommands(ui).also { it.declare() } }
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

/** The original UI showcase, kept reachable from the app's menu. */
@Composable
private fun Showcase(onBack: () -> Unit) {
    Box {
        App()
        OmarchyTheme {
            FilledTonalButton(onClick = onBack, modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Text("Back to Tono")
            }
        }
    }
}

private fun OmarchyPalette.toTerminalColors(): TerminalColors {
    val colors = terminalColors()
    fun rgb(hex: String) = hex.removePrefix("#").toInt(16)
    return TerminalColors(rgb(colors.foreground), rgb(colors.background), rgb(colors.cursor), colors.ansi.map(::rgb))
}
