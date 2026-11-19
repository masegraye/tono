package org.example.kotlintest.rex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/*
 * What a Rex server says about itself, for showing: `server.status`, `server.version` and
 * `server.whoami`, read together. Rex for Mac ships its own server and manages it in its
 * settings; this app doesn't, so what it can do is show what there is to know.
 */

/** Somewhere a server listens: its own socket, a TCP address, the tailnet, the login server. */
data class ServerListener(val endpoint: String, val transport: String, val primary: Boolean) {
    /** What kind of place it is, in words. A kind this app doesn't know is shown as the server names it. */
    val kind: String
        get() = when {
            endpoint.startsWith("login-server:") -> "login server"
            transport == "unix" -> "Unix socket"
            transport == "named_pipe" -> "named pipe"
            transport == "tcp" -> "TCP"
            transport == "tailscale" -> "Tailscale"
            else -> transport
        }
}

/**
 * The server's own Tailscale device, if it can have one.
 *
 * @param state `disabled`, `starting`, `needs_login`, `needs_machine_approval` or `running`
 * @param name its name on the tailnet, once known
 * @param httpsUrl its address there, once its HTTPS listener is up
 * @param account the Tailscale login that signed it in
 * @param signInLink the link that signs it in, while it waits for that and Tailscale has made one
 */
data class ServerTailscale(
    val state: String, val name: String? = null, val httpsUrl: String? = null, val account: String? = null,
    val tailnet: String? = null, val signInLink: String? = null, val error: String? = null,
) {
    val needsSignIn: Boolean get() = state == "needs_login"

    /** The state in words. One this app doesn't know is taken as still starting, as the server asks. */
    val stateWords: String
        get() = when (state) {
            "disabled" -> "Off"
            "running" -> "On"
            "needs_login" -> "Waiting to be signed in"
            "needs_machine_approval" -> "Waiting for a tailnet administrator to approve this device"
            else -> "Starting…"
        }
}

/**
 * @param version the server's Rex version, or null from a server that doesn't say
 * @param startedAt when it started, as the server wrote it (RFC 3339)
 * @param account the account it runs as
 * @param configGeneration how many times it has read its configuration file
 */
data class ServerInfo(
    val version: String? = null,
    val platform: String? = null,
    val apiRevision: Int? = null,
    val pid: Int? = null,
    val startedAt: String? = null,
    val account: String? = null,
    val sessionCount: Int? = null,
    val listeners: List<ServerListener> = emptyList(),
    val tailscale: ServerTailscale? = null,
    val configGeneration: Int? = null,
) {
    /** When it started, to the minute, for reading: `2026-11-19 13:17 UTC`. As written, if it isn't a time this reads. */
    val startedShown: String?
        get() = startedAt?.let { written ->
            val time = Regex("""^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})[\d:.]*Z$""").find(written) ?: return@let written
            "${time.groupValues[1]} ${time.groupValues[2]} UTC"
        }
}

private fun JsonObject.number(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

/** Reads the three answers. Any of them may be missing, from a server too old to give it or a call that failed. */
fun parseServerInfo(status: JsonObject?, version: JsonObject?, whoami: JsonObject?): ServerInfo {
    val tailscale = status?.obj("tailscale")
    return ServerInfo(
        version = version?.string("rex_version"),
        platform = version?.string("platform"),
        apiRevision = version?.number("api_revision") ?: status?.number("api_revision"),
        pid = status?.number("pid"),
        startedAt = status?.string("started_at"),
        account = whoami?.string("account"),
        sessionCount = status?.number("session_count"),
        listeners = (status?.get("listeners") as? JsonArray).orEmpty().mapNotNull { element ->
            val listener = element as? JsonObject ?: return@mapNotNull null
            ServerListener(listener.string("endpoint") ?: return@mapNotNull null, listener.string("transport").orEmpty(), (listener["primary"] as? JsonPrimitive)?.content == "true")
        },
        tailscale = tailscale?.string("state")?.let { state ->
            ServerTailscale(
                state = state,
                name = tailscale.string("dns_name"),
                httpsUrl = tailscale.obj("https")?.string("url"),
                account = tailscale.obj("account")?.string("login_name"),
                tailnet = tailscale.string("tailnet_name"),
                signInLink = tailscale.string("auth_url")?.takeIf { it.isNotEmpty() },
                error = tailscale.string("error")?.takeIf { it.isNotEmpty() },
            )
        },
        configGeneration = status?.obj("config")?.number("generation"),
    )
}
