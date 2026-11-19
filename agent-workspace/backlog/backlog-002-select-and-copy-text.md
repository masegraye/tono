# backlog-002: Select and copy text

| | |
|---|---|
| Status | done (commits `COMMITS`, 2026-11-18) |
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

## What was built

Selecting and copying work in the desktop app and the web app, from the same code: dragging,
a double click for a word and a triple click for a line, dragging past the top or bottom to
scroll, Shift to select over a program that has the mouse, `pane.copy` on Ctrl+Shift+C and
`pane.select_all` on Ctrl+Shift+A (the Mac app's Command+C and Command+A).

libghostty-vt does the thinking. Its selection gesture turns presses and drags into a
selection (`GhosttySelection`, bound for the desktop in `ghostty/src@jvm/` and for the web in
`ghostty-glue.js`), the selection is then handed to the terminal, which keeps it, and the
render state says which cells of each row are in it. `rex/src/TerminalPointer.kt` decides
whether an event is the program's, a selection's or a scroll.

Checked by the worker:

- Unit tests of the binding and of the pointer's decisions, on the JVM (`GhosttySelectionTest`,
  `TerminalPointerTest`).
- The web app in headless Chromium, against a scratch session: a drag, a double and a triple
  click, a drag over two lines, Select All over 64 lines, a click clearing, dragging past the
  top scrolling to the first line, a plain drag reaching a program that tracks the mouse and
  a Shift drag selecting there instead, each read back off the clipboard after Ctrl+Shift+C.
  The browser was left none of the keys.
- The glue's functions on their own, in Node, with the same cases as the JVM tests. That
  script is not checked in: there is nowhere in the build to run it yet.

Decisions made while building, none of them asked for:

- **Output scrolling under a selection.** The selection belongs to the text, not to the place
  on screen. It moves up with its lines as output arrives, can still be copied once they have
  scrolled out of view, and comes back into view with them. This is what the library does
  when the terminal owns the selection, and nothing was added to it. If a program writes over
  the selected cells in place, the highlight stays on those cells and a copy takes what they
  hold now. Erasing the screen (`clear`, a full-screen program starting) drops the selection.
- **A terminal sized by another client, or resized.** The selection is this client's alone,
  kept in its own copy of the terminal; other clients don't see it. It survives a resize and
  the reflow that goes with it. Where the pane is larger than the terminal, a drag past the
  terminal's edge counts as the nearest cell.
- Typing or pasting into the terminal drops the selection, as Ghostty does by default. So
  does a click.
- Copying with nothing selected leaves the clipboard alone, and the key still doesn't reach
  the terminal.
- A copy is what Ghostty copies: a line the terminal wrapped is one line, and trailing blanks
  are left out.
- The highlight is a wash of the foreground color over the cells, so text keeps its own
  colors. Rex for Mac has settings for the selection's colors; Tono has none yet.
- In a browser Ctrl+Shift+C and Ctrl+Shift+A are taken from the browser (`page-keys.js`),
  which would otherwise inspect the page and search tabs.
- Shift with a click starts a new selection; it doesn't extend the one there.

Not checked: the desktop app in a real window (it shares everything above but the window
toolkit's pointer events and clipboard, the second of which backlog-001 checked); Firefox and
Safari; selecting by touch, which a drag with a finger now does but nothing on a phone can
then copy without the palette; rectangular selection, which the library has and nothing here
asks for.
