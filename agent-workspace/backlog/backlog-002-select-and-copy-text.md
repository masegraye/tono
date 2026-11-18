# backlog-002: Select and copy text

| | |
|---|---|
| Status | captured |
| Area | Clipboard |
| Size | large |
| Source | inv-001 |

## What

Drag to select text in a terminal, double-click for a word, triple-click for a line, and copy with Ctrl+Shift+C. Shift+drag selects where a program has the mouse. Select All.

## Why

There is no way to get text out of a terminal in Tono other than a program copying it.

## Builds on

libghostty-vt already has selection: `selection.h` in `native/out/include/ghostty/vt/` has the press, drag, release and autoscroll gestures, word and line selection, and formatting a selection as text. The work is binding those functions for the desktop (`native/ghostty-vt.symbols`) and the web (`webApp/resources/ghostty-glue.js`), drawing the highlight in `tono/src/TerminalView.kt`, and the `pane.copy` and `pane.select_all` actions.

## Promote when

After backlog-001. Links (backlog-005) and search (backlog-006) reuse the same plumbing for pointing at cells, so this goes before them.

## Not known yet

How a selection behaves while output scrolls under it, and on a terminal sized by another client.
