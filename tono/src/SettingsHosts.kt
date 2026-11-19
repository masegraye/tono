package org.example.kotlintest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.kotlintest.rex.HostConnection
import org.example.kotlintest.rex.HostListing
import org.example.kotlintest.rex.HostReach
import org.example.kotlintest.rex.HostSource
import org.example.kotlintest.rex.HostState
import org.example.kotlintest.rex.RexHost
import org.example.kotlintest.rex.endpointKey
import org.example.kotlintest.rex.hostOrderAfterMove
import org.example.kotlintest.rex.hostOrderAfterStep
import org.example.kotlintest.rex.suggestedHostLabel

/*
 * Remote Hosts, after Rex.app's Settings > Remote Hosts: every host the app connects to in
 * one card, each with its picture, its name over its address, and how reaching it stands.
 * A host's details are where it is changed: its name, its addresses and its picture, its
 * place in the list, and whether it is a host at all.
 *
 * Where a host is kept decides what a change touches, so every host says where: the home
 * server's host list, which every device following that server shares; Rex's configuration
 * file, which declares hosts the list then carries; or this app alone.
 *
 * The order is this app's. Rex's host list has none (it comes sorted by name), so the order
 * someone arranges is kept in the workspace, and the sidebar follows it.
 */

/** What a place within Remote Hosts is known by: a host's details, by its label, or the form that adds one. */
internal fun hostDetail(label: String): String = "host:$label"
internal const val ADD_HOST_DETAIL = "add"

/** The label of the host a place's detail is about, or null if it is about something else. */
internal fun hostOfDetail(detail: String?): String? = detail?.takeIf { it.startsWith("host:") }?.removePrefix("host:")

/**
 * How reaching a host stands, for its line in the list, in Rex.app's words where it has
 * them: one that is down says so plainly, and why is in its details.
 */
internal fun hostStatusLine(reach: HostReach): String = when (reach) {
    HostReach.Connected -> "Connected"
    HostReach.Connecting -> "Connecting…"
    HostReach.Reconnecting -> "Reconnecting…"
    HostReach.Down -> "Couldn't connect"
}

/** Where a host is kept and what a change to it touches, in a sentence or two. */
internal fun hostSourceWords(source: HostSource, home: String, listing: HostListing?): String = when (source) {
    HostSource.Home -> "This is the home server: the one $APP_NAME follows for its hosts and its shortcuts. It is kept in $APP_NAME's workspace, and can't be removed."
    HostSource.HomeList -> "From $home's host list. Every device that follows $home has this host, and sees a change made here." +
        listing?.addedFrom?.takeIf { it.isNotBlank() }?.let { " It was added from $it." }.orEmpty()
    HostSource.ConfigurationFile -> "Declared in Rex's configuration file on $home (${listing?.source ?: "init.lua"}). " +
        "A change made here is stored in $home's host list and takes the declared host's place, for every device. The file itself isn't changed."
    HostSource.ThisApp -> "Kept by $APP_NAME alone, in its workspace. No other device has it."
}

/** The question asked before a host is removed, in the same words wherever it is asked. */
internal fun removeHostQuestion(host: RexHost, onConfirm: () -> Unit) = Confirmation(
    title = "Remove host “${host.saved.label}”?",
    text = if (host.discovered) "It leaves your Rex host list, so the rex command and other clients that follow this machine stop listing it too. Its sessions keep running."
    else "This app stops connecting to it. Its sessions keep running.",
    confirmLabel = "Remove Host",
    onConfirm = onConfirm,
)

/** The name Rex for Mac writes for each picture, which is what the host list keeps. */
internal val HostIcon.listName: String
    get() = when (this) {
        HostIcon.Globe -> "globe"
        HostIcon.Server -> "server.rack"
        HostIcon.Cloud -> "cloud"
        HostIcon.Laptop -> "macbook"
        HostIcon.Mini -> "macmini"
        HostIcon.Studio -> "macstudio"
        HostIcon.Desktop -> "desktopcomputer"
    }

