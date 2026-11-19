package org.example.kotlintest

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import dev.mg.omarchy.theme.OmarchyPalette
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.kotlintest.rex.HostConnection
import org.example.kotlintest.rex.HostReach
import org.example.kotlintest.rex.RexHost
import org.example.kotlintest.rex.Keymap
import org.example.kotlintest.rex.RexEndpoint
import org.example.kotlintest.rex.tailnetRexServers
import org.example.kotlintest.rex.endpointKey
import org.example.kotlintest.rex.suggestedHostLabel

/** One thing the palette offers. */
internal class PaletteItem(
    val title: String,
    /** Shown dimmed beside the title: a category, or where the thing is. */
    val detail: String,
    val shortcut: String? = null,
    val keywords: String = "",
    /** For a host in a list of hosts: its picture, as its heading in the sidebar has it. */
    val host: HostPicture? = null,
    /** For a theme: its colors, shown at the end of its line. */
    val swatch: OmarchyPalette? = null,
    /** Whether it is the one in effect, in a list to choose one from: the list opens on it. */
    val current: Boolean = false,
    /** Called when the line becomes the highlighted one, for a list that shows what choosing it would do. */
    val onHighlight: (() -> Unit)? = null,
    val run: () -> Unit,
)

/** The picture of a host, and whether the host is down, which the picture's color says. */
internal class HostPicture(val icon: HostIcon, val down: Boolean)

/** A host's picture for the palette: the one its heading in the sidebar shows. */
private fun hostPicture(ui: RexUiState, host: RexHost): HostPicture {
    val reach = host.state.value.reach
    return HostPicture(
        HostIcon.of(ui.client.hostIcons.value[host.saved.label], isHome = host.saved.label == ui.client.homeLabel),
        down = reach == HostReach.Reconnecting || reach == HostReach.Down,
    )
}

/**
 * Everything the palette can list in a mode: the commands that can run right now, and the
 * places to go (sessions on every host, the current session's tabs, the hosts).
 */
