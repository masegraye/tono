package org.example.kotlintest.server

import dev.mg.omarchy.theme.ktor.omarchyThemeApi
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import java.io.File

/**
 * Configuration comes from the environment:
 * - `WEB_DIR`: directory holding the built web app (index.html, *.wasm, ...) to serve at `/`.
 * - `HOST` (default 127.0.0.1) and `PORT` (default 8080).
 */
fun main() {
    val host = System.getenv("HOST") ?: "127.0.0.1"
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val webDir = System.getenv("WEB_DIR")?.let(::File)
    require(webDir == null || webDir.isDirectory) { "WEB_DIR is not a directory: $webDir" }

    embeddedServer(Netty, port = port, host = host) {
        // /api/theme and /api/theme/events: tells the web app how the desktop is themed,
        // and pushes every change. From the omarchy-theme-ktor library.
        omarchyThemeApi()
        webApp(webDir)
    }.start(wait = true)
}

/** Serves the built web app at `/`, if there is one. */
fun Application.webApp(webDir: File?) {
    if (webDir == null) return
    routing {
        staticFiles("/", webDir)
    }
}
