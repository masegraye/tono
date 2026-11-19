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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.TextFieldValue
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

/** What a place within Remote Hosts is known by: a host's details, by its label. */
internal fun hostDetail(label: String): String = "host:$label"

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
        CardButton("Add Remote Host…") { state.form = SettingsForm.AddHost }
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
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Icon", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (icon in listOf<HostIcon?>(null) + HostIcon.entries) {
                val selected = icon == chosen
                Box(
                    Modifier.size(TOUCH_TARGET).clip(MaterialTheme.shapes.small)
                        .background(if (selected) colors.secondaryContainer else colors.surfaceContainerLowest)
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

    // What it is, to read. Changing it is a form of its own: Edit…, below.
    val addresses = listing?.endpoints ?: listOf(host.saved.endpoint)
    SettingsGroup(if (addresses.size == 1) "Address" else "Addresses", note = if (addresses.size > 1) "Tried in this order. $APP_NAME connects to the first." else null) {
        InfoRow(if (addresses.size == 1) "This app reaches it at" else "It can be reached at", addresses.joinToString("\n"))
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

    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        CardButton("Edit…") { settings.form = SettingsForm.EditHost(label) }
        CardButton("Remove Host…", color = MaterialTheme.colorScheme.error) {
            settings.confirmation = removeHostQuestion(host) {
                client.removeHost(label)
                // Its details have nothing left to show.
                settings.replace(SettingsPlace(SettingsSection.RemoteHosts))
            }
        }
    }
}

/** Which field of a host's form a refusal is about, going by its words: an address, or else the label, which is what a server most often refuses. */
internal enum class HostField { Label, Address }

internal fun fieldOfRefusal(refusal: String): HostField =
    if (Regex("address|endpoint", RegexOption.IGNORE_CASE).containsMatchIn(refusal) && !Regex("label|called|name", RegexOption.IGNORE_CASE).containsMatchIn(refusal)) HostField.Address else HostField.Label

/**
 * The form a host is changed in, and the one a host is added with: its label, its addresses
 * and its picture, saved together or not at all (design-001). Adding also lists the Rex
 * servers found on the tailnet, and choosing one fills the fields in; nothing is added until
 * Add Host is pressed. A host goes where the palette's Add Host… puts one: the home server's
 * host list, or this app alone when the home server isn't there to keep it.
 *
 * @param editing the label of the host being changed, or null to add one
 */