internal fun paletteItems(ui: RexUiState, commands: RexCommands, keymap: Keymap, mode: PaletteMode): List<PaletteItem> = buildList {
    val hosts = ui.orderedHosts()
    if (mode == PaletteMode.Commands) {
        for (action in commands.actions) {
            if (!commands.isAvailable(action)) continue
            for ((title, args) in action.variants) {
                add(PaletteItem(title, action.category, keymap.shortcutFor(action.name, args)?.display(), action.keywords) {
                    commands.run(action.name, args, origin = "palette")
                })
            }
            if (!action.inPalette) continue
            val detail = action.state?.invoke(ui)?.let { "${action.category} · $it" } ?: action.category
            add(PaletteItem(action.title, detail, keymap.shortcutFor(action.name)?.display(), action.keywords) {
                commands.run(action.name, origin = "palette")
            })
        }
        for (action in keymap.serverActions) {
            add(PaletteItem(action.title, action.category, keymap.shortcutFor(action.name)?.display(), action.keywords) {
                commands.run(action.name, origin = "palette")
            })
        }
        // Every tab of every session, folded away or not, with the digit that goes to it if it has one.
        val numbered = ui.tabs()
        ui.allTabs().forEach { stop ->
            val state = hosts.firstOrNull { it.saved.label == stop.host }?.state?.value ?: return@forEach
            val session = state.views[stop.sessionId] ?: return@forEach
            val tab = session.windows.firstOrNull { it.id == stop.windowId } ?: return@forEach
            val digit = numbered.indexOf(stop).takeIf { it >= 0 }?.let { tabShortcutDigit(it, numbered.size) }
            val goto = digit?.let { buildJsonObject { put("index", if (it == 9) -1 else it) } }
            add(PaletteItem("Go to Tab: ${state.nameOf(tab).text}", "${session.label} · ${stop.host}", goto?.let { keymap.shortcutFor("client.tab.goto", it)?.display() }, "tab") {
                ui.go(stop)
            })
        }
    }
    if (mode == PaletteMode.Shortcuts) {
        // Every action, whether or not it can run just now: its key is the same either way.
        for (shortcut in ui.client.shortcutList(commands.shortcutTargets(keymap))) {
            val target = shortcut.target
            val keys = shortcut.keysShown()
            add(PaletteItem(target.title, listOf(target.category, shortcut.sourceShown()).filter { it.isNotEmpty() }.joinToString(" · "), keys, "${target.keywords} ${keys.orEmpty()}") {
                ui.shortcutEdit = target
            })
        }
        return@buildList
    }
    val look = ui.appearance
    if (mode == PaletteMode.Themes) {
        // Following a desktop that has no theme to follow isn't offered.
        for (option in look.themes) {
            if (!option.available) continue
            add(PaletteItem(option.title, option.detail, keywords = "theme ${option.slug.orEmpty()}", swatch = option.palette, current = option.current, onHighlight = { look.triedTheme = Tried(option.slug) }) {
                look.chooseTheme(option.slug)
            })
        }
        return@buildList
    }
    if (mode == PaletteMode.Fonts) {
        for (option in look.fonts) {
            add(PaletteItem(option.title, option.detail, keywords = "font", current = option.current, onHighlight = { look.triedFont = Tried(option.family) }) {
                look.chooseFont(option.family)
            })
        }
        return@buildList
    }
    if (mode == PaletteMode.FontWeights) {
        for (weight in look.weights) {
            add(PaletteItem(fontWeightName(weight), weight.toString(), keywords = "weight", current = weight == look.choice.fontWeight ?: DEFAULT_FONT_WEIGHT, onHighlight = { look.triedWeight = weight }) {
                look.chooseFontWeight(weight)
            })
        }
        return@buildList
    }
    if (mode == PaletteMode.AddHost) {
        // The Rex servers on the tailnet that aren't hosts yet. One that is down can still be
        // added: it connects when it comes up.
        for (server in ui.tailnetServers.orEmpty()) {
            if (ui.client.isConfigured(server.endpoint) || hosts.any { it.saved.label.equals(server.label, ignoreCase = true) }) continue
            add(PaletteItem(server.label, "${server.address} · ${if (server.online) "online" else "offline"}", keywords = "tailscale tailnet") {
                ui.client.addHost(server.label, server.endpoint)
                ui.returnFocusToTerminal()
            })
        }
        return@buildList
    }
    if (mode == PaletteMode.MoveToTab) {
        // The other tabs of the session on screen. A pane can't leave its session.
        val host = ui.host ?: return@buildList
        val state = host.state.value
        val blockId = ui.focusedBlockId ?: return@buildList
        for (tab in state.view?.windows.orEmpty()) {
            if (tab.id == state.window?.id) continue
            val name = state.nameOf(tab)
            add(PaletteItem(name.text, listOfNotNull(name.directory, "${tab.panes.size} pane${if (tab.panes.size == 1) "" else "s"}").joinToString(" · "), keywords = "tab") {
                host.movePaneToTab(blockId, tab.id)
            })
        }
        return@buildList
    }
    if (mode == PaletteMode.RemoveHost) {
        for (host in hosts) {
            if (host.saved.label == ui.client.homeLabel) continue // the app follows its home; it stays
            add(PaletteItem(host.saved.label, host.saved.endpoint, keywords = "host server", host = hostPicture(ui, host)) {
                ui.confirmation = Confirmation(
                    title = "Remove host “${host.saved.label}”?",
                    text = if (host.discovered) "It leaves your Rex host list, so the rex command and other clients that follow this machine stop listing it too. Its sessions keep running."
                    else "This app stops connecting to it. Its sessions keep running.",
                    confirmLabel = "Remove Host",
                    onConfirm = { ui.client.removeHost(host.saved.label) },
                )
            })
        }
        return@buildList
    }
    if (mode != PaletteMode.Hosts) {
        val prefix = if (mode == PaletteMode.Commands) "Go to Session: " else ""
        for (host in hosts) {
            if (host.state.value.connection != HostConnection.Connected) continue
            for (session in host.state.value.sessions) {
                add(PaletteItem("$prefix${session.label}", host.saved.label, keywords = "session") {
                    ui.go(NavigationStop(host.saved.label, session.id))
                })
            }
        }
    }
    if (mode != PaletteMode.Sessions) {
        for (host in hosts) {
            val state = host.state.value
            // In the sidebar's words, so a host being tried again isn't "connecting" here and down there.
            val status = hostReachWords(state.reach) ?: "${state.sessions.size} session${if (state.sessions.size == 1) "" else "s"}"
            val prefix = if (mode == PaletteMode.Commands) "Switch to Host: " else ""
            // Pictures only in the list of hosts: among commands, one line in ten would have one.
            add(PaletteItem("$prefix${host.saved.label}", status, keywords = "host server ${host.saved.endpoint}", host = if (mode == PaletteMode.Hosts) hostPicture(ui, host) else null) {
                ui.client.selectHost(host.saved.label)
                ui.returnFocusToTerminal()
            })
        }
        // The way to a host that isn't listed yet.
        if (mode == PaletteMode.Hosts) add(PaletteItem("Add Host…", "from your tailnet, or by address", keywords = "new server connect") { ui.palette = PaletteMode.AddHost })
    }
}

