package org.example.kotlintest.rex

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap
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
    parentScope: CoroutineScope,
    /** What is remembered between runs. The app keeps its sidebar's folded state here too. */
    val workspace: Workspace = Workspace.load(),
    private val info: RexClientInfo = RexClientInfo(name = "kotlin-test", version = "0.1.0"),
) : AutoCloseable {
    // The client's own work, under the caller's scope but ended by close(): a caller that
    // waits for its scope to finish (a command-line tool in runBlocking) would otherwise
    // wait forever on the loops started here.
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val http = rexHttpClient()
    private val mutableHosts = MutableStateFlow<List<RexHost>>(emptyList())
    private val mutableSelectedHost = MutableStateFlow<String?>(null)
    private val mutableKeymap = MutableStateFlow(Keymap(emptyList()))
    private val keymapRefresh = Channel<Unit>(Channel.CONFLATED)
    private val hostListRefresh = Channel<Unit>(Channel.CONFLATED)
    /** For each host that came from the home server's host list, by label, its id in that list. */
    private val listedIds = ConcurrentHashMap<String, String>()
    /** Addresses in that list that turned out to be the home server itself, as [endpointKey]s. */
    private val ownAddresses = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var terminalColors: TerminalColors? = null
    @Volatile private var declaredActions: List<RexAction> = emptyList()
    @Volatile private var defaultBindings: List<KeyBinding> = emptyList()
    private val home: RexHost?
    private val hostListLock = Mutex()

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
        scope.launch { for (request in hostListRefresh) home?.let { syncHostList(it) } }
        scope.launch { for (request in keymapRefresh) resolveKeymap() }
    }

    /** The label of the home server: the one whose host list and keymap this app follows. It can't be removed. */
    val homeLabel: String? get() = home?.saved?.label

    /**
     * Whether an address is already among the hosts, under any spelling, or is the home
     * server's own.
     */
    fun isConfigured(endpoint: String): Boolean =
        endpointKey(endpoint).let { key -> key in ownAddresses || mutableHosts.value.any { endpointKey(it.saved.endpoint) == key } }

    /**
     * Adds a host and goes to it. It is added to the home server's host list, which is where
     * Rex keeps hosts, so the `rex` command and every other client that follows this home see
     * it too. Without a home to keep it, it is saved in this app's own workspace instead.
     */
    fun addHost(label: String, endpoint: String) {
        scope.launch {
            val name = label.trim()
            if (runCatching { RexEndpoint.parse(endpoint) }.isFailure || name.isEmpty()) {
                log.add("hosts", "can't add $endpoint: not an address Rex can reach", isError = true)
                return@launch
            }
            if (mutableHosts.value.any { it.saved.label.equals(name, ignoreCase = true) } || isConfigured(endpoint)) {
                log.add("hosts", "$name ($endpoint) is already a host")
                return@launch
            }
            val home = home?.takeIf { it.state.value.connection == HostConnection.Connected }
            if (home == null) {
                addSavedHost(name, endpoint)
                return@launch
            }
            // Ask the server who it is: the home server under its tailnet name is not another host.
            val serverId = serverHostIdAt(endpoint)
            if (serverId != null && serverId == home.state.value.serverHostId) {
                ownAddresses += endpointKey(endpoint)
                log.add("hosts", "$endpoint is ${home.saved.label}, which is already here")
                return@launch
            }
            runCatching {
                home.call("hosts.put", buildJsonObject {
                    putJsonObject("host") {
                        put("label", name)
                        putJsonArray("endpoints") { add(endpoint) }
                        serverId?.let { put("host_id", it) }
                        put("added_from", info.name)
                    }
                })
            }.onSuccess {
                log.add("hosts", "added $name ($endpoint) to ${home.saved.label}'s host list")
                syncHostList(home)
                if (mutableHosts.value.any { it.saved.label == name }) selectHost(name)
            }.onFailure {
                // A server without a state directory can't keep hosts. This app can.
                log.add("hosts", "${home.saved.label} couldn't keep $name (${it.message}); saving it in this app instead", isError = true)
                addSavedHost(name, endpoint)
            }
        }
    }

    private fun addSavedHost(label: String, endpoint: String) {
        val saved = SavedHost(label, endpoint)
        workspace.hosts = workspace.hosts + saved
        workspace.save()
        mutableHosts.update { it + host(saved, discovered = false) }
        log.add("hosts", "added $label ($endpoint) to this app's workspace")
        selectHost(label)
    }

    /**
     * Removes a host: from the home server's host list if it came from there, else from this
     * app's workspace. Nothing on the host itself changes; its sessions carry on.
     */
    fun removeHost(label: String) {
        scope.launch {
            val host = mutableHosts.value.firstOrNull { it.saved.label == label } ?: return@launch
            if (host === home) {
                log.add("hosts", "$label is the home server and stays", isError = true)
                return@launch
            }
            val listedId = listedIds[label]
            if (listedId != null) {
                val home = home ?: return@launch
                runCatching { home.call("hosts.remove", buildJsonObject { put("id", listedId) }) }
                    .onSuccess {
                        log.add("hosts", "removed $label from ${home.saved.label}'s host list")
                        syncHostList(home)
                    }
                    .onFailure { log.add("hosts", "couldn't remove $label: ${it.message}", isError = true) }
            } else {
                workspace.hosts = workspace.hosts.filterNot { it.label == label }
                workspace.save()
                drop(host)
                log.add("hosts", "removed $label from this app's workspace")
            }
        }
    }

    /** Stops showing a host and forgets where the user was on it. */
    private fun drop(host: RexHost) {
        host.close()
        mutableHosts.update { current -> current.filterNot { it === host } }
        listedIds.remove(host.saved.label)
        workspace.sessions.remove(host.saved.label)
        if (mutableSelectedHost.value == host.saved.label) mutableHosts.value.firstOrNull()?.let { selectHost(it.saved.label) } else workspace.save()
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
        onServerEvent = { type ->
            if (type == "connected" || type == "keymap_changed") keymapRefresh.trySend(Unit)
            // Every server has a host list; the one this app follows is its home's.
            if (saved == home?.saved && (type == "connected" || type == "hosts_changed")) hostListRefresh.trySend(Unit)
        },
    ).also { host -> terminalColors?.let(host::setTerminalColors) }

    /**
     * Makes the hosts shown match the home server's host list: connects to the ones that are
     * new, and lets go of the ones that came from the list and have left it. Runs when the
     * home server connects and whenever it says the list changed, such as after
     * `rex hosts add` in a terminal.
     */
    private suspend fun syncHostList(home: RexHost) = hostListLock.withLock {
        if (home.state.value.connection != HostConnection.Connected) return@withLock
        val homeId = home.state.value.serverHostId
        val listed = runCatching { home.call("hosts.list")["hosts"] as? JsonArray }
            .onFailure { log.add("hosts", "couldn't read ${home.saved.label}'s host list: ${it.message}", isError = true) }
            .getOrNull() ?: return@withLock
        val entries = listed.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val label = entry.string("label") ?: return@mapNotNull null
            val endpoint = (entry["endpoints"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.content } ?: return@mapNotNull null
            ListedHost(entry.string("id"), label, endpoint, entry.string("host_id"))
        }

        // Hosts that came from the list and are no longer in it as they were.
        for (host in mutableHosts.value.filter { it.discovered }) {
            if (entries.none { it.label == host.saved.label && it.endpoint == host.saved.endpoint }) {
                log.add("hosts", "${host.saved.label} left ${home.saved.label}'s host list")
                drop(host)
            }
        }

        // A server commonly lists itself under its tailnet name. Ask each new server who it
        // is before showing it, so the home server doesn't appear twice. All at once: one
        // that is down takes seconds to give up on. A server that can't be reached is still
        // shown, as a configured host that happens to be down.
        val fresh = entries.filter { entry ->
            endpointKey(entry.endpoint) !in ownAddresses &&
                mutableHosts.value.none { it.saved.label.equals(entry.label, ignoreCase = true) || endpointKey(it.saved.endpoint) == endpointKey(entry.endpoint) }
        }
        val isHome = coroutineScope {
            fresh.map { entry -> async { homeId != null && (entry.hostId ?: serverHostIdAt(entry.endpoint)) == homeId } }.awaitAll()
        }
        fresh.forEachIndexed { index, entry ->
            if (isHome[index]) {
                ownAddresses += endpointKey(entry.endpoint)
                return@forEachIndexed
            }
            entry.id?.let { listedIds[entry.label] = it }
            mutableHosts.update { it + host(SavedHost(entry.label, entry.endpoint), discovered = true) }
            log.add("hosts", "${entry.label} (${entry.endpoint}) from ${home.saved.label}'s host list")
        }
    }

    private class ListedHost(val id: String?, val label: String, val endpoint: String, val hostId: String?)

    /**
     * Which machine and account the server at [endpoint] runs as, read without attaching to
     * anything. Null if it doesn't answer within a few seconds.
     */
    private suspend fun serverHostIdAt(endpoint: String): String? = runCatching {
        withTimeout(5_000) {
            coroutineScope {
                val control = RexControl.connect(http, RexEndpoint.parse(endpoint), this, info)
                try {
                    control.call("server.status").string("host_id")
                } finally {
                    control.close()
                }
            }
        }
    }.getOrNull()

    override fun close() {
        mutableHosts.value.forEach { it.close() }
        scope.cancel()
        http.close()
    }
}
