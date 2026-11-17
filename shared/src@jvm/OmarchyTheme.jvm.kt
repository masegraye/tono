package org.example.kotlintest

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.util.concurrent.TimeUnit

/** `~/.local/state/omarchy/current` — where Omarchy keeps the applied theme. */
private fun omarchyCurrentDir(): File? =
    System.getenv("HOME")?.let { File(it, ".local/state/omarchy/current") }?.takeIf { it.isDirectory }

private fun omarchyThemeDirs(): List<File> = listOfNotNull(
    System.getenv("HOME")?.let { File(it, ".config/omarchy/themes") }, // user themes and overlays
    File("/usr/share/omarchy/themes"),                                  // stock themes
)

/**
 * Reads the active Omarchy theme straight from disk.
 *
 * `current/theme/colors.toml` is the palette Omarchy actually applied (stock theme with
 * the user's overlays merged in), so it is preferred over the theme source directories.
 */
actual fun readOmarchyPalette(): OmarchyPalette? {
    val current = omarchyCurrentDir() ?: return null
    val slug = File(current, "theme.name").takeIf { it.isFile }?.readText()?.trim().orEmpty()
    if (slug.isEmpty()) return null
    val candidates = listOf(File(current, "theme/colors.toml")) +
        omarchyThemeDirs().map { File(it, "$slug/colors.toml") }
    return candidates.firstOrNull { it.isFile }
        ?.let { runCatching { omarchyPaletteFromToml(slug, it.readText()) }.getOrNull() }
}

actual fun listOmarchyThemeSlugs(): List<String> =
    omarchyThemeDirs()
        .flatMap { dir -> dir.takeIf { it.isDirectory }?.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList() }
        .distinct()
        .sorted()

/**
 * Resolves the Omarchy font the way `omarchy font current` does: ask fontconfig what
 * `monospace` maps to, then load that installed family by name.
 */
@OptIn(ExperimentalTextApi::class) // SystemFont: loads an installed font by family name
actual fun readOmarchyFont(): OmarchyFont? {
    if (omarchyCurrentDir() == null) return null
    val name = runCatching {
        val process = ProcessBuilder("fc-match", "monospace", "-f", "%{family}").start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroy()
        output.substringBefore(',').trim() // "JetBrainsMono Nerd Font,JetBrainsMono NF"
    }.getOrNull().orEmpty()
    if (name.isEmpty()) return null
    val family = FontFamily(
        SystemFont(name, FontWeight.Normal, FontStyle.Normal),
        SystemFont(name, FontWeight.Normal, FontStyle.Italic),
        SystemFont(name, FontWeight.Bold, FontStyle.Normal),
        SystemFont(name, FontWeight.Bold, FontStyle.Italic),
    )
    return OmarchyFont(name, family)
}

/**
 * Suspends until Omarchy applies a theme or a font.
 *
 * `omarchy theme set` stages `current/next-theme`, swaps it in as `current/theme` and
 * rewrites `current/theme.name`; `omarchy font set` rewrites `~/.config/fontconfig/fonts.conf`.
 * Watching those two directories catches every switch the moment it happens — no polling,
 * and no hook installation required.
 */
actual suspend fun awaitOmarchyAppearanceChange() {
    val current = omarchyCurrentDir() ?: awaitCancellation()
    withContext(Dispatchers.IO) {
        FileSystems.getDefault().newWatchService().use { watcher ->
            val fontconfig = System.getenv("HOME")?.let { File(it, ".config/fontconfig") }
            for (dir in listOfNotNull(current, fontconfig?.takeIf { it.isDirectory })) {
                dir.toPath().register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
            }
            while (true) {
                ensureActive()
                val key = watcher.poll(500, TimeUnit.MILLISECONDS) ?: continue
                key.pollEvents()
                key.reset()
                break
            }
        }
    }
    delay(150) // let Omarchy finish writing before the palette and font are re-read
}