/** What a picture is called where one is chosen. */
internal val HostIcon.title: String
    get() = when (this) {
        HostIcon.Globe -> "Globe"
        HostIcon.Server -> "Server"
        HostIcon.Cloud -> "Cloud"
        HostIcon.Laptop -> "Laptop"
        HostIcon.Mini -> "Mini"
        HostIcon.Studio -> "Studio"
        HostIcon.Desktop -> "Desktop"
    }

/** The addresses typed one to a line, as a list: blank lines and the space round an address are set aside. */
internal fun addressesTyped(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

/** The hosts in the order the sidebar lists them, each with its state, read here so that the list follows every one of them. */
@Composable
private fun orderedHosts(ui: RexUiState): List<Pair<RexHost, HostState>> {
    val hosts by ui.client.hosts.collectAsState()
    val order by ui.client.hostOrder.collectAsState()
    return sidebarOrder(hosts.map { host -> host to key(host) { host.state.collectAsState().value } }, ui.client.homeLabel, order) { it.saved.label }
}

/**
 * @param wide whether there is room for the handle a host is dragged by and the button for
 *   its details. Without it the whole line opens the details, where a host is moved by buttons.
 */
@Composable
internal fun RemoteHostSettings(ui: RexUiState, place: SettingsPlace, wide: Boolean) {
    val label = hostOfDetail(place.detail)
    when {
        place.detail == ADD_HOST_DETAIL -> ScrollingSection { AddHostForm(ui) }
        label != null -> ScrollingSection { HostDetails(ui, label) }
        else -> ScrollingSection { HostList(ui, wide) }
    }
}

@Composable
private fun HostList(ui: RexUiState, wide: Boolean) {
    val client = ui.client
    val state = ui.settings
    val ordered = orderedHosts(ui)
    val icons by client.hostIcons.collectAsState()
    val listings by client.hostListings.collectAsState()
    val home = client.homeLabel
    val movable = ordered.map { it.first.saved.label }.filter { it != home }
    val reorder = rememberReorder(movable) { moved, before -> client.setHostOrder(hostOrderAfterMove(movable, moved, before)) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        ordered.forEachIndexed { index, (host, hostState) ->
            key(host) {
                val name = host.saved.label
                val canMove = wide && name != home && movable.size > 1
                Column(if (name != home) Modifier.reorderItem(reorder, name).background(MaterialTheme.colorScheme.surfaceContainerHigh) else Modifier) {
                    if (index > 0) RowDivider()
                    HostRow(
                        label = name,
                        // Every address the list has for it; this app connects to the first.
                        address = listings[name]?.endpoints?.firstOrNull() ?: host.saved.endpoint,
                        icon = HostIcon.of(icons[name], isHome = name == home),
                        reach = hostState.reach,
                        handle = if (canMove) Modifier.reorderHandle(reorder, name) else null,
                        details = wide,
                        onDetails = { state.go(SettingsPlace(SettingsSection.RemoteHosts, hostDetail(name))) },
                    )
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (movable.size > 1) "The order is $APP_NAME's own, and the sidebar follows it." else "",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 6.dp, end = 8.dp),
        )
        CardButton("Add Remote Host…") { state.go(SettingsPlace(SettingsSection.RemoteHosts, ADD_HOST_DETAIL)) }
    }
}

/** A button that stands on the panel as a card of its own, as Rex.app's Add Remote Host… does. */
@Composable
internal fun CardButton(text: String, enabled: Boolean = true, color: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Box(
        Modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick).heightIn(min = TOUCH_TARGET).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color.copy(alpha = if (enabled) 1f else 0.4f))
    }
}

/**
 * One host's line: what it is dragged by, its picture, its name over its address, how
 * reaching it stands, and the way to its details. The whole line leads to the details too.
 *
 * @param handle makes the handle what the line is dragged by, or null for a line that has none
 * @param details whether the line has a button for the details of its own
 */
