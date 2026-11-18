package org.example.kotlintest.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.unixSocket
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.example.kotlintest.rex.RexEndpoint
import org.example.kotlintest.rex.local
import java.net.URI
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/**
 * Carries the web app's connections to Rex servers.
 *
 * A page can't reach a Rex server itself: it can't open the local server's Unix socket, and
 * a server turns away a WebSocket that says it comes from another site's page. So the page
 * connects here, says where it wants to go, and this makes that connection and passes the
 * messages both ways, unread.
 *
 * The two sockets a Rex client uses are relayed, at their own paths under [path]:
 * `/api/control` and `/api/data`, each with its subprotocol. `?endpoint=` names the server,
 * written as it would be in a workspace.
 *
 * @param localEndpoint the Rex server on this machine, which the page calls `unix:///@local`
 */
fun Application.tonoRelay(path: String = "/api/tono/relay", localEndpoint: String = RexEndpoint.local()) {
    if (pluginOrNull(WebSockets) == null) install(WebSockets)
    val client = HttpClient(CIO) { install(ClientWebSockets) }
    routing {
        for ((socketPath, protocol) in RELAYED) {
            webSocket("$path$socketPath", protocol) {
                if (!call.isFromOwnPage()) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "This relay serves its own page only"))
                    return@webSocket
                }
                val target = relayTarget(call.request.queryParameters["endpoint"], localEndpoint)
                if (target == null) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Not a Rex server this relay will connect to"))
                    return@webSocket
                }
                val upstream = runCatching {
                    client.webSocketSession {
                        when (target) {
                            is RexEndpoint.UnixSocket -> {
                                url("ws://localhost$socketPath")
                                unixSocket(target.path)
                            }
                            is RexEndpoint.Network -> url(target.baseUrl + socketPath)
                        }
                        header(HttpHeaders.SecWebSocketProtocol, protocol)
                    }
                }.getOrElse {
                    // The page shows this as why the host isn't connected.
                    close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, (it.message ?: "The server couldn't be reached").take(100)))
                    return@webSocket
                }
                relay(this, upstream)
            }
        }
    }
}

/** Passes everything each side sends to the other, and when one closes, closes the other the same way. */
private suspend fun relay(page: DefaultWebSocketSession, server: DefaultWebSocketSession) = coroutineScope {
    suspend fun pump(from: DefaultWebSocketSession, to: DefaultWebSocketSession) {
        try {
            for (frame in from.incoming) {
                when (frame) {
                    is Frame.Text -> to.send(Frame.Text(frame.readText()))
                    is Frame.Binary -> to.send(Frame.Binary(true, frame.readBytes()))
                    else -> {}
                }
            }
        } finally {
            // Rex's close codes mean things to a client (a terminal's block closing, for one),
            // so the other side is closed with the same code and reason.
            val reason = runCatching { from.closeReason.await() }.getOrNull()
            runCatching { to.close(reason ?: CloseReason(CloseReason.Codes.GOING_AWAY, "")) }
        }
    }
    launch { pump(page, server) }
    launch { pump(server, page) }
}

/**
 * Where the relay will connect for a page that asked for [endpoint], or null if it won't.
 *
 * A relay that connected anywhere it was asked would let anyone who can reach this server
 * use it to reach other machines. It goes to two kinds of place, both yours: the Rex server
 * on this machine, and Rex servers on the tailnet.
 */
internal fun relayTarget(endpoint: String?, localEndpoint: String): RexEndpoint? {
    if (endpoint == null) return null
    if (endpoint == LOCAL_NAME) return runCatching { RexEndpoint.parse(localEndpoint) }.getOrNull()
    val parsed = runCatching { RexEndpoint.parse(endpoint) }.getOrNull() as? RexEndpoint.Network ?: return null
    val host = runCatching { URI(parsed.baseUrl).host }.getOrNull() ?: return null
    // A name under ts.net, or one of Tailscale's own addresses. Not this machine's loopback
    // address: other things listen there that the page has no business reaching.
    val onTailnet = host.endsWith(".ts.net", ignoreCase = true) || isTailnetAddress(host.trim('[', ']'))
    return parsed.takeIf { onTailnet }
}

/** What the page calls the Rex server on this machine, whose socket it has no way to know. */
private const val LOCAL_NAME = "unix:///@local"

/** The sockets a Rex client opens, and the subprotocol each speaks. */
private val RELAYED = listOf("/api/control" to "rex.control.v1", "/api/data" to "rex.data.terminal.v1")
