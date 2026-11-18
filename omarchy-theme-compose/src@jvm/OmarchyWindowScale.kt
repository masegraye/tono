package dev.mg.omarchy.theme.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import java.util.concurrent.TimeUnit

/**
 * Wrap a desktop window's content in this so it renders at the monitor's real scale.
 *
 * Under XWayland the JVM is told the display scale is 1, so on a HiDPI Hyprland monitor a
 * Compose window comes out tiny. This applies the compositor's scale instead, and does
 * nothing when the JVM already reports a scale or when no better answer is available.
 *
 * ```
 * Window(onCloseRequest = ::exitApplication) {
 *     OmarchyWindowScale { OmarchyTheme { MyApp() } }
 * }
 * ```
 */
@Composable
fun OmarchyWindowScale(content: @Composable () -> Unit) {
    val reported = LocalDensity.current
    val scale = omarchyUiScale
    val density = if (scale != null && reported.density == 1f) Density(scale, reported.fontScale) else reported
    CompositionLocalProvider(LocalDensity provides density, content = content)
}

/** Looked up once: the answer doesn't change while the app runs. */
private val omarchyUiScale: Float? by lazy(::detectOmarchyUiScale)

/**
 * The scale a Linux desktop app should render at, or null to trust what the JVM reports.
 * `OMARCHY_UI_SCALE` (for example `1.5`) overrides the detection.
 */
fun detectOmarchyUiScale(): Float? {
    if (!System.getProperty("os.name").orEmpty().contains("linux", ignoreCase = true)) return null
    val scale = System.getenv("OMARCHY_UI_SCALE")?.toFloatOrNull()
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
