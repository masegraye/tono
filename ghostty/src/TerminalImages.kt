package org.example.kotlintest.ghostty

/**
 * The pixels of an image a program sent, as rows of red, green, blue and alpha bytes. One
 * object stands for one version of one image: a program that sends new pixels under the same
 * number gets a new object, so a renderer can cache what it makes from one by identity.
 */
class TerminalImage(val id: Int, val width: Int, val height: Int, val rgba: ByteArray)

/** Where to draw part of a [TerminalImage], and how large. */
class TerminalImagePlacement(
    val image: TerminalImage,
    val layer: Layer,
    /** The cell at the image's top left. Either can be negative for an image partly scrolled out of view. */
    val column: Int,
    val row: Int,
    /** How many cells the placement covers. */
    val columns: Int,
    val rows: Int,
    /** The part of the image to draw, in the image's own pixels. */
    val sourceX: Int,
    val sourceY: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    /**
     * The size to draw at and the offset from the top-left cell's corner, in the pixels of the
     * terminal's own cells ([cellWidth] by [cellHeight]), which may not be the size a client
     * draws cells at. Zero cell sizes mean the terminal was never told one.
     */
    val pixelWidth: Int,
    val pixelHeight: Int,
    val offsetX: Int,
    val offsetY: Int,
) {
    enum class Layer { BelowBackground, BelowText, AboveText }
}
