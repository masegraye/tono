package org.example.kotlintest.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import org.example.kotlintest.rex.tailnetRexServers
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * What the web Tono needs from the machine it is served by, because a page can't get it:
 *
 * - `GET /ghostty-vt.wasm`: the terminal library, built by `task native:wasm`.
 * - `GET /api/tono/font/{style}`: the desktop's monospace font (regular, bold, italic or
 *   bolditalic), so terminals are drawn in the same font as on the desktop, with the same
 *   symbols. A page has only the fonts it is given.
 * - `GET /api/tono/sounds/{name}`: the desktop's warning, error and completion sounds.
 * - `GET /api/tono/tailnet`: the Rex servers on the tailnet, as `tailscale` reports them.
 *
 * @param library the WebAssembly build of libghostty-vt, or null to look for it in a checkout
 */
fun Application.tonoAssets(library: File? = null) {
    val wasm = library ?: findInCheckout("native/out/wasm/ghostty-vt.wasm")
    routing {
        get("/ghostty-vt.wasm") {
            if (wasm?.isFile == true) call.respondFile(wasm)
            else call.respondText("The terminal library isn't built: run `task native:wasm`.", status = HttpStatusCode.NotFound)
        }
        get("/api/tono/font/{style}") {
            val file = FONT_STYLES[call.parameters["style"]]?.let { desktopFont(it) }
            if (file != null) call.respondFile(file) else call.respond(HttpStatusCode.NotFound)
        }
        get("/api/tono/sounds/{name}") {
            // Three fixed names: nothing a request says ends up in a path.
            val file = SOUNDS.firstOrNull { it == call.parameters["name"] }?.let { name ->
                SOUND_DIRECTORIES.map { File(it, "$name.oga") }.firstOrNull(File::isFile)
            }
            if (file != null) call.respondFile(file) else call.respond(HttpStatusCode.NotFound)
        }
        get("/api/tono/tailnet") {
            val servers = tailnetRexServers()
            call.respondText(
                buildJsonArray {
                    for (server in servers) addJsonObject {
                        put("name", server.name)
                        put("address", server.address)
                        put("online", server.online)
                    }
                }.toString(),
                ContentType.Application.Json,
            )
        }
    }
}

/** A file of this checkout, looking up from the working directory, as the desktop app finds the native library. */
private fun findInCheckout(relative: String): File? =
    generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, relative) }.firstOrNull(File::isFile)

/** The file fontconfig resolves a monospace [pattern] to: the font Omarchy has set. */
private suspend fun desktopFont(pattern: String): File? = withContext(Dispatchers.IO) {
    runCatching {
        val process = ProcessBuilder("fc-match", "-f", "%{file}", pattern).redirectErrorStream(false).start()
        val path = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        // A collection (.ttc) holds several fonts in one file, which the page's text engine can't pick from.
        File(path).takeIf { it.isFile && it.extension.lowercase() in setOf("ttf", "otf") }
    }.getOrNull()
}

private val FONT_STYLES = mapOf(
    "regular" to "monospace",
    "bold" to "monospace:bold",
    "italic" to "monospace:italic",
    "bolditalic" to "monospace:bold:italic",
)
private val SOUNDS = listOf("dialog-warning", "dialog-error", "complete")
private val SOUND_DIRECTORIES = listOf("/usr/local/share/sounds/freedesktop/stereo", "/usr/share/sounds/freedesktop/stereo")
