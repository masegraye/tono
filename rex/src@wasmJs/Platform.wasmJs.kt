@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.kotlintest.rex

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlin.js.ExperimentalWasmJsInterop

/*
 * In a browser. A page can't open a Unix socket, and a Rex server turns away a WebSocket
 * that says it comes from another site's page, as a browser always says. So every
 * connection goes to the server this page came from, which makes the real one and passes
 * the messages both ways (server/src/TonoRelay.kt).
 */

private fun pageOrigin(): String = js("globalThis.location.origin")
private fun storageGet(key: String): String? = js("globalThis.localStorage.getItem(key)")
private fun storageSet(key: String, value: String): Unit = js("globalThis.localStorage.setItem(key, value)")
private fun dateNow(): Double = js("Date.now()")

/** The address of something on the page's own server, such as `api/tono/tailnet`. */
fun pageUrl(path: String): String = pageOrigin().trimEnd('/') + "/" + path.trimStart('/')

actual fun rexHttpClient(): HttpClient = HttpClient(Js) { install(WebSockets) }

/** The relay's address for a connection to [path] on [endpoint]: it is told where the connection is really going, in the form the endpoint was written. */
internal fun relayUrl(endpoint: RexEndpoint, path: String): String {
    val target = when (endpoint) {
        is RexEndpoint.UnixSocket -> "unix://" + endpoint.path
        is RexEndpoint.Network -> endpoint.baseUrl
    }
    return pageUrl("api/tono/relay$path").replaceFirst("http", "ws") + "?endpoint=" + target.encodeURLParameter()
}

internal actual suspend fun HttpClient.rexWebSocket(endpoint: RexEndpoint, path: String, subprotocol: String): DefaultClientWebSocketSession =
    openWebSocket {
        url(relayUrl(endpoint, path))
        header(HttpHeaders.SecWebSocketProtocol, subprotocol)
    }

/** A page has one thread, so there is nothing to keep terminals apart from. */
internal actual val terminalDispatcher: CoroutineDispatcher get() = Dispatchers.Default

internal actual fun <K, V> concurrentMap(): MutableMap<K, V> = HashMap()
internal actual fun <T> concurrentSet(): MutableSet<T> = HashSet()

internal actual inline fun <T> locked(lock: Any, block: () -> T): T = block()

internal actual class Counter actual constructor() {
    private var value = 1L
    actual fun next(): Long = value++
}

internal actual val clientPlatform: String = "web"

actual fun nowMillis(): Long = dateNow().toLong()

internal actual fun connectFailureMessage(failure: Exception): String? = null

/**
 * The Rex server on the machine that serves this page. The page doesn't know where that
 * server's socket is, and needn't: the relay does, and reads this name as "your own".
 */
actual fun defaultLocalEndpoint(): String = LOCAL_ENDPOINT

/** What the relay takes to mean the Rex server on its own machine. */
const val LOCAL_ENDPOINT = "unix:///@local"

/** Kept in the browser's storage for this site, so each browser has its own. */
actual fun defaultWorkspace(): Workspace = Workspace.load(
    object : WorkspaceStorage {
        override fun read(): String? = storageGet(STORAGE_KEY)
        override fun write(text: String) = storageSet(STORAGE_KEY, text)
    },
)

private const val STORAGE_KEY = "tono.workspace"

/** Asked of the page's server, which can run the `tailscale` command where a page can't. */
actual suspend fun tailnetRexServers(): List<TailnetServer> = runCatching {
    val client = HttpClient(Js)
    try {
        Json.parseToJsonElement(client.get(pageUrl("api/tono/tailnet")).bodyAsText()).jsonArray.mapNotNull { element ->
            val server = element as? JsonObject ?: return@mapNotNull null
            TailnetServer(
                name = server.string("name") ?: return@mapNotNull null,
                address = server.string("address") ?: return@mapNotNull null,
                online = (server["online"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
    } finally {
        client.close()
    }
}.getOrDefault(emptyList())
