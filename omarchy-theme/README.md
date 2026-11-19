# The omarchy-theme modules

Three Kotlin Multiplatform modules that make an app look like the [Omarchy](https://omarchy.org/) desktop it runs on, and
keep it that way: when the user switches theme or font, the app restyles at runtime.

| Module | Platforms | What it does |
| --- | --- | --- |
| `omarchy-theme` | Android, iOS, JVM, Wasm | Reads the applied theme's palette and the Omarchy font, and emits them again on every change. No UI dependencies. |
| `omarchy-theme-compose` | Android, iOS, JVM, Wasm | A Compose Multiplatform Material 3 theme built from that: `OmarchyTheme { }`. |
| `omarchy-theme-ktor` | JVM | Ktor routes that serve the theme to web clients, which cannot read the desktop's files themselves. |

## Using them in an app

They are modules of this project, so an app here depends on one by naming it in its `module.yaml`. Nothing is
published or installed first; they are built along with whatever uses them.

```yaml
dependencies:
  - //omarchy-theme-compose   # or //omarchy-theme, or //omarchy-theme-ktor
```

They know nothing about the rest of this project and depend only on each other, so they can be copied into another
Kotlin Toolchain project as they are.

### A Compose app

```kotlin
@Composable
fun App() {
    OmarchyTheme {
        // Material 3 components in here use the desktop's colors, font and rounded shapes
    }
}
```

To show what is in effect, or to let the user preview another palette or flip dark and light, hold the state yourself:

```kotlin
val theme = rememberOmarchyThemeState()
OmarchyTheme(theme) {
    Text("Theme: ${theme.palette.displayName}")
    Switch(checked = theme.isDark, onCheckedChange = { theme.darkOverride = it })
}
```

A desktop theme change always wins: it clears `preview` and `darkOverride`.

On a desktop (JVM) app, wrap the window content in `OmarchyWindowScale` as well. Under XWayland the JVM does not see
Hyprland's monitor scale, so without it the window renders tiny on a HiDPI display. `OMARCHY_UI_SCALE` (for example
`1.5`) overrides the detected scale.

```kotlin
Window(onCloseRequest = ::exitApplication) {
    OmarchyWindowScale { App() }
}
```

### A web app

A browser page cannot read files on the desktop, so something on the server side has to. Add `omarchy-theme-ktor` to a
Ktor server that serves the page:

```kotlin
embeddedServer(Netty, port = 8080) {
    omarchyThemeApi()                       // GET /api/theme and /api/theme/events
    routing { staticFiles("/", webDir) }    // the built web app
}.start(wait = true)
```

The web build of `omarchy-theme` subscribes to `api/theme/events` (relative to the page) by itself, so the same
`OmarchyTheme { }` follows the desktop there too. Set `omarchyThemeEventsUrl` before first use if the API lives
elsewhere.

### Without Compose

```kotlin
readOmarchyAppearance()                  // the palette and font right now, or null
omarchyAppearanceFlow().collect { ... }  // now, and again on every change
readInstalledOmarchyPalettes()           // every theme installed on this machine
```

## Trying an app without Omarchy

Set `OMARCHY_ABSENT=1`, or the system property `omarchy.absent=true`, and the JVM library behaves as on a desktop
that isn't Omarchy: `readOmarchyAppearance()` is null, `omarchyAppearanceFlow()` never emits and no installed themes
are found. Nothing of Omarchy's is changed. It is for seeing an app's fallback on a machine that has Omarchy.

## What each platform can see

| Platform | Theme | Font |
| --- | --- | --- |
| JVM on an Omarchy desktop | Live, from the files Omarchy writes | The Omarchy font, loaded by name |
| Web, served with `omarchy-theme-ktor` | Live, pushed by the server | Default font |
| Web on static hosting, Android, iOS, JVM elsewhere | A fallback palette, or one the app lets its user choose: every stock Omarchy theme is in `bundledOmarchyPalettes` | Default font |

## How it works

- Every Omarchy theme ships a `colors.toml`: a mode plus named colors. Omarchy keeps the applied theme in
  `~/.local/state/omarchy/current`, with the user's overrides already merged in.
- On the JVM, `omarchy-theme` reads `theme/colors.toml` from there and watches the directory, so it follows any theme,
  including ones installed later. It needs no Omarchy hook and does not poll.
- The font is whatever fontconfig resolves `monospace` to, which is what `omarchy font current` reports.
- `omarchy-theme-compose` maps the palette onto Material 3 color roles. A theme only has to define `background`,
  `foreground` and `accent`; other colors fall back to related ones.

## Working on them

`task test` runs their tests with everything else. iOS variants compile on a Mac only, and have not been run.
