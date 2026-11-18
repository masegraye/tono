# backlog-001: Program copies reach the clipboard (OSC 52)

| | |
|---|---|
| Status | done (commits `971682c`, `9378172`, 2026-11-18) |
| Area | Clipboard |
| Size | small |
| Source | inv-001 |

## What

When a program in a terminal copies (Neovim yank, tmux, an agent's copy), put the text on this machine's clipboard.

## Why

Today the copy goes nowhere. The Rex server already tells every client about it; Tono ignores the event.

## Builds on

The server's `clipboard_written` terminal event, and `com.superlogical.terminal.clipboard_read` for text too large for the event. Tono's `block_event` handling in `rex/src/RexHost.kt` reads only `program_status_changed`, `process_changed` and `program_status_removed`.

Rex Rune's rules, in `src/connection/clipboard.rs` and `docs/terminal.md`, are the ones to copy: accept a copy only from the focused pane of the tab on screen while the window has the keyboard and nothing is open over it; refuse text over 8 MiB; only the newest copy counts; never answer a program's request to read the clipboard.

## Promote when

First in line. It is the smallest piece of the clipboard work and stands alone.

## Not known yet

On the web a page may write to the clipboard only when it is served securely and has focus. Firefox and Safari may refuse a write that doesn't follow a key press or a click. Not tested.

## What was built

A copy from the terminal in use reaches the clipboard on the desktop and in a browser. Built
by a worker and checked by the orchestrator:

- On the desktop, in a real focused window: a copy landed, as did one with non-ASCII text and
  one of 200,000 bytes read in parts. A copy made while the window was not in front, and one
  made with the palette open, left the clipboard alone.
- In the web app, in headless Chromium, by the worker: the same, and copies up to 5 MB.

Decisions made while building, none of them asked for:

- A dropped copy leaves a line in the debug log, with the text's length and never the text.
- In a narrow window showing the sidebar, a copy is dropped, since the terminal isn't on screen.
- Large copies are read 256 KiB at a time.

Not checked: Firefox and Safari, and the 8 MiB refusal against a real server (unit tests only).