@Composable
internal fun HostForm(ui: RexUiState, editing: String?, sheet: Boolean) {
    val client = ui.client
    val settings = ui.settings
    val scope = rememberCoroutineScope()
    val hosts by client.hosts.collectAsState()
    val listings by client.hostListings.collectAsState()
    val host = editing?.let { label -> hosts.firstOrNull { it.saved.label == label } }
    val close = { settings.form = null }
    if (editing != null && host == null) {
        // Gone while its form was open: removed here or on another device.
        LaunchedEffect(Unit) { close() }
        return
    }
    val listing = editing?.let { listings[it] }
    val source = editing?.let(client::sourceOf)
    val home = hosts.firstOrNull { it.saved.label == client.homeLabel }
    val keptByHome = if (host != null) host.discovered else home?.state?.collectAsState()?.value?.connection == HostConnection.Connected
    val before = listing?.endpoints ?: listOfNotNull(host?.saved?.endpoint)

    // What is typed, until it is saved. It opens with the host as it is, and stays as typed whatever the host does meanwhile.
    var name by remember { mutableStateOf(selected(editing.orEmpty())) }
    val addresses = remember { mutableStateListOf<TextFieldValue>().apply { addAll(before.ifEmpty { listOf("") }.map { TextFieldValue(it) }) } }
    var icon by remember { mutableStateOf(HostIcon.named(listing?.icon)) }
    var iconChanged by remember { mutableStateOf(false) }
    var refusal by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    if (editing == null) LaunchedEffect(Unit) { ui.tailnetServers = ui.findTailnetServers() }

    val typed = addresses.map { it.text.trim() }.filter { it.isNotEmpty() }
    val label = name.text.trim()
    val changed = if (editing != null) label != editing || typed != before || iconChanged else label.isNotEmpty() || typed.isNotEmpty() || icon != null
    val valid = typed.isNotEmpty() && (editing == null || label.isNotEmpty())

    fun save() {
        busy = true
        scope.launch {
            refusal = if (editing != null) {
                // A picture left as it was keeps the name the list has for it, known here or not.
                client.changeHost(editing, label, typed, if (iconChanged) icon?.listName else listing?.icon)
            } else {
                client.addHostNow(label.ifEmpty { suggestedHostLabel(typed.first()) }, typed.first(), icon?.listName)
            }
            busy = false
            if (refusal == null) {
                // Under a new name its details are somewhere else in the settings: stay with it.
                if (editing != null && label != editing && settings.place?.detail == hostDetail(editing)) settings.replace(SettingsPlace(SettingsSection.RemoteHosts, hostDetail(label)))
                close()
            }
        }
    }

    val wrong = refusal?.let(::fieldOfRefusal)
    FormDialog(
        title = if (editing != null) "Edit $editing" else "Add Remote Host",
        saveLabel = if (busy) (if (editing != null) "Saving…" else "Adding…") else if (editing != null) "Save" else "Add Host",
        canSave = valid && (editing == null || changed), changed = changed, busy = busy, sheet = sheet,
        cancelRequests = settings.formCancelRequests, onSave = ::save, onClose = close,
    ) { first ->
        val colors = MaterialTheme.colorScheme
        if (editing == null) {
            val servers = ui.tailnetServers
            val offered = servers.orEmpty().filter { server -> !client.isConfigured(server.endpoint) && hosts.none { it.saved.label.equals(server.label, ignoreCase = true) } }
            Text("On your tailnet", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(colors.surfaceContainerLowest)) {
                when {
                    servers == null -> InfoLine("Looking for Rex servers on your tailnet…")
                    offered.isEmpty() -> InfoLine(if (servers.isEmpty()) "No Rex server was found on your tailnet." else "Every Rex server on your tailnet is already a host.")
                    else -> offered.forEachIndexed { index, server ->
                        if (index > 0) RowDivider()
                        // Choosing one fills in the fields under it, and adds nothing.
                        val chosen = typed.firstOrNull()?.let(::endpointKey) == endpointKey(server.endpoint)
                        Row(
                            Modifier.fillMaxWidth().background(if (chosen) colors.secondaryContainer else Color.Transparent)
                                .clickable(enabled = !busy) { addresses[0] = TextFieldValue(server.endpoint); name = TextFieldValue(server.label); refusal = null }
                                .heightIn(min = TOUCH_TARGET).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MachineIcon(HostIcon.Server, tint = colors.onSurfaceVariant, size = 20.dp)
                            Column(Modifier.weight(1f).padding(start = 12.dp, top = 6.dp, bottom = 6.dp)) {
                                Text(server.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(server.address, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                            }
                            Text(if (server.online) "Online" else "Offline", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
            Text(
                (if (offered.isNotEmpty()) "Choose one to fill in its address and label, or type an address. " else "") +
                    if (keptByHome) "The host goes in ${client.homeLabel ?: "the home server"}'s host list, so every device that follows it has it."
                    else "The home server isn't connected, so the host is kept by $APP_NAME alone.",
                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )
        }

        // The label comes first for a host that has one to change. A new host is its address first: the label can come from it.
        val labelField: @Composable (FocusRequester?) -> Unit = { focus ->
            FormField(
                name, { name = it; refusal = null }, label = "Label", focus = focus, plain = true,
                placeholder = if (editing == null) typed.firstOrNull()?.let(::suggestedHostLabel).orEmpty() else "",
                hint = if (editing == null) "Left empty, it is named after its address." else null,
                error = refusal.takeIf { wrong == HostField.Label },
            )
        }
        if (editing != null) labelField(first)
        addresses.forEachIndexed { index, address ->
            Row(verticalAlignment = Alignment.Top) {
                FormField(
                    address, { addresses[index] = it; refusal = null },
                    label = if (addresses.size == 1) "Address" else "Address ${index + 1}",
                    placeholder = "studio.tail1234.ts.net", plain = true, keyboard = KeyboardType.Uri,
                    focus = first.takeIf { editing == null && index == 0 },
                    hint = when {
                        index != addresses.lastIndex -> null
                        editing != null && !keptByHome -> "A host kept by $APP_NAME alone has one address, and no icon."
                        addresses.size > 1 -> "Tried in this order. $APP_NAME connects to the first."
                        else -> null
                    },
                    error = refusal.takeIf { wrong == HostField.Address && index == 0 },
                    modifier = Modifier.weight(1f),
                )
                if (addresses.size > 1) TextButton(enabled = !busy, modifier = Modifier.padding(start = 4.dp, top = 6.dp).heightIn(min = TOUCH_TARGET), onClick = { addresses.removeAt(index); refusal = null }) { Text("Remove") }
            }
        }
        // More than one address is something the home server's list keeps, for a host it has already.
        if (editing != null && keptByHome) {
            TextButton(enabled = !busy, modifier = Modifier.heightIn(min = TOUCH_TARGET), onClick = { addresses.add(TextFieldValue("")) }) { Text("Add Another Address") }
        }
        if (editing == null) labelField(null)
        if (keptByHome) IconPicker(icon) { icon = it; iconChanged = true }
        // Said before saving: what is stored takes the declared host's place.
        if (source == HostSource.ConfigurationFile) {
            Text(
                "The configuration file declares this host (${listing?.source ?: "init.lua"}). Saving stores it in ${client.homeLabel ?: "the home server"}'s host list, in place of the declared one. The file itself isn't changed.",
                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** A line that only says something, where a card would otherwise be empty. */
@Composable
internal fun InfoLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp))
}
