package org.example.kotlintest.ghostty

import org.example.kotlintest.ghostty.ffi.GhosttyVt
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads libghostty-vt. The generated bindings look their functions up in whatever this
 * class's loader has loaded, so every wrapper calls [ensureLoaded] before its first call.
 */
internal object GhosttyLibrary {
    private val loaded: Unit by lazy {
        val path = candidates().firstOrNull(Files::isRegularFile)
            ?: throw UnsatisfiedLinkError(
                "libghostty-vt.so not found. Build it with `task native`, or set GHOSTTY_VT_LIB to its path.",
            )
        System.load(path.toAbsolutePath().toString())
        PngDecoder.install()
    }

    fun ensureLoaded() = loaded

    private fun candidates(): List<Path> = listOfNotNull(
        System.getenv("GHOSTTY_VT_LIB")?.let(Path::of),
        System.getProperty("ghostty.vt.lib")?.let(Path::of),
    ) + generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("native/out/lib/libghostty-vt.so") } // a checkout, from any module directory
}

/** A libghostty-vt call that reported a failure. */
class GhosttyException(call: String, val result: Int) :
    RuntimeException("ghostty_$call failed: ${resultName(result)}")

internal fun check(call: String, result: Int) {
    if (result != GhosttyVt.GHOSTTY_SUCCESS()) throw GhosttyException(call, result)
}

private fun resultName(result: Int): String = when (result) {
    GhosttyVt.GHOSTTY_OUT_OF_MEMORY() -> "out of memory"
    GhosttyVt.GHOSTTY_INVALID_VALUE() -> "invalid value"
    GhosttyVt.GHOSTTY_OUT_OF_SPACE() -> "out of space"
    GhosttyVt.GHOSTTY_NO_VALUE() -> "no value"
    GhosttyVt.GHOSTTY_IO_ERROR() -> "I/O error"
    GhosttyVt.GHOSTTY_LIMIT_EXCEEDED() -> "limit exceeded"
    GhosttyVt.GHOSTTY_REJECTED() -> "rejected"
    else -> "result $result"
}