/**
 * The item that adds a typed address as a host, when what is typed is an address and not
 * the name of something listed: it has a dot, a slash or a colon in it, as `studio.ts.net`,
 * `10.0.0.5:7297` and `https://studio` do.
 */
internal fun typedHostItem(ui: RexUiState, query: String): PaletteItem? {
    val address = query.trim()
    if (address.none { it in "./:" } || ' ' in address || runCatching { RexEndpoint.parse(address) }.isFailure) return null
    if (ui.client.isConfigured(address)) return null
    // While what is typed is the start of a listed server's address, that server is the answer.
    val typed = endpointKey(address)
    if (ui.tailnetServers.orEmpty().any { endpointKey(it.endpoint).startsWith(typed) }) return null
    val label = suggestedHostLabel(address)
    return PaletteItem("Add $label", "at $address") {
        ui.client.addHost(label, address)
        ui.returnFocusToTerminal()
    }
}

/**
 * Keeps the items matching every word of [query], best first. A word matches where it
 * appears, or failing that where its letters appear in order (`nt` finds New Tab).
 */
internal fun filterPalette(items: List<PaletteItem>, query: String): List<PaletteItem> {
    val words = query.lowercase().split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return items
    return items.mapIndexedNotNull { index, item ->
        val title = item.title.lowercase()
        val rest = "${item.detail} ${item.keywords}".lowercase()
        var score = 0
        for (word in words) {
            val at = title.indexOf(word)
            score += when {
                at == 0 -> 100
                at > 0 && !title[at - 1].isLetterOrDigit() -> 80 // at the start of a word
                at > 0 -> 60
                rest.contains(word) -> 30
                isSubsequence(word, title) -> 10
                else -> return@mapIndexedNotNull null
            }
        }
        Triple(score, index, item)
    }.sortedWith(compareByDescending<Triple<Int, Int, PaletteItem>> { it.first }.thenBy { it.second }).map { it.third }
}

private fun isSubsequence(word: String, text: String): Boolean {
    var next = 0
    for (char in text) if (next < word.length && char == word[next]) next++
    return next == word.length
}

