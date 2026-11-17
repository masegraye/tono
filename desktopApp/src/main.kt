package org.example.kotlintest

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.util.concurrent.TimeUnit

fun main() {
    val uiScale = detectLinuxUiScale()
    application {
        Window(onCloseRequest = ::exitApplication, title = "kotlin-test") {
            // Under XWayland the JVM is told the display scale is 1, so on a HiDPI Hyprland
            // monitor the UI would render tiny. Apply the compositor's real scale instead.
            val reported = LocalDensity.current
            val density = if (uiScale != null && reported.density == 1f) {
                Density(uiScale, reported.fontScale)
            } else {
                reported
            }
            CompositionLocalProvider(LocalDensity provides density) {
                App()
            }
        }
    }
}

/** The scale to render at on Linux, or null to trust what the JVM reports. */
private fun detectLinuxUiScale(): Float? {
    if (!System.getProperty("os.name").orEmpty().contains("linux", ignoreCase = true)) return null
    val scale = System.getenv("KMP_UI_SCALE")?.toFloatOrNull()   // manual override
        ?: hyprlandMonitorScale()
        ?: System.getenv("GDK_SCALE")?.toFloatOrNull()
    return scale?.takeIf { it in 0.5f..4f }
}

/** Scale of the focused Hyprland monitor (e.g. 1.6), read from `hyprctl monitors -j`. */
private fun hyprlandMonitorScale(): Float? = runCatching {
    if (System.getenv("HYPRLAND_INSTANCE_SIGNATURE") == null) return null
    val process = ProcessBuilder("hyprctl", "monitors", "-j").redirectErrorStream(true).start()
    val json = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroy()
    val scales = Regex(""""scale":\s*([0-9.]+)""").findAll(json).map { it.groupValues[1].toFloat() }.toList()
    val focused = Regex(""""focused":\s*(true|false)""").findAll(json).map { it.groupValues[1] == "true" }.toList()
    scales.getOrNull(focused.indexOf(true)) ?: scales.firstOrNull()
}.getOrNull()
