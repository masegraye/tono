# backlog-045: Look right without Omarchy: built-in themes and a terminal font

| | |
|---|---|
| Status | captured |
| Area | The app |
| Size | medium |
| Source | Mase, 2026-11-19, comparing with Rex for Mac's Themes and Terminal settings |

## What

An intentional difference from Rex for Mac: Tono follows the Omarchy desktop's theme and monospace font instead of having its own. Where Omarchy isn't there (another Linux desktop, a web build served without our server, later iOS), Tono needs defaults of its own, and a way to choose:

- **Themes:** a choice of built-in themes, dark and light, with a sensible default. While Omarchy is present it is followed, unless the person turns that off and picks a theme. The terminal's 16 colours, background, foreground, cursor and selection come from the theme, as they do from Omarchy's.
- **Terminal font:** family, size, and (if the family has them) weight, for when Omarchy doesn't supply one; and a way to override Omarchy's.

## Why

Without Omarchy, Tono uses the first of four stock palettes in `omarchy-theme` with no way to choose, and the platform's monospace font, whose blank is about a third the width of its letters (seen in backlog-040).

## Builds on

`omarchy-theme` (`BundledOmarchyPalettes.kt`, `OmarchyTerminalColors.kt`) and `omarchy-theme-compose` (`OmarchyTheme`'s `fallback` and `preview`); the showcase's Theming tab already previews palettes. Settings window: spec-001 (an Appearance section can be added there once it lands).

## Promote when

Now (Mase, 2026-11-19).
