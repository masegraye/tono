package dev.mg.omarchy.theme.ktor

import dev.mg.omarchy.theme.OmarchyAppearance
import dev.mg.omarchy.theme.omarchyAppearanceFlow
import dev.mg.omarchy.theme.readOmarchyAppearance
import dev.mg.omarchy.theme.toJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
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

/**
 * Serves the desktop's Omarchy theme so that web clients can follow it. A browser page
 * cannot read the desktop's files; this server can, and passes each change on.
 *
 * - `GET {path}` returns the current theme as JSON, or 404 on a machine with no Omarchy theme.
 * - `GET {path}/events` is a Server-Sent Events stream: one `theme` event with the current
 *   theme on connect, then one for every change. The web build of omarchy-theme subscribes
 *   to it out of the box when the page and this API share an origin.
 *
 * ```
 * embeddedServer(Netty, port = 8080) {
 *     omarchyThemeApi()
 * }.start(wait = true)
 * ```
 *
 * @param appearance how the desktop looks; by default this application watches it itself
 * @param path where to mount the API
 */
fun Application.omarchyThemeApi(
    appearance: StateFlow<OmarchyAppearance?> = watchOmarchyAppearance(),
    path: String = "/api/theme",
) {
    if (pluginOrNull(SSE) == null) install(SSE)
    routing {
        get(path) {
            val current = appearance.value
            if (current == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respondText(current.toJson(), ContentType.Application.Json)
            }
        }
        sse("$path/events") {
            appearance.filterNotNull().collect { current ->
                send(ServerSentEvent(data = current.toJson(), event = "theme"))
            }
        }
    }
}

/**
 * Watches the desktop's theme and font for as long as this application runs. The value is
 * null on a machine with no Omarchy theme. One watcher serves every connected client.
 */
fun Application.watchOmarchyAppearance(): StateFlow<OmarchyAppearance?> =
    omarchyAppearanceFlow().stateIn(this, SharingStarted.Eagerly, readOmarchyAppearance())
