package org.example.kotlintest.rex

/** Where a Rex server listens. */
sealed interface RexEndpoint {
    /** A local server's Unix socket. */
    data class UnixSocket(val path: String) : RexEndpoint

    /**
     * A server reached over the network, as a `ws://` or `wss://` base URL. This is how a
     * server with built-in Tailscale is reached: it is a device on the tailnet with its own
     * HTTPS listener, and it knows the caller by their Tailscale login.
     */
    data class Network(val baseUrl: String) : RexEndpoint

    companion object {
        /**
         * Reads an endpoint the way the `rex` CLI's `-S` option does: `unix:///path`,
         * `https://host`, `http://host:port`, `wss://`, `ws://`, or a bare host name,
         * which means HTTPS.
         */
        fun parse(text: String): RexEndpoint {
            val value = text.trim()
            return when {
                value.startsWith("unix://") -> UnixSocket(value.removePrefix("unix://"))
                value.startsWith("/") -> UnixSocket(value)
                value.startsWith("https://") -> Network("wss://" + value.removePrefix("https://").trimEnd('/'))
                value.startsWith("http://") -> Network("ws://" + value.removePrefix("http://").trimEnd('/'))
                value.startsWith("wss://") || value.startsWith("ws://") -> Network(value.trimEnd('/'))
                value.startsWith("ssh://") -> throw IllegalArgumentException("SSH hosts aren't supported yet: $value")
                value.isEmpty() || "://" in value -> throw IllegalArgumentException("Not a Rex endpoint: $text")
                else -> Network("wss://$value")
            }
        }

        /** The local server: `REX_SERVER`, or the per-user socket under `XDG_RUNTIME_DIR`. */
        fun local(environment: Map<String, String> = System.getenv()): String =
            environment["REX_SERVER"]
                ?: "unix://${environment["XDG_RUNTIME_DIR"] ?: "/run/user/${ProcessHandle.current().info().user().map(::uidOf).orElse("1000")}"}/rex/server.sock"

        private fun uidOf(user: String): String =
            runCatching { ProcessBuilder("id", "-u", user).start().inputStream.bufferedReader().readText().trim() }
                .getOrNull()?.takeIf { it.isNotEmpty() } ?: "1000"
    }
}
