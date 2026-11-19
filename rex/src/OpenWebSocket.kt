package org.example.kotlintest.rex

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareRequest
import io.ktor.http.URLProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Opens a WebSocket, and gives the attempt up when the caller stops waiting for it.
 *
 * Ktor's own `webSocketSession` makes its request in a coroutine of the client's, which a
 * caller that is cancelled has no way to stop. So an attempt that ran out of time went on
 * waiting for a server that accepted the connection and never answered, with its socket open
 * for as long as the server kept it. This is that function with the one difference: cancel
 * the wait, and the request is cancelled too, which closes its connection.
 */
suspend fun HttpClient.openWebSocket(block: HttpRequestBuilder.() -> Unit): DefaultClientWebSocketSession {
    val opened = CompletableDeferred<DefaultClientWebSocketSession>()
    val statement = prepareRequest {
        url {
            protocol = URLProtocol.WS
            port = protocol.defaultPort
        }
        block()
    }
    val request = launch {
        try {
            statement.body<DefaultClientWebSocketSession, Unit> { session ->
                // The request lasts as long as its socket: returning from here closes the connection.
                val finished = CompletableDeferred<Unit>()
                opened.complete(session)
                session.outgoing.invokeOnClose { cause ->
                    if (cause != null) finished.completeExceptionally(cause) else finished.complete(Unit)
                }
                finished.await()
            }
        } catch (failure: Throwable) {
            opened.completeExceptionally(failure)
        }
    }
    try {
        return opened.await()
    } catch (cancelled: CancellationException) {
        // Also when the socket opened just as the wait ended: nobody was given it, so nobody would close it.
        request.cancel(cancelled)
        throw cancelled
    }
}

/**
 * Runs [block], the first exchange on a socket just opened, and drops the socket if it fails.
 * A server that took the connection and then never said its first word would otherwise keep
 * it: nothing else has the socket yet to close it.
 */
internal suspend fun <T> DefaultClientWebSocketSession.orDrop(block: suspend () -> T): T =
    try {
        block()
    } catch (failure: Throwable) {
        // The other end may not be there to finish a polite close with.
        cancel(CancellationException(failure.message ?: "The connection wasn't set up"))
        throw failure
    }
