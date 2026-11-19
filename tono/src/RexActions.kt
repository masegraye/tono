package org.example.kotlintest

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.example.kotlintest.rex.ActivityState
import kotlinx.coroutines.launch
import org.example.kotlintest.rex.KeyBinding
import org.example.kotlintest.rex.KeyChord
import org.example.kotlintest.rex.RexAction
import org.example.kotlintest.rex.SplitDirection

/**
 * Something this app can do by name. Names, titles and categories are the ones Rex.app
 * uses, so a key bound to `client.tab.new` in Rex's configuration file means the same here.
 *
 * @param keys the keys bound to it by default. They follow Rex.app's, with Control in place
 *   of Command, and Shift added where Control alone belongs to the terminal (Control+T
 *   reaches the shell; Control+Shift+T opens a tab). A few have a second key with Alt, for
 *   a browser tab, where the browser keeps the first for itself.
 * @param argKeys default keys that run the action with arguments, such as Control+3 for tab 3
 * @param inPalette false for actions that only make sense with arguments
 */
class ClientAction(
    val name: String,
    val title: String,
    val category: String,
    val keywords: String = "",
    val keys: List<String> = emptyList(),
    val argKeys: List<Pair<String, JsonObject>> = emptyList(),
    val inPalette: Boolean = true,
    val available: RexUiState.() -> Boolean = { true },
    /** For an action that switches something: how it stands now, shown beside it in the palette. */
    val state: (RexUiState.() -> String)? = null,
    val perform: RexUiState.(args: JsonObject?) -> Unit,
)

/** The app's actions, and the means to run them from a key, the palette, or by name. */
class RexCommands(private val ui: RexUiState) {
    val actions: List<ClientAction> = clientActions()
    private val byName = actions.associateBy { it.name }

    /** Declares the actions and their default keys to Rex, which answers with the keymap to use. */
    fun declare() = ui.client.declareKeymap(
        actions = actions.map { RexAction(it.name, it.title, it.category, it.keywords) },
        defaults = actions.flatMap { action ->
            action.keys.map { KeyBinding(listOf(KeyChord.parse(it)!!), action.name) } +
                action.argKeys.map { (keys, args) -> KeyBinding(listOf(KeyChord.parse(keys)!!), action.name, args) }
        },
    )

    /**
     * Runs whatever is bound to a key press. Returns false when nothing is, so the key goes
     * to the terminal instead.
     */
    fun handleKey(chord: KeyChord): Boolean {
        for (binding in ui.client.keymap.value.bindingsFor(chord)) {
            // Bound to a function in the configuration file: the server runs it when it gets the key.
            if (binding.action == FORWARD_TO_SERVER) return false
            if (run(binding.action, binding.args, origin = chord.display())) return true
        }
        return false
    }

    /** Runs an action by name. Returns false if there is no such action, or it can't run right now. */
    fun run(name: String, args: JsonObject? = null, origin: String): Boolean {
        val action = byName[name]
        if (action != null) {
            if (!action.available(ui)) return false
            ui.client.log.add("app", "action $name${args?.let { " $it" }.orEmpty()} ($origin)")
            action.perform(ui, args)
            return true
        }
        // Not one of this app's: an action of the server's, such as clearing the terminal.
        if (ui.client.keymap.value.serverActions.none { it.name == name }) return false
        val host = ui.host ?: return false
        ui.client.log.add("app", "server action $name ($origin)")
        host.performServerAction(name, args, ui.focusedBlockId)
        return true
    }

    fun isAvailable(action: ClientAction): Boolean = action.available(ui)

    private companion object {
        const val FORWARD_TO_SERVER = "client.key.forward"
    }
}

private val hasHost: RexUiState.() -> Boolean = { hostState?.connection == org.example.kotlintest.rex.HostConnection.Connected }
private val hasSession: RexUiState.() -> Boolean = { view != null }
private val hasPane: RexUiState.() -> Boolean = { focusedBlockId != null }
private val hasTerminal: RexUiState.() -> Boolean = { focusedTerminal != null }

