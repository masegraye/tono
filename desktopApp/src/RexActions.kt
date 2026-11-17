package org.example.kotlintest

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.example.kotlintest.rex.KeyBinding
import org.example.kotlintest.rex.KeyChord
import org.example.kotlintest.rex.RexAction
import org.example.kotlintest.rex.SplitDirection
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

/**
 * Something this app can do by name. Names, titles and categories are the ones Rex.app
 * uses, so a key bound to `client.tab.new` in Rex's configuration file means the same here.
 *
 * @param keys the keys bound to it by default. They follow Rex.app's, with Control in place
 *   of Command, and Shift added where Control alone belongs to the terminal (Control+T
 *   reaches the shell; Control+Shift+T opens a tab).
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
    add(ClientAction("session.new", "New Session", "Session", "create", keys = listOf("ctrl+shift+n"), available = hasHost) {
        host?.newSession()
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
    add(ClientAction("client.tab.new", "New Tab", "Tab", "create window", keys = listOf("ctrl+shift+t"), available = hasSession) {
        host?.newTab()
    })
    add(ClientAction("client.tab.close", "Close Tab", "Tab", "window", keys = listOf("ctrl+alt+w"), available = { window != null }) {
        val tab = window ?: return@ClientAction
        val owner = host ?: return@ClientAction
        confirmation = Confirmation(
            title = "Close tab “${owner.state.value.nameOf(tab).text}”?",
            text = if (tab.panes.size > 1) "Its ${tab.panes.size} panes will close, and the programs running in them will end."
            else "The program running in it will end.",
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
            text = "The program running in it will end.",
            confirmLabel = "Close Pane",
            onConfirm = { owner.closeBlock(blockId) },
        )
    })
    add(ClientAction("pane.paste", "Paste into Pane", "Pane", "clipboard insert", keys = listOf("ctrl+shift+v"), available = hasTerminal) {
        val blockId = focusedBlockId ?: return@ClientAction
        clipboardText()?.let { host?.paste(blockId, it) }
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
    add(ClientAction("client.sidebar.toggle", "Toggle Sidebar", "View", "hide show hosts sessions", keys = listOf("ctrl+shift+b")) {
        sidebarVisible = !sidebarVisible
    })
    // Not in Rex.app: this app's log of what it and the servers are doing.
    add(ClientAction("client.debug_log.toggle", "Toggle Debug Log", "View", "events console diagnostics", keys = listOf("ctrl+`")) {
        debugLogVisible = !debugLogVisible
    })
}

private fun clipboardText(): String? = runCatching {
    Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
}.getOrNull()?.takeIf { it.isNotEmpty() }
