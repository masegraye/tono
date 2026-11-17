package org.example.kotlintest.rex

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.example.kotlintest.ghostty.TerminalColors

/**
 * The app's Rex connections: one [RexHost] for every configured server, all started at once.
 *
 * Which servers those are:
 * - the hosts saved in the [Workspace], or just the local server the first time the app runs;
 * - plus the hosts in the home server's host list (`hosts.list`), which is where Rex keeps
 *   the servers you connect to by name, so that every device sees the same ones. The home
 *   server is the first saved host on a Unix socket. A listed host that turns out to be
 *   the home server under another address is left out.
 *
 * Where the user was (which host, and which session on each) is remembered in the
 * workspace and restored on the next start.
 */
class RexClient(
    private val scope: CoroutineScope,
    /** What is remembered between runs. The app keeps its sidebar's folded state here too. */
    val workspace: Workspace = Workspace.load(),
    private val info: RexClientInfo = RexClientInfo(name = "kotlin-test", version = "0.1.0"),
) : AutoCloseable {
    private val http = rexHttpClient()
    private val mutableHosts = MutableStateFlow<List<RexHost>>(emptyList())
    private val mutableSelectedHost = MutableStateFlow<String?>(null)
    private val mutableKeymap = MutableStateFlow(Keymap(emptyList()))
    private val keymapRefresh = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var terminalColors: TerminalColors? = null
    @Volatile private var declaredActions: List<RexAction> = emptyList()
    @Volatile private var defaultBindings: List<KeyBinding> = emptyList()
    private val home: RexHost?

    /** What the client has been doing, for the debug panel. */
    val log = RexLog()

    /** What each key does. Starts as the app's defaults, then follows the home server's configuration. */
    val keymap: StateFlow<Keymap> get() = mutableKeymap

    val hosts: StateFlow<List<RexHost>> get() = mutableHosts
    /** The label of the host being shown. */
    val selectedHost: StateFlow<String?> get() = mutableSelectedHost

    init {
        // The first time the app runs there is one host, the local server. It is written to
        // the workspace like any other, so the file shows what the app connects to.
        if (workspace.hosts.isEmpty()) {
            workspace.hosts = listOf(SavedHost("Local", RexEndpoint.local()))
            workspace.save()
        }
        val saved = workspace.hosts
        mutableHosts.value = saved.map { host(it, discovered = false) }
        mutableSelectedHost.value = workspace.lastHost?.takeIf { label -> saved.any { it.label == label } } ?: saved.first().label

        home = mutableHosts.value.firstOrNull { it.saved.endpoint.startsWith("unix://") || it.saved.endpoint.startsWith("/") }
        if (home != null) scope.launch { addHostsFrom(home) }
        scope.launch { for (request in keymapRefresh) resolveKeymap() }
    }

    /**
     * Tells Rex which actions this app performs and which keys it binds to them by default.
     * The home server layers the account's configuration file on top and answers with what
     * each key does, so a binding made there applies here as it does in other Rex clients.
     * That is read again whenever the configuration changes.
     */
    fun declareKeymap(actions: List<RexAction>, defaults: List<KeyBinding>) {
        declaredActions = actions
        defaultBindings = defaults
        // Until the server answers, and whenever it can't be reached: the defaults as they are.
        // Later bindings sit above earlier ones, and the keymap lists the top first.
        mutableKeymap.value = Keymap(defaults.reversed())
        keymapRefresh.trySend(Unit)
    }

    private suspend fun resolveKeymap() {
        val home = home ?: return
        if (home.state.value.connection != HostConnection.Connected || declaredActions.isEmpty()) return
        runCatching { home.keymap(declaredActions, defaultBindings) }
            .onSuccess { resolved ->
                mutableKeymap.value = resolved
                val configured = resolved.bindings.count { it.source != "default" }
                log.add("keys", "keymap from ${home.saved.label}: ${resolved.bindings.size} bindings, $configured from the configuration file, ${resolved.serverActions.size} server actions")
            }
            .onFailure { log.add("keys", "couldn't read the keymap: ${it.message}", isError = true) }
    }

    fun selectHost(label: String) {
        mutableSelectedHost.value = label
        workspace.lastHost = label
        workspace.save()
    }

    /** Sets the colors every terminal is drawn with, now and for terminals opened later. */
    fun setTerminalColors(colors: TerminalColors) {
        terminalColors = colors
        mutableHosts.value.forEach { it.setTerminalColors(colors) }
    }

    private fun host(saved: SavedHost, discovered: Boolean) = RexHost(
        saved = saved,
        http = http,
        parentScope = scope,
        info = info,
        rememberedSession = workspace.sessions[saved.label],
        discovered = discovered,
        log = log,
        onSessionSelected = { sessionId ->
            workspace.sessions[saved.label] = sessionId
            workspace.save()
        },
        // The keymap belongs to the home server; read it when that connects or its configuration changes.
        onServerEvent = { type -> if (type == "connected" || type == "keymap_changed") keymapRefresh.trySend(Unit) },
    ).also { host -> terminalColors?.let(host::setTerminalColors) }

    /** Connects to the hosts the home server lists, once it is reachable. */
    private suspend fun addHostsFrom(home: RexHost) {
        val homeId = home.state.first { it.connection == HostConnection.Connected }.serverHostId
        val listed = runCatching { home.call("hosts.list")["hosts"] as? JsonArray }.getOrNull().orEmpty()
        for (element in listed) {
            val entry = element as? JsonObject ?: continue
            val label = entry.string("label") ?: continue
            val endpoint = (entry["endpoints"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.content } ?: continue
            if (mutableHosts.value.any { it.saved.label.equals(label, ignoreCase = true) || it.saved.endpoint == endpoint }) continue
            // A server commonly lists itself under its tailnet name. Ask the listed server who
            // it is before showing it, so the home server doesn't appear twice. One that can't
            // be reached is still shown: it is a configured host that happens to be down.
            scope.launch {
                if (homeId != null && serverHostIdAt(endpoint) == homeId) return@launch
                mutableHosts.update { current ->
                    if (current.any { it.saved.label.equals(label, ignoreCase = true) || it.saved.endpoint == endpoint }) current
                    else current + host(SavedHost(label, endpoint), discovered = true)
                }
            }
        }
    }

    /** Which machine and account the server at [endpoint] runs as, read without attaching to anything. */
    private suspend fun serverHostIdAt(endpoint: String): String? = runCatching {
        coroutineScope {
            val control = RexControl.connect(http, RexEndpoint.parse(endpoint), this, info)
            try {
                withTimeout(5_000) { control.call("server.status") }.string("host_id")
            } finally {
                control.close()
            }
        }
    }.getOrNull()

    override fun close() {
        mutableHosts.value.forEach { it.close() }
        http.close()
    }
}
