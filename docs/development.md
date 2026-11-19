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

## Measuring scrolling

Scrolling can be measured as it happens, from the turn of the wheel to the frame that shows its effect. Start the
desktop app with `TONO_SCROLL_TELEMETRY=<file>` in the environment (or `-Dtono.scrollTelemetry=<file>`; `1` is
standard error), or open the web app with `?scrollTelemetry` in its address, where the lines go to the browser's
console. The installed `tono` command passes the variable on. Off, it costs a flag read.

```sh
TONO_SCROLL_TELEMETRY=/tmp/tono-scroll.jsonl tono      # scroll for a while, then
tools/scroll/analyze.py /tmp/tono-scroll.jsonl         # where the time went, in words and numbers
tools/scroll/graph.py /tmp/tono-scroll.jsonl           # the same as a page, /tmp/tono-scroll.html
```

The file is JSON Lines, each with `t` in microseconds on one clock and `ev`. `rex/src/ScrollTelemetry.kt` says
what each is. In short: the wheel event and what the toolkit said of it; what it became (rows scrolled, or a
report for the program); the report's wait for the control connection, its handing to the socket and the server's
answer to it; each chunk of the terminal's output as it came off its socket, and when and how long the terminal's
thread took it in, with the synchronized updates (mode 2026) in it; each frame read and why; each frame drawn and
what it cost; the window's own frames; and any stall of the drawing thread or the terminals' thread, with where it
was. It writes for a second and a half after a turn of the wheel and no longer, and stops at two million lines.
A file that holds several runs is read for its last.

The analysis gives each stop's share (median, p90, p99, worst), how long scrolling went on after the wheel
stopped, the gaps between frames beside the gaps between turns of the wheel, and what waited on the control
connection. The page draws every turn as a line down through its stops to its frame, so a turn answered late
leans, and the gaps between frames are colored.

More, when that isn't enough:

- `TONO_SCROLL_JFR=<file>` beside it writes a Java Flight Recorder recording of the run (`jfr print <file>`, or JDK
  Mission Control).
- `tools/scroll/proxy.py` stands between Tono and a Rex server and holds every chunk for a time, each way, to make
  a server on another machine reproducible: `--delay 30 --jitter 10` for a slow link, `--tls-cert` and `--tls-key`
  to be an https host (give the app's Java a trust store that holds the certificate, with
  `-Djavax.net.ssl.trustStore`). With `--log <file>` it writes down every read and write of the client's socket
  and every WebSocket frame, on the clock the telemetry's `start` line names, and `analyze.py` and `graph.py` take
  that file as `--link`: they then show when bytes really left and arrived, and what the server and the program
  took, apart from what Tono took.
- `tools/scroll/tui.py` is a full-screen program that redraws for every wheel report, as an agent's interface
  does, and marks its output so that each report is matched with its answer exactly (`--sync` brackets each redraw
  as a synchronized update). For any other program the match is by order, which the analysis says.
- `task probe -- <workspace file> --scroll 10x100,30x8` turns the wheel with no window over the selected host's
  focused terminal: ten notches a tenth of a second apart, then thirty in a flick. `rexSpike/src/Scroll.kt`.

```sh
TONO_SCROLL_MEASURE=mine ./kotlin test -p jvm -m tono --include-classes='*ScrollMeasureTest'
```

runs the drawing side offscreen, from wheel event to picture, over plain text, a colored log, an agent's output and a
full-screen program, and writes a table to `build/scroll-measure/mine.md`. Without the variable it is a short test.

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
