package org.example.kotlintest.ghostty

import org.example.kotlintest.ghostty.ffi.GhosttySysDecodePngFn
import org.example.kotlintest.ghostty.ffi.GhosttySysImage
import org.example.kotlintest.ghostty.ffi.GhosttyVt
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import javax.imageio.ImageIO

/** How much image data one terminal keeps, as in other Rex clients. */
internal const val IMAGE_STORAGE_BYTES = 64L shl 20

/**
 * Lets terminals accept PNG images: libghostty-vt takes raw pixels itself and asks its host
 * to decode anything else. Installed once, for every terminal.
 */
internal object PngDecoder {
    // The library keeps the function pointer for as long as it is loaded.
    private val arena = Arena.global()

    fun install() {
        val callback = GhosttySysDecodePngFn.allocate(::decode, arena)
        check("sys_set", GhosttyVt.ghostty_sys_set(GhosttyVt.GHOSTTY_SYS_OPT_DECODE_PNG(), callback))
    }

    /** Called by the library, on the thread that is feeding a terminal its output. */
    @Suppress("UNUSED_PARAMETER")
    private fun decode(userdata: MemorySegment, allocator: MemorySegment, data: MemorySegment, length: Long, out: MemorySegment): Boolean =
        // Nothing may be thrown back into native code: a picture that can't be read is just refused.
        runCatching {
            val png = data.reinterpret(length).toArray(JAVA_BYTE)
            val image = ImageIO.read(ByteArrayInputStream(png)) ?: return false
            val pixels = rgbaOf(image)
            val buffer = GhosttyVt.ghostty_alloc(allocator, pixels.size.toLong())
            if (buffer == MemorySegment.NULL) return false // larger than a terminal may keep
            MemorySegment.copy(pixels, 0, buffer.reinterpret(pixels.size.toLong()), JAVA_BYTE, 0, pixels.size)
            val result = out.reinterpret(GhosttySysImage.sizeof())
            GhosttySysImage.width(result, image.width)
            GhosttySysImage.height(result, image.height)
            GhosttySysImage.data(result, buffer)
            GhosttySysImage.data_len(result, pixels.size.toLong())
            true
        }.getOrDefault(false)

    private fun rgbaOf(image: BufferedImage): ByteArray {
        val width = image.width
        val height = image.height
        val argb = image.getRGB(0, 0, width, height, null, 0, width)
        val rgba = ByteArray(argb.size * 4)
        for (i in argb.indices) {
            val pixel = argb[i]
            rgba[i * 4] = (pixel shr 16).toByte()
            rgba[i * 4 + 1] = (pixel shr 8).toByte()
            rgba[i * 4 + 2] = pixel.toByte()
            rgba[i * 4 + 3] = (pixel ushr 24).toByte()
        }
        return rgba
    }
}

/** Widens the pixel formats a program can send to four bytes a pixel. Null for one this doesn't know. */
internal fun toRgba(format: Int, pixels: ByteArray, count: Int): ByteArray? = when (format) {
    GhosttyVt.GHOSTTY_KITTY_IMAGE_FORMAT_RGBA() -> pixels.takeIf { it.size >= count * 4 }
    GhosttyVt.GHOSTTY_KITTY_IMAGE_FORMAT_RGB() -> pixels.takeIf { it.size >= count * 3 }?.let { rgb ->
        ByteArray(count * 4).also { out ->
            for (i in 0 until count) {
                out[i * 4] = rgb[i * 3]; out[i * 4 + 1] = rgb[i * 3 + 1]; out[i * 4 + 2] = rgb[i * 3 + 2]; out[i * 4 + 3] = -1
            }
        }
    }
    GhosttyVt.GHOSTTY_KITTY_IMAGE_FORMAT_GRAY() -> pixels.takeIf { it.size >= count }?.let { gray ->
        ByteArray(count * 4).also { out ->
            for (i in 0 until count) {
                out[i * 4] = gray[i]; out[i * 4 + 1] = gray[i]; out[i * 4 + 2] = gray[i]; out[i * 4 + 3] = -1
            }
        }
    }
    GhosttyVt.GHOSTTY_KITTY_IMAGE_FORMAT_GRAY_ALPHA() -> pixels.takeIf { it.size >= count * 2 }?.let { gray ->
        ByteArray(count * 4).also { out ->
            for (i in 0 until count) {
                out[i * 4] = gray[i * 2]; out[i * 4 + 1] = gray[i * 2]; out[i * 4 + 2] = gray[i * 2]; out[i * 4 + 3] = gray[i * 2 + 1]
            }
        }
    }
    else -> null
}
