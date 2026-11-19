# Running workers on Tono

How to fan work out to subagents in this repository. The general routine is a Pi skill of
the author's, `rex-subagents`, which is not part of this repository: each worker is an
interactive Pi session in its own Rex tab and its own git worktree, talking to the
orchestrator over pi-intercom. Its two scripts are `spawn-worker` and `close-worker`. This
document is what is particular to Tono. Checked on 2026-11-18.

## Starting a worker

```bash
spawn-worker \
  --name <backlog-slug> --brief <file> --worktree \
  --setup 'mkdir -p native && cp -a --reflink=auto "$MAIN_WORKTREE/native/out" "$MAIN_WORKTREE/native/out-wasm" native/ && mise trust --quiet'
```

The `--setup` copies in the two built terminal libraries, which git doesn't carry. Without
it the first `task` in the worktree builds libghostty-vt again, which takes about two
minutes and a download. With it, `task native` and `task native:wasm` both say the library
is already built.

Workers land in a Rex session named after the project (`<project>-agents`), in worktrees
beside the checkout (`../<project>.worktrees/`), on branches named `agent/<name>`.

## What a fresh worktree costs

Measured in a worktree set up as above, with nothing built in it:

| | Time |
|---|---|
| The setup command | under a second |
| `task test` (all 162 tests, compiling everything first) | 32 seconds |
| `./kotlin build -m webApp` | 52 seconds |
| `task package` after that | 2 seconds |
| Disk, after all three | about 400 MB |

The web app served from the worktree on another port loaded, connected and took typing,
while the main checkout's server stayed up on 8080.

## What workers share, and how to keep them apart

- **Rex servers. Test against a scratch server of your own, never Mase's.** A web server
  started without `REX_SERVER`, or a desktop app without a `--workspace` file of its own,
  uses Mase's real home server and its host list. It then connects to every machine on it
  (his Mac, his other Linux boxes) as a "Tono" client, and a test page that counts as
  focused can take over terminal sizes there. One click on a row of the tailnet list can do
  the same. Start your own:

  ```bash
  rex server run --listen unix:///tmp/<name>/rex.sock   # with its own state directory; see rex server run --help
  REX_SERVER=unix:///tmp/<name>/rex.sock ...            # for the web server
  ```

  For the desktop app, use a `--workspace` file whose only host is that socket. Give test
  pages a stubbed `/api/tono/tailnet` answer, so no real machine is one click away. Seen
  twice on 2026-11-19.

- **Ports.** `task web` serves on 8080 by default, and the main checkout's server usually
  has it. A worker runs its own with `PORT=<a free port>`, building first and then
  `WEB_DIR=<its build> PORT=<port> ./kotlin run -m server`, as `task web` does.
- **Scratch Rex sessions.** The existing test scripts all use a session called `kt-web`
  and kill it when they start, so two workers using them at once would end each other's
  tests. A worker names its scratch sessions after itself (`<name>-web`), and folds every
  other session away in its test workspace as the workspace readme says.
- **The headless browser.** Each run needs its own debugging port and profile directory.
- **The desktop.** There is one keyboard, pointer and focused window. Checks that need a
  real window go one at a time, through the orchestrator, and are announced first.
- **The installed `tono`.** `task install` replaces the app the user runs. Only the
  orchestrator does it, from the main checkout, after merging.
- **Generated bindings.** `task bindings` rewrites `ghostty/src@jvm/ffi/`, and two workers
  adding library functions will conflict there and in `native/ghostty-vt.symbols` and
  `webApp/resources/ghostty-glue.js`. Work that binds new functions goes one at a time.

## A desktop check with real keys and mouse buttons, on a display of its own

The desktop app can be sent real key presses and mouse buttons without the desktop's
keyboard, pointer or focus, by running it on an X server nested in a window that sits on a
hidden Hyprland workspace. Worked out for backlog-014 on 2026-11-18; still ask the
orchestrator first, since it opens a window.

```bash
# The server: one window, on a hidden workspace, never focused. Pick a display number of your own.
hyprctl dispatch "hl.dsp.exec_cmd(\"Xwayland :77 -geometry 1400x900 -noreset -nolisten tcp\", { workspace = \"special:<name> silent\" })"
DISPLAY=:77 xdotool getdisplaygeometry || exit 1     # not up: stop, never fall back to the real display

# The app, as a client of that server only
env -u WAYLAND_DISPLAY DISPLAY=:77 java -Dskiko.renderApi=SOFTWARE --enable-native-access=ALL-UNNAMED \
  --add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED -jar <the packaged jar> --workspace <file> &
WIN=$(DISPLAY=:77 xdotool search --name "<scratch session's name>" | head -1)   # the window whose title names the session

DISPLAY=:77 xdotool mousemove --window $WIN 900 500 click 1   # a click gives the app the keyboard; there is no window manager
DISPLAY=:77 xdotool key ctrl+2
DISPLAY=:77 xdotool click 8                                   # the mouse's back button; 9 is forward, 6 and 7 the wheel tilted
```

What to know:

- `DISPLAY` goes on every `xdotool` call. Without it the keys go to the real desktop.
- There is no window manager in there, so `xdotool windowfocus` and `windowactivate` don't
  give the app the keyboard. A left click in the window does; wait two seconds after it.
- The window is never shown, so the app draws late: its window title was seen a step behind
  what it had done. Read what happened from the server (`rex window ls -s <session> --json`,
  `active_window_id`) and not from the title or a screenshot.
- Run the app with `-Dskiko.renderApi=SOFTWARE`, as above. Without it the app waits on the
  graphics card to show a frame that is never shown, and acts on a click seconds late or
  only when the next thing happens: a paste turned up one click after the click that asked
  for it. With it the app answered in under a second (backlog-004, 2026-11-18), and
  `import -window root` showed the window as it was.
- Positions for `xdotool mousemove --window` count from the window's corner in the server's
  pixels, and the app is drawn larger than in a browser (the sidebar was 386 pixels wide,
  not 240). Take a screenshot and measure before clicking, and keep the numbers in the
  script: a click meant for the terminal that lands in the sidebar lands on a real session.
- The nested server has a PRIMARY and a CLIPBOARD of its own, which the desktop's never
  see: `xclip -selection primary` with `DISPLAY` set reads and sets them.
- End the app first, then the scratch session, then the server. `pkill -f "Xwayland :77"`
  also matches the shell running it; find the process by its arguments and kill its number.
  Never `pkill Xwayland`: the desktop's own is one too.

## What isn't in the repository yet

The scripts that drive the web app in a headless browser, and the ones that put a real
window on a hidden workspace, were written as the work went and are not checked in. A
worker in a worktree doesn't have them. Until they are, the orchestrator runs the browser
checks itself, or hands a worker the files.

## Which backlog entries can run side by side

The entries that touch the terminal view and the library bindings overlap: 002 (select and
copy), 005 (links), 006 (search), 007 (prompts), 017 (underlines), 018 (box drawing). Run
those in sequence. Entries that mostly add to what Tono reads from the server stand apart
from them: 001 (program copies), 008 and 009 (notifications, bell, progress), 010 (window
title), 012 and 013 (pane and tab management). These can run side by side, but several add
cases to the same event handling in `rex/src/RexHost.kt` and functions to
`tono/src/TonoPlatform.kt`, so expect small conflicts there when merging.
