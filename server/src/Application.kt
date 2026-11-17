package org.example.kotlintest.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import org.example.kotlintest.OmarchyAppearance
import org.example.kotlintest.omarchyAppearanceFlow
import org.example.kotlintest.readOmarchyAppearance
import org.example.kotlintest.toJson
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
        // One watcher on the desktop's theme for the whole server, shared by every client.
        val appearance = omarchyAppearanceFlow()
            .stateIn(this, SharingStarted.Eagerly, readOmarchyAppearance())
        module(appearance, webDir)
    }.start(wait = true)
}

/**
 * @param appearance how the desktop looks right now; null when this machine has no Omarchy theme
 * @param webDir the built web app to serve at `/`, if any
 */
fun Application.module(appearance: StateFlow<OmarchyAppearance?>, webDir: File?) {
    install(SSE)
    routing {
        // The current theme, for clients that just want to ask once.
        get("/api/theme") {
            val current = appearance.value
            if (current == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respondText(current.toJson(), ContentType.Application.Json)
            }
        }
        // The current theme immediately, then every change as it happens.
        sse("/api/theme/events") {
            appearance.filterNotNull().collect { current ->
                send(ServerSentEvent(data = current.toJson(), event = "theme"))
            }
        }
        if (webDir != null) {
            staticFiles("/", webDir)
        }
    }
}
