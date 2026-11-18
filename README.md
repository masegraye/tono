# Tono

A Linux client for [Rex](https://www.superlogical.com/rex) that takes its colors and font from the
[Omarchy](https://omarchy.org/) desktop. Rex is Latin for king; *tono* (殿) is Japanese for lord. It is a Kotlin Multiplatform project targeting Android, iOS, Desktop (fast UI
development with Compose Hot Reload) and Web (Wasm), built with the
[Kotlin Toolchain](https://kotlin-toolchain.org/latest/).

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
- [/omarchy-theme](./omarchy-theme), [/omarchy-theme-compose](./omarchy-theme-compose) and
  [/omarchy-theme-ktor](./omarchy-theme-ktor) are the libraries that read the Omarchy theme, turn it into a Compose
  Material theme, and serve it to web pages.
- [/webApp](./webApp) contains the web application, compiled to WebAssembly with Kotlin/Wasm.

The `kotlin` (macOS/Linux) and `kotlin.bat` (Windows) scripts in the project root are self-bootstrapping wrappers for
the Kotlin Toolchain: they download the pinned toolchain version on first use, so no separate installation is required.
Build the whole project with `./kotlin build`.

Everything else needed comes from [mise](https://mise.jdx.dev/), and what you run day to day is a
[Task](https://taskfile.dev/) task:

```sh
mise install   # once: Task, and the Java and Zig that some tasks use
task           # lists the tasks in Taskfile.yml
```

A fresh clone builds as it is: every module it depends on is in this repository.

### Running

Run each module from the command line:

- Android app: `./kotlin run -m androidApp`
- Desktop app: `task desktop` (it needs the native library; see [Tono](#tono-the-rex-client))
- iOS app: `./kotlin run -m iosApp`
- Web app: `./kotlin run -m webApp` (static files only), or `task web` to serve it from the server module with
  live theming

### UI showcase and Omarchy theming

The shared module is a tabbed showcase of what Compose Multiplatform offers on every target: Material 3 components,
inputs, lazy lists and pagers, animations, canvas drawing, and gestures. One file per tab lives in
[shared/src](./shared/src) (`Section*.kt`).

The app takes its colors, and on desktop its font, from the [Omarchy](https://omarchy.org/) desktop theme. It follows
whichever theme is applied, including themes installed later, and restyles itself at runtime when the theme changes:
nothing is rebuilt or reloaded.

That behaviour comes from the three `omarchy-theme` modules, described in
[omarchy-theme/README.md](./omarchy-theme/README.md). They know nothing about this app, so another app could use them
as they are. What this project does with them:

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

The tasks are in [Taskfile.yml](./Taskfile.yml) (`task` lists them):

- `task desktop` runs the desktop app with Compose Hot Reload: saved UI changes appear in the running window within
  a second or two and keep its state. Changing dependencies or `desktopApp/src/main.kt` needs a restart.
  `task desktop:steady` runs it without Hot Reload, for when you are using the app while changing it: a build then
  leaves the running app alone.
- `task test` runs the tests that can run on this machine.
- `task web` builds the web app and serves it from the server module at http://127.0.0.1:8080. `task web:watch`
  in a second terminal rebuilds it whenever a source file changes and reloads open browser tabs. Hot Reload is JVM-only,
  so on web a code change is a full rebuild and page reload (about 15 seconds) and the page state is lost. The reload is
  driven by [dev-reload.js](./webApp/resources/dev-reload.js), which only runs on localhost.
- `task web:release` builds the optimized web app and a self-contained server jar and runs them, with no build tool
  involved at runtime. The server reads `WEB_DIR`, `HOST` (default `127.0.0.1`) and `PORT` (default `8080`) from the
  environment.

### Tono, the Rex client

The desktop app is a Linux client for [Rex](https://www.superlogical.com/rex), which keeps terminals running on a server
and lets clients attach to them. It is desktop-only; the other targets still show the UI showcase, which the desktop
app keeps under its menu.

```sh
task native    # once: download and build libghostty-vt into native/out (about two minutes)
task desktop   # the client
task probe     # the same connections with no window: prints what was restored
```

To have it as a command, `task install` builds the app as one jar and installs `tono` into `~/.local/bin`, with the
app, the terminal library and a link to the Java that runs them in `~/.local/share/tono`, and an entry for the
desktop's app launcher. It no longer needs this checkout after that; run `task install` again to update it, and
`task uninstall` to remove it. Its windows have the class `tono`, which is what a Hyprland window rule matches.

```sh
task install
tono            # or: tono --workspace FILE
```

When it starts it connects to every configured server at once and restores where you were:

- The servers are the local one, plus every host in its host list. That list is where Rex itself keeps hosts
  (`rex hosts`), so a host added in the app is also known to the `rex` command, and one added with `rex hosts add`
  appears in the app at once. An endpoint is `unix:///path`, or `https://host` for a server reached over the network.
  A server with built-in Tailscale is a device on the tailnet, so its `https://` address just works on a machine
  that is on the tailnet too.
- **Add Host…** (the palette, the sidebar's menu, or the end of the Switch Host list) lists the Rex servers on your
  tailnet that aren't hosts yet: the devices `tailscale status` reports whose names end in `-rex`, which is how a
  server names its own tailnet device. Pick one, or type any address. A server that is down can be added too. **Remove
  Host…** takes one off the list. Hosts saved in `~/.config/kotlin-test/workspace.json` are connected to as well; a
  host goes there only when no local server can keep it.
- A host that can't be reached shows a red dot and nothing else, and is tried again every few seconds until it
  answers. Hovering it offers a button to try now; the reason is in the debug log.
- For each server it selects the session you last had (else the first listed) and the tab you last had in it, and
  lays out that tab's terminals where the server says they sit. It creates and closes things only when asked to.
- The sidebar is a set of nested headings: servers, each with the same weight (this machine's first, then the ones
  that are connected by name, then the ones that aren't, so a server that is down takes no room from those in use);
  their sessions, as small quiet headings; and under each session its tabs (a tab is a Rex window), which are what you go
  to. Clicking a server or a session folds it away. With the pointer over one, a plus fades in that creates what
  belongs under it: a session on that server, or a tab in that session. The layouts of all sessions are read and
  kept current, not just the one on screen.
- Dragging a tab moves it among the tabs of its session, and dragging a session by its heading moves it, tabs and
  all, among the sessions of its server. Both orders are the server's (`session.move_window`, `session.move`), so
  the new order shows in every client, as an order made in Rex.app shows here.
- A tab is called by the program in the foreground of its focused pane, with that program's directory beside it in
  quieter text (`nvim  ~/dev/rex`), and follows it as commands start and finish. Rex itself stores only a label per
  window, which is a placeholder such as `Window 89abcdef` until someone sets it; what to show instead of the
  placeholder is each client's choice, made here from the process the server reports for the terminal. Naming a tab
  (Rename Tab… in the palette, or a double click on it) sets that label, so the name shows in every client and stays
  until it is cleared. Saving an empty name clears it, and the tab is called by its program again.
- Three dots beside a tab show what its program says it is doing (programs report this with OSC 7501): rippling
  while it works, steady in the accent color while it waits for you, and in the error color after a failure. A
  folded server or session shows the dots for whatever is inside it.
- A dropped connection is retried, and everything is read again when it comes back.

A terminal takes its size from one client at a time, its owner, and the client someone is using is the one whose
size should count. So Tono claims the terminals on screen while its window has focus: when the window comes forward
and when you go to a tab, as Rex's own apps do. Typing or clicking in a terminal claims it too, and so does moving
the pointer over it. If another client takes a terminal back, Tono leaves it alone until one of those happens again,
so two clients never fight over one.
Without focus, Tono only looks: a terminal is drawn at the size its owner gave it.

Which tab is on screen is this app's own choice. Two clients on one session share its terminals, so each sees what
the other types, but each looks at the tab it chose: going to a tab in Rex.app doesn't move this app, and the other
way round. The session does keep an active window, the last one any client went to, and this app sets it when you
go to a tab, as Rex.app does; that is where a command naming no window acts. Which pane of a tab has the keyboard
is the tab's own state, and is the same for every client looking at it.

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
| Alt+] / Alt+[ | Focus the next / previous pane |
| Ctrl+Shift+Enter | Zoom the pane, or restore the layout |
| Ctrl+Shift+W / Ctrl+Alt+W | Close the pane / the tab, after asking |
| Ctrl+Shift+V | Paste, bracketed when the program asks for that |
| Shift+PageUp / Shift+PageDown | Scroll through history |
| Ctrl+= / Ctrl+- / Ctrl+0 | Larger / smaller / default text |
| Ctrl+Shift+B | Show or hide the sidebar |
| Ctrl+` | Show or hide the debug log |

Tabs are one list, in sidebar order, across every session and server: Control and a digit go to that tab, whichever
session it is in, and next and previous carry on from one session into the next. Holding Control shows each tab's
digit at the right of its row, until Control is let go. Tabs folded away with their session or server are left out of
the numbering, so folding what you aren't using brings the rest within reach.

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
  foreign function API, using bindings generated from its headers (`task bindings` regenerates them from
  [ghostty-vt.symbols](./native/ghostty-vt.symbols)).
- **Drawing.** libghostty-vt reports which rows changed; [TerminalView.kt](./desktopApp/src/TerminalView.kt) draws the
  cells on a Compose canvas, in the Omarchy font and colors, and recolors when the desktop's theme changes.
- **Typing.** Key events go to the server as they came from the keyboard (`client.key`); the server applies key
  bindings and encodes them for the terminal. Pasted text is made safe and framed by libghostty-vt's paste encoder.
- **Images.** Programs can show pictures with the Kitty graphics protocol (`kitten icat`, image previews in file
  managers, plots). libghostty-vt keeps the images and works out where each goes; the app decodes PNGs for it and
  draws them, scaled to cover the same cells as in the terminal's owner. Images come back with a terminal's snapshot,
  so they are still there after going to another tab and back. Only pixels sent in the output are accepted: the
  protocol's ways of naming a file or shared memory to read are turned off, so what a remote program prints can't make
  this machine read its own files. Animated images show their first frame.
- **The mouse.** A program that tracks the mouse (Neovim, htop, tmux) says so by setting terminal modes, which
  libghostty-vt keeps; its mouse encoder turns each click, drag, movement and wheel notch into the report the
  program asked for, by cell, or into nothing if it didn't ask. Over a program that isn't tracking, the wheel scrolls
  the terminal's own history. A click claims the terminal as a key press does; passing over it doesn't.
- **Actions.** [RexActions.kt](./desktopApp/src/RexActions.kt) is the table of what the app can do and its default keys.

Not there yet: text selection and copy, SSH hosts, resuming a terminal's stream after a drop (it is
reopened from a new snapshot instead), floating layers drawn as windows, animated images, key sequences and modes in the
keymap, and performing actions for other clients (`rex -C … do`). Keys are named for a US layout, so on other layouts
some shortcuts may be read as the wrong key; the text typed is still right.

### Testing

Run the project's tests with `./kotlin test`, or `./kotlin test -m <module>` for a single module.

---

Learn more
about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html), [Kotlin Toolchain](https://kotlin-toolchain.org/latest/), [Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/), [Kotlin/Wasm](https://kotl.in/wasm/), [Ktor](https://ktor.io/).
