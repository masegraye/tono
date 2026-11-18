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
                if (tabs.isNotEmpty()) putJsonObject("tabs") { for ((session, tab) in tabs.entries.sortedBy { it.key }) put(session, tab) }
                if (collapsedHosts.isNotEmpty()) put("collapsed_hosts", buildJsonArray { collapsedHosts.sorted().forEach { add(JsonPrimitive(it)) } })
                if (collapsedSessions.isNotEmpty()) put("collapsed_sessions", buildJsonArray { collapsedSessions.sorted().forEach { add(JsonPrimitive(it)) } })
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
            )
        }
    }
}
