# Kotlin Multiplatform project

This project is targeting Android, iOS, Desktop (fast UI development with Compose Hot Reload), Web (Wasm), built with
the [Kotlin Toolchain](https://kotlin-toolchain.org/latest/).

- [/androidApp](./androidApp) contains the Android application.
- [/desktopApp](./desktopApp) contains the desktop (JVM) application: a client for [Rex](https://www.superlogical.com/rex).
- [/ghostty](./ghostty) wraps libghostty-vt, Ghostty's terminal emulation core, for the JVM.
- [/rex](./rex) is the Rex client itself, without any UI: connections, sessions and live terminals.
- [/rexSpike](./rexSpike) is a command-line check of that client.
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
- Desktop app: `mise run desktop` (it needs the native library; see [Rex client](#rex-client))
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

That behaviour comes from the `omarchy-theme` libraries, which live in their own repository (`../omarchy-theme-kmp`)
so other apps can use them. They are resolved from the local Maven repository, so **install them before building this
project**, and again after changing them:

```sh
cd ../omarchy-theme-kmp && ./kotlin publish mavenLocal
```

What this project does with them:

- [App.kt](./shared/src/App.kt) wraps the UI in `OmarchyTheme`. On desktop that reads the files Omarchy writes and
  watches them; it also sets all text in the Omarchy font.
- A web page cannot read the desktop's files, so the [server](./server) calls `omarchyThemeApi()`, which pushes each
  change to the page over Server-Sent Events. The web app keeps its default font.
- Android, iOS, and a web build hosted without the server have no way to see the desktop, so they use one of four stock
  palettes embedded in the library.
- The Theming tab can preview another palette: every installed theme on desktop, the embedded ones elsewhere. A preview
  only affects the app, and ends when the desktop's theme changes.
- [main.kt](./desktopApp/src/main.kt) wraps the window in `OmarchyWindowScale`, so under Hyprland the desktop app
  renders at the focused monitor's scale. Set `OMARCHY_UI_SCALE` (for example `1.5`) to override it.

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

### Rex client

The desktop app is a Linux client for [Rex](https://www.superlogical.com/rex), which keeps terminals running on a server
and lets clients attach to them. It is desktop-only; the other targets still show the UI showcase, which the desktop
app keeps under its menu.

```sh
mise run native    # once: download and build libghostty-vt into native/out (about two minutes)
mise run desktop   # the client
mise run probe     # the same connections with no window: prints what was restored
```

When it starts it connects to every configured server at once and restores where you were:

- The servers are the hosts saved in `~/.config/kotlin-test/workspace.json` (the local server, the first time), plus
  any in the local server's own host list. An endpoint is `unix:///path`, or `https://host` for a server reached over
  the network. A server with built-in Tailscale is a device on the tailnet, so its `https://` address just works on a
  machine that is on the tailnet too.
- For each server it selects the session you last had (else the first listed) and lays out its active tab's terminals
  where the server says they sit. It creates and closes things only when asked to.
- The sidebar lists every server, its sessions, and each session's tabs beneath it (a tab is a Rex window). The
  layouts of all sessions are read and kept current, not just the one on screen.
- A dropped connection is retried, and everything is read again when it comes back.

Looking at a terminal is passive: it is drawn at the size its current owner gave it. Pressing a key in one claims it,
which makes the terminal take its size from this client, as it would from any Rex client. A session's active tab and
focused pane are the session's own state, so changing them here changes them for every client on that session.

#### Shortcuts and the command palette

Everything the app can do is an action with a name, and the names, titles and categories are Rex.app's
(`client.tab.new`, `pane.split.right`, `session.switch`, …). The default keys are Rex.app's too, with Control in place
of Command, and Shift added where Control alone belongs to the terminal.

| Keys | Action |
| --- | --- |
| Ctrl+Shift+P | Command palette: every command, plus sessions, tabs and hosts to go to |
| Ctrl+K | Change session, on any host |
| Ctrl+Shift+H | Switch host |
| Ctrl+1 … Ctrl+8, Ctrl+9 | Go to that tab; Ctrl+9 is the last tab |
| Ctrl+Tab / Ctrl+Shift+Tab, Ctrl+PageDown / PageUp | Next / previous tab |
| Ctrl+Shift+T / Ctrl+Shift+N | New tab / new session |
| Ctrl+Shift+D / Ctrl+Alt+D | Split the pane right / down |
| Ctrl+Alt+arrows | Focus the pane in that direction |
| Ctrl+Shift+Enter | Zoom the pane, or restore the layout |
| Ctrl+Shift+W / Ctrl+Alt+W | Close the pane / the tab, after asking |
| Ctrl+Shift+V | Paste, bracketed when the program asks for that |
| Shift+PageUp / Shift+PageDown | Scroll through history |
| Ctrl+= / Ctrl+- / Ctrl+0 | Larger / smaller / default text |
| Ctrl+Shift+B | Show or hide the sidebar |
| Ctrl+` | Show or hide the debug log |

Tabs are one list, in sidebar order, across every session and server: the digit beside a tab is the one that goes to
it, whichever session it is in, and next and previous carry on from one session into the next. Going to a tab makes it
its session's active tab.

A bound key does not reach the terminal; every other key does. The keymap is not fixed in the app: it declares its
actions and these defaults to the home server (`client.keymap`), which layers the account's configuration file on top
and answers with what each key does. So a key bound in Rex's `init.lua` applies here as it does in Rex.app, and
changes take effect when the file is reloaded. The server's own actions, such as Clear Terminal, appear in the
palette as well.

The debug log is a panel along the bottom showing what the client is doing: connections, the events servers send,
the requests that change something, and the actions run. It never records what is typed.

How it is put together:

- **The protocol.** The server documents its own API (`GET /llms.txt` on its socket). [rex](./rex/src) speaks it:
  a JSON control connection, and one binary data connection per visible terminal.
- **Terminal emulation.** A terminal's stream opens with a snapshot in Ghostty's format, so the client uses
  libghostty-vt to restore it and to interpret the output that follows. [native/](./native) builds that library from the
  Ghostty source pinned in [GHOSTTY.lock](./native/GHOSTTY.lock). [ghostty](./ghostty/src) calls it through Java's
  foreign function API, using bindings generated from its headers (`mise run bindings` regenerates them from
  [ghostty-vt.symbols](./native/ghostty-vt.symbols)).
- **Drawing.** libghostty-vt reports which rows changed; [TerminalView.kt](./desktopApp/src/TerminalView.kt) draws the
  cells on a Compose canvas, in the Omarchy font and colors, and recolors when the desktop's theme changes.
- **Typing.** Key events go to the server as they came from the keyboard (`client.key`); the server applies key
  bindings and encodes them for the terminal. Pasted text is made safe and framed by libghostty-vt's paste encoder.
- **Actions.** [RexActions.kt](./desktopApp/src/RexActions.kt) is the table of what the app can do and its default keys.

Not there yet: mouse reporting, text selection and copy, SSH hosts, resuming a terminal's stream after a drop (it is
reopened from a new snapshot instead), floating layers drawn as windows, images, key sequences and modes in the
keymap, and performing actions for other clients (`rex -C … do`). Keys are named for a US layout, so on other layouts
some shortcuts may be read as the wrong key; the text typed is still right.

### Testing

Run the project's tests with `./kotlin test`, or `./kotlin test -m <module>` for a single module.

---

Learn more
about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html), [Kotlin Toolchain](https://kotlin-toolchain.org/latest/), [Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/), [Kotlin/Wasm](https://kotl.in/wasm/), [Ktor](https://ktor.io/).
