package org.example.kotlintest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.util.concurrent.TimeUnit

private fun home(): File? = System.getenv("HOME")?.let(::File)

/** `~/.local/state/omarchy/current` — where Omarchy keeps the theme it has applied. */
private fun omarchyCurrentDir(): File? =
    home()?.resolve(".local/state/omarchy/current")?.takeIf { it.isDirectory }

/** Theme sources, user themes (and overlays of stock themes) first. */
private fun omarchyThemeDirs(): List<File> =
    listOfNotNull(home()?.resolve(".config/omarchy/themes"), File("/usr/share/omarchy/themes"))

actual fun readOmarchyAppearance(): OmarchyAppearance? {
    val palette = readAppliedPalette() ?: return null
    return OmarchyAppearance(palette, readOmarchyFontName())
}

/**
 * `current/theme/colors.toml` is the palette Omarchy actually applied (the stock theme
 * with the user's overlay merged in), whatever theme that is.
 */
private fun readAppliedPalette(): OmarchyPalette? {
    val current = omarchyCurrentDir() ?: return null
    val slug = runCatching { File(current, "theme.name").readText().trim() }.getOrNull().orEmpty()
    if (slug.isEmpty()) return null
    val candidates = listOf(File(current, "theme/colors.toml")) +
        omarchyThemeDirs().map { File(it, "$slug/colors.toml") }
    return candidates.firstNotNullOfOrNull { file ->
        runCatching { omarchyPaletteFromToml(slug, file.readText()) }.getOrNull()
    }
}

/**
 * Omarchy uses one monospace font for its whole shell, and fontconfig's `monospace` alias
 * is its source of truth. This is the lookup `omarchy font current` does.
 */
private fun readOmarchyFontName(): String? = runCatching {
    val process = ProcessBuilder("fc-match", "monospace", "-f", "%{family}").start()
    val output = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroy()
    output.substringBefore(',').trim() // "JetBrainsMono Nerd Font,JetBrainsMono NF"
}.getOrNull()?.takeIf { it.isNotEmpty() }

actual fun readInstalledOmarchyPalettes(): List<OmarchyPalette> {
    val dirs = omarchyThemeDirs()
    val slugs = dirs.flatMap { dir -> dir.listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty() }
    return slugs.distinct().sorted().mapNotNull { slug ->
        // A user overlay may not carry its own colors.toml; fall through to the stock one.
        dirs.firstNotNullOfOrNull { dir ->
            runCatching { omarchyPaletteFromToml(slug, File(dir, "$slug/colors.toml").readText()) }.getOrNull()
        }
    }
}

/**
 * `omarchy theme set` stages `current/next-theme`, swaps it in as `current/theme` and
 * rewrites `current/theme.name`; `omarchy font set` rewrites `~/.config/fontconfig/fonts.conf`.
 * One watcher stays registered on both directories for the life of the flow, so no switch
 * is missed, however quickly they follow each other. No polling, no Omarchy hook needed.
 */
actual fun omarchyAppearanceFlow(): Flow<OmarchyAppearance> = flow {
    val current = omarchyCurrentDir() ?: awaitCancellation() // not an Omarchy desktop
    FileSystems.getDefault().newWatchService().use { watcher ->
        val fontconfig = home()?.resolve(".config/fontconfig")?.takeIf { it.isDirectory }
        for (dir in listOfNotNull(current, fontconfig)) {
            dir.toPath().register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
        }
        readOmarchyAppearance()?.let { emit(it) }
        while (true) {
            currentCoroutineContext().ensureActive()
            var key: WatchKey? = watcher.poll(500, TimeUnit.MILLISECONDS) ?: continue
            // A switch touches several files; wait until Omarchy has gone quiet.
            while (key != null) {
                key.pollEvents()
                key.reset()
                delay(150)
                key = watcher.poll()
            }
            readOmarchyAppearance()?.let { emit(it) }
        }
    }
}.flowOn(Dispatchers.IO).distinctUntilChanged()
