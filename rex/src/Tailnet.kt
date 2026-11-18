package org.example.kotlintest.rex

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * A Rex server found on the tailnet. A server with Tailscale turned on joins the tailnet as
 * a device of its own, apart from the machine it runs on, named after that machine with
 * `-rex` on the end.
 *
 * @param name the device's name on the tailnet, such as `studio-rex`
 * @param address its full DNS name, such as `studio-rex.tail1234.ts.net`
 */
data class TailnetServer(val name: String, val address: String, val online: Boolean) {
    /** What to call the host: the machine's name, without the suffix every server shares. */
    val label: String get() = name.removeSuffix(REX_SUFFIX)
    val endpoint: String get() = "https://$address"
}

object Tailnet {
    /** Picks the Rex servers out of `tailscale status --json`. */
    fun parseStatus(status: JsonObject): List<TailnetServer> =
        (listOfNotNull(status.obj("Self")) + status.obj("Peer")?.values.orEmpty().mapNotNull { it as? JsonObject })
            .mapNotNull { device ->
                val address = device.string("DNSName")?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val name = address.substringBefore('.')
                if (!name.endsWith(REX_SUFFIX) || name == REX_SUFFIX) return@mapNotNull null
                TailnetServer(name, address, online = (device["Online"] as? JsonPrimitive)?.booleanOrNull == true)
            }
            .distinctBy { it.address }
            .sortedWith(compareByDescending<TailnetServer> { it.online }.thenBy { it.name })
}

/**
 * An endpoint reduced to what makes it the same place, for telling whether two spellings
 * reach one server: `https://Studio.ts.net/`, `studio.ts.net` and `wss://studio.ts.net` do.
 */
fun endpointKey(endpoint: String): String {
    val value = endpoint.trim().lowercase()
    if (value.startsWith("unix://") || value.startsWith("/")) return "unix:" + value.removePrefix("unix://")
    val scheme = value.substringBefore("://", "")
    val rest = value.substringAfter("://").trimEnd('/').trimEnd('.')
    // A bare name means HTTPS, and a secure WebSocket is the same listener.
    return if (scheme == "http" || scheme == "ws") "http:$rest" else rest
}

/**
 * A label for a host known only by its address: the first part of its name, without the
 * suffix Rex servers share on a tailnet. `studio-rex.tail1234.ts.net` is `studio`.
 */
fun suggestedHostLabel(endpoint: String): String {
    val value = endpoint.trim()
    if (value.startsWith("unix://") || value.startsWith("/")) return value.substringAfterLast('/').substringBeforeLast('.').ifEmpty { "socket" }
    val host = value.substringAfter("://").substringBefore('/').substringBefore(':')
    // An IP address has no name to shorten.
    if (host.all { it.isDigit() || it == '.' }) return host
    return host.substringBefore('.').removeSuffix(REX_SUFFIX).ifEmpty { host }
}

private const val REX_SUFFIX = "-rex"
