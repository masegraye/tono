package org.example.kotlintest.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.mg.omarchy.theme.installedFontFile
import dev.mg.omarchy.theme.omarchyIsPresent
import dev.mg.omarchy.theme.readInstalledMonospaceFonts
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
 *   symbols. A page has only the fonts it is given. It is Omarchy's font: on a machine
 *   without Omarchy there is none, and the page uses its own.
 *   With `?family=NAME` it is that installed monospace font instead, and with `&weight=300`
 *   (or another hundred) its regular and italic styles are of that weight.
 * - `GET /api/tono/fonts`: the monospace fonts installed here, which can be asked for by
 *   name as above, each with the weights it comes in.
 * - `GET /api/tono/sounds/{name}`: the desktop's warning, error and completion sounds.
 * - `GET /api/tono/tailnet`: the Rex servers on the tailnet, as `tailscale` reports them.
 * - `GET /api/tono/whoami`: which device the page is open on, so it can say so to the Rex
 *   servers it connects to. They all see this machine connecting, whichever device it is for.
 *
 * @param library the WebAssembly build of libghostty-vt, or null to look for it in a checkout
 */
fun Application.tonoAssets(library: File? = null) {
    val wasm = library ?: findInCheckout("native/out-wasm/ghostty-vt.wasm")
    routing {
        get("/ghostty-vt.wasm") {
            if (wasm?.isFile == true) call.respondKept(wasm)
            else call.respondText("The terminal library isn't built: run `task native:wasm`.", status = HttpStatusCode.NotFound)
        }
        get("/api/tono/font/{style}") {
            val style = call.parameters["style"]
            val family = call.request.queryParameters["family"]
            val file = when {
                style !in FONT_STYLES -> null
                family != null -> withContext(Dispatchers.IO) {
                    val bold = style == "bold" || style == "bolditalic"
                    val regular = call.request.queryParameters["weight"]?.toIntOrNull()?.takeIf { it in 100..900 } ?: 400
                    // Only a font fontconfig lists is looked for, so nothing a request says ends up in a path.
                    installedFontFile(family, if (bold) boldWeightFor(regular) else regular, italic = style == "italic" || style == "bolditalic")
                }
                omarchyIsPresent() -> FONT_STYLES[style]?.let { desktopFont(it) }
                else -> null
            }
            if (file != null) call.respondKept(file) else call.respond(HttpStatusCode.NotFound)
        }
        get("/api/tono/fonts") {
            val fonts = withContext(Dispatchers.IO) { readInstalledMonospaceFonts() }
            call.respondText(
                buildJsonArray {
                    for (font in fonts) addJsonObject {
                        put("family", font.family)
                        put("weights", buildJsonArray { font.weights.forEach { add(JsonPrimitive(it)) } })
                    }
                }.toString(),
                ContentType.Application.Json,
            )
        }
        get("/api/tono/sounds/{name}") {
            // Three fixed names: nothing a request says ends up in a path.
            val file = SOUNDS.firstOrNull { it == call.parameters["name"] }?.let { name ->
                SOUND_DIRECTORIES.map { File(it, "$name.oga") }.firstOrNull(File::isFile)
            }
            if (file != null) call.respondKept(file) else call.respond(HttpStatusCode.NotFound)
        }
        get("/api/tono/whoami") {
            val caller = call.caller
            call.respondText(
                buildJsonObject {
                    put("local", caller.local)
                    caller.device?.let { put("device", it) }
                    caller.login?.let { put("login", it) }
                }.toString(),
                ContentType.Application.Json,
            )
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

/** Sends a file for the browser to keep, and to ask about before it uses it again. */
private suspend fun ApplicationCall.respondKept(file: File) {
    response.header(HttpHeaders.CacheControl, REVALIDATE.toString())
    respondFile(file)
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

/** How heavy bold is beside regular text of a weight: bold, or heavier still where regular is nearly that. */
internal fun boldWeightFor(regular: Int): Int = if (regular >= 600) 900 else 700

private val FONT_STYLES = mapOf(
    "regular" to "monospace",
    "bold" to "monospace:bold",
    "italic" to "monospace:italic",
    "bolditalic" to "monospace:bold:italic",
)
private val SOUNDS = listOf("dialog-warning", "dialog-error", "complete")
private val SOUND_DIRECTORIES = listOf("/usr/local/share/sounds/freedesktop/stereo", "/usr/share/sounds/freedesktop/stereo")