@Composable
internal fun HostRow(label: String, address: String, icon: HostIcon, reach: HostReach, handle: Modifier?, details: Boolean, onDetails: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val down = reach == HostReach.Down || reach == HostReach.Reconnecting
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onDetails).heightIn(min = 56.dp).padding(start = if (handle != null) 0.dp else 14.dp, end = if (details) 0.dp else 14.dp),
    ) {
        if (handle != null) {
            Box(handle.size(TOUCH_TARGET).semantics { contentDescription = "Drag $label to another place" }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Menu, contentDescription = null, tint = colors.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
            }
        }
        MachineIcon(icon, tint = colors.onSurfaceVariant, size = 20.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 6.dp, bottom = 6.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(address, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.padding(top = 1.dp))
        }
        Text(hostStatusLine(reach), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        // A host that can't be reached is marked, in the color the sidebar draws it in.
        if (down) Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.error, modifier = Modifier.padding(start = 6.dp).size(16.dp))
        if (details) HeadingButton(Icons.Filled.Info, "Details of $label", onDetails)
    }
}

/** A line to type in, under its name, with what is wrong with what was typed under it, if anything is. */
@Composable
internal fun SettingsField(
    label: String, value: String, onChange: (String) -> Unit,
    placeholder: String = "", note: String? = null, error: String? = null, lines: Int = 1, keyboard: KeyboardType = KeyboardType.Uri,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        TextField(
            value = value,
            onValueChange = onChange,
            singleLine = lines == 1,
            minLines = lines,
            maxLines = if (lines == 1) 1 else 8,
            isError = error != null,
            placeholder = { if (placeholder.isNotEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant.copy(alpha = 0.6f), maxLines = 1) },
            textStyle = MaterialTheme.typography.bodyMedium,
            // Addresses and names are typed as they are: nothing capitalized or corrected.
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = keyboard),
            shape = MaterialTheme.shapes.small,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = colors.background, unfocusedContainerColor = colors.background, errorContainerColor = colors.background,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, errorIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        if (error != null) Text(error, style = MaterialTheme.typography.labelMedium, color = colors.error, modifier = Modifier.padding(top = 4.dp))
        else if (note != null) Text(note, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * The pictures a host can have, to choose one from, and none. They are the seven backlog-027
 * draws, kept in the host list under the names Rex for Mac gives them.
 *
 * @param chosen the picture it has, or null for none, or for a name this app has no picture for
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IconPicker(chosen: HostIcon?, onChoose: (HostIcon?) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text("Icon", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (icon in listOf<HostIcon?>(null) + HostIcon.entries) {
                val selected = icon == chosen
                Box(
                    Modifier.size(TOUCH_TARGET).clip(MaterialTheme.shapes.small)
                        .background(if (selected) colors.secondaryContainer else colors.background)
                        .border(1.dp, if (selected) colors.primary else Color.Transparent, MaterialTheme.shapes.small)
                        .clickable { onChoose(icon) }
                        .semantics { contentDescription = icon?.title ?: "No icon" },
                    contentAlignment = Alignment.Center,
                ) {
                    if (icon != null) MachineIcon(icon, tint = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant, size = 20.dp)
                    else Text("None", style = MaterialTheme.typography.labelSmall, color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant)
                }
            }
        }
    }
}

/** A host's details: how it stands, where it is kept, and everything that can be done to it. */
@Composable
private fun HostDetails(ui: RexUiState, label: String) {
    val client = ui.client
    val settings = ui.settings
    val ordered = orderedHosts(ui)
    val listings by client.hostListings.collectAsState()
    val icons by client.hostIcons.collectAsState()
    val found = ordered.firstOrNull { it.first.saved.label == label }
    if (found == null) {
        // Removed here a moment ago, or on another device, or renamed there.
        Text("$label is no longer a host.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 14.dp))
        return
    }
    val (host, state) = found
    val home = client.homeLabel
    val isHome = label == home
    val listing = listings[label]
    val source = client.sourceOf(label) ?: HostSource.ThisApp
    val scope = rememberCoroutineScope()

    SettingsGroup("Status") {
        Row(Modifier.fillMaxWidth().heightIn(min = TOUCH_TARGET).padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(hostStatusLine(state.reach), style = MaterialTheme.typography.bodyMedium)
                // Why, in the words the attempt gave, while it isn't connected.
                state.lastFailure?.takeIf { state.reach != HostReach.Connected }?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (offersRetry(state)) TextButton(onClick = host::retry, modifier = Modifier.heightIn(min = TOUCH_TARGET)) { Text("Try Again Now") }
        }
    }

    SettingsGroup("Where It Comes From") {
        Text(hostSourceWords(source, home ?: "the home server", listing), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
    }

    // What the server says of itself, for any host that is connected: the home server's is in Server too.
    if (state.reach == HostReach.Connected) SettingsGroup("Rex Server") { ServerDetails(host, state) }

    if (isHome) {
        SettingsGroup("Address") { InfoRow("This app reaches it at", host.saved.endpoint) }
        return
    }

    // What is being typed, until it is saved. It starts again from the host whenever the host changes under it.
    val addresses = listing?.endpoints ?: listOf(host.saved.endpoint)
    var name by remember(label) { mutableStateOf(label) }
    var typed by remember(label, addresses) { mutableStateOf(addresses.joinToString("\n")) }
    var icon by remember(label, listing?.icon) { mutableStateOf(HostIcon.named(listing?.icon)) }
    var iconChanged by remember(label, listing?.icon) { mutableStateOf(false) }
    var refusal by remember(label) { mutableStateOf<String?>(null) }
    var saving by remember(label) { mutableStateOf(false) }
    val changed = name.trim() != label || addressesTyped(typed) != addresses || iconChanged
    val listed = host.discovered

    SettingsGroup("Edit") {
        // What the server refuses is about the name more often than not (another host has it), and is said in its own words.
        SettingsField("Label", name, { name = it; refusal = null }, error = refusal, keyboard = KeyboardType.Text)
        RowDivider()
        if (listed) {
            SettingsField("Addresses", typed, { typed = it; refusal = null }, lines = 2, note = "One to a line, in the order they are tried. $APP_NAME connects to the first.")
            RowDivider()
            IconPicker(icon) { icon = it; iconChanged = true }
        } else {
            SettingsField("Address", typed, { typed = it.replace("\n", ""); refusal = null }, note = "A host kept by $APP_NAME alone has one address, and the picture every such host has.")
        }
        RowDivider()
        Row(Modifier.fillMaxWidth().heightIn(min = TOUCH_TARGET).padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                // Said before saving: what is stored takes the declared host's place.
                if (source == HostSource.ConfigurationFile) "Saving stores this host in ${home ?: "the home server"}'s host list, in place of the one the configuration file declares." else "",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(end = 8.dp, top = 6.dp, bottom = 6.dp),
            )
            TextButton(
                enabled = changed && !saving,
                modifier = Modifier.heightIn(min = TOUCH_TARGET),
                onClick = {
                    saving = true
                    scope.launch {
                        // A picture left as it was keeps the name the list has for it, known here or not.
                        val iconName = if (iconChanged) icon?.listName else listing?.icon
                        refusal = client.changeHost(label, name, addressesTyped(typed), iconName)
                        saving = false
                        // Under a new name it is somewhere else in the settings: stay with it.
                        if (refusal == null && name.trim() != label) settings.replace(SettingsPlace(SettingsSection.RemoteHosts, hostDetail(name.trim())))
                    }
                },
            ) { Text(if (saving) "Saving…" else "Save Changes") }
        }
    }

    val movable = ordered.map { it.first.saved.label }.filter { it != home }
    val at = movable.indexOf(label)
    if (movable.size > 1) {
        SettingsGroup("Place in the List", note = "The order is $APP_NAME's own: Rex's host list has none. The sidebar follows it.") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${at + 1} of ${movable.size}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
                TextButton(enabled = at > 0, modifier = Modifier.heightIn(min = TOUCH_TARGET), onClick = { client.setHostOrder(hostOrderAfterStep(movable, label, up = true)) }) { Text("Move Up") }
                TextButton(enabled = at < movable.lastIndex, modifier = Modifier.heightIn(min = TOUCH_TARGET), onClick = { client.setHostOrder(hostOrderAfterStep(movable, label, up = false)) }) { Text("Move Down") }
            }
        }
    }

    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.End) {
        CardButton("Remove Host…", color = MaterialTheme.colorScheme.error) {
            settings.confirmation = removeHostQuestion(host) {
                client.removeHost(label)
                // Its details have nothing left to show.
                settings.replace(SettingsPlace(SettingsSection.RemoteHosts))
            }
        }
    }
}

