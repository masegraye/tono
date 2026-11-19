# Working on Tono

## Tools

- The Kotlin Toolchain builds everything. The `kotlin` script in the project root downloads the pinned version the
  first time you run it, so you don't have to install it. `./kotlin --help` lists its commands.
- [mise](https://mise.jdx.dev) installs the other tools (Task, Java and Zig) when you run `mise install`.
- [Task](https://taskfile.dev) is what you run day to day. Running `task` lists the tasks in
  [Taskfile.yml](../Taskfile.yml).

A fresh clone builds without anything else, because every module it depends on is in this repository.

## Tasks

| Task | What it does |
|---|---|
| `task desktop` | Runs the desktop app with Compose Hot Reload. A saved change shows up in the running window within a second or two, and the window keeps its state |
| `task desktop:steady` | Runs it without Hot Reload, for when you're using the app while changing it |
| `task web` | Builds the web app and serves it at http://127.0.0.1:8080 |
| `task web:watch` | Run it next to `task web`. It rebuilds on every change and reloads open browser tabs, which takes about 15 seconds and loses the page's state |
| `task web:release` | Builds the optimized web app and a self-contained server jar, and runs them |
| `task test` | Runs the tests that can run on this machine |
| `task probe` | Makes the app's connections without opening a window, and prints what it found |
| `task native`, `task native:wasm` | Build Ghostty's terminal core for the desktop and for the browser. Other tasks run these when they need to |
| `task bindings` | Regenerates the Java bindings for the terminal core from [ghostty-vt.symbols](../native/ghostty-vt.symbols) |
| `task install`, `task uninstall` | See [Installing](install.md) |

Options for the app go after `--`, as in `task desktop -- --workspace FILE`.

Hot Reload doesn't pick up changes to dependencies or to `desktopApp/src/main.kt`. Restart the app after those.

## Modules

| Module | What it is |
|---|---|
| [tono](../tono) | Tono's interface, shared by the desktop and the web |
| [rex](../rex) | The Rex client, with no interface: connections, sessions and live terminals |
| [rexSpike](../rexSpike) | `task probe`, a command-line check of that client |
| [ghostty](../ghostty) | Wraps libghostty-vt, which is a shared library on the JVM and a WebAssembly module in a browser |
| [desktopApp](../desktopApp) | Puts Tono in a window (JVM) |
| [webApp](../webApp) | Puts Tono on a web page, compiled to WebAssembly |
| [server](../server) | A Ktor server that serves the web app and does what a page can't do for itself |
| [omarchy-theme](../omarchy-theme), [omarchy-theme-compose](../omarchy-theme-compose), [omarchy-theme-ktor](../omarchy-theme-ktor) | The theme libraries, described in [Omarchy theming](omarchy-theming.md) |
| [shared](../shared), [androidApp](../androidApp), [iosApp](../iosApp) | The Compose showcase the project started as, and its Android and iOS apps |
| [native](../native) | Builds libghostty-vt from the Ghostty source pinned in [GHOSTTY.lock](../native/GHOSTTY.lock) |
| [packaging](../packaging) | The installer, the launcher entry and the icons |

Inside a module, `src` holds code for every platform. `src@jvm` and `src@wasmJs` hold code for just that platform.

The package is still `org.example.kotlintest`, because the project was called kotlin-test before it was called
Tono. Tono's own files are under its name (`~/.config/tono/settings.json` and `~/.local/state/tono/state.json` on
Linux: see [Where Tono keeps its files](using-tono.md#where-tono-keeps-its-files)). The old
`~/.config/kotlin-test/workspace.json` is only ever read once, to move from.

Run a build you're working on with `--workspace FILE`, as in `task desktop -- --workspace /tmp/tono-dev.json`, so
that it keeps everything in that one file and leaves your own settings and state alone.

## Running as if Omarchy weren't there

Tono follows the Omarchy desktop where there is one, and has themes and a font of its own for where there isn't.
To see the second on a machine that has Omarchy, set `OMARCHY_ABSENT=1` (or the system property
`-Domarchy.absent=true`): the theme library then reads nothing of the desktop, for the desktop app and for the
server that feeds the web app alike. Nothing of Omarchy's is touched.

```sh
OMARCHY_ABSENT=1 task desktop:steady
OMARCHY_ABSENT=1 task web
```

The tests in `tono/test@jvm/AppearanceRenderTest.kt` draw the app this way, in several themes and fonts, and leave
the pictures in `build/appearance-renders/`.

## Tests

```sh
task test                    # everything that runs here: JVM and Android
./kotlin test -m rex -p jvm  # one module
```

The Android tests need the Android SDK licence to be accepted on the machine. `./kotlin test -p jvm` also renders
every tab of the showcase offscreen into `build/showcase-renders/`.

## Checking connections without a window

```sh
task probe                               # every configured server: sessions, tabs, terminals
task probe -- FILE --watch 60            # stay connected a minute, and say when a connection changes
```

Run like this, the probe is read-only. It makes the same connections as the app, through the same code.

## Tracking work

[agent-workspace](../agent-workspace/readme.md) holds the backlog, investigations and notes. Coding agents built
much of Tono, and [AGENTS.md](../AGENTS.md) is the first thing they read.

## The launch film

[marketing/launch-video](../marketing/launch-video) contains the short film made for Tono's launch and the scripts
that recorded it. The images in these docs are frames from its footage.
