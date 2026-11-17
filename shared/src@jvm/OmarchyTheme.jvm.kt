package org.example.kotlintest

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
 * Suspends until Omarchy applies a theme.
 *
 * `omarchy theme set` stages `current/next-theme`, swaps it in as `current/theme` and
 * rewrites `current/theme.name`, so watching the `current` directory catches every switch
 * the moment it happens — no polling, and no hook installation required.
 */
actual suspend fun awaitOmarchyThemeChange() {
    val current = omarchyCurrentDir() ?: awaitCancellation()
    withContext(Dispatchers.IO) {
        FileSystems.getDefault().newWatchService().use { watcher ->
            current.toPath().register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
            while (true) {
                ensureActive()
                val key = watcher.poll(500, TimeUnit.MILLISECONDS) ?: continue
                key.pollEvents()
                key.reset()
                break
            }
        }
    }
    delay(150) // let Omarchy finish the directory swap before the palette is re-read
}
