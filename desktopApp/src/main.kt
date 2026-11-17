package org.example.kotlintest

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.mg.omarchy.theme.compose.OmarchyWindowScale

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "kotlin-test") {
        // Renders at the Hyprland monitor's scale, which the JVM doesn't see under XWayland.
        OmarchyWindowScale {
            App()
        }
    }
}
