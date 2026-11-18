# Working on Tono

## Tools

- **The Kotlin Toolchain** builds everything. The `kotlin` script in the project root fetches the pinned version on
  first use, so there is nothing to install. `./kotlin --help` lists its commands.
- **[mise](https://mise.jdx.dev)** provides the rest: Task, Java and Zig. `mise install` gets them.
- **[Task](https://taskfile.dev)** is what you run day to day. `task` lists the tasks in
  [Taskfile.yml](../Taskfile.yml).

A fresh clone builds as it is: every module it depends on is in this repository.

## The tasks

| Task | What it does |
|---|---|
| `task desktop` | Runs the desktop app with Compose Hot Reload: a saved change shows in the running window within a second or two, and the window keeps its state |
| `task desktop:steady` | Runs it without Hot Reload, for when you are using the app while changing it |
| `task web` | Builds the web app and serves it at http://127.0.0.1:8080 |
| `task web:watch` | Beside `task web`: rebuilds on every change and reloads open browser tabs (about 15 seconds; the page's state is lost) |
| `task web:release` | Builds the optimized web app and a self-contained server jar, and runs them |
| `task test` | Runs the tests that can run on this machine |
| `task probe` | Makes the app's connections with no window, and prints what it found |
| `task native`, `task native:wasm` | Build Ghostty's terminal core for the desktop and for the browser. Other tasks run these when needed |
| `task bindings` | Regenerates the Java bindings for the terminal core, from [ghostty-vt.symbols](../native/ghostty-vt.symbols) |
| `task install`, `task uninstall` | See [Installing](install.md) |

Options for the app go after `--`, as in `task desktop -- --workspace FILE`.

Hot Reload doesn't cover changes to dependencies or to `desktopApp/src/main.kt`; those need a restart.

## The modules

| Module | What it is |
|---|---|
| [tono](../tono) | Tono's interface, shared by the desktop and the web |
| [rex](../rex) | The Rex client with no interface: connections, sessions, live terminals |
| [rexSpike](../rexSpike) | `task probe`: a command-line check of that client |
| [ghostty](../ghostty) | Wraps libghostty-vt: a shared library on the JVM, a WebAssembly module in a browser |
| [desktopApp](../desktopApp) | Puts Tono in a window (JVM) |
| [webApp](../webApp) | Puts Tono on a web page, compiled to WebAssembly |
| [server](../server) | A Ktor server that serves the web app and gives it what a page can't get for itself |
| [omarchy-theme](../omarchy-theme), [omarchy-theme-compose](../omarchy-theme-compose), [omarchy-theme-ktor](../omarchy-theme-ktor) | The theme libraries; see [Omarchy theming](omarchy-theming.md) |
| [shared](../shared), [androidApp](../androidApp), [iosApp](../iosApp) | The Compose showcase the project began as, and its Android and iOS apps |
| [native](../native) | Builds libghostty-vt from the Ghostty source pinned in [GHOSTTY.lock](../native/GHOSTTY.lock) |
| [packaging](../packaging) | The installer, the launcher entry and the icons |

In a module, `src` holds code for every platform, and `src@jvm` or `src@wasmJs` code for the one named.

The package is `org.example.kotlintest` and the settings directory `~/.config/kotlin-test`: the project was called
that before it was called Tono.

## Tests

```sh
task test                    # everything that runs here: JVM and Android
./kotlin test -m rex -p jvm  # one module
```

The Android tests need the Android SDK's licence accepted on the machine. `./kotlin test -p jvm` also draws every
tab of the showcase offscreen, into `build/showcase-renders/`.

## Checking connections without a window

```sh
task probe                               # every configured server: sessions, tabs, terminals
task probe -- FILE --watch 60            # stay connected a minute, and say when a connection changes
```

The probe only looks. It makes the same connections the app does, through the same code.

## Tracking work

[agent-workspace](../agent-workspace/readme.md) holds the backlog, investigations and notes. Much of Tono was built
by coding agents, and [AGENTS.md](../AGENTS.md) is what they read first.

## The launch film

[marketing/launch-video](../marketing/launch-video) is the short film made for Tono's launch, and the scripts that
recorded it. The pictures in these documents are frames from its footage.
