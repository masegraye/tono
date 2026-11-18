@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.mg.omarchy.theme

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlin.js.ExperimentalWasmJsInterop

/**
 * Where [omarchyAppearanceFlow] subscribes. The default is relative to the page, and matches
 * the path omarchy-theme-ktor serves. Set it before the flow is first collected.
 */
var omarchyThemeEventsUrl: String = "api/theme/events"

// A browser page cannot read the desktop's files, so it has nothing until a server tells it.
actual fun readOmarchyAppearance(): OmarchyAppearance? = null

/**
 * Subscribes to a Server-Sent Events stream at [omarchyThemeEventsUrl]. The server sends the current
 * theme on connect and again on every change, so the page re-themes at runtime with no
 * rebuild and no reload. The browser reconnects by itself if the server restarts.
 *
 * When the app is hosted somewhere without that endpoint (static hosting), the request
 * fails once, the browser gives up, and this flow simply never emits.
 */
actual fun omarchyAppearanceFlow(): Flow<OmarchyAppearance> = callbackFlow {
    val source = openThemeEvents(omarchyThemeEventsUrl) { json ->
        omarchyAppearanceFromJson(json)?.let { trySend(it) }
    }
    awaitClose { closeThemeEvents(source) }
}.conflate()

actual fun readInstalledOmarchyPalettes(): List<OmarchyPalette> = emptyList()

private fun openThemeEvents(url: String, onTheme: (String) -> Unit): JsAny = js(
    """{
        const source = new EventSource(url);
        source.addEventListener("theme", (event) => onTheme(event.data));
        return source;
    }"""
)

private fun closeThemeEvents(source: JsAny): Unit = js("source.close()")
