package org.example.kotlintest.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import java.net.InetAddress
import java.net.URI

/*
 * Who may use this server. It hands out terminals on your Rex servers, acting as you, so the
 * answer has to be "only you".
 *
 * It listens on this machine only, unless told otherwise. `tailscale serve` (task
 * web:tailnet) is the way to reach it from your other devices: Tailscale then accepts
 * connections from your tailnet alone, over HTTPS, and passes them on to this machine's own
 * address. Nothing is ever opened to the internet.
 *
 * Two checks here back that up, whatever address the server is given to listen on.
 */

/**
 * Refuses any request that didn't come from this machine or from a device on the tailnet.
 * With the server listening only on this machine nothing else can arrive; this is for when
 * it is set to listen on more.
 */
fun Application.tailnetOnly() {
    install(
        createApplicationPlugin("TailnetOnly") {
            onCall { call ->
                val address = call.request.origin.remoteAddress
                if (!isLocalOrTailnet(address)) {
                    call.respondText("Tono is only served to this machine and its tailnet.", status = HttpStatusCode.Forbidden)
                }
            }
        },
    )
}

/**
 * Whether an address is this machine's own or one Tailscale gives out: 100.64.0.0/10 and
 * fd7a:115c:a1e0::/48, which are not routed on the internet and reach a machine only
 * through its tailnet.
 */
internal fun isLocalOrTailnet(address: String): Boolean =
    isTailnetAddress(address) || runCatching { InetAddress.getByName(address.substringBefore('%')).isLoopbackAddress }.getOrDefault(false)

/** Whether an address is one Tailscale gives out. This machine's own loopback address is not. */
internal fun isTailnetAddress(address: String): Boolean {
    // Only an address written out: a name would be looked up, and this is asked about what strangers send.
    if (address.isEmpty() || address.any { !(it.isLetterOrDigit() || it in ".:%") } || address.none { it == '.' || it == ':' }) return false
    if (address.first().isLetter() && ':' !in address) return false
    val ip = runCatching { InetAddress.getByName(address.substringBefore('%')) }.getOrNull() ?: return false
    val bytes = ip.address
    return when (bytes.size) {
        4 -> (bytes[0].toInt() and 0xFF) == 100 && (bytes[1].toInt() and 0xC0) == 0x40
        16 -> bytes.take(6) == listOf(0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0).map { it.toByte() }
        else -> false
    }
}

/**
 * Whether a WebSocket was opened by this server's own page. A browser lets any web page
 * open a socket to any address and says in `Origin` which page did; without this check a
 * page on some other site, open in your browser, could reach your terminals through here.
 * A request with no `Origin` isn't from a browser page, and is refused too: only the page
 * has a use for the relay.
 */
internal fun ApplicationCall.isFromOwnPage(): Boolean {
    val origin = request.headers[HttpHeaders.Origin] ?: return false
    val host = request.headers[HttpHeaders.Host] ?: return false
    val from = runCatching { URI(origin) }.getOrNull() ?: return false
    val authority = from.host?.let { if (from.port >= 0) "$it:${from.port}" else it } ?: return false
    return authority.equals(host, ignoreCase = true)
}
