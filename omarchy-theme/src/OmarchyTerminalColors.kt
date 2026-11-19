package dev.mg.omarchy.theme

/**
 * The colors Omarchy gives a terminal for a theme, as `#rrggbb`.
 *
 * @property selection the background of selected text; Omarchy leaves the text's own color
 *   to each terminal
 * @property ansi the 16 ANSI colors: black, red, green, yellow, blue, magenta, cyan, white,
 *   then their bright versions
 */
data class OmarchyTerminalColors(
    val foreground: String,
    val background: String,
    val cursor: String,
    /** What selected text is drawn on. */
    val selection: String,
    val ansi: List<String>,
)

/**
 * This theme's terminal colors, by the same mapping Omarchy uses to generate its own
 * terminal configurations (Ghostty, Kitty, Alacritty, Foot), so a terminal drawn with these
 * matches the user's other terminals.
 *
 * A theme that leaves a color out gets a related one: a bright color falls back to its
 * normal one, and that to the accent.
 */
fun OmarchyPalette.terminalColors(): OmarchyTerminalColors {
    fun color(vararg names: String): String = names.firstNotNullOfOrNull { colors[it] } ?: colors.getValue("foreground")
    val foreground = color("foreground")
    val background = color("background")
    val brightForeground = color("bright_foreground", "foreground")
    val normal = listOf("red", "green", "yellow", "blue", "magenta", "cyan")
    return OmarchyTerminalColors(
        foreground = foreground,
        background = background,
        cursor = brightForeground,
        selection = color("selection", "lighter_background", "muted", "dark_foreground"),
        ansi = listOf(background) + normal.map { color(it, "accent") } + listOf(foreground) +
            listOf(color("muted", "dark_foreground")) + normal.map { color("bright_$it", it, "accent") } + listOf(brightForeground),
    )
}
