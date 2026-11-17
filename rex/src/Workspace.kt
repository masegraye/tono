package org.example.kotlintest.rex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** A Rex server the app connects to every time it starts. */
data class SavedHost(val label: String, val endpoint: String)

/**
 * What the app remembers between runs: the hosts to connect to, and where the user was.
 * Stored as JSON, in the same shape Rex Rune uses for these fields.
 */
class Workspace(
    private val file: Path?,
    hosts: List<SavedHost> = emptyList(),
    /** The label of the host that was showing. */
    var lastHost: String? = null,
    /** For each host label, the session that was selected on it. */
    sessions: Map<String, String> = emptyMap(),
) {
    var hosts: List<SavedHost> = hosts
    val sessions: MutableMap<String, String> = sessions.toMutableMap()

    /** Writes the file through a temporary one, so a crash can't leave it half-written. */
    @Synchronized
    fun save() {
        val target = file ?: return
        val json = buildJsonObject {
            put("hosts", buildJsonArray {
                for (host in hosts) add(buildJsonObject {
                    put("label", host.label)
                    put("endpoint", host.endpoint)
                })
            })
            lastHost?.let { put("last_host", it) }
            putJsonObject("sessions") { for ((host, session) in sessions) put(host, session) }
        }
        runCatching {
            Files.createDirectories(target.parent)
            val temporary = target.resolveSibling("${target.fileName}.tmp")
            Files.writeString(temporary, PRETTY.encodeToString(JsonObject.serializer(), json) + "\n")
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    companion object {
        private val PRETTY = Json { prettyPrint = true }

        /** `$XDG_CONFIG_HOME/kotlin-test/workspace.json`, or under `~/.config`. */
        fun defaultFile(environment: Map<String, String> = System.getenv()): Path {
            val config = environment["XDG_CONFIG_HOME"]?.takeIf { it.isNotEmpty() }?.let(Path::of)
                ?: Path.of(environment["HOME"] ?: System.getProperty("user.home"), ".config")
            return config.resolve("kotlin-test/workspace.json")
        }

        /** Reads the workspace. A missing or unreadable file gives an empty one that saves to [file]. */
        fun load(file: Path = defaultFile()): Workspace {
            val root = runCatching { Json.parseToJsonElement(Files.readString(file)).jsonObject }.getOrNull()
                ?: return Workspace(file)
            val hosts = (root["hosts"] as? JsonArray).orEmpty().mapNotNull { element ->
                val host = element as? JsonObject ?: return@mapNotNull null
                SavedHost(host.string("label") ?: return@mapNotNull null, host.string("endpoint") ?: return@mapNotNull null)
            }
            val sessions = root.obj("sessions")?.mapNotNull { (host, session) ->
                (session as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.let { host to it.content }
            }?.toMap().orEmpty()
            return Workspace(file, hosts, root.string("last_host"), sessions)
        }
    }
}
