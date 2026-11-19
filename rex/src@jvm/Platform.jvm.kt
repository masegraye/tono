package org.example.kotlintest.rex

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
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
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

actual fun rexHttpClient(): HttpClient = HttpClient(CIO) { install(WebSockets) }

internal actual suspend fun HttpClient.rexWebSocket(endpoint: RexEndpoint, path: String, subprotocol: String): DefaultClientWebSocketSession =
    openWebSocket {
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

/** Where Tono keeps its files when it isn't told where: adr-001. Moves them there first from the one file there used to be. */
actual fun defaultWorkspace(): Workspace = Workspace.loadDefault()

/**
 * A workspace, or half of one, kept in a file, written through a temporary one so a crash can't leave it half-written.
 *
 * @param privateDirectory whether the file's directory, if this makes it, is for its owner alone
 */
class WorkspaceFile(val file: Path, private val privateDirectory: Boolean = false) : WorkspaceStorage {
    override val location: String get() = file.toAbsolutePath().toString()

    /** Null only when there is no file. One that is there and can't be read throws, so that nobody takes it for missing and writes over it. */
    override fun read(): String? = if (Files.notExists(file)) null else Files.readString(file)

    private fun makeDirectory() {
        val directory = file.toAbsolutePath().parent ?: return
        if (Files.isDirectory(directory)) return
        directory.parent?.let(Files::createDirectories)
        // The mode is for this directory alone: the ones above it are the user's, not Tono's.
        val made = privateDirectory && runCatching { Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))) }.isSuccess
        if (!made) Files.createDirectories(directory)
    }

    override fun write(text: String) {
        makeDirectory()
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temporary, text)
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * Puts the file there whole or not at all, and never in place of one that is there: it
     * is written beside, read back, and then given its name with a link, which fails if the
     * name is taken.
     */
    override fun writeNew(text: String): Boolean {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false
        makeDirectory()
        val temporary = file.resolveSibling("${file.fileName}.new.tmp")
        try {
            Files.writeString(temporary, text)
            if (Files.readString(temporary) != text) throw IOException("$temporary doesn't read back as it was written")
            try {
                Files.createLink(file, temporary)
            } catch (taken: FileAlreadyExistsException) {
                return false
            } catch (noLinks: Exception) {
                // A file system without links. Without REPLACE_EXISTING a move doesn't take a name in use either.
                try {
                    Files.move(temporary, file)
                } catch (taken: FileAlreadyExistsException) {
                    return false
                }
            }
            return true
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

/** The two files Tono keeps: what a person chose, and where they were. */
data class TonoFiles(val settings: Path, val state: Path)

/** The directory that names Tono where a platform names directories by app id (adr-001). */
const val TONO_APP_ID = "dev.mg.tono"

/**
 * Where Tono's two files go on this platform (adr-001, inv-003):
 *
 * - Linux, and anything else Unix-like: `$XDG_CONFIG_HOME/tono/settings.json` and
 *   `$XDG_STATE_HOME/tono/state.json`, which are `~/.config` and `~/.local/state` when the
 *   variable is unset, empty, or not an absolute path, as the XDG specification says.
 * - macOS: both in `~/Library/Application Support/dev.mg.tono/`.
 * - Windows: `%APPDATA%\Tono\settings.json` and `%LOCALAPPDATA%\Tono\state.json`.
 *
 * @param environment the environment variables, to be given by a test
 * @param os the system's name as Java has it (`os.name`)
 */
fun Workspace.Companion.defaultFiles(environment: Map<String, String> = System.getenv(), os: String = System.getProperty("os.name").orEmpty()): TonoFiles {
    val home = Path.of(environment["HOME"]?.takeIf { it.isNotEmpty() } ?: environment["USERPROFILE"]?.takeIf { it.isNotEmpty() } ?: System.getProperty("user.home"))
    fun absolute(variable: String): Path? = environment[variable]?.takeIf { it.isNotEmpty() }?.let { runCatching { Path.of(it) }.getOrNull() }?.takeIf { it.isAbsolute }
    val system = os.lowercase()
    return when {
        "mac" in system || "darwin" in system -> home.resolve("Library").resolve("Application Support").resolve(TONO_APP_ID).let { TonoFiles(it.resolve("settings.json"), it.resolve("state.json")) }
        // A Windows path isn't absolute to a Java running elsewhere, as in a test, so there it is taken as given.
        "windows" in system -> TonoFiles(
            (environment["APPDATA"]?.takeIf { it.isNotEmpty() }?.let(Path::of) ?: home.resolve("AppData").resolve("Roaming")).resolve("Tono").resolve("settings.json"),
            (environment["LOCALAPPDATA"]?.takeIf { it.isNotEmpty() }?.let(Path::of) ?: home.resolve("AppData").resolve("Local")).resolve("Tono").resolve("state.json"),
        )
        else -> TonoFiles(
            (absolute("XDG_CONFIG_HOME") ?: home.resolve(".config")).resolve("tono").resolve("settings.json"),
            (absolute("XDG_STATE_HOME") ?: home.resolve(".local").resolve("state")).resolve("tono").resolve("state.json"),
        )
    }
}

/**
 * The one file everything was kept in before there were two: `$XDG_CONFIG_HOME/kotlin-test/workspace.json`,
 * or under `~/.config`. Found the way it was found then, on every system. Only ever read, to move from.
 */
fun Workspace.Companion.oldFile(environment: Map<String, String> = System.getenv()): Path {
    val config = environment["XDG_CONFIG_HOME"]?.takeIf { it.isNotEmpty() }?.let(Path::of)
        ?: Path.of(environment["HOME"]?.takeIf { it.isNotEmpty() } ?: System.getProperty("user.home"), ".config")
    return config.resolve("kotlin-test/workspace.json")
}

/**
 * Reads the workspace from Tono's own places ([defaultFiles]). The first time, what is in
 * [oldFile] is copied there ([Workspace.loadMoving]), and a note is left beside the old file,
 * which itself is never changed or removed.
 */
fun Workspace.Companion.loadDefault(environment: Map<String, String> = System.getenv(), os: String = System.getProperty("os.name").orEmpty()): Workspace {
    val files = defaultFiles(environment, os)
    val old = oldFile(environment)
    return loadMoving(WorkspaceFile(files.settings), WorkspaceFile(files.state, privateDirectory = true), WorkspaceFile(old)) {
        val note = old.resolveSibling("moved.txt")
        if (Files.notExists(note)) Files.writeString(
            note,
            "Tono's settings moved to ${files.settings}\nand where you were (the host, session and tab showing) to ${files.state}\n\n" +
                "Tono no longer reads or writes anything in this directory. workspace.json is the copy from\n" +
                "before the move, kept as a backup. The whole directory can be deleted.\n",
        )
    }
}

/** Reads a workspace kept whole in one file, as `--workspace FILE` asks. A missing file gives an empty one that saves to [file]. */
fun Workspace.Companion.load(file: Path): Workspace = load(WorkspaceFile(file))

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

/**
 * Asked for with `TONO_SCROLL_TELEMETRY` or `-Dtono.scrollTelemetry`: a file to add the
 * lines to, or `1` for standard error.
 */
internal actual fun scrollTelemetrySink(): ((String) -> Unit)? {
    val wanted = (System.getProperty("tono.scrollTelemetry") ?: System.getenv("TONO_SCROLL_TELEMETRY"))?.takeIf { it.isNotEmpty() && it != "0" && it != "false" } ?: return null
    if (wanted == "1" || wanted == "true") return { line -> System.err.println(line) }
    val file = runCatching { java.io.PrintWriter(java.io.FileWriter(wanted, true), true) }.getOrElse {
        System.err.println("tono: can't write scroll telemetry to $wanted: ${it.message}")
        return null
    }
    return { line -> file.println(line) }
}