private fun index(value: Int) = buildJsonObject { put("index", value) }

internal fun clientActions(): List<ClientAction> = buildList {
    // App
    add(ClientAction("client.palette.toggle", "Command Palette…", "App", "commands actions run search", keys = listOf("ctrl+shift+p")) {
        if (palette == null) palette = PaletteMode.Commands else closePalette()
    })
    add(ClientAction("client.showcase.toggle", "UI Showcase", "App", "compose components demo gallery") {
        showcase = !showcase
    })

    // Session
    add(ClientAction("session.new", "New Session", "Session", "create", keys = listOf("ctrl+shift+n", "ctrl+alt+n"), available = hasHost) {
        host?.newSession()
    })
    add(ClientAction("session.close", "Close Session", "Session", "kill destroy delete end", available = hasSession) {
        val session = view ?: return@ClientAction
        val owner = host ?: return@ClientAction
        val tabs = session.windows.size
        confirmation = Confirmation(
            title = "Close session “${session.label}”?",
            text = if (tabs == 0) "It has no tabs." else "Its $tabs tab${if (tabs == 1) "" else "s"} will close, and the programs running in them will end.",
            confirmLabel = "Close Session",
            onConfirm = { owner.closeSession(session.sessionId) },
        )
    })
    add(ClientAction("session.switch", "Change Session…", "Session", "go to switch find", keys = listOf("ctrl+k")) {
        palette = PaletteMode.Sessions
    })
    add(ClientAction("session.select", "Go to Session", "Session", inPalette = false) { args ->
        val sessionId = args?.get("session_id")?.jsonPrimitive?.content ?: return@ClientAction
        client.hosts.value.firstOrNull { host -> host.state.value.sessions.any { it.id == sessionId } }
            ?.let { go(NavigationStop(it.saved.label, sessionId)) }
    })
    // Sessions fold away in the sidebar, which also takes their tabs out of the numbering.
    add(ClientAction("session.collapse", "Collapse Session", "Session", "fold hide tabs", available = hasSession) { args ->
        (args?.get("session_id")?.jsonPrimitive?.content ?: hostState?.selectedSessionId)?.let { setSessionCollapsed(it, true) }
    })
    add(ClientAction("session.expand", "Expand Session", "Session", "unfold show tabs", available = hasSession) { args ->
        (args?.get("session_id")?.jsonPrimitive?.content ?: hostState?.selectedSessionId)?.let { setSessionCollapsed(it, false) }
    })
    add(ClientAction("session.collapse_others", "Collapse Other Sessions", "Session", "fold hide focus", available = hasSession) { args ->
        (args?.get("session_id")?.jsonPrimitive?.content ?: hostState?.selectedSessionId)?.let(::collapseSessionsExcept)
    })
    add(ClientAction("session.next", "Next Session", "Session", "cycle") { stepSession(forward = true) })
    add(ClientAction("session.previous", "Previous Session", "Session", "cycle") { stepSession(forward = false) })

    // Host
    add(ClientAction("client.host.switch", "Switch Host", "Host", "server machine", keys = listOf("ctrl+shift+h")) {
        palette = PaletteMode.Hosts
    })

    add(ClientAction("client.host.add", "Add Host…", "Host", "server machine connect tailscale tailnet new") {
        palette = PaletteMode.AddHost
    })
    add(ClientAction("client.host.remove", "Remove Host…", "Host", "server machine disconnect forget delete", available = { client.hosts.value.any { it.saved.label != client.homeLabel } }) {
        palette = PaletteMode.RemoveHost
    })

    // Tab
    add(ClientAction("client.tab.new", "New Tab", "Tab", "create window", keys = listOf("ctrl+shift+t", "ctrl+alt+t"), available = hasSession) {
        host?.newTab()
    })
    add(ClientAction("client.tab.close", "Close Tab", "Tab", "window", keys = listOf("ctrl+alt+w"), available = { window != null }) {
        val tab = window ?: return@ClientAction
        val owner = host ?: return@ClientAction
        confirmation = Confirmation(
            title = "Close tab “${owner.state.value.nameOf(tab).text}”?",
            text = (if (tab.panes.size > 1) "Its ${tab.panes.size} panes will close, and the programs running in them will end."
            else "The program running in it will end.") +
                if (owner.isLastTab(tab.id)) " It is the last tab of ${view?.label ?: "its session"}, so the session closes too." else "",
            confirmLabel = "Close Tab",
            onConfirm = { owner.closeWindow(tab.id) },
        )
    })
    add(ClientAction("client.tab.rename", "Rename Tab…", "Tab", "name label title window", available = { window != null }) {
        val tab = window ?: return@ClientAction
        val owner = host ?: return@ClientAction
        renameTab(owner, owner.state.value.selectedSessionId ?: return@ClientAction, tab)
    })
    // Tabs are one list across every session and host, in sidebar order, so "next" carries
    // on from a session's last tab into the next session's first.
    add(ClientAction("client.tab.next", "Show Next Tab", "Tab", "cycle", keys = listOf("ctrl+tab", "ctrl+pagedown", "ctrl+shift+]")) {
        stepTab(forward = true)
    })
    add(ClientAction("client.tab.previous", "Show Previous Tab", "Tab", "cycle", keys = listOf("ctrl+shift+tab", "ctrl+pageup", "ctrl+shift+[")) {
        stepTab(forward = false)
    })
    add(ClientAction(
        "client.tab.goto", "Go to Tab", "Tab", inPalette = false,
        // As in Rex.app: 1 to 8 are the first eight tabs in sidebar order, whichever session
        // each is in, and 9 is always the last one.
        argKeys = (1..8).map { "ctrl+$it" to index(it) } + ("ctrl+9" to index(-1)),
    ) { args ->
        args?.get("index")?.jsonPrimitive?.int?.let(::goToTab)
    })

    // Pane
    for ((direction, keys) in listOf(
        SplitDirection.Right to listOf("ctrl+shift+d"),
        SplitDirection.Down to listOf("ctrl+alt+d"),
        SplitDirection.Left to emptyList(),
        SplitDirection.Up to emptyList(),
    )) {
        val name = direction.name.lowercase()
        add(ClientAction("pane.split.$name", "Split Pane ${direction.name}", "Pane", "divide new terminal", keys = keys, available = hasPane) {
            focusedBlockId?.let { host?.split(it, direction) }
        })
    }
    add(ClientAction("pane.split", "Split Pane", "Pane", inPalette = false, available = hasPane) { args ->
        val direction = SplitDirection.entries.firstOrNull { it.name.equals(args?.get("direction")?.jsonPrimitive?.content, ignoreCase = true) }
        focusedBlockId?.let { host?.split(it, direction ?: SplitDirection.Right) }
    })
    for (direction in listOf("left", "right", "up", "down")) {
        add(ClientAction(
            "pane.focus.$direction", "Focus Pane ${direction.replaceFirstChar { it.uppercase() }}", "Pane", "move select",
            keys = listOf("ctrl+alt+$direction"), available = { (window?.panes?.size ?: 0) > 1 },
        ) { host?.focusDirection(direction) })
    }
    add(ClientAction("pane.focus", "Focus Pane", "Pane", inPalette = false) { args ->
        args?.get("direction")?.jsonPrimitive?.content?.let { host?.focusDirection(it) }
    })
    // Rex.app has these on Command+] and Command+[. Control+[ is Escape to a terminal, so
    // here they are on Alt, where the keys otherwise only start an escape sequence.
    add(ClientAction("pane.focus_next", "Focus Next Pane", "Pane", "cycle", keys = listOf("alt+]"), available = { (window?.panes?.size ?: 0) > 1 }) { host?.stepPane(forward = true) })
    add(ClientAction("pane.focus_previous", "Focus Previous Pane", "Pane", "cycle", keys = listOf("alt+["), available = { (window?.panes?.size ?: 0) > 1 }) { host?.stepPane(forward = false) })
    add(ClientAction("pane.zoom", "Zoom Pane", "Pane", "maximize fullscreen restore", keys = listOf("ctrl+shift+enter"), available = hasPane) {
        focusedBlockId?.let { host?.toggleZoom(it) }
    })
    add(ClientAction("pane.close", "Close Pane", "Pane", "kill terminal", keys = listOf("ctrl+shift+w"), available = hasPane) {
        val blockId = focusedBlockId ?: return@ClientAction
        val owner = host ?: return@ClientAction
        confirmation = Confirmation(
            title = "Close this pane?",
            text = "The program running in it will end." +
                if (window?.panes?.size == 1 && window?.let { owner.isLastTab(it.id) } == true) " It is the last of ${view?.label ?: "its session"}, so the session closes too." else "",
            confirmLabel = "Close Pane",
            onConfirm = { owner.closeBlock(blockId) },
        )
    })
    add(ClientAction("pane.paste", "Paste into Pane", "Pane", "clipboard insert", keys = listOf("ctrl+shift+v"), available = hasTerminal) {
        val blockId = focusedBlockId ?: return@ClientAction
        val owner = host ?: return@ClientAction
        // A browser hands the clipboard over only when it is ready to, so this doesn't wait.
        scope?.launch { clipboardText()?.takeIf { it.isNotEmpty() }?.let { owner.paste(blockId, it) } }
    })
    add(ClientAction("pane.copy", "Copy from Pane", "Pane", "copy selection clipboard text terminal", keys = listOf("ctrl+shift+c"), available = hasTerminal) {
        val terminal = focusedTerminal ?: return@ClientAction
        scope?.launch {
            val text = terminal.selectedText()
            // Nothing selected leaves the clipboard as it was.
            if (text.isEmpty()) return@launch
            val refusal = setClipboardText(text)
            if (refusal == null) client.log.add("clipboard", "copied ${text.length} characters from the terminal")
            else client.log.add("clipboard", "couldn't copy from the terminal: $refusal", isError = true)
        }
    })
    add(ClientAction("pane.select_all", "Select All in Pane", "Pane", "select all selection text terminal scrollback", keys = listOf("ctrl+shift+a"), available = hasTerminal) {
        focusedTerminal?.selectAll()
    })
    add(ClientAction("client.scroll.page_up", "Scroll Page Up", "Pane", "history scrollback", keys = listOf("shift+pageup"), available = hasTerminal) {
        focusedTerminal?.let { terminal -> terminal.scroll(-((terminal.grid.value?.rows ?: 24) - 1)) }
    })
    add(ClientAction("client.scroll.page_down", "Scroll Page Down", "Pane", "history scrollback", keys = listOf("shift+pagedown"), available = hasTerminal) {
        focusedTerminal?.let { terminal -> terminal.scroll((terminal.grid.value?.rows ?: 24) - 1) }
    })

    // View
    add(ClientAction("client.font.increase", "Increase Font Size", "View", "bigger zoom in text", keys = listOf("ctrl+=", "ctrl+shift+=")) {
        fontSize = (fontSize + 1).coerceIn(RexUiState.FONT_SIZES)
    })
    add(ClientAction("client.font.decrease", "Decrease Font Size", "View", "smaller zoom out text", keys = listOf("ctrl+-")) {
        fontSize = (fontSize - 1).coerceIn(RexUiState.FONT_SIZES)
    })
    add(ClientAction("client.font.reset", "Reset Font Size", "View", "default zoom text", keys = listOf("ctrl+0")) {
        fontSize = RexUiState.DEFAULT_FONT_SIZE
    })
    add(ClientAction("client.sidebar.toggle", "Toggle Sidebar", "View", "hide show collapse hosts sessions tabs", keys = listOf("ctrl+shift+/")) {
        sidebarVisible = !sidebarVisible
    })
    add(ClientAction(
        "client.sidebar.activity.toggle", "Toggle Activity", "View",
        "activity filter working waiting attention programs status sidebar tabs",
        keys = listOf("ctrl+shift+."),
        state = { if (activityOnly) "on" else "off" },
    ) {
        activityOnly = !activityOnly
        if (activityOnly) sidebarVisible = true // it is a way of looking at the sidebar
    })
    add(ClientAction(
        "client.setting.notifications.activity.sounds", "Play Activity Sounds", "Notifications",
        "sound audio chime alert beep bell mute silence program finished blocked error",
        state = { if (soundsEnabled) "on" else "off" },
    ) {
        setSounds(!soundsEnabled)
        client.log.add("sound", "activity sounds ${if (soundsEnabled) "on" else "off"}")
        // Turning them on plays one, so you know what to listen for and that it works here.
        if (soundsEnabled) sounds?.request(ActivityState.Done)
    })
    // The switches for the kinds of news a program's own state makes (waiting, failed, done) are
    // in the workspace file until there is a settings screen. These two are Rex.app's actions.
    add(ClientAction(
        "client.setting.notifications.activity.system_notifications", "Show System Notifications", "Notifications",
        "desktop notification notify alert popup banner program finished blocked error needs allow permission",
        state = {
            when {
                !notifications.desktop -> "off"
                else -> when (notificationAccess()) {
                    NotificationAccess.Granted -> "on"
                    NotificationAccess.Undecided -> "on, once the browser allows it"
                    NotificationAccess.Refused -> "blocked in the browser's settings"
                    NotificationAccess.Unavailable -> "on, with nothing here to show them"
                }
            }
        },
    ) {
        // On, and the browser not asked yet: what is wanted is the question, not turning them off.
        val ask = notificationAccess() == NotificationAccess.Undecided
        if (!(notifications.desktop && ask)) notifications = notifications.copy(desktop = !notifications.desktop)
        client.log.add("notify", "notifications ${if (notifications.desktop) "on" else "off"}")
        when {
            !notifications.desktop -> notificationQuestion = false
            ask -> notifier.askAccess() // this runs from a key or a click, which is when a browser will ask
            else -> notifier.showExample()
        }
    })
    add(ClientAction(
        // Rex.app files this one under macOS, where it is the system that shows them. The name is kept, so a key bound to it means the same here.
        "client.setting.notifications.macos.terminal_notifications", "Terminal Notifications", "Notifications",
        "desktop notification notify program message osc 9 777 terminal asks",
        state = { if (notifications.programMessages) "on" else "off" },
    ) {
        notifications = notifications.copy(programMessages = !notifications.programMessages)
        client.log.add("notify", "notifications that programs ask for ${if (notifications.programMessages) "on" else "off"}")
    })
    // Only where the app is what asks for it, which is a browser. There it is also the way to
    // get every key: a browser keeps some for itself in a tab, and gives them up in full screen.
    fullScreen?.let { screen ->
        add(ClientAction(
            "client.window.fullscreen.toggle", "Toggle Full Screen", "Window", "fullscreen enter exit window browser keys shortcuts",
            keys = listOf("f11"),
            state = { if (screen.isOn) "on" else "off" },
        ) {
            screen.toggle()
        })
    }
    // Not in Rex.app: this app's log of what it and the servers are doing.
    add(ClientAction("client.debug_log.toggle", "Toggle Debug Log", "View", "events console diagnostics", keys = listOf("ctrl+`")) {
        debugLogVisible = !debugLogVisible
    })
}
