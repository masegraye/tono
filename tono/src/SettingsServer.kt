package org.example.kotlintest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.kotlintest.rex.HostReach
import org.example.kotlintest.rex.HostState
import org.example.kotlintest.rex.RexHost
import org.example.kotlintest.rex.ServerInfo
import org.example.kotlintest.rex.TonoClient
import org.example.kotlintest.rex.defaultLocalEndpoint
import org.example.kotlintest.rex.endpointKey

/*
 * Server: the first thing in the settings, as in Rex.app's. Rex.app ships a Rex server and
 * manages it there. This app ships none: on Linux the server is installed by itself, and
 * started by a systemd unit or by hand. So this shows what the server says of itself, and,
 * when no server answers on this machine, says so and offers what would get one going. It
 * runs nothing until a button is pressed.
 */

/** Where Rex's documentation says how to install it on Linux. */
internal const val REX_INSTALL_DOCUMENTATION = "https://www.superlogical.com/rex/docs/linux/installation"

/** What can be offered when no server answers on this machine. */
internal enum class LocalServerOffer { StartServer, StartAtLogin, InstallRex }

/**
 * What fits, given what the machine has: without `rex` there is only installing it; with it,
 * starting a server, and having it start by itself unless that is set up already.
 */
internal fun localServerOffers(setup: LocalServerSetup): List<LocalServerOffer> = when {
    setup.rexPath == null -> listOf(LocalServerOffer.InstallRex)
    setup.socketEnabled == true -> listOf(LocalServerOffer.StartServer)
    else -> listOf(LocalServerOffer.StartServer, LocalServerOffer.StartAtLogin)
}

/** What is wrong, in plain words, and what the machine has to put it right with. */
internal fun localServerWords(setup: LocalServerSetup): String = when {
    setup.rexPath == null ->
        "No Rex server is running on this machine, and Rex isn't installed: there is no rex command on PATH. $APP_NAME is a client. It needs a Rex server to show anything."
    setup.socketEnabled == true ->
        "No Rex server answered on this machine. Rex is installed (${setup.rexPath}) and set to start by itself (rex.socket is enabled" +
            (if (setup.socketActive == false) ", though it isn't active just now" else "") + "), so one should have. Starting it here says why if it can't."
    else ->
        "No Rex server is running on this machine. Rex is installed (${setup.rexPath}), but nothing starts it" +
            (if (setup.socketEnabled == false) ": rex.socket isn't enabled." else ".")
}

/**
 * Whether a host is this machine's own server at its usual place with nothing answering,
 * which is when there is something to offer. A server somewhere else on this machine was put
 * there by someone who knows how it is started.
 */
internal fun isMissingLocalServer(endpoint: String, state: HostState, usual: String = defaultLocalEndpoint()): Boolean =
    endpointKey(endpoint) == endpointKey(usual) && state.failed && state.reach != HostReach.Connected

/** The API revision this app and its home server speak, or why that isn't known. */
internal fun homeServerWords(state: HostState): String =
    state.apiRevision?.takeIf { hostReachWords(state.reach) == null }?.toString() ?: (hostReachWords(state.reach) ?: "not known yet").replaceFirstChar { it.uppercase() }

/** A server's listeners, one to a line, the one commands look for first. */
internal fun listenersShown(info: ServerInfo): String =
    info.listeners.sortedByDescending { it.primary }.joinToString("\n") { "${it.endpoint}  ·  ${it.kind}" }

/** The account a server runs as and its process, in a line. */
internal fun processShown(info: ServerInfo): String? =
    listOfNotNull(info.account?.let { "as $it" }, info.pid?.let { "process $it" }).takeIf { it.isNotEmpty() }?.joinToString(", ")?.replaceFirstChar { it.uppercase() }

