package org.example.kotlintest.rex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Somewhere a workspace can be kept: a file on the desktop, the browser's own storage on the web. */
interface WorkspaceStorage {
    /** What was last written, or null if nothing was. */
    fun read(): String?

    fun write(text: String)
}

/** A Rex server the app connects to every time it starts. */
data class SavedHost(val label: String, val endpoint: String)

/**
 * Which things the desktop is asked to show a notification for. They are told apart as Rex
 * Rune tells them, and are written to the workspace under its names.
 *
 * @param desktop whether any are shown at all; the rest say which
 * @param blocked a program in a tab out of view started waiting for the user
 * @param error one failed
 * @param done one finished
 * @param programMessages a program asked for one itself, with OSC 9 or OSC 777
 */
data class NotificationSwitches(
    val desktop: Boolean = true,
    val blocked: Boolean = true,
    val error: Boolean = true,
    val done: Boolean = true,
    val programMessages: Boolean = true,
) {
    internal fun toJson(): JsonObject = buildJsonObject {
        put("system_notifications", desktop)
        put("blocked", blocked)
        put("error", error)
        put("done", done)
        put("program_messages", programMessages)
    }

    companion object {
        /** Reads what [toJson] writes. A switch that is missing, or isn't a yes or a no, is on. */
        internal fun parse(json: JsonObject?): NotificationSwitches {
            fun on(key: String) = (json?.get(key) as? JsonPrimitive)?.content != "false"
            return NotificationSwitches(on("system_notifications"), on("blocked"), on("error"), on("done"), on("program_messages"))
        }
    }
}

/**
 * What selecting text with the pointer does besides selecting it. Rex Rune's two switches,
 * written to the workspace under its names, and off unless turned on, as there.
 *
 * @param copyOnSelection a selection the pointer has finished goes to the clipboard
 * @param primarySelection it becomes the desktop's primary selection, and a middle click
 *   pastes that selection. A browser has no primary selection: there the clipboard stands in.
 */
data class SelectionSwitches(val copyOnSelection: Boolean = false, val primarySelection: Boolean = false) {
    internal fun toJson(): JsonObject = buildJsonObject {
        put("copy_on_selection", copyOnSelection)
        put("primary_selection", primarySelection)
    }

    companion object {
        /** Reads what [toJson] writes. A switch that is missing, or isn't a yes, is off. */
        internal fun parse(json: JsonObject?): SelectionSwitches {
            fun on(key: String) = (json?.get(key) as? JsonPrimitive)?.content == "true"
            return SelectionSwitches(on("copy_on_selection"), on("primary_selection"))
        }
    }
}

/**
 * What the app remembers between runs: the hosts to connect to, and where the user was.
 * Stored as JSON, in the same shape Rex Rune uses for these fields.
 */
class Workspace(
    /** Where it is kept, or null for one that is never saved. */
    private val storage: WorkspaceStorage?,
    hosts: List<SavedHost> = emptyList(),
    /** The label of the host that was showing. */
    var lastHost: String? = null,
    /** For each host label, the session that was selected on it. */
    sessions: Map<String, String> = emptyMap(),
    /** The labels of the hosts folded away in the sidebar. */
    var collapsedHosts: Set<String> = emptySet(),
    /** The ids of the sessions folded away in the sidebar. */
    var collapsedSessions: Set<String> = emptySet(),
    /** For each session id, the tab this app was showing in it. */
    tabs: Map<String, String> = emptyMap(),
    /** Whether to play a sound when a program in a tab out of view wants the user, fails, or finishes. */
    var sounds: Boolean = true,
    /** What to show a desktop notification for. */
    var notifications: NotificationSwitches = NotificationSwitches(),
    /** What selecting with the pointer copies, and whether the middle button pastes. */
    var selection: SelectionSwitches = SelectionSwitches(),
    /** The tabs this app keeps quiet. This client's alone: the servers never hear of them. */
    var mutedTabs: List<MutedTab> = emptyList(),
) {
    val tabs: MutableMap<String, String> = tabs.toMutableMap()
    var hosts: List<SavedHost> = hosts
    val sessions: MutableMap<String, String> = sessions.toMutableMap()

    /** Writes it out. Several threads may ask at once; one writes at a time. */
    fun save() {
        locked(this) {
            val target = storage ?: return@locked
            val json = buildJsonObject {
                put("hosts", buildJsonArray {
                    for (host in hosts) add(buildJsonObject {
                        put("label", host.label)
                        put("endpoint", host.endpoint)
                    })
                })
                lastHost?.let { put("last_host", it) }
                putJsonObject("sessions") { for ((host, session) in sessions) put(host, session) }
                if (!sounds) put("sounds", false)
                // Always written, every switch: until there is a settings screen, this file is where they are found.
                put("notifications", notifications.toJson())
                // The same goes for these, which Rex Rune keeps under its terminal settings.
                put("terminal", selection.toJson())
                if (tabs.isNotEmpty()) putJsonObject("tabs") { for ((session, tab) in tabs.entries.sortedBy { it.key }) put(session, tab) }
                if (collapsedHosts.isNotEmpty()) put("collapsed_hosts", buildJsonArray { collapsedHosts.sorted().forEach { add(JsonPrimitive(it)) } })
                if (collapsedSessions.isNotEmpty()) put("collapsed_sessions", buildJsonArray { collapsedSessions.sorted().forEach { add(JsonPrimitive(it)) } })
                if (mutedTabs.isNotEmpty()) put("muted_tabs", buildJsonArray {
                    for (tab in mutedTabs) add(buildJsonObject {
                        put("endpoint", tab.endpoint)
                        put("session", tab.session)
                        put("window", tab.window)
                    })
                })
            }
            runCatching { target.write(PRETTY.encodeToString(JsonObject.serializer(), json) + "\n") }
        }
    }

    companion object {
        private val PRETTY = Json { prettyPrint = true }

        /** Reads the workspace from [storage]. If there is nothing there, or nothing readable, it is an empty one that saves there. */
        fun load(storage: WorkspaceStorage): Workspace {
            val root = runCatching { storage.read()?.let { Json.parseToJsonElement(it).jsonObject } }.getOrNull()
                ?: return Workspace(storage)
            val hosts = (root["hosts"] as? JsonArray).orEmpty().mapNotNull { element ->
                val host = element as? JsonObject ?: return@mapNotNull null
                SavedHost(host.string("label") ?: return@mapNotNull null, host.string("endpoint") ?: return@mapNotNull null)
            }
            fun map(key: String) = root.obj(key)?.mapNotNull { (name, value) ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.let { name to it.content }
            }?.toMap().orEmpty()
            val sessions = map("sessions")
            fun strings(key: String) = (root[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }.toSet()
            return Workspace(storage, hosts, root.string("last_host"), sessions, strings("collapsed_hosts"), strings("collapsed_sessions"), map("tabs"),
                sounds = (root["sounds"] as? JsonPrimitive)?.content != "false",
                notifications = NotificationSwitches.parse(root.obj("notifications")),
                selection = SelectionSwitches.parse(root.obj("terminal")),
                mutedTabs = (root["muted_tabs"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val tab = element as? JsonObject ?: return@mapNotNull null
                    MutedTab(tab.string("endpoint") ?: return@mapNotNull null, tab.string("session") ?: return@mapNotNull null, tab.string("window") ?: return@mapNotNull null)
                }.takeLast(MUTED_TABS_LIMIT),
            )
        }
    }
}
