package org.example.kotlintest.rexspike

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.example.kotlintest.rex.HostConnection
import org.example.kotlintest.rex.RexClient
import org.example.kotlintest.rex.RexKey
import org.example.kotlintest.rex.Workspace
import java.nio.file.Path

/**
 * `--type <text>`: types the text and Enter into the selected host's focused terminal, the
 * way the app does when you press keys. Unlike everything else here this is not passive:
 * typing claims the terminal for this client.
 */
private suspend fun typeInto(client: RexClient, text: String) {
    val host = client.hosts.value.firstOrNull { it.saved.label == client.selectedHost.value } ?: return
    val window = host.state.value.view?.activeWindow ?: return
    val blockId = window.focusedBlockId ?: window.panes.firstOrNull()?.blockId ?: return
    fun press(name: String, typed: String, unshifted: Int) {
        host.sendKey(blockId, RexKey(name, "", "", typed, unshifted, RexKey.Action.Press))
        host.sendKey(blockId, RexKey(name, "", "", "", unshifted, RexKey.Action.Release))
    }
    for (char in text) {
        when (char) {
            in 'a'..'z' -> press("key_$char", char.toString(), char.code)
            in '0'..'9' -> press("digit_$char", char.toString(), char.code)
            ' ' -> press("space", " ", ' '.code)
            '-' -> press("minus", "-", '-'.code)
            else -> error("The probe can only type lowercase letters, digits, spaces and dashes")
        }
    }
    press("enter", "", 0)
    delay(1_200) // let the shell answer
}

/**
 * Connects to every configured Rex server as the app would, waits for things to settle, and
 * prints what was restored: each host, its sessions, the selected session's tabs and panes,
 * and the text of each live terminal.
 *
 * It only looks. Pass a workspace file to use instead of the app's own.
 */
fun main(args: Array<String>) {
    runBlocking { probe(args) }
    // Connections keep threads alive; this is a one-shot command.
    kotlin.system.exitProcess(0)
}

private suspend fun kotlinx.coroutines.CoroutineScope.probe(args: Array<String>) {
    val typed = args.indexOf("--type").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val workspace = args.firstOrNull()?.takeIf { !it.startsWith("--") }?.let { Workspace.load(Path.of(it)) } ?: Workspace.load()
    val client = RexClient(this, workspace)

    // Give every host a chance to connect (or fail), and discovered hosts time to appear.
    delay(1_500)
    for (host in client.hosts.value) {
        withTimeoutOrNull(6_000) { host.state.first { it.connection != HostConnection.Connecting } }
    }
    // Every session's layout is read in the background, so all their tabs can be listed.
    for (host in client.hosts.value) {
        withTimeoutOrNull(6_000) { host.state.first { state -> state.sessions.all { it.id in state.views } } }
    }
    delay(600) // and each terminal is asked what its program has reported
    // Terminals open after the session's layout arrives; wait for their first frames.
    for (host in client.hosts.value) {
        withTimeoutOrNull(6_000) { host.state.first { state -> state.terminals.isNotEmpty() && state.terminals.values.all { it.frame.value != null } } }
    }

    if (typed != null) typeInto(client, typed)

    for (host in client.hosts.value) {
        val state = host.state.value
        val selected = if (host.saved.label == client.selectedHost.value) " (selected)" else ""
        println("host ${host.saved.label}$selected  ${host.saved.endpoint}${if (host.discovered) "  [from the home server's host list]" else ""}")
        println("  ${state.connection}  api ${state.apiRevision}  client ${state.clientId}")
        for (session in state.sessions) {
            println("  session ${session.label}${if (session.id == state.selectedSessionId) "  <- showing" else ""}")
            val layout = state.views[session.id]
            if (layout == null) println("    (layout not read)")
            for (window in layout?.windows.orEmpty()) {
                val activity = state.activityOf(window).takeIf { it != org.example.kotlintest.rex.ActivityState.Idle }?.let { "  [${it.name.lowercase()}]" }.orEmpty()
                val name = state.nameOf(window)
                println("    tab ${listOfNotNull(name.text, name.directory).joinToString("  ")}${if (name.text != window.label) "  (label: ${window.label})" else ""}${if (window.id == layout?.activeWindowId) "  <- active" else ""}  (${window.panes.size} pane${if (window.panes.size == 1) "" else "s"})$activity")
            }
        }
        val view = state.view ?: continue
        for ((blockId, terminal) in state.terminals) {
            val frame = terminal.frame.value
            val pane = view.activeWindow?.panes?.firstOrNull { it.blockId == blockId }
            println("    terminal $blockId  ${terminal.status.value}  grid ${terminal.grid.value}  rect ${pane?.rect}")
            if (frame == null) continue
            val runs = frame.rows.sumOf { it.runs.size }
            println("      frame: ${frame.rows.size} rows, $runs runs, cursor ${frame.cursor?.let { "${it.column},${it.row} ${it.shape}" }}")
            frame.rows.map { it.text() }.filter { it.isNotBlank() }.takeLast(6).forEach { println("      | ${it.take(110)}") }
        }
    }
    client.close()
}