/** The section: the home server, then this client, then this app. */
@Composable
internal fun ServerSettings(ui: RexUiState) {
    val client = ui.client
    val hosts by client.hosts.collectAsState()
    val keymap by client.keymap.collectAsState()
    val home = hosts.firstOrNull { it.saved.label == client.homeLabel }
    if (home == null) {
        SettingsGroup("Rex Server") {
            InfoLine("None. No host here is on this machine, so shortcuts are $APP_NAME's own and hosts are kept in its workspace.")
        }
    } else {
        val state by home.state.collectAsState()
        if (isMissingLocalServer(home.saved.endpoint, state, ui.usualLocalEndpoint)) MissingLocalServer(ui, home)
        SettingsGroup("Rex Server") {
            InfoRow("Home server", "${home.saved.label} · ${home.saved.endpoint}")
            RowDivider()
            if (state.reach == HostReach.Connected) {
                ServerDetails(home, state, configPath = keymap.configPath, onLink = { url -> openLink(url) { message, isError -> client.log.add("links", message, isError = isError) } })
            } else {
                InfoRow("Status", hostStatusLine(state.reach) + state.lastFailure?.let { ": $it" }.orEmpty())
            }
        }
    }
    ClientInfo(ui)
    SettingsGroup(APP_NAME) {
        InfoRow("Version", TonoClient.version)
        RowDivider()
        InfoRow("Workspace", client.workspace.location ?: "Not kept: what is set here lasts until $APP_NAME closes.")
    }
}

/**
 * What a connected server says about itself, as lines of a card. Asked when the card is
 * shown and again whenever the connection is made anew.
 *
 * @param configPath where its configuration file is, where that is known: the home server says, with the keymap
 * @param onLink opens a link, where signing its Tailscale device in can be offered: over its own socket only
 */
