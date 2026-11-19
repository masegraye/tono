package org.example.kotlintest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mg.omarchy.theme.OmarchyPalette
import dev.mg.omarchy.theme.compose.OmarchyTheme
import dev.mg.omarchy.theme.terminalColors
import org.example.kotlintest.ghostty.TerminalColors
import org.example.kotlintest.rex.RexClient

/**
 * Tono: the whole app, for a window on the desktop or a page in a browser. [client] holds
 * its connections, which the caller starts and ends.
 */
@Composable
fun TonoApp(client: RexClient) = TonoApp(rememberTono(client))

/**
 * The app's own state, for a caller that shows it in more than one window: the main one
 * ([TonoApp]) and the settings ([TonoSettings]). Both act on the one client.
 */
@Stable
class Tono internal constructor(internal val ui: RexUiState, internal val commands: RexCommands) {
    /** Whether the settings have been asked for and not closed. A caller with a window for them shows it while this is true. */
    val settingsOpen: Boolean get() = ui.settingsOpen

    /** Goes up each time the settings are asked for, open or not: the moment to bring their window to the front. */
    val settingsRaises: Int get() = ui.settingsRaises

    fun closeSettings() = ui.closeSettings()

    /** A key pressed in the settings' window. True if it was the window's own: the key that closes it, or the one that asked for it. */
    fun settingsKey(event: KeyEvent): Boolean = settingsKey(ui, event)
}

/**
 * @param settingsInOwnWindow whether the caller shows the settings itself, in a window of
 *   their own, while [Tono.settingsOpen]. Otherwise they are a panel over the app.
 */
@Composable
fun rememberTono(client: RexClient, settingsInOwnWindow: Boolean = false): Tono = remember(client) {
    val ui = RexUiState(client).also { it.settingsWindowed = settingsInOwnWindow }
    // Tells Rex what this app can do and its default keys; the keymap comes back resolved.
    Tono(ui, RexCommands(ui).also { it.declare() })
}

@Composable
fun TonoApp(tono: Tono) {
    val ui = tono.ui
    if (ui.showcase) {
        Showcase(onBack = { ui.showcase = false })
    } else {
        // Terminals are drawn in the theme's colors too, and follow a change of theme.
        val palette = ui.appearance.palette
        LaunchedEffect(palette) { ui.client.setTerminalColors(palette.toTerminalColors()) }
        TonoTheme(ui) {
            RexApp(ui, tono.commands)
        }
    }
}

/** The settings, filling a window of their own, in the desktop's theme as the app is. */
@Composable
fun TonoSettings(tono: Tono) {
    // In the theme the person chose, as the main window is.
    TonoTheme(tono.ui) {
        SettingsContent(tono.ui, tono.commands, Modifier.fillMaxSize())
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
