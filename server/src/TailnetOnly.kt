package org.example.kotlintest.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
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
 * A tailnet can have other people on it, though, so being on the tailnet is not enough. As
 * a Rex server does, this lets in the Tailscale user who owns this machine and nobody else,
 * unless it is told to let in everyone Tailscale can put a name to.
 */

/**
 * Whoever a request is from.
 *
 * @param local made on this machine, by the account's own browser
 * @param device the name of the device it came from, as the tailnet knows it
 * @param login the Tailscale user who owns that device, usually an email address
 */
class Caller(val local: Boolean, val device: String?, val login: String?)

/**
 * Refuses any request this server shouldn't answer: one from outside this machine and its
 * tailnet, and one from a device on the tailnet that isn't its owner's (see [callerOf]).
 * Those it lets through can ask who they are with [caller].
 *
 * @param allowIdentified lets in every person on the tailnet, not only this machine's owner
 */
fun Application.tailnetOnly(directory: TailnetDirectory = TailscaleCli, allowIdentified: Boolean = false) {
    install(
        createApplicationPlugin("TailnetOnly") {
            onCall { call ->
                val caller = callerOf(
                    remote = call.request.origin.remoteAddress,
                    forwardedFor = call.request.headers[HttpHeaders.XForwardedFor],
                    directory = directory,
                    allowIdentified = allowIdentified,
                )
                if (caller == null) {
                    call.respondText("Tono is only served to this machine, and to its owner's devices on the tailnet.", status = HttpStatusCode.Forbidden)
                } else {
                    call.attributes.put(CALLER, caller)
                }
            }
        },
    )
}

/** Who this request is from. Only for a server with [tailnetOnly] installed. */
val ApplicationCall.caller: Caller get() = attributes[CALLER]

private val CALLER = AttributeKey<Caller>("TonoCaller")

/**
 * Who a request is from, or null if it is to be refused.
 *
 * A request reaches this server in one of three ways:
 * - from this machine itself, which is let in as it is;
 * - through `tailscale serve`, which connects from this machine and says in
 *   `X-Forwarded-For` which tailnet device it is passing on (it replaces whatever the
 *   device sent under that name, so a caller can't choose it);
 * - straight from a tailnet address, when the server was set to listen on one.
 *
 * For the last two the tailnet is asked whose device that is. Its owner has to be the owner
 * of this machine, or with [allowIdentified] any person at all. A tagged device, which is a
 * machine with no person behind it, is never let in.
 */
internal suspend fun callerOf(remote: String, forwardedFor: String?, directory: TailnetDirectory, allowIdentified: Boolean): Caller? {
    val fromHere = isLoopback(remote)
    if (fromHere && forwardedFor == null) return Caller(local = true, device = directory.self()?.name, login = directory.self()?.login)
    val address = if (fromHere) forwardedFor!!.trim() else remote
    if (!isTailnetAddress(address)) return null
    val device = directory.whoIs(address) ?: return null
    val login = device.login?.takeIf { !device.tagged } ?: return null
    val owner = directory.self()?.login
    return if (allowIdentified || (owner != null && login.equals(owner, ignoreCase = true))) Caller(local = false, device = device.name, login = login) else null
}

/**
 * Whether an address is this machine's own or one Tailscale gives out: 100.64.0.0/10 and
 * fd7a:115c:a1e0::/48, which are not routed on the internet and reach a machine only
 * through its tailnet.
 */
internal fun isLocalOrTailnet(address: String): Boolean = isTailnetAddress(address) || isLoopback(address)

private fun isLoopback(address: String): Boolean =
    address == "localhost" || isWrittenOut(address) && runCatching { InetAddress.getByName(address.substringBefore('%')).isLoopbackAddress }.getOrDefault(false)

/** Only an address written out: a name would be looked up, and this is asked about what strangers send. */
private fun isWrittenOut(address: String): Boolean =
    address.isNotEmpty() && address.all { it.isLetterOrDigit() || it in ".:%" } && address.any { it == '.' || it == ':' } &&
        (!address.first().isLetter() || ':' in address)

/** Whether an address is one Tailscale gives out. This machine's own loopback address is not. */
internal fun isTailnetAddress(address: String): Boolean {
    if (!isWrittenOut(address)) return false
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