@Composable
internal fun ServerDetails(host: RexHost, state: HostState, configPath: String? = null, onLink: ((String) -> Unit)? = null) {
    var info by remember(host) { mutableStateOf<ServerInfo?>(null) }
    var failure by remember(host) { mutableStateOf<String?>(null) }
    var asked by remember(host) { mutableStateOf(0) }
    LaunchedEffect(host, state.clientId, asked) {
        runCatching { host.serverInfo() }.onSuccess { info = it; failure = null }.onFailure { failure = it.message }
    }
    val known = info
    if (known == null) {
        InfoRow("API revision", state.apiRevision?.toString() ?: "Not known yet")
        failure?.let { RowDivider(); InfoRow("The server wouldn't say more", it) }
        return
    }
    ServerInfoRows(known, sessions = state.sessions.size, configPath = configPath)
    val tailscale = known.tailscale
    if (tailscale != null && tailscale.needsSignIn && onLink != null) {
        val scope = rememberCoroutineScope()
        var signIn by remember(host) { mutableStateOf<String?>(null) }
        RowDivider()
        Row(Modifier.fillMaxWidth().heightIn(min = TOUCH_TARGET).padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(signIn ?: "Its Tailscale device hasn't been signed in.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
            TextButton(modifier = Modifier.heightIn(min = TOUCH_TARGET), onClick = {
                signIn = "Asking Tailscale for a sign-in link…"
                scope.launch {
                    val link = tailscale.signInLink ?: runCatching { host.tailscaleSignInLink() }.getOrElse { signIn = it.message; return@launch }
                    if (link == null) signIn = "Tailscale hasn't made a sign-in link yet. Try again in a moment."
                    else { signIn = "Opened $link"; onLink(link); asked++ }
                }
            }) { Text("Sign In…") }
        }
    }
}

/** The lines themselves, apart from asking for them, so that they can be drawn from made-up answers. */
@Composable
internal fun ServerInfoRows(info: ServerInfo, sessions: Int? = info.sessionCount, configPath: String? = null) {
    val rows = buildList {
        add("Version" to listOfNotNull(info.version, info.platform).joinToString(" · ").ifEmpty { "Not said" })
        add("API revision" to (info.apiRevision?.toString() ?: "Not said"))
        info.startedShown?.let { add("Running since" to it) }
        processShown(info)?.let { add("Runs" to it) }
        sessions?.let { add("Sessions" to it.toString()) }
        if (info.listeners.isNotEmpty()) add("Listens on" to listenersShown(info))
        info.tailscale?.let { tailscale ->
            add("Tailscale" to listOfNotNull(
                tailscale.stateWords,
                tailscale.name?.let { "Name on the tailnet: $it" },
                tailscale.httpsUrl?.let { "Address: $it" },
                listOfNotNull(tailscale.account, tailscale.tailnet).takeIf { it.isNotEmpty() }?.let { "Account: ${it.joinToString(" · ")}" },
                tailscale.error?.let { "It turned off: $it" },
            ).joinToString("\n"))
        }
        if (info.configGeneration != null || configPath != null) {
            add("Configuration file" to listOfNotNull(configPath, info.configGeneration?.let { "Read $it time${if (it == 1) "" else "s"} since the server started" }).joinToString("\n"))
        }
    }
    rows.forEachIndexed { index, (label, value) ->
        if (index > 0) RowDivider()
        InfoRow(label, value)
    }
}

/**
 * When no server answers on this machine: what is wrong in plain words, and what would put
 * it right, each a button that runs nothing until pressed. What a command printed is shown
 * if it failed.
 */
@Composable
private fun MissingLocalServer(ui: RexUiState, home: RexHost) {
    val scope = rememberCoroutineScope()
    var setup by remember { mutableStateOf<LocalServerSetup?>(null) }
    var looked by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf<LocalServerCommand?>(null) }
    var output by remember { mutableStateOf<String?>(null) }
    var ran by remember { mutableStateOf(0) }
    // Looked at when this shows, and again after a command has run: it may have changed what there is.
    LaunchedEffect(ran) {
        setup = ui.lookAtLocalServer()
        looked = true
    }
    // A page in a browser can't look, and the server that serves it runs beside a Rex server already.
    val found = setup ?: return
    if (!looked) return
    val offers = localServerOffers(found)

    fun run(command: LocalServerCommand) {
        running = command
        output = null
        scope.launch {
            output = ui.runRexCommand(command)
            running = null
            ran++
            // Whatever it did, see whether a server answers now, without waiting out the retry.
            home.retry()
        }
    }

    SettingsGroup("No Rex Server Here") {
        Text(localServerWords(found), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        RowDivider()
        Row(Modifier.fillMaxWidth().heightIn(min = TOUCH_TARGET).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            for (offer in offers) when (offer) {
                LocalServerOffer.StartServer, LocalServerOffer.StartAtLogin -> {
                    val command = if (offer == LocalServerOffer.StartServer) LocalServerCommand.Start else LocalServerCommand.StartAtLogin
                    TextButton(enabled = running == null, modifier = Modifier.heightIn(min = TOUCH_TARGET), onClick = { run(command) }) {
                        Text(if (running == command) "Running rex ${command.arguments.joinToString(" ")}…" else command.title)
                    }
                }
                LocalServerOffer.InstallRex -> TextButton(modifier = Modifier.heightIn(min = TOUCH_TARGET), onClick = {
                    openLink(REX_INSTALL_DOCUMENTATION) { message, isError -> ui.client.log.add("links", message, isError = isError) }
                }) { Text("How to Install Rex on Linux") }
            }
        }
        // What each button runs, said before it is pressed.
        if (LocalServerOffer.InstallRex !in offers) {
            Text(
                "Start Server runs “rex server start”" + (if (LocalServerOffer.StartAtLogin in offers) ", and Start at Login runs “rex server install”, which has the server start by itself from now on." else "."),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
            )
        }
        output?.let { printed ->
            RowDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
                Text("It didn't work. rex said:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                SelectionContainer { Text(printed, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
    }
}

/** Rex.app's Settings > Server > Client Info: what this client is called in a server's list of clients. */
@Composable
private fun ClientInfo(ui: RexUiState) {
    val client = ui.client
    val scope = rememberCoroutineScope()
    val label by client.clientLabel.collectAsState()
    var typed by remember(label) { mutableStateOf(label) }
    var refusal by remember { mutableStateOf<String?>(null) }
    SettingsGroup("Client Info") {
        SettingsField(
            "Label", typed, { typed = it.replace("\n", ""); refusal = null }, error = refusal, keyboard = KeyboardType.Text,
            note = "What this $APP_NAME is called in a server's list of clients (rex client ls), on every server it connects to. Empty for none.",
        )
        Row(Modifier.fillMaxWidth().heightIn(min = TOUCH_TARGET).padding(end = 6.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = typed.trim() != label, modifier = Modifier.heightIn(min = TOUCH_TARGET), onClick = { scope.launch { refusal = client.setClientLabel(typed) } }) { Text("Save Label") }
        }
    }
}
