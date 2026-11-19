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
import org.example.kotlintest.rex.Keymap
import org.example.kotlintest.rex.FORWARDED_KEY
import org.example.kotlintest.rex.ShortcutTarget
import kotlinx.serialization.json.JsonPrimitive
import org.example.kotlintest.rex.PaneSide
import org.example.kotlintest.rex.RESIZE_STEP_PERCENT
import kotlinx.serialization.json.doubleOrNull
import org.example.kotlintest.rex.RexAction
import org.example.kotlintest.rex.SplitDirection
import org.example.kotlintest.rex.tabMove
import org.example.kotlintest.rex.tabMoveIsForward

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
 * @param variants the action with arguments, as entries of their own in the palette: a title
 *   and the arguments it stands for, such as "Resize Pane Left" for `pane.resize` leftward
 */
class ClientAction(
    val name: String,
    val title: String,
    val category: String,
    val keywords: String = "",
    val keys: List<String> = emptyList(),
    val argKeys: List<Pair<String, JsonObject>> = emptyList(),
    val inPalette: Boolean = true,
    val variants: List<Pair<String, JsonObject>> = emptyList(),
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
        defaults = defaultKeyBindings(actions),
    )

    /**
     * Runs whatever is bound to a key press. Returns false when nothing is, so the key goes
     * to the terminal instead.
     *
     * A key bound to one of this app's actions is the app's whether or not the action can
     * run just now: Control+Alt+Left in a tab with one pane has nowhere to go, and does
     * nothing. Handed to the terminal instead, a shell prints the end of the key's escape
     * sequence, a stray "D". A shortcut that sometimes does nothing is better than one that
     * sometimes types.
     */
    fun handleKey(chord: KeyChord): Boolean =
        takesKey(ui.client.keymap.value.bindingsFor(chord), isOurs = { it in byName }) { run(it.action, it.args, origin = chord.display()) }

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

    /**
     * Everything a key can be bound to, for Change Shortcut… and a settings window: this
     * app's actions, each with the arguments it is offered or bound with, then the server's,
     * then whatever else the keymap binds (an action with arguments in the configuration file).
     */
    fun shortcutTargets(keymap: Keymap): List<ShortcutTarget> {
        val listed = buildList {
            for (action in actions) {
                if (action.inPalette) add(ShortcutTarget(action.name, null, action.title, action.category, action.keywords))
                for ((title, args) in action.variants) add(ShortcutTarget(action.name, args, title, action.category, action.keywords))
                for ((_, args) in action.argKeys) add(ShortcutTarget(action.name, args, "${action.title}: ${argsShown(args)}", action.category, action.keywords))
            }
            for (action in keymap.serverActions) add(ShortcutTarget(action.name, null, action.title, action.category, action.keywords))
        }
        val others = keymap.bindings
            .filter { binding -> binding.action != FORWARDED_KEY && listed.none { binding.isFor(it.action, it.args) } }
            .distinctBy { it.action to it.args?.takeIf { args -> args.isNotEmpty() } }
            .map { ShortcutTarget(it.action, it.args, titleOf(it, listed), byName[it.action]?.category ?: "Actions") }
        return listed + others
    }

    /** What a binding does, in words, for saying what a key is already used for. */
    fun titleOf(binding: KeyBinding, targets: List<ShortcutTarget> = shortcutTargets(ui.client.keymap.value)): String {
        if (binding.action == FORWARDED_KEY) return "a function in the configuration file"
        targets.firstOrNull { binding.isFor(it.action, it.args) }?.let { return it.title }
        val title = byName[binding.action]?.title ?: targets.firstOrNull { it.action == binding.action && it.args == null }?.title ?: binding.action
        return binding.args?.takeIf { it.isNotEmpty() }?.let { "$title: ${argsShown(it)}" } ?: title
    }

    private fun argsShown(args: JsonObject): String =
        args.entries.joinToString(", ") { (name, value) -> "$name ${(value as? JsonPrimitive)?.content ?: value.toString()}" }

}

/** The keys the actions are bound to when nothing has changed them, lowest first. */
internal fun defaultKeyBindings(actions: List<ClientAction>): List<KeyBinding> = actions.flatMap { action ->
    action.keys.map { KeyBinding(listOf(KeyChord.parse(it)!!), action.name) } +
        action.argKeys.map { (keys, args) -> KeyBinding(listOf(KeyChord.parse(keys)!!), action.name, args) }
}