/** The palette itself: a search field over a list, drawn above everything else. */
@Composable
fun CommandPalette(ui: RexUiState, commands: RexCommands, mode: PaletteMode) {
    // Browsing for a path fills the same frame from somewhere else: the terminal's machine.
    if (mode == PaletteMode.InsertPath || mode == PaletteMode.GoToDirectory) {
        PathBrowser(ui, mode)
        return
    }
    val keymap by ui.client.keymap.collectAsState()
    var query by remember(mode) { mutableStateOf("") }
    // Listed once per opening: the palette shows the moment it was opened.
    val tailnet = ui.tailnetServers
    val recorded by ui.client.shortcuts.collectAsState()
    val all = remember(mode, keymap, tailnet, recorded, ui.appearance.installedFonts) { paletteItems(ui, commands, keymap, mode) }
    // A list to choose one of something from opens on the one in effect.
    var selected by remember(mode) { mutableIntStateOf(all.indexOfFirst { it.current }.coerceAtLeast(0)) }
    val shown = remember(all, query) {
        filterPalette(all, query) + listOfNotNull(if (mode == PaletteMode.AddHost) typedHostItem(ui, query) else null)
    }
    // Look for servers each time the picker opens: machines come and go. What was found
    // last time shows meanwhile.
    LaunchedEffect(mode) {
        if (mode == PaletteMode.AddHost) ui.tailnetServers = tailnetRexServers()
    }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(mode) { focus.requestFocus() }
    // A theme or a font is shown while its line is the highlighted one, and what was chosen
    // shows again when the list closes without a choice.
    val highlighted = shown.getOrNull(selected)
    LaunchedEffect(highlighted) { highlighted?.onHighlight?.invoke() }
    DisposableEffect(mode) { onDispose { ui.appearance.endTrying() } }
    LaunchedEffect(selected, shown.size) { if (selected in shown.indices) listState.scrollToItem(selected) }

    fun choose(item: PaletteItem) {
        // Close first: the item may open the palette again in another mode, or a dialog. Only
        // if it opened nothing does the keyboard go back to the terminal.
        ui.palette = null
        item.run()
        ui.returnFocusToTerminal()
    }

    PaletteFrame(onDismiss = ui::closePalette) {
        TextField(
            value = query,
            onValueChange = { query = it; selected = 0 },
            singleLine = true,
            placeholder = {
                Text(
                    when (mode) {
                        PaletteMode.Commands -> "Run a command, or go to a session, tab or host"
                        PaletteMode.Sessions -> "Go to a session"
                        PaletteMode.Hosts -> "Switch to a host"
                        PaletteMode.AddHost -> "Add a server from your tailnet, or type an address"
                        PaletteMode.RemoveHost -> "Remove a host"
                        PaletteMode.MoveToTab -> "Move this pane to a tab"
                        PaletteMode.Shortcuts -> "Change the shortcut of an action"
                        PaletteMode.Themes -> "Choose a theme"
                        PaletteMode.Fonts -> "Choose the terminals' font"
                        PaletteMode.FontWeights -> "Choose how heavy the terminals' text is"
                        PaletteMode.InsertPath, PaletteMode.GoToDirectory -> "" // drawn by PathBrowser
                    },
                )
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> ui.closePalette()
                    Key.DirectionDown -> if (shown.isNotEmpty()) selected = (selected + 1) % shown.size
                    Key.DirectionUp -> if (shown.isNotEmpty()) selected = (selected - 1).mod(shown.size)
                    Key.Enter, Key.NumPadEnter -> shown.getOrNull(selected)?.let(::choose)
                    else -> {
                        // The key that opened the palette closes it again.
                        val chord = keyChord(event) ?: return@onPreviewKeyEvent false
                        if (keymap.bindingsFor(chord).firstOrNull()?.action != "client.palette.toggle") return@onPreviewKeyEvent false
                        ui.closePalette()
                    }
                }
                true
            },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (shown.isEmpty()) {
            Text(
                when {
                    mode != PaletteMode.AddHost -> "Nothing matches"
                    tailnet == null -> "Looking for Rex servers on your tailnet…"
                    query.isBlank() -> "Every Rex server on your tailnet is already a host. Type an address to add another."
                    else -> "No server on your tailnet matches. Type a full address to add it."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(state = listState, modifier = Modifier.heightIn(max = 420.dp).padding(vertical = 4.dp)) {
            // Where any line has a host's picture, every line keeps room for one, so the names line up.
            val pictures = shown.any { it.host != null }
            itemsIndexed(shown) { index, item ->
                PaletteRow(item.title, item.detail, item.shortcut, selected = index == selected, onClick = { choose(item) }, host = item.host, pictures = pictures, swatch = item.swatch)
            }
        }
    }
}

/** What the palette and the path browser are drawn in: a panel near the top of the window, over a dimmed app. */
@Composable
internal fun PaletteFrame(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
            // A pointer handler, not clickable: clickable also answers Space and Enter, and
            // those belong to the search field.
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp).widthIn(max = 640.dp).fillMaxWidth(0.8f)
                // Clicks inside the palette stop here instead of dismissing it.
                .pointerInput(Unit) { detectTapGestures { } },
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
        ) {
            Column(content = content)
        }
    }
}

/** One line of the list: what it is, something about it, and the key for it if there is one. */
@Composable
internal fun PaletteRow(
    title: String, detail: String, shortcut: String?, selected: Boolean, onClick: () -> Unit,
    /** A host's picture, drawn before the title. */
    host: HostPicture? = null,
    /** Whether lines in this list have pictures, so that one without keeps the room for it. */
    pictures: Boolean = host != null,
    /** A theme's colors, drawn at the end of the line. */
    swatch: OmarchyPalette? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        if (pictures) Box(Modifier.padding(end = 10.dp).size(16.dp)) {
            host?.let { MachineIcon(it.icon, if (it.down) MaterialTheme.colorScheme.error.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            detail,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = 10.dp).weight(1f),
        )
        shortcut?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        swatch?.let { ThemeSwatch(it, Modifier.size(width = 72.dp, height = 16.dp)) }
    }
}
