package org.example.kotlintest.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A device on the tailnet: its name, the user who owns it, and whether it is a tagged machine with no owner. */
class TailnetDevice(val name: String?, val login: String?, val tagged: Boolean)

/** What the tailnet knows about its devices. */
interface TailnetDirectory {
    /** This machine, or null when Tailscale isn't running. */
    suspend fun self(): TailnetDevice?

    /** The device that has [address], or null if the tailnet has none there. */
    suspend fun whoIs(address: String): TailnetDevice?
}

/**
 * The tailnet as the `tailscale` command on this machine reports it. Answers are kept for a
 * minute: one page load is dozens of requests, and who owns a device doesn't change often.
 */
object TailscaleCli : TailnetDirectory {
    private class Kept(val device: TailnetDevice?, val at: Long)

    private val kept = ConcurrentHashMap<String, Kept>()

    override suspend fun self(): TailnetDevice? = remembered("") {
        val status = run("status", "--json") ?: return@remembered null
        val self = status["Self"] as? JsonObject ?: return@remembered null
        val user = (self["UserID"] as? JsonPrimitive)?.content
        val login = ((status["User"] as? JsonObject)?.get(user) as? JsonObject)?.text("LoginName")
        TailnetDevice(self.text("HostName"), login, self.isTagged())
    }

    override suspend fun whoIs(address: String): TailnetDevice? = remembered(address) {
        // The address is checked to be one written out before it gets here (isTailnetAddress).
        val answer = run("whois", "--json", address) ?: return@remembered null
        val node = answer["Node"] as? JsonObject ?: return@remembered null
        TailnetDevice(node.text("ComputedName") ?: node.text("Name"), (answer["UserProfile"] as? JsonObject)?.text("LoginName"), node.isTagged())
    }

    private suspend fun remembered(key: String, find: suspend () -> TailnetDevice?): TailnetDevice? {
        val now = System.nanoTime()
        kept[key]?.takeIf { now - it.at < KEEP_NANOS }?.let { return it.device }
        return find().also { kept[key] = Kept(it, now) }
    }

    private suspend fun run(vararg arguments: String): JsonObject? = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder(listOf("tailscale") + arguments).redirectErrorStream(false).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            if (process.exitValue() == 0) Json.parseToJsonElement(output).jsonObject else null
        }.getOrNull()
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
    private fun JsonObject.isTagged(): Boolean = (this["Tags"] as? JsonArray)?.isNotEmpty() == true

    private val KEEP_NANOS = TimeUnit.SECONDS.toNanos(60)
}
