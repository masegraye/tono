package org.example.kotlintest.ghostty

enum class MouseAction { Press, Release, Motion }

/** The buttons a terminal can be told about. The wheel is two of them: a notch is a press. */
enum class MouseButton { Left, Right, Middle, WheelUp, WheelDown }

/**
 * Something the pointer did over a terminal.
 *
 * @param button the button pressed or released, or the one held during a motion, if any
 * @param x where, in pixels from the terminal's left edge
 * @param y in pixels from its top edge
 * @param cellWidth the size of a character cell as drawn, in pixels, which turns the position into a cell
 * @param anyButtonDown whether any button is down after this event, which some programs ask to be told
 */
data class MouseInput(
    val action: MouseAction,
    val button: MouseButton?,
    val x: Float,
    val y: Float,
    val cellWidth: Int,
    val cellHeight: Int,
    val shift: Boolean = false,
    val control: Boolean = false,
    val alt: Boolean = false,
    val anyButtonDown: Boolean = action == MouseAction.Press,
)
