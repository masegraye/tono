# Kotlin Multiplatform project

This project is targeting Android, iOS, Desktop (fast UI development with Compose Hot Reload), Web (Wasm), built with
the [Kotlin Toolchain](https://kotlin-toolchain.org/latest/).

- [/androidApp](./androidApp) contains the Android application.
- [/core](./core) holds code with no UI in it, shared by the applications and the server: the Omarchy palette model and
  the code that reads and watches the desktop's theme.
- [/desktopApp](./desktopApp) contains the desktop (JVM) application.
- [/iosApp](./iosApp) contains the iOS application.
- [/shared](./shared) holds the code shared across your applications — Compose UI,
  business logic, and platform-specific implementations. [src](./shared/src) is for common code; the
  sibling `src@<platform>` folders (for example `src@android`) hold code compiled only for the platform named in the
  folder.
- [/server](./server) is a Ktor server that serves the web application and tells it how the desktop is themed.
- [/webApp](./webApp) contains the web application, compiled to WebAssembly with Kotlin/Wasm.

The `kotlin` (macOS/Linux) and `kotlin.bat` (Windows) scripts in the project root are self-bootstrapping wrappers for
the Kotlin Toolchain: they download the pinned toolchain version on first use, so no separate installation is required.
Build the whole project with `./kotlin build`.

### Running

Run each module from the command line:

- Android app: `./kotlin run -m androidApp`
- Desktop app: `./kotlin run -m desktopApp`
- iOS app: `./kotlin run -m iosApp`
- Web app: `./kotlin run -m webApp` (static files only), or `mise run web` to serve it from the server module with
  live theming

### UI showcase and Omarchy theming

The shared module is a tabbed showcase of what Compose Multiplatform offers on every target: Material 3 components,
inputs, lazy lists and pagers, animations, canvas drawing, and gestures. One file per tab lives in
[shared/src](./shared/src) (`Section*.kt`).

The app takes its colors, and on desktop its font, from the [Omarchy](https://omarchy.org/) desktop theme. It follows
whichever theme is applied, including themes installed later, and restyles itself at runtime when the theme changes:
nothing is rebuilt or reloaded.

- Omarchy keeps the applied theme in `~/.local/state/omarchy/current`. [core](./core/src@jvm) reads the palette from
  `theme/colors.toml` there and watches the directory, so `omarchy theme set <name>` takes effect immediately.
  [OmarchyTheme.kt](./shared/src/OmarchyTheme.kt) maps the palette onto a Material 3 color scheme.
- The desktop app reads those files itself. It also sets all text in the Omarchy font (whatever fontconfig resolves
  `monospace` to) and follows `omarchy font set <name>`.
- A web page cannot read the desktop's files, so the [server](./server) does it with the same `core` code and pushes
  each change to the page over Server-Sent Events (`/api/theme/events`; `/api/theme` returns the current theme as JSON).
  The web app keeps its default font.
- Android, iOS, and a web build hosted without the server have no way to see the desktop, so they use one of four stock
  palettes embedded in the app.
- The Theming tab can preview another palette: every installed theme on desktop, the embedded ones elsewhere. A preview
  only affects the app, and ends when the desktop's theme changes.
- Under Hyprland the desktop app renders at the focused monitor's scale. Set `KMP_UI_SCALE` (for example `1.5`) to
  override it.

`./kotlin test -p jvm` also renders every tab offscreen to `build/showcase-renders/*.png`.

### Development loop

[mise](https://mise.jdx.dev/) pins the JDK and provides a few tasks (`mise tasks` lists them):

- `mise run desktop` runs the desktop app with Compose Hot Reload: saved UI changes appear in the running window within
  a second or two and keep its state. Changing dependencies or `desktopApp/src/main.kt` needs a restart.
- `mise run web` builds the web app and serves it from the server module at http://127.0.0.1:8080. `mise run web:watch`
  in a second terminal rebuilds it whenever a source file changes and reloads open browser tabs. Hot Reload is JVM-only,
  so on web a code change is a full rebuild and page reload (about 15 seconds) and the page state is lost. The reload is
  driven by [dev-reload.js](./webApp/resources/dev-reload.js), which only runs on localhost.
- `mise run web:release` builds the optimized web app and a self-contained server jar and runs them, with no build tool
  involved at runtime. The server reads `WEB_DIR`, `HOST` (default `127.0.0.1`) and `PORT` (default `8080`) from the
  environment.

### Testing

Run the project's tests with `./kotlin test`, or `./kotlin test -m <module>` for a single module.

---

Learn more
about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html), [Kotlin Toolchain](https://kotlin-toolchain.org/latest/), [Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/), [Kotlin/Wasm](https://kotl.in/wasm/), [Ktor](https://ktor.io/).
