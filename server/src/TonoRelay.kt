package org.example.kotlintest.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.example.kotlintest.rex.RexEndpoint
import org.example.kotlintest.rex.local
import org.example.kotlintest.rex.openWebSocket
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
 * `/api/control` and `/api/data`, each with its subprotocols. `?endpoint=` names the server,
 * written as it would be in a workspace.
 *
 * A page that vanishes closes nothing: a phone that sleeps, a lid that shuts, a network that
 * drops. Its connection looks open for as long as nobody writes to it, and to the Rex server
 * the relay's connection is the client, so a client nobody is using would stay listed. So
 * every page is asked whether it is still there (a WebSocket ping, which a browser answers
 * itself, however idle or far in the background the page is), and one that doesn't answer is
 * taken to be gone.
 *
 * @param localEndpoint the Rex server on this machine, which the page calls `unix:///@local`
 * @param pageAskMillis how often a page is asked whether it is still there
 * @param pagePatienceMillis how long it then has to answer
 */
fun Application.tonoRelay(
    path: String = "/api/tono/relay",
    localEndpoint: String = RexEndpoint.local(),
    pageAskMillis: Long = PAGE_ASK_MILLIS,
    pagePatienceMillis: Long = PAGE_PATIENCE_MILLIS,
) {
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
                // Set here and not where the plugin is installed, which may not be in this file.
                timeoutMillis = pagePatienceMillis
                pingIntervalMillis = pageAskMillis
                // A page gives a server ten seconds to answer and then closes this socket. The
                // attempt made for it ends with it, or a server that takes connections and
                // answers none would keep one open here for every time the page tried.
                val attempt = async {
                    runCatching {
                        client.openWebSocket {
                            when (target) {
                                is RexEndpoint.UnixSocket -> {
                                    url("ws://localhost$socketPath")
                                    unixSocket(target.path)
                                }
                                is RexEndpoint.Network -> url(target.baseUrl + socketPath)
                            }
                            header(HttpHeaders.SecWebSocketProtocol, protocol)
                        }
                    }
                }
                val pageLeft = launch {
                    runCatching { closeReason.await() }
                    attempt.cancel()
                }
                val upstream = try {
                    attempt.await()
                } catch (cancelled: CancellationException) {
                    coroutineContext.ensureActive()
                    return@webSocket
                } finally {
                    pageLeft.cancel()
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

/**
 * Passes everything each side sends to the other, and when one ends, ends the other the same way.
 *
 * A Rex server tells a client that left from one whose connection was lost: the first is
 * forgotten at once, and the second keeps its place for a minute, so that it can come back
 * as the client it was, with the terminals it had. Which of the two a page is has to reach
 * the server, so a page that says goodbye has that passed on, and the connection of a page
 * that broke off or stopped answering is dropped with nothing said.
 */
private suspend fun relay(page: DefaultWebSocketSession, server: DefaultClientWebSocketSession) = coroutineScope {
    suspend fun pump(from: DefaultWebSocketSession, to: DefaultWebSocketSession, end: suspend (CloseReason?) -> Unit) {
        var broken = false
        try {
            for (frame in from.incoming) {
                when (frame) {
                    is Frame.Text -> to.send(Frame.Text(frame.readText()))
                    is Frame.Binary -> to.send(Frame.Binary(true, frame.readBytes()))
                    else -> {}
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // What reading ends with when the other end stopped answering, among other things.
            broken = true
        } finally {
            val reason = runCatching { from.closeReason.await() }.getOrNull()
            // Without a goodbye the reason is one made up here, with a code that is never sent.
            runCatching { end(reason?.takeUnless { broken || it.code == CLOSED_ABNORMALLY }) }
        }
    }
    launch {
        pump(page, server) { said ->
            // Rex's close codes mean things, so the server hears the page's own.
            // Cancelling the request ends its connection. Cancelling the socket would say goodbye first.
            if (said != null) server.close(said) else server.call.cancel(CancellationException("The page is gone"))
        }
    }
    launch {
        // A page is always told, since it shows why a host isn't connected, and with the
        // server's code if there was one: a terminal's block closing is one of them.
        pump(server, page) { said -> page.close(said ?: CloseReason(CloseReason.Codes.GOING_AWAY, "")) }
    }
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

/** The close code that stands for a connection that ended without one. */
private const val CLOSED_ABNORMALLY: Short = 1006

/** What the page calls the Rex server on this machine, whose socket it has no way to know. */
private const val LOCAL_NAME = "unix:///@local"

/** The sockets a Rex client opens, and the subprotocol each speaks. */
private val RELAYED = listOf(
    "/api/control" to "rex.control.v1",
    "/api/data" to "rex.data.terminal.v1",
    // Files on their way to a terminal's machine: the one stream whose bytes go from the page to the server.
    "/api/data" to "rex.data.bytes.v1",
)

/**
 * How often a page is asked whether it is still there, and how long it has to answer: a page
 * that is gone is noticed within a minute. A Rex server gives a quiet client five (a ping
 * after two, then one a minute, three unanswered), having learnt to be patient with slow
 * links. This is less patient because being wrong costs little here: a page that is cut off
 * while it is still there connects again and carries on as the client it was.
 */
private const val PAGE_ASK_MILLIS = 20_000L
private const val PAGE_PATIENCE_MILLIS = 40_000L
