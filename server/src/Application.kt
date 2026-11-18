package org.example.kotlintest.server

import dev.mg.omarchy.theme.ktor.omarchyThemeApi
import io.ktor.http.CacheControl
import io.ktor.http.content.CachingOptions
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.routing.routing
import java.io.File

/**
 * Configuration comes from the environment:
 * - `WEB_DIR`: directory holding the built web app (index.html, *.wasm, ...) to serve at `/`.
 * - `HOST` (default 127.0.0.1) and `PORT` (default 8080).
 * - `TONO_ALLOW_IDENTIFIED`: set to 1 to let in everyone on the tailnet that Tailscale can
 *   put a name to, where by default only the owner of this machine gets in.
 */
fun main() {
    val host = System.getenv("HOST") ?: "127.0.0.1"
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val webDir = System.getenv("WEB_DIR")?.let(::File)
    require(webDir == null || webDir.isDirectory) { "WEB_DIR is not a directory: $webDir" }

    embeddedServer(Netty, port = port, host = host) {
        // Only this machine and its owner's devices on the tailnet, whatever HOST says: see TailnetOnly.kt.
        tailnetOnly(allowIdentified = System.getenv("TONO_ALLOW_IDENTIFIED") in setOf("1", "true", "yes"))
        sentSmall()
        // /api/theme and /api/theme/events: tells the web app how the desktop is themed,
        // and pushes every change. From the omarchy-theme-ktor library.
        omarchyThemeApi()
        // For Tono in the browser: its connections to Rex servers, and what a page can't fetch itself.
        tonoRelay()
        tonoAssets()
        webApp(webDir)
    }.start(wait = true)
}

/**
 * Makes what the page downloads small. The app, the terminal library and the desktop's font
 * come to tens of megabytes as they are, which is a long wait from a phone:
 *
 * - they are compressed on the way out, to about a quarter;
 * - a browser that has a file already is told so, and doesn't download it again. Each file
 *   is sent with its date, and marked to be checked every time rather than trusted for a
 *   while (see [REVALIDATE]), so a rebuilt app is still picked up on the next load.
 */
fun Application.sentSmall() {
    install(Compression)
    install(ConditionalHeaders)
}

/** "Keep this, but ask whether it changed before using it again." */
internal val REVALIDATE = CacheControl.NoCache(null)

/** Serves the built web app at `/`, if there is one. */
fun Application.webApp(webDir: File?) {
    if (webDir == null) return
    routing {
        staticFiles("/", webDir) {
            cacheControl { listOf(REVALIDATE) }
        }
    }
}