/**
 * Whether a key press is the app's, given what the key is bound to, in the order to try
 * them. The first binding that runs settles it. If none does, the key is still the app's
 * when one of them names an action of the app's ([isOurs]) that couldn't run just now;
 * otherwise, and whenever a binding hands the key to the server, it goes to the terminal.
 */
internal fun takesKey(bindings: List<KeyBinding>, isOurs: (action: String) -> Boolean, run: (KeyBinding) -> Boolean): Boolean {
    var ours = false
    for (binding in bindings) {
        // Bound to a function in the configuration file: the server runs it when it gets the key.
        if (binding.action == FORWARD_TO_SERVER) return false
        if (run(binding)) return true
        if (isOurs(binding.action)) ours = true
    }
    return ours
}

private const val FORWARD_TO_SERVER = FORWARDED_KEY

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
    // Rex.app's name and title, and like it without a key of its own.
    add(ClientAction("client.shortcuts.edit", "Change Shortcut…", "App", "keyboard key binding bindings keybinding keybindings rebind record hotkey shortcut remove unbind reset") {
        palette = PaletteMode.Shortcuts
    })
    // Rex.app's name and title, on Control where it has Command. Everything that can be chosen,
    // in one place: a window of its own on the desktop, a panel over the app in a browser.
    add(ClientAction("client.settings", "Settings…", "App", "preferences settings options configure", keys = listOf("ctrl+,")) {
        openSettings()
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
    // Rex.app's name for it; Rex.app gives it no key, and neither does this.
    add(ClientAction("session.rename", "Rename Session…", "Session", "name label title", available = hasSession) {
        val session = view ?: return@ClientAction
        val owner = host ?: return@ClientAction
        // The list's label is the one the sidebar shows; the layout's is the same thing read at another moment.
        val name = owner.state.value.sessions.firstOrNull { it.id == session.sessionId }?.label ?: session.label
        renamingSession = SessionRename(owner, session.sessionId, name)
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
    // Rex.app's name and title. It is this client's alone, as in Rex Rune: other clients on
    // the session go on hearing from the tab.
    add(ClientAction(
        "client.tab.mute", "Mute Tab", "Tab", "unmute silence quiet sound notification alert notify bell hush",
        available = { window != null },
        state = { if (window?.let { tab -> host?.let { isMuted(it, tab.id) } } == true) "muted" else "not muted" },
    ) {
        val tab = window ?: return@ClientAction
        val owner = host ?: return@ClientAction
        val sessionId = owner.state.value.selectedSessionId ?: return@ClientAction
        val muted = !isMuted(owner, tab.id)
        setMuted(owner, sessionId, tab.id, muted)
        client.log.add("app", "${if (muted) "muted" else "unmuted"} the tab “${owner.state.value.nameOf(tab).text}”")
    })
    // A tab moves within its session, one place at a time, and stops at the ends. The order is
    // the session's own, so it changes for every client. Names and keys are Rex.app's, which has
    // them on Control already. The keys are taken wherever there is a tab, and do nothing at an end.
    val moveTab: RexUiState.(Boolean) -> Unit = move@{ forward ->
        val tab = window ?: return@move
        val session = view ?: return@move
        tabMove(session.windows.map { it.id }, tab.id, forward)?.let { host?.moveTab(session.sessionId, tab.id, it.beforeWindowId) }
    }
    add(ClientAction("client.tab.move.backward", "Move Tab Backward", "Tab", "reorder rearrange up left earlier before", keys = listOf("ctrl+shift+pageup"), available = { window != null }) {
        moveTab(false)
    })
    add(ClientAction("client.tab.move.forward", "Move Tab Forward", "Tab", "reorder rearrange down right later after", keys = listOf("ctrl+shift+pagedown"), available = { window != null }) {
        moveTab(true)
    })
    add(ClientAction("client.tab.move", "Move Tab", "Tab", inPalette = false, available = { window != null }) { args ->
        tabMoveIsForward(args?.get("direction")?.jsonPrimitive?.content)?.let { moveTab(it) }
    })
    // Asked about first, as closing one tab is, and as Rex Rune asks. The tabs named when the
    // question is put are the ones that close: one opened while it stands stays.
    add(ClientAction("client.tab.close_others", "Close Other Tabs", "Tab", "window all but this only keep", available = { window != null && (view?.windows?.size ?: 0) > 1 }) {
        val tab = window ?: return@ClientAction
        val session = view ?: return@ClientAction
        val owner = host ?: return@ClientAction
        val others = session.windows.map { it.id } - tab.id
        if (others.isEmpty()) return@ClientAction
        val kept = owner.state.value.nameOf(tab).text
        confirmation = Confirmation(
            title = if (others.size == 1) "Close the other tab?" else "Close ${others.size} other tabs?",
            text = "The programs running in ${if (others.size == 1) "the other tab" else "the ${others.size} other tabs"} of ${session.label} will end. “$kept” stays open.",
            confirmLabel = if (others.size == 1) "Close Other Tab" else "Close Other Tabs",
            onConfirm = { owner.closeOtherTabs(session.sessionId, tab.id, others) },
        )
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

    // Back and forward through the tabs this window has shown, across sessions and hosts.
    // The names are Rex Rune's; Rex.app has no such actions. Rune gives them no key, only the
    // mouse's back and forward buttons, which work here too (see RexApp). The keys are this
    // app's: brackets, as for the next tab (Control+Shift) and the next pane (Alt), with
    // Control+Alt, which a terminal has no use for. The keys are taken at either end of the
    // history too, where they do nothing.
    add(ClientAction("client.history.back", "Back in History", "Tab", "navigate previous last recent visited return go", keys = listOf("ctrl+alt+["), available = { canStepHistory(forward = false) }) {
        stepHistory(forward = false)
    })
    add(ClientAction("client.history.forward", "Forward in History", "Tab", "navigate next recent visited again go", keys = listOf("ctrl+alt+]"), available = { canStepHistory(forward = true) }) {
        stepHistory(forward = true)
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
    // Rearranging. Names and keys are Rex.app's, with Control where it has Command. A pane
    // "moves" by trading places with the one beside it, so every split keeps its size.
    val severalPanes: RexUiState.() -> Boolean = { (window?.panes?.count { !it.floating } ?: 0) > 1 && window?.zoomedBlockId == null }
    for (side in PaneSide.entries) {
        val name = side.name.lowercase()
        add(ClientAction(
            "pane.move.$name", "Move Pane ${side.name}", "Pane", "swap rearrange trade places",
            keys = listOf("ctrl+alt+shift+$name"),
            // Offered wherever there is a pane to trade with, including at the tab's edge, where
            // it does nothing. In a tab with one pane it isn't offered, and its key still
            // does nothing rather than reach the terminal: see handleKey.
            available = severalPanes,
        ) { focusedBlockId?.let { host?.swapPane(it, side) } })
    }
    add(ClientAction("pane.move", "Move Pane", "Pane", inPalette = false) { args ->
        val side = PaneSide.parse(args?.get("direction")?.jsonPrimitive?.content) ?: return@ClientAction
        focusedBlockId?.let { host?.swapPane(it, side) }
    })
    // Rex.app gives this no key, and none here: bind one in Rex's configuration file, as its
    // documentation shows (`rex.bind("ctrl+alt+left", "pane.resize", { direction = "left" })`).
    add(ClientAction(
        "pane.resize", "Resize Pane", "Pane", "grow shrink divider wider narrower taller shorter border", inPalette = false,
        variants = PaneSide.entries.map { side -> "Resize Pane ${side.name}" to buildJsonObject { put("direction", side.name.lowercase()) } },
        available = severalPanes,
    ) { args ->
        val side = PaneSide.parse(args?.get("direction")?.jsonPrimitive?.content) ?: return@ClientAction
        // How far, in hundredths of the split the border divides.
        val amount = args?.get("amount")?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() } ?: RESIZE_STEP_PERCENT
        focusedBlockId?.let { host?.resizePane(it, side, amount) }
    })
    add(ClientAction("pane.balance", "Balance Splits", "Pane", "equalize equalise evenly distribute resize layout same size", available = { (window?.panes?.count { !it.floating } ?: 0) > 1 }) {
        host?.balancePanes()
    })
    // Listed before the one for a new tab, so that typing its name in the palette finds it first.
    add(ClientAction("pane.move_to_tab", "Move Pane to Tab…", "Pane", "block window send other existing join", available = { focusedBlockId != null && (view?.windows?.size ?: 0) > 1 }) { args ->
        val windowId = args?.get("window_id")?.jsonPrimitive?.content
        if (windowId != null) focusedBlockId?.let { host?.movePaneToTab(it, windowId) } else palette = PaletteMode.MoveToTab
    })
    add(ClientAction("pane.move_to_new_tab", "Move Pane to New Tab", "Pane", "block window detach break out", available = severalPanes) {
        focusedBlockId?.let { host?.movePaneToNewTab(it) }
    })
    add(ClientAction("pane.rename", "Rename Pane…", "Pane", "name label title block", available = hasPane) {
        val pane = window?.panes?.firstOrNull { it.blockId == focusedBlockId } ?: return@ClientAction
        renamingPane = PaneRename(host ?: return@ClientAction, pane.blockId, givenName = pane.name.orEmpty())
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
    // Rex Rune's name and key for the menu a pane also gets from the right button (PaneMenu.kt).
    // Rex.app has the menu and no action for it. Not while the sidebar is shown instead of
    // the terminals, as in a narrow window, when there is no pane on screen to open it on.
    add(ClientAction("pane.actions", "Pane Actions…", "Pane", "menu context right click button popup", keys = listOf("ctrl+shift+space"), available = { focusedBlockId != null && !(narrow && sidebarVisible) }) {
        val blockId = focusedBlockId ?: return@ClientAction
        val owner = host ?: return@ClientAction
        if (paneMenu == null) openPaneMenu(owner, blockId, at = null) else closePaneMenu()
    })
    add(ClientAction("pane.paste", "Paste into Pane", "Pane", "clipboard insert", keys = listOf("ctrl+shift+v"), available = hasTerminal) {
        val blockId = focusedBlockId ?: return@ClientAction
        val owner = host ?: return@ClientAction
        // A browser hands the clipboard over only when it is ready to, so this doesn't wait.
        // Text is pasted. Copied files, or an image with no text, go to the terminal's machine
        // and their paths are typed there: see Uploads.kt.
        scope?.launch {
            when (val content = clipboardContent()) {
                is ClipboardContent.Text -> owner.paste(blockId, content.text)
                is ClipboardContent.Files -> owner.offer(blockId, content.offer)
                null -> {}
            }
        }
    })
    // Rex.app's names and titles. Insert Path is Control+Command+I there, and Control+Alt+I
    // here: Control+Shift+I is a browser's, for its developer tools, and Control+I is Tab.
    // Go to Directory is Shift+Command+G there, the same key as its Find Previous. Here
    // Control+Shift+G is Find Next and Control+Alt+G Find Previous, so it has no key, as in
    // Rex Rune: bind one in Rex's configuration file.
    add(ClientAction("pane.insert_path", "Insert Path…", "Pane", "file folder directory browse type quote filename", keys = listOf("ctrl+alt+i"), available = hasTerminal) {
        palette = PaletteMode.InsertPath
    })
    add(ClientAction("pane.go_to_directory", "Go to Directory…", "Pane", "cd folder change directory browse path", available = hasTerminal) {
        palette = PaletteMode.GoToDirectory
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
    // Rex.app has these on Command+Up and Command+Down. Control with an arrow is the
    // program's (a shell moves by a word with it, an editor by a paragraph), so Shift is added.
    add(ClientAction("client.prompt.previous", "Jump to Previous Prompt", "Pane", "jump scroll previous prompt command mark shell integration up back history", keys = listOf("ctrl+shift+up"), available = hasTerminal) {
        focusedTerminal?.jumpToPrompt(older = true)
    })
    add(ClientAction("client.prompt.next", "Jump to Next Prompt", "Pane", "jump scroll next prompt command mark shell integration down forward", keys = listOf("ctrl+shift+down"), available = hasTerminal) {
        focusedTerminal?.jumpToPrompt(older = false)
    })

    // Find. Rex.app has these on Command+F, Command+G and Shift+Command+G. Control+F and
    // Control+G are a shell's, so Shift is added, and Find Previous, which has Shift
    // already, takes Alt instead, as Split Pane Down does.
    add(ClientAction("client.find.open", "Find…", "Find", "find search text scrollback highlight", keys = listOf("ctrl+shift+f"), available = hasTerminal) {
        focusedTerminal?.openSearch()
    })
    add(ClientAction("client.find.next", "Find Next", "Find", "find search next forward match older", keys = listOf("ctrl+shift+g"), available = hasTerminal) {
        focusedTerminal?.searchStep(forward = true)
    })
    add(ClientAction("client.find.previous", "Find Previous", "Find", "find search previous back match newer", keys = listOf("ctrl+alt+g"), available = hasTerminal) {
        focusedTerminal?.searchStep(forward = false)
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
    // How the app looks: see Appearance.kt. Each lists what there is, and shows the one highlighted as the list is gone through.
    add(ClientAction("client.theme.select", "Theme…", "Appearance", "theme themes color colors colour colours dark light appearance omarchy palette scheme look", state = { appearance.themeTitle }) {
        palette = PaletteMode.Themes
    })
    add(ClientAction("client.font.select", "Terminal Font…", "Appearance", "font family typeface monospace terminal text appearance", state = { appearance.fontTitle }) {
        palette = PaletteMode.Fonts
    })
    add(ClientAction(
        "client.font.weight", "Terminal Font Weight…", "Appearance", "font weight light regular medium bold thin heavy terminal text appearance",
        available = { appearance.weights.size > 1 }, state = { fontWeightName(appearance.fontWeight) },
    ) {
        palette = PaletteMode.FontWeights
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
        switchSounds(!soundsEnabled)
    })
    // The switches for the kinds of news a program's own state makes (waiting, failed, done)
    // are in the settings. These two are Rex.app's actions.
    add(ClientAction(
        "client.setting.notifications.activity.system_notifications", "Show System Notifications", "Notifications",
        "desktop notification notify alert popup banner program finished blocked error needs allow permission",
        state = {
            when {
                !notifications.desktop -> "off"
                else -> when (notificationAccess()) {
                    NotificationAccess.Granted -> "on"
                    NotificationAccess.Undecided -> "not allowed yet"
                    NotificationAccess.Refused -> "blocked by the browser"
                    NotificationAccess.Unavailable -> "unavailable"
                }
            }
        },
    ) {
        // On, and the browser not asked yet: what is wanted is the question, not turning them off.
        switchSystemNotifications(on = !notifications.desktop || notificationAccess() == NotificationAccess.Undecided)
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
    // Rex.app's action, and Rex Rune's second switch beside it under its name there. Both
    // are also in the workspace file, under "terminal".
    add(ClientAction(
        "client.setting.terminal.selection.copy_on_select", "Copy on Selection", "Terminal",
        "copy select selection clipboard automatic mouse drag highlight",
        state = { if (selection.copyOnSelection) "on" else "off" },
    ) {
        selection = selection.copy(copyOnSelection = !selection.copyOnSelection)
        client.log.add("clipboard", "copy on selection ${if (selection.copyOnSelection) "on" else "off"}")
    })
    add(ClientAction(
        "client.setting.terminal.selection.primary_selection", "Primary Selection", "Terminal",
        "primary selection middle click button paste mouse copy select x11 wayland linux",
        state = {
            when {
                !selection.primarySelection -> "off"
                else -> when (primarySelection()) {
                    PrimarySelection.Desktop -> "on"
                    PrimarySelection.Clipboard -> "on, with the clipboard"
                    PrimarySelection.None -> "unavailable"
                }
            }
        },
    ) {
        selection = selection.copy(primarySelection = !selection.primarySelection)
        client.log.add("clipboard", "primary selection ${if (selection.primarySelection) "on" else "off"}")
    })
    // Rex.app's action and its name there.
    add(ClientAction(
        "client.setting.terminal.scrolling.smooth", "Smooth Scrolling", "Terminal",
        "smooth scroll scrolling pixel precise fluid glide wheel trackpad touchpad line by line history",
        state = { if (smoothScrolling) "on" else "off" },
    ) {
        smoothScrolling = !smoothScrolling
        client.log.add("app", "smooth scrolling ${if (smoothScrolling) "on" else "off"}")
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
