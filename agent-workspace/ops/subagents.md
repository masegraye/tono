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
