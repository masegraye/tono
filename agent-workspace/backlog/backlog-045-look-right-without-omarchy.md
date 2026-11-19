# backlog-045: Look right without Omarchy: built-in themes and a terminal font

| | |
|---|---|
| Status | done (commits 9800e33 and the one after it, 2026-11-19) |
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

## What was built

Tono has themes and a terminal font of its own, follows Omarchy unless told otherwise, and looks deliberate without
it. Built by a worker; described for users in `docs/using-tono.md` ("Themes and the terminal's font").

- **Themes.** All 22 of Omarchy's stock themes are built in (`omarchy-theme`'s `bundledOmarchyPalettes`, verbatim
  `colors.toml`), 17 dark and 5 light. Each gives the Material colors and the terminal's 16 colors, background,
  foreground and cursor as Omarchy's own do; `OmarchyTerminalColors` now carries the selection color too.
- **Choosing.** `AppearanceChoice` (theme, font family, size, weight) in `rex/src/Workspace.kt`, written as
  `appearance` and only when something was chosen; `RexClient.appearance` is its StateFlow, `setAppearance` sets it.
  No theme chosen means follow Omarchy where it is, and Tokyo Night where it isn't.
- **The palette.** `Theme…` (`client.theme.select`), `Terminal Font…` (`client.font.select`) and
  `Terminal Font Weight…` (`client.font.weight`). Each list opens on what is in effect and shows the highlighted
  line live, in the whole window; Escape puts back what was chosen. "Follow Omarchy" is listed only where there is
  an Omarchy to follow.
- **The font.** JetBrains Mono (the no-ligatures cut, OFL, six styles, 1.2 MB) is in `tono/composeResources/font`
  and is the default without Omarchy, on the desktop and the web. The other choices are the installed monospace
  fonts: on the desktop from fontconfig, on the web from the page's server (`GET /api/tono/fonts`, and
  `/api/tono/font/{style}?family=…&weight=…`). The font size keys work as before and the size is now kept.
- **Without Omarchy.** `OMARCHY_ABSENT=1` or `-Domarchy.absent=true` makes `omarchy-theme` read nothing of the
  desktop, for the app and the server (`docs/development.md`).
- **Settings.** `AppearanceSettings(ui)` in `tono/src/AppearanceSettings.kt` is the section: Follow Omarchy (shown
  unavailable without it), a card per theme, and family, weight and size with a sample.

### Hooking the section into the settings (spec-001)

1. In `Settings.kt` add `Appearance("Appearance", "The theme, and the terminals' font")` to `SettingsSection`, and
   in `SectionBody`: `SettingsSection.Appearance -> ScrollingSection { AppearanceSettings(ui) }`.
2. In `TonoApp.kt`, `TonoSettings` must use `TonoTheme(tono.ui) { … }` in place of `OmarchyTheme { … }`, or the
   settings window keeps the desktop's theme when another is chosen. `TonoTheme` is also what keeps the choice in
   step between the windows, so nothing has to be added to `followSettings`.
3. When merging `RexUiState.kt`: `fontSize` is now a property over `ui.appearance`, not a state of its own.

## Decisions made while building, none of them asked for

- The font chosen is the terminals' only. The rest of the app stays in Omarchy's font, or in JetBrains Mono where
  there is no Omarchy, so that the interface keeps one look.
- Without Omarchy the default font is the built-in one even when the system has a monospace font, so that Tono
  looks the same everywhere. For the same reason the server no longer gives a page fontconfig's `monospace` when
  its machine has no Omarchy: `/api/tono/font/{style}` without a family is then a 404, and the page uses its own.
- A theme the app doesn't have, or a font that is gone, counts as not chosen; nothing is rewritten in the file.
- The weight is for text that isn't bold. Bold stays 700, or 900 where the weight chosen is 600 or more.
- The action names are guesses: Rex.app's names for these weren't found in the Rex documentation on this machine.
- On the web the page's background and `theme-color` now follow the theme.
- How a selection is drawn (a wash of the foreground) is unchanged; the palette's selection color is carried but not used.

## Checked

- `task test` passes. New tests: the choice logic and the palette lists (`tono/test@jvm/AppearanceTest.kt`), storage
  and the client's state (`rex/test@jvm/AppearanceChoiceTest.kt`), the fonts' listing and serving
  (`omarchy-theme/test@jvm`, `server/test/TonoFontsTest.kt`), and offscreen pictures of the app, the picker and the
  section in several themes, fonts, weights and sizes (`tono/test@jvm/AppearanceRenderTest.kt`, pictures in
  `build/appearance-renders/`), including that a blank in the built-in font is a full cell (backlog-040).
- The web app, in headless Chromium, with the server run as if without Omarchy: Tokyo Night and JetBrains Mono at
  the start; `Theme…` previewed and kept Catppuccin Latte; `Terminal Font…` fetched and kept a font from the server;
  both were there after a reload; Increase Font Size was kept. With Omarchy: the desktop's theme and font as before,
  the built-in font not fetched, and Follow Omarchy first in the list.
- The desktop app, on a nested X server with real keys, run as if without Omarchy: the same default, live preview
  of themes, fonts and weights, each choice written to the workspace file, and Ctrl+=, Ctrl+0.

## Not checked, and known

- The desktop app with Omarchy present was not looked at in a window: only tests and the web app cover "nothing
  changes". The settings section was only drawn offscreen, since it isn't in the settings yet.
- Firefox, Safari and a phone. A machine that really has no Omarchy, or no fontconfig.
- The built-in font has no Nerd Font symbols. On the web they show as boxes until a Nerd Font is chosen.
- Trying a font or a weight in the picker changes the size of the cells, so the terminal on screen is resized on
  its server while the list is gone through, as it is by the font size keys.
- On the web, a page with nothing chosen shows the default theme for a moment before the server has said what the
  desktop's is, as it showed the old fallback before.
- The first part of the web check was run by mistake against Mase's real home server (stopped by the orchestrator;
  it typed only into its own scratch session). Checks since ran against a scratch Rex server.
