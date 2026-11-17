# Kotlin Multiplatform project

This project is targeting Android, iOS, Desktop (fast UI development with Compose Hot Reload), Web (Wasm), built with
the [Kotlin Toolchain](https://kotlin-toolchain.org/latest/).

- [/androidApp](./androidApp) contains the Android application.
- [/desktopApp](./desktopApp) contains the desktop (JVM) application.
- [/iosApp](./iosApp) contains the iOS application.
- [/shared](./shared) holds the code shared across your applications — Compose UI,
  business logic, and platform-specific implementations. [src](./shared/src) is for common code; the
  sibling `src@<platform>` folders (for example `src@android`) hold code compiled only for the platform named in the
  folder.
- [/webApp](./webApp) contains the web application, compiled to WebAssembly with Kotlin/Wasm.

The `kotlin` (macOS/Linux) and `kotlin.bat` (Windows) scripts in the project root are self-bootstrapping wrappers for
the Kotlin Toolchain: they download the pinned toolchain version on first use, so no separate installation is required.
Build the whole project with `./kotlin build`.

### Running

Run each module from the command line:

- Android app: `./kotlin run -m androidApp`
- Desktop app: `./kotlin run -m desktopApp`
- iOS app: `./kotlin run -m iosApp`
- Web app: `./kotlin run -m webApp`

### UI showcase and Omarchy theming

The shared module is a tabbed showcase of what Compose Multiplatform offers on every target: Material 3 components,
inputs, lazy lists and pagers, animations, canvas drawing, and gestures. One file per tab lives in
[shared/src](./shared/src) (`Section*.kt`).

The app takes its colors from the [Omarchy](https://omarchy.org/) desktop theme
([OmarchyTheme.kt](./shared/src/OmarchyTheme.kt)):

- On desktop it reads the applied theme's palette from `~/.local/state/omarchy/current/theme/colors.toml`, maps it onto
  a Material 3 color scheme, and watches that directory, so `omarchy theme set <name>` re-themes the running app
  immediately.
- On Android, iOS and web, and on desktops without Omarchy, it falls back to four stock Omarchy palettes that are
  embedded in the app. The Theming tab switches between them.
- The desktop app also uses the Omarchy font (whatever fontconfig resolves `monospace` to) for all text, and picks up
  `omarchy font set <name>` the same way. Other platforms keep their default font.
- Under Hyprland the desktop app renders at the focused monitor's scale. Set `KMP_UI_SCALE` (for example `1.5`) to
  override it.

`./kotlin test -p jvm` also renders every tab offscreen to `build/showcase-renders/*.png`.

### Development loop

[mise](https://mise.jdx.dev/) pins the JDK and provides a few tasks (`mise tasks` lists them):

- `mise run desktop` runs the desktop app with Compose Hot Reload: saved UI changes appear in the running window within
  a second or two and keep its state. Changing dependencies or `desktopApp/src/main.kt` needs a restart.
- `mise run web` serves the web app, and `mise run web:watch` in a second terminal rebuilds it whenever a source file
  changes and reloads open browser tabs. Hot Reload is JVM-only, so on web this is a full rebuild and page reload
  (about 15 seconds) and the page state is lost. The reload is driven by
  [dev-reload.js](./webApp/resources/dev-reload.js), which only runs on localhost.

### Testing

Run the project's tests with `./kotlin test`, or `./kotlin test -m <module>` for a single module.

---

Learn more
about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html), [Kotlin Toolchain](https://kotlin-toolchain.org/latest/), [Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/), [Kotlin/Wasm](https://kotl.in/wasm/), [Ktor](https://ktor.io/).
