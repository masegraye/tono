# backlog-049: Keep Tono's files where each platform expects them

| | |
|---|---|
| Status | done (commits 526d198, d95faca, 289dbe1, 87cfdc1, 2026-11-19) |
| Area | Shipping |
| Size | medium |
| Source | Mase, 2026-11-19 |

## What

Tono keeps everything in one file, `~/.config/kotlin-test/workspace.json` (or `$XDG_CONFIG_HOME/…`), on every platform that has files. It mixes settings (notifications, selection, shortcuts, appearance, host order, client label) with state (the last host, folded sessions, the tab shown in each session, muted tabs). Files should go where each platform expects them, under Tono's name, with settings and state apart where the platform separates them.

## Why

Getting ready to ship on several platforms (big-idea-001). The directory is named after the repository, not the app, and Linux keeps configuration and state in different places (XDG config vs state), macOS in Application Support, and so on.

## Builds on

`rex/src@jvm/Platform.jvm.kt` (`defaultWorkspace`, the path), `rex/src/Workspace.kt`, the web build's `localStorage`. The agent rule "the configuration path stays as it is" (agent-workspace readme) is under review until this is decided.

## Promote when

Now; investigated in inv-003, then built from its recommendation, with a migration from the old file.

## What was built

Steps 1 to 4 of inv-003, as adr-001 decides. Built by a worker.

- **Two files.** `Workspace` keeps its settings in one storage and its state in another, and writes each only
  when its text changed. Switching host, session or tab writes the state alone. What was read counts as written,
  so a settings file kept by hand is not reformatted until a setting changes. Given one storage it keeps
  everything together in the order it always had: `--workspace FILE` and every older test are unchanged.
- **The two losses of data are closed.**
  - A file (or browser key) that is there and can't be read is never written. The app runs on defaults for that
    half, says so in the debug log, on standard error and in Settings › Server, and saves nothing to it until it
    is started again. `RexClient`'s first save, the one that used to wipe it, writes nothing. A file that exists
    and can't be opened counts as unreadable, not as missing.
  - Keys the app doesn't know are written back: at the top of each file, inside `notifications`, `terminal` and
    `appearance`, and on each host.
- **The places** (`Workspace.defaultFiles`, a function of an environment and a system name):
  `$XDG_CONFIG_HOME/tono/settings.json` and `$XDG_STATE_HOME/tono/state.json` on Linux, with relative or empty
  variables ignored; `~/Library/Application Support/dev.mg.tono/` on macOS; `%APPDATA%\Tono` and
  `%LOCALAPPDATA%\Tono` on Windows. The state directory is made mode 700, as Rex's is.
- **The move** (`Workspace.loadMoving`, shared by the desktop and the web): if settings are already in the new
  place the old one isn't looked at. Otherwise the old file's state keys go to `state.json` and everything else,
  unknown keys included, to `settings.json`, written last and only where there is none (a hard link on the
  desktop, which can't replace a file). Both are read back and compared. The old file is never written. The
  desktop leaves a `moved.txt` beside it.
- **The web:** `tono.settings` and `tono.state`, moved from `tono.workspace` the same way.
- **Settings › Server** shows both locations, or the one for `--workspace`, and a line for a half that isn't
  being saved.
- **Documentation:** a section "Where Tono keeps its files" in `docs/using-tono.md`, the install and development
  pages, `packaging/tono`, and `ops/subagents.md`.

How it was checked:

- Unit tests (`rex/test@jvm/WorkspaceFilesTest.kt`, `WorkspaceMoveTest.kt`, and `KeptRowsTest` in `tono`): the
  split; no settings write on a switch; unreadable files kept, with `RexClient` running over them; unknown keys;
  the Linux, macOS and Windows paths from an environment; the move with a temporary home (fresh, with XDG
  variables, started twice, settings already there readable or not, stopped halfway, old file unreadable then
  corrected, no old file, copies that don't read back).
- A real process (`rexSpike`, which starts as the app does) with `HOME` in a temporary directory: the first start
  made the two files and `moved.txt` and left the old file's bytes and time as they were; the second changed
  nothing.
- The web app in headless Chromium against a scratch Rex server: the old key seeded, then both new keys there,
  the old one kept to the byte, the settings equal to the old non-state keys, a tab switch writing the state
  only, a second load ignoring a changed old key, and an unreadable old key left alone with nothing written.
  Settings › Server was seen there showing both locations.

Decisions made while building, none of them asked for:

- A half that couldn't be read is not saved for the rest of that run, and nothing is written beside it. The
  file is not renamed aside, as inv-003 suggested: the person corrects it, or moves it away, and starts again.
- If the *old* file can't be read, neither new file is written during that run, so that correcting it and
  starting again still moves it.
- A `state.json` already there when the move runs is kept and not replaced.
- An empty file counts as nothing there and is written over.
- `Workspace.defaultFile()` and the no-argument `Workspace.load()` are gone; `defaultWorkspace()` is the way in.
- `marketing/launch-video/record/web.sh` still seeds `tono.workspace`. It makes a fresh browser profile each
  time, so the move takes it.

Not checked: the desktop app's own window (the Settings rows there are the same code the web showed, and their
content is unit-tested); macOS and Windows beyond the unit tests of their paths; a file system without hard
links; Firefox and Safari. Not built: reading the files again when they change on disk, and steps 5 to 8 of
inv-003.
