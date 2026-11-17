package org.example.kotlintest

/**
 * Stock Omarchy palettes embedded in the app. They are only a fallback for places where
 * the real theme cannot be read: Android, iOS, and a web build served without the server
 * module. Anywhere the desktop's theme is reachable, the app uses that instead.
 */
val bundledOmarchyPalettes: List<OmarchyPalette> by lazy {
    listOfNotNull(
        omarchyPaletteFromToml("retro-82", RETRO_82_TOML),
        omarchyPaletteFromToml("catppuccin", CATPPUCCIN_TOML),
        omarchyPaletteFromToml("catppuccin-latte", CATPPUCCIN_LATTE_TOML),
        omarchyPaletteFromToml("tokyo-night", TOKYO_NIGHT_TOML),
    )
}

// Verbatim from /usr/share/omarchy/themes/<slug>/colors.toml

private val RETRO_82_TOML = """
mode = "dark"

accent = "#faa968"
selection = "#134e5a"
muted = "#2a6b78"

background = "#05182e"
dark_background = "#031222"
darker_background = "#020c17"
lighter_background = "#0a2540"

foreground = "#f6dcac"
dark_foreground = "#3f8f8a"
light_foreground = "#a7c9c6"
bright_foreground = "#f6dcac"

red = "#f85525"
yellow = "#e97b3c"
orange = "#faa968"
green = "#028391"
cyan = "#8cbfb8"
blue = "#3f8f8a"
magenta = "#3f8f8a"
brown = "#743d1e"
""".trim()

private val CATPPUCCIN_TOML = """
mode = "dark"

accent = "#89b4fa"
selection = "#45475a"
muted = "#585b70"

background = "#1e1e2e"
dark_background = "#161622"
darker_background = "#101019"
lighter_background = "#313244"

foreground = "#cdd6f4"
dark_foreground = "#6c7086"
light_foreground = "#bac2de"
bright_foreground = "#cdd6f4"

red = "#f38ba8"
yellow = "#f9e2af"
orange = "#f6b6ab"
green = "#a6e3a1"
cyan = "#94e2d5"
blue = "#89b4fa"
magenta = "#f5c2e7"
brown = "#7b5b55"
""".trim()

private val CATPPUCCIN_LATTE_TOML = """
mode = "light"

accent = "#1e66f5"
selection = "#ccd0da"
muted = "#acb0be"

background = "#eff1f5"
dark_background = "#e3e4e8"
darker_background = "#d7d8dc"
lighter_background = "#dce0e8"

foreground = "#4c4f69"
dark_foreground = "#9ca0b0"
light_foreground = "#5c5f77"
bright_foreground = "#4c4f69"

red = "#d20f39"
yellow = "#df8e1d"
orange = "#d84e2b"
green = "#40a02b"
cyan = "#179299"
blue = "#1e66f5"
magenta = "#ea76cb"
brown = "#6c2715"
""".trim()

private val TOKYO_NIGHT_TOML = """
mode = "dark"

accent = "#7aa2f7"
selection = "#292e42"
muted = "#414868"

background = "#1a1b26"
dark_background = "#13141c"
darker_background = "#0e0e14"
lighter_background = "#24283b"

foreground = "#a9b1d6"
dark_foreground = "#565f89"
light_foreground = "#b4bee6"
bright_foreground = "#c0caf5"

red = "#f7768e"
yellow = "#e0af68"
orange = "#eb927b"
green = "#9ece6a"
cyan = "#449dab"
blue = "#7aa2f7"
magenta = "#ad8ee6"
brown = "#75493d"
""".trim()