/**
 * Adding a host: the choice the palette's Add Host… offers, a Rex server found on the
 * tailnet or an address typed in, with a name and a picture for it if wanted. It goes where
 * the palette's goes: the home server's host list, or this app alone when the home server
 * isn't there to keep it.
 */
@Composable
private fun AddHostForm(ui: RexUiState) {
    val client = ui.client
    val settings = ui.settings
    val scope = rememberCoroutineScope()
    val hosts by client.hosts.collectAsState()
    val home = hosts.firstOrNull { it.saved.label == client.homeLabel }
    val homeState = home?.state?.collectAsState()?.value
    var address by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf<HostIcon?>(null) }
    var refusal by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    // Looked for each time the form opens: machines come and go. What was found last time shows meanwhile.
    LaunchedEffect(Unit) { ui.tailnetServers = ui.findTailnetServers() }

    fun add(label: String, endpoint: String, picture: HostIcon?) {
        adding = true
        scope.launch {
            refusal = client.addHostNow(label, endpoint, picture?.listName)
            adding = false
            if (refusal == null) settings.replace(SettingsPlace(SettingsSection.RemoteHosts))
        }
    }

    val keptByHome = homeState?.connection == HostConnection.Connected
    SettingsGroup(
        "On Your Tailnet",
        note = (if (offersTailnet(ui, hosts)) "Choose one to fill in its address and name below. " else "") + if (keptByHome) "A host added here goes in ${client.homeLabel ?: "the home server"}'s host list, so every device that follows it has it."
        else "The home server isn't connected, so a host added now is kept by $APP_NAME alone.",
    ) {
        val servers = ui.tailnetServers
        val offered = servers.orEmpty().filter { server -> !client.isConfigured(server.endpoint) && hosts.none { it.saved.label.equals(server.label, ignoreCase = true) } }
        when {
            servers == null -> InfoLine("Looking for Rex servers on your tailnet…")
            offered.isEmpty() -> InfoLine(if (servers.isEmpty()) "No Rex server was found on your tailnet." else "Every Rex server on your tailnet is already a host.")
            else -> offered.forEachIndexed { index, server ->
                if (index > 0) RowDivider()
                // Choosing one fills in the form under it. Nothing is added until Add Host is pressed:
                // a line pressed by mistake, on a page where lines lead somewhere, adds no machine.
                val chosen = endpointKey(address) == endpointKey(server.endpoint)
                Row(
                    Modifier.fillMaxWidth().background(if (chosen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable(enabled = !adding) { address = server.endpoint; name = server.label; refusal = null }
                        .heightIn(min = 56.dp).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MachineIcon(HostIcon.Server, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 20.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp, top = 6.dp, bottom = 6.dp)) {
                        Text(server.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(server.address, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                    Text(if (server.online) "Online" else "Offline", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    SettingsGroup("By Address") {
        SettingsField("Address", address, { address = it.replace("\n", ""); refusal = null }, placeholder = "studio.tail1234.ts.net", error = refusal)
        RowDivider()
        // Left empty, it is named after its address, as the palette names it.
        SettingsField("Label", name, { name = it; refusal = null }, placeholder = address.trim().takeIf { it.isNotEmpty() }?.let(::suggestedHostLabel).orEmpty(), keyboard = KeyboardType.Text)
        if (keptByHome) {
            RowDivider()
            IconPicker(icon) { icon = it }
        }
        RowDivider()
        Row(Modifier.fillMaxWidth().heightIn(min = TOUCH_TARGET).padding(end = 6.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                enabled = address.isNotBlank() && !adding,
                modifier = Modifier.heightIn(min = TOUCH_TARGET),
                onClick = { add(name.trim().ifEmpty { suggestedHostLabel(address) }, address, icon) },
            ) { Text(if (adding) "Adding…" else "Add Host") }
        }
    }
}

/** Whether there is a Rex server on the tailnet that isn't a host yet, to choose from. */
private fun offersTailnet(ui: RexUiState, hosts: List<RexHost>): Boolean =
    ui.tailnetServers.orEmpty().any { server -> !ui.client.isConfigured(server.endpoint) && hosts.none { it.saved.label.equals(server.label, ignoreCase = true) } }

/** A line that only says something, where a card would otherwise be empty. */
@Composable
internal fun InfoLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp))
}
