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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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
import kotlin.concurrent.Volatile
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
    val workspace: Workspace = defaultWorkspace(),
    private val info: RexClientInfo = TonoClient,
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
    private val listedIds = concurrentMap<String, String>()
    /** Addresses in that list that turned out to be the home server itself, as [endpointKey]s. */
    private val ownAddresses = concurrentSet<String>()
    @Volatile private var terminalColors: TerminalColors? = null
    @Volatile private var declaredActions: List<RexAction> = emptyList()
    @Volatile private var defaultBindings: List<KeyBinding> = emptyList()
    private val home: RexHost?
    private val hostListLock = Mutex()

    /** What the client has been doing, for the debug panel. */
    val log = RexLog()

    /** What each key does. Starts as the app's defaults, then follows the home server's configuration. */
    val keymap: StateFlow<Keymap> get() = mutableKeymap

    private val mutableShortcuts = MutableStateFlow(workspace.shortcuts)

    /** The shortcuts changed in this app: its own layer of the keymap, above the configuration file. */
    val shortcuts: StateFlow<ShortcutOverrides> get() = mutableShortcuts

    private val mutableAppearance = MutableStateFlow(workspace.appearance)

    /**
     * The theme and terminal font the person chose, as they stand. Kept in the workspace, and
     * here so that everything drawn from them (every window of the app, a settings screen, a
     * palette) hears of a change wherever it was made.
     */
    val appearance: StateFlow<AppearanceChoice> get() = mutableAppearance

    /** Sets the theme and terminal font chosen. Applies at once, and is remembered. */
    fun setAppearance(choice: AppearanceChoice) {
        if (mutableAppearance.value == choice) return
        mutableAppearance.value = choice
        workspace.appearance = choice
        workspace.save()
    }

    private val mutableSounds = MutableStateFlow(workspace.sounds)
    private val mutableNotifications = MutableStateFlow(workspace.notifications)
    private val mutableSelection = MutableStateFlow(workspace.selection)

    /*
     * The switches a person sets, as they stand. Each is kept in the workspace, and is here so
     * that everything showing one (a palette, a settings window, another window of the app)
     * hears of a change wherever it was made.
     */

    /** Whether a program in a tab out of view makes a sound when it wants the user, fails, or finishes. */
    val sounds: StateFlow<Boolean> get() = mutableSounds

    /** What a desktop notification is shown for. */
    val notifications: StateFlow<NotificationSwitches> get() = mutableNotifications

    /** What selecting with the pointer copies, and whether the middle button pastes. */
    val selection: StateFlow<SelectionSwitches> get() = mutableSelection

    /** Turns the sounds on or off. Applies at once, and is remembered. */
    fun setSounds(enabled: Boolean) {
        if (mutableSounds.value == enabled) return
        mutableSounds.value = enabled
        workspace.sounds = enabled
        workspace.save()
    }

    /** Sets what a desktop notification is shown for. Applies at once, and is remembered. */
    fun setNotifications(switches: NotificationSwitches) {
        if (mutableNotifications.value == switches) return
        mutableNotifications.value = switches
        workspace.notifications = switches
        workspace.save()
    }

    /** Sets what selecting with the pointer does. Applies at once, and is remembered. */
    fun setSelection(switches: SelectionSwitches) {
        if (mutableSelection.value == switches) return
        mutableSelection.value = switches
        workspace.selection = switches
        workspace.save()
    }

    private val mutableTabNews = MutableSharedFlow<TabNews>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * What has just happened in tabs nobody is looking at that someone would want to know:
     * a program started waiting for them, failed or finished, or asked to tell them
     * something. On any host.
     */
    val tabNews: SharedFlow<TabNews> get() = mutableTabNews

    private val mutableProgramCopies = MutableSharedFlow<ProgramCopy>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Text that programs have copied in the terminal in use, for this machine's clipboard.
     * A copy that nobody has taken yet is replaced by the next, and one that arrives while
     * nobody is listening is lost, which is right: it should never turn up later.
     */
    val programCopies: SharedFlow<ProgramCopy> get() = mutableProgramCopies

    val hosts: StateFlow<List<RexHost>> get() = mutableHosts

    private val mutableHostIcons = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * The icon the home server's host list names for each host, by the host's label here.
     * The value is the list's `icon` as written: a name that Rex keeps and doesn't interpret,
     * such as the `server.rack` or `macbook` that Rex for Mac's host form writes. A host the
     * list gives no icon, or doesn't have, isn't in the map. It follows the list as it changes.
     */
    val hostIcons: StateFlow<Map<String, String>> get() = mutableHostIcons
    /** The label of the host being shown. */
    val selectedHost: StateFlow<String?> get() = mutableSelectedHost

    private val mutableHostListings = MutableStateFlow<Map<String, HostListing>>(emptyMap())

    /**
     * The hosts shown that came from the home server's host list, by label, as the list has
     * them: every address, the icon's name, and where the configuration file declares one
     * that it declares. It follows the list as it changes.
     */
    val hostListings: StateFlow<Map<String, HostListing>> get() = mutableHostListings

    private val mutableClientLabel = MutableStateFlow(workspace.clientLabel ?: info.label.orEmpty())

    /**
     * What this app calls itself in each server's list of clients (`rex client ls`), or empty
     * for no label. It is the one someone chose here, and failing that the one the app was
     * started with: in a browser, the name of the device the page is on.
     */
    val clientLabel: StateFlow<String> get() = mutableClientLabel

    /**
     * Calls this app [label] on every server it is connected to, and on the ones it connects
     * to later. Kept in the workspace. Empty goes back to the label the app was started with.
     * Returns null once the servers have it, and otherwise the first refusal, in the server's
     * words: a label can be 64 bytes at most, and can't have control characters.
     */
    suspend fun setClientLabel(label: String): String? {
        val chosen = label.trim()
        val effective = chosen.ifEmpty { info.label.orEmpty() }
        var refusal: String? = null
        for (host in mutableHosts.value.filter { it.state.value.connection == HostConnection.Connected }) {
            runCatching { host.setClientLabel(effective) }.onFailure {
                if (it is RexError && refusal == null) refusal = it.message
            }
        }
        if (refusal != null) return refusal
        mutableClientLabel.value = effective
        workspace.clientLabel = chosen.takeIf { it.isNotEmpty() }
        workspace.save()
        log.add("app", if (effective.isEmpty()) "this client has no label now" else "this client is labelled $effective")
        return null
    }

    private val mutableHostOrder = MutableStateFlow(workspace.hostOrder)

    /** The order someone arranged the hosts in, by label: see [Workspace.hostOrder]. */
    val hostOrder: StateFlow<List<String>> get() = mutableHostOrder

    /** Arranges the hosts. Applies at once, and is remembered, for this app alone. */
    fun setHostOrder(labels: List<String>) {
        val order = labels.distinct()
        if (mutableHostOrder.value == order) return
        mutableHostOrder.value = order
        workspace.hostOrder = order
        workspace.save()
    }

    /** Where a host shown here is kept. Null for a label that isn't a host's. */
    fun sourceOf(label: String): HostSource? {
        val host = mutableHosts.value.firstOrNull { it.saved.label == label } ?: return null
        return when {
            host === home -> HostSource.Home
            !host.discovered -> HostSource.ThisApp
            mutableHostListings.value[label]?.source != null -> HostSource.ConfigurationFile
            else -> HostSource.HomeList
        }
    }

    init {
        // The first time the app runs there is one host, the local server. It is written to
        // the workspace like any other, so the file shows what the app connects to.
        if (workspace.hosts.isEmpty()) {
            workspace.hosts = listOf(SavedHost("Local", defaultLocalEndpoint()))
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
    fun addHost(label: String, endpoint: String, icon: String? = null) {
        scope.launch { addHostNow(label, endpoint, icon) }
    }

    /**
     * Adds a host as [addHost] does, and says how it went: null once it is added, and
     * otherwise why it wasn't, in words to show beside what was typed.
     *
     * @param icon the name of a picture for it, which only the home server's list can keep
     */
    suspend fun addHostNow(label: String, endpoint: String, icon: String? = null): String? {
        val name = label.trim()
        val address = endpoint.trim()
        if (name.isEmpty()) return "A host needs a name."
        if (address.isEmpty() || runCatching { RexEndpoint.parse(address) }.isFailure) {
            log.add("hosts", "can't add $address: not an address Rex can reach", isError = true)
            return "That isn't an address Rex can reach."
        }
        if (mutableHosts.value.any { it.saved.label.equals(name, ignoreCase = true) }) {
            log.add("hosts", "$name ($address) is already a host")
            return "There is a host called $name already."
        }
        if (isConfigured(address)) {
            log.add("hosts", "$name ($address) is already a host")
            return "That address is a host already."
        }
        val home = home?.takeIf { it.state.value.connection == HostConnection.Connected }
        if (home == null) {
            addSavedHost(name, address)
            return null
        }
        // Ask the server who it is: the home server under its tailnet name is not another host.
        val serverId = serverHostIdAt(address)
        if (serverId != null && serverId == home.state.value.serverHostId) {
            ownAddresses += endpointKey(address)
            log.add("hosts", "$address is ${home.saved.label}, which is already here")
            return "That address is ${home.saved.label}, which is here already."
        }
        runCatching {
            home.call("hosts.put", buildJsonObject {
                putJsonObject("host") {
                    put("label", name)
                    putJsonArray("endpoints") { add(address) }
                    serverId?.let { put("host_id", it) }
                    icon?.takeIf { it.isNotBlank() }?.let { put("icon", it) }
                    put("added_from", info.name)
                }
            })
        }.onSuccess {
            log.add("hosts", "added $name ($address) to ${home.saved.label}'s host list")
            syncHostList(home)
            if (mutableHosts.value.any { it.saved.label == name }) selectHost(name)
        }.onFailure {
            // A label the configuration file uses, say: that is the person's to put right.
            if (it is RexError && it.code != "not_supported") {
                log.add("hosts", "${home.saved.label} wouldn't keep $name: ${it.message}", isError = true)
                return it.message
            }
            // A server without a state directory can't keep hosts. This app can.
            log.add("hosts", "${home.saved.label} couldn't keep $name (${it.message}); saving it in this app instead", isError = true)
            addSavedHost(name, address)
        }
        return null
    }

    /**
     * Changes a host: its name, its addresses, in the order to try them, and its picture.
     * Returns null once it is changed, and otherwise why it wasn't, in the server's words
     * where the server refused (a label another host has, an address it can't read).
     *
     * A host from the home server's host list is stored there under its id, so every device
     * that follows the home sees the change. For one the configuration file declares, what is
     * stored takes the place of the declared one; the file isn't written. A host of this
     * app's alone has one address and no picture, and is changed in the workspace.
     */
    suspend fun changeHost(label: String, newLabel: String, endpoints: List<String>, icon: String?): String? {
        val host = mutableHosts.value.firstOrNull { it.saved.label == label } ?: return "$label isn't a host any more."
        if (host === home) return "$label is the home server, which this app follows as it is."
        val name = newLabel.trim()
        val addresses = endpoints.map { it.trim() }.filter { it.isNotEmpty() }
        if (name.isEmpty()) return "A host needs a name."
        if (addresses.isEmpty()) return "A host needs an address."
        val listing = mutableHostListings.value[label]
        // What the home server keeps is the home server's to judge: a name another host has, an
        // address it can't read. Its refusal names the host in the way, and the line that declared it.
        if (host.discovered) {
            val home = home?.takeIf { it.state.value.connection == HostConnection.Connected } ?: return "The home server isn't connected, and it keeps this host."
            if (listing?.id == null) return "The home server's list has no id for this host."
            runCatching {
                home.call("hosts.put", buildJsonObject { put("host", changedHostEntry(listing, name, addresses, icon)) })
            }.onFailure {
                log.add("hosts", "couldn't change $label: ${it.message}", isError = true)
                return it.message ?: "The home server refused."
            }
            log.add("hosts", "changed $label in ${home.saved.label}'s host list")
            renamed(label, name)
            syncHostList(home)
            return null
        }
        // This app's own: the workspace has it, with the one address, and there is nobody else to judge it.
        addresses.firstOrNull { runCatching { RexEndpoint.parse(it) }.isFailure }?.let { return "$it isn't an address Rex can reach." }
        if (mutableHosts.value.any { it !== host && it.saved.label.equals(name, ignoreCase = true) }) return "There is a host called $name already."
        val saved = SavedHost(name, addresses.first())
        if (saved == host.saved) return null
        workspace.hosts = workspace.hosts.map { if (it.label == label) saved else it }
        workspace.sessions.remove(label)?.let { workspace.sessions[name] = it }
        host.close()
        val changed = host(saved, discovered = false)
        mutableHosts.update { current -> current.map { if (it === host) changed else it } }
        renamed(label, name)
        if (mutableSelectedHost.value == label) selectHost(name) else workspace.save()
        log.add("hosts", "changed $label in this app's workspace")
        return null
    }

    /** A host has a new name: what was kept under the old one follows it. */
    private fun renamed(from: String, to: String) {
        if (from == to) return
        if (from in mutableHostOrder.value) setHostOrder(mutableHostOrder.value.map { if (it == from) to else it })
        if (mutableSelectedHost.value == from) renamedSelection = to
    }

    /** The name a host that was showing is about to be listed under, so that it goes on showing. */
    @Volatile private var renamedSelection: String? = null

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
        // Until the server answers, and whenever it can't be reached: the defaults as they
        // are, with the shortcuts changed in this app.
        mutableKeymap.value = resolveLocally(defaults, mutableShortcuts.value)
        resolvedShortcuts = mutableShortcuts.value
        keymapRefresh.trySend(Unit)
    }

    private suspend fun resolveKeymap() {
        if (declaredActions.isEmpty()) return
        val overrides = mutableShortcuts.value
        val home = home?.takeIf { it.state.value.connection == HostConnection.Connected }
        if (home == null) {
            // Nobody to ask. A change made meanwhile still applies, over the defaults alone.
            if (resolvedShortcuts != overrides) mutableKeymap.value = resolveLocally(defaultBindings, overrides)
            resolvedShortcuts = overrides
            return
        }
        runCatching { home.keymap(declaredActions, defaultBindings, overrides) }
            .onSuccess { resolved ->
                mutableKeymap.value = resolved
                resolvedShortcuts = overrides
                val recorded = resolved.bindings.count { it.source == SHORTCUT_LAYER }
                val configured = resolved.bindings.count { it.source != "default" && it.source != SHORTCUT_LAYER }
                log.add("keys", "keymap from ${home.saved.label}: ${resolved.bindings.size} bindings, $configured from the configuration file, $recorded bound and ${overrides.removals.size} removed in this app, ${resolved.serverActions.size} server actions")
                // Only what is wrong with this app's own layer. A person can put that right here.
                for (problem in resolved.diagnostics.filter { it.source == SHORTCUT_LAYER }) {
                    log.add("keys", "shortcuts changed in this app: ${problem.message}", isError = problem.severity == "error")
                }
            }
            .onFailure { log.add("keys", "couldn't read the keymap: ${it.message}", isError = true) }
    }

    /** The layer the keymap on show was resolved with. */
    @Volatile private var resolvedShortcuts: ShortcutOverrides? = null

    /**
     * Each of [targets] with the keys it has now and where they come from: the defaults, the
     * configuration file, or this app.
     */
    fun shortcutList(targets: List<ShortcutTarget>): List<Shortcut> = shortcutList(targets, mutableKeymap.value, mutableShortcuts.value)

    /** What else a key is bound to now. [setShortcut] takes those off it, so ask before calling it when there are any. */
    fun shortcutConflicts(action: String, args: JsonObject?, chord: KeyChord): List<KeyBinding> =
        mutableShortcuts.value.conflicts(action, args, chord, mutableKeymap.value)

    /** Gives an action one shortcut, for this app alone, in place of whatever it had. Applies at once, and is remembered. */
    fun setShortcut(action: String, args: JsonObject?, chord: KeyChord) {
        changeShortcuts("$action${args.shown()} set to ${chord.toWire()}") { it.set(action, args, chord, mutableKeymap.value) }
    }

    /** Leaves an action with no shortcut in this app, so that its keys reach the terminal. */
    fun removeShortcut(action: String, args: JsonObject?) {
        changeShortcuts("$action${args.shown()} left with no shortcut") { it.remove(action, args, mutableKeymap.value) }
    }

    /** The keys an action has when nothing has changed them, as this app declares them. */
    fun defaultShortcuts(action: String, args: JsonObject?): List<KeyBinding> = defaultBindings.filter { it.isFor(action, args) }

    /** The bindings made in this app that sit on an action's default keys. [resetShortcut] takes them off. */
    fun shortcutResetConflicts(action: String, args: JsonObject?): List<ShortcutBinding> =
        mutableShortcuts.value.resetConflicts(action, args, defaultBindings)

    /** Forgets what was changed in this app about an action, so it has its default keys, or the configuration file's. */
    fun resetShortcut(action: String, args: JsonObject?) {
        changeShortcuts("$action${args.shown()} reset") { it.reset(action, args, defaultBindings) }
    }

    /** Forgets everything changed in this app, so that every action has its default keys, or the configuration file's. */
    fun resetAllShortcuts() {
        changeShortcuts("all reset") { ShortcutOverrides() }
    }

    private fun JsonObject?.shown() = this?.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()

    private fun changeShortcuts(what: String, change: (ShortcutOverrides) -> ShortcutOverrides) {
        val before = mutableShortcuts.value
        val after = change(before)
        if (after == before) return
        mutableShortcuts.value = after
        workspace.shortcuts = after
        workspace.save()
        log.add("keys", "shortcut changed: $what")
        keymapRefresh.trySend(Unit)
    }

    fun selectHost(label: String) {
        mutableSelectedHost.value = label
        workspace.lastHost = label
        workspace.save()
        tellHostsWhichIsInFront()
    }

    @Volatile private var windowFocused = false

    /**
     * Says whether the app's window has focus. While it does, the host showing claims the
     * terminals on screen, so that they take their size from the client being used.
     */
    fun setWindowFocused(focused: Boolean) {
        windowFocused = focused
        tellHostsWhichIsInFront()
    }

    private fun tellHostsWhichIsInFront() {
        val showing = mutableSelectedHost.value
        mutableHosts.value.forEach { it.setInFront(windowFocused && it.saved.label == showing) }
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
        onNews = { mutableTabNews.tryEmit(it) },
        onProgramCopy = { mutableProgramCopies.tryEmit(it) },
        rememberedTabs = workspace.tabs.toMap(),
        onTabShown = { sessionId, windowId ->
            val before = workspace.tabs[sessionId]
            if (windowId == null) workspace.tabs.remove(sessionId) else workspace.tabs[sessionId] = windowId
            if (before != windowId) workspace.save()
        },
        // The keymap belongs to the home server; read it when that connects or its configuration changes.
        onServerEvent = { type ->
            // A label chosen here isn't the one the connection opened with, so each new connection is told it.
            if (type == "connected" && workspace.clientLabel != null) scope.launch {
                mutableHosts.value.firstOrNull { it.saved == saved }?.let { host -> runCatching { host.setClientLabel(mutableClientLabel.value) } }
            }
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
            val endpoints = (entry["endpoints"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
            ListedHost(entry.string("id"), label, endpoint, entry.string("host_id"), entry.string("icon"), endpoints, entry.string("source"), entry.string("added_from"), entry)
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
        // A host renamed here while it was showing goes on showing, under its new name.
        renamedSelection?.let { name ->
            renamedSelection = null
            if (mutableHosts.value.any { it.saved.label == name }) selectHost(name)
        }
        // Read afresh each time, so an icon changed with `rex hosts edit` shows without a restart.
        mutableHostIcons.value = hostIconsByLabel(entries, mutableHosts.value.map { it.saved }, home.saved.label, ownAddresses)
        // The hosts shown that came from the list, as the list has them now.
        val fromList = mutableHosts.value.filter { it.discovered }.map { it.saved.label }.toSet()
        mutableHostListings.value = entries.filter { it.label in fromList }
            .associate { it.label to HostListing(it.id, it.label, it.endpoints, it.icon?.trim()?.takeIf { icon -> icon.isNotEmpty() }, it.source, it.hostId, it.addedFrom, it.raw) }
    }

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

/** One host of the home server's host list, as far as this app reads it. [endpoint] is the first of [endpoints], the one this app connects to. */
internal class ListedHost(
    val id: String?, val label: String, val endpoint: String, val hostId: String?, val icon: String? = null,
    val endpoints: List<String> = listOf(endpoint), val source: String? = null, val addedFrom: String? = null,
    /** The entry whole, as the list gave it, with whatever fields this app doesn't read. */
    val raw: JsonObject? = null,
)

/**
 * A host shown here as the home server's host list has it, for showing and changing it.
 *
 * @param id its id in the list, which a change names it by
 * @param endpoints its addresses, in the order to try them. This app connects to the first.
 * @param icon the list's name for its picture, as written
 * @param source where Rex's configuration file declares it, such as `init.lua:12`, or null for one stored through the API
 * @param addedFrom the device or client that added it, for showing
 */
data class HostListing(
    val id: String?, val label: String, val endpoints: List<String>, val icon: String?, val source: String?, val hostId: String? = null, val addedFrom: String? = null,
    /** The entry whole, as the list gave it. A change sends it back with what changed, so that fields this app doesn't know are kept. */
    val raw: JsonObject? = null,
)

/**
 * A host as `hosts.put` takes it, to store in place of [listing]: everything the list had
 * for it, known to this app or not, with its name, addresses and picture as given. What the
 * server sets itself (where the configuration file declares it, when it last changed) is
 * left for the server to set.
 */
internal fun changedHostEntry(listing: HostListing, label: String, endpoints: List<String>, icon: String?): JsonObject = buildJsonObject {
    for ((name, value) in listing.raw.orEmpty()) if (name !in setOf("label", "endpoints", "icon", "source", "updated_at")) put(name, value)
    listing.id?.let { put("id", it) }
    put("label", label)
    putJsonArray("endpoints") { endpoints.forEach { add(it) } }
    icon?.takeIf { it.isNotBlank() }?.let { put("icon", it) }
}

/** Where a host shown here is kept, which is what a change to it touches. */
enum class HostSource {
    /** The server this app follows. It is in this app's workspace, and stays. */
    Home,

    /** The home server's host list, stored there: every device that follows the home sees it. */
    HomeList,

    /** Declared in Rex's configuration file on the home server, and listed from there. */
    ConfigurationFile,

    /** This app's workspace alone. */
    ThisApp,
}

/**
 * The labels of the hosts that aren't the home server, in the order to list them, after the
 * one called [label] has moved to stand before [before], or to the end for null. For keeping
 * as the workspace's order. Unchanged if [label] isn't among them.
 */
fun hostOrderAfterMove(labels: List<String>, label: String, before: String?): List<String> {
    if (label !in labels || before == label) return labels
    val rest = labels - label
    val at = before?.let(rest::indexOf)?.takeIf { it >= 0 } ?: rest.size
    return rest.take(at) + label + rest.drop(at)
}

/** The same, after [label] has moved one place up or down. Unchanged at either end. */
fun hostOrderAfterStep(labels: List<String>, label: String, up: Boolean): List<String> {
    val at = labels.indexOf(label)
    val to = at + if (up) -1 else 1
    if (at < 0 || to !in labels.indices) return labels
    return labels.toMutableList().apply { this[at] = labels[to]; this[to] = label }
}

/**
 * Which icon the host list gives each host shown, by the label it is shown under.
 *
 * A listed host is the one shown with its label or its address, and that covers a host saved
 * in this app's workspace that the list names too. One whose address turned out to be the
 * home server ([ownAddresses], as [endpointKey]s) gives its icon to the home server, which is
 * shown under [homeLabel] and not under the name the list has for it. Where two listed hosts
 * are the same host here, the first with an icon counts.
 */
internal fun hostIconsByLabel(listed: List<ListedHost>, shown: List<SavedHost>, homeLabel: String, ownAddresses: Set<String>): Map<String, String> = buildMap {
    for (entry in listed) {
        val icon = entry.icon?.trim()?.takeIf { it.isNotEmpty() } ?: continue
        val key = endpointKey(entry.endpoint)
        val label = if (key in ownAddresses) homeLabel
        else shown.firstOrNull { it.label.equals(entry.label, ignoreCase = true) || endpointKey(it.endpoint) == key }?.label ?: continue
        if (label !in this) put(label, icon)
    }
}
