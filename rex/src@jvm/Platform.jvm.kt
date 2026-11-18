package org.example.kotlintest.rex

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.unixSocket
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

actual fun rexHttpClient(): HttpClient = HttpClient(CIO) { install(WebSockets) }

internal actual suspend fun HttpClient.rexWebSocket(endpoint: RexEndpoint, path: String, subprotocol: String): DefaultClientWebSocketSession =
    webSocketSession {
        when (endpoint) {
            // Over a Unix socket the host name is ignored.
            is RexEndpoint.UnixSocket -> {
                url("ws://localhost$path")
                unixSocket(endpoint.path)
            }
            is RexEndpoint.Network -> url(endpoint.baseUrl + path)
        }
        header(HttpHeaders.SecWebSocketProtocol, subprotocol)
    }

/** libghostty-vt handles aren't thread-safe, so every terminal lives on this one thread. */
internal actual val terminalDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { task ->
    Thread(task, "rex-terminals").apply { isDaemon = true }
}.asCoroutineDispatcher()

internal actual fun <K, V> concurrentMap(): MutableMap<K, V> = ConcurrentHashMap()
internal actual fun <T> concurrentSet(): MutableSet<T> = ConcurrentHashMap.newKeySet()

internal actual inline fun <T> locked(lock: Any, block: () -> T): T = synchronized(lock, block)

internal actual class Counter actual constructor() {
    private val value = AtomicLong(1)
    actual fun next(): Long = value.getAndIncrement()
}

internal actual val clientPlatform: String = System.getProperty("os.name").orEmpty().lowercase().let { os ->
    when {
        "linux" in os -> "linux"
        "mac" in os -> "macos"
        "windows" in os -> "windows"
        else -> "other"
    }
}

actual fun nowMillis(): Long = System.currentTimeMillis()

internal actual fun connectFailureMessage(failure: Exception): String? = when (failure) {
    is java.nio.channels.UnresolvedAddressException -> "Host name not found"
    is java.nio.file.NoSuchFileException -> "No server is listening at ${failure.file}"
    else -> null
}

actual fun defaultLocalEndpoint(): String = RexEndpoint.local()

/** The local server: `REX_SERVER`, or the per-user socket under `XDG_RUNTIME_DIR`. */
fun RexEndpoint.Companion.local(environment: Map<String, String> = System.getenv()): String =
    environment["REX_SERVER"]
        ?: "unix://${environment["XDG_RUNTIME_DIR"] ?: "/run/user/${ProcessHandle.current().info().user().map(::uidOf).orElse("1000")}"}/rex/server.sock"

private fun uidOf(user: String): String =
    runCatching { ProcessBuilder("id", "-u", user).start().inputStream.bufferedReader().readText().trim() }
        .getOrNull()?.takeIf { it.isNotEmpty() } ?: "1000"

actual fun defaultWorkspace(): Workspace = Workspace.load()

/** A workspace kept in a file, written through a temporary one so a crash can't leave it half-written. */
class WorkspaceFile(val file: Path) : WorkspaceStorage {
    override fun read(): String? = runCatching { Files.readString(file) }.getOrNull()

    override fun write(text: String) {
        Files.createDirectories(file.parent)
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temporary, text)
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

/** `$XDG_CONFIG_HOME/kotlin-test/workspace.json`, or under `~/.config`. */
fun Workspace.Companion.defaultFile(environment: Map<String, String> = System.getenv()): Path {
    val config = environment["XDG_CONFIG_HOME"]?.takeIf { it.isNotEmpty() }?.let(Path::of)
        ?: Path.of(environment["HOME"] ?: System.getProperty("user.home"), ".config")
    return config.resolve("kotlin-test/workspace.json")
}

/** Reads the workspace. A missing or unreadable file gives an empty one that saves to [file]. */
fun Workspace.Companion.load(file: Path = defaultFile()): Workspace = load(WorkspaceFile(file))

/**
 * As the `tailscale` command reports them. Empty when the command is missing, Tailscale is
 * stopped, or it doesn't answer in time.
 */
actual suspend fun tailnetRexServers(): List<TailnetServer> = withContext(Dispatchers.IO) {
    runCatching {
        val process = ProcessBuilder("tailscale", "status", "--json").redirectErrorStream(false).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        Tailnet.parseStatus(Json.parseToJsonElement(output).jsonObject)
    }.getOrDefault(emptyList())
}
