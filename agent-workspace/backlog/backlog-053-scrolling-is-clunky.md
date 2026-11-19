# backlog-053: Scrolling a terminal is clunky

| | |
|---|---|
| Status | captured (being diagnosed) |
| Area | Reading a terminal |
| Size | unknown |
| Source | Mase, 2026-11-19, in the installed desktop app on Linux |

## What

Scrolling up and down in a terminal with the mouse wheel or touchpad feels clunky and chunky, not smooth. It's worst with a Pi agent running in the tab, but Pi isn't the only case; any long body of text should be checked. Seen on Linux so far; it may happen elsewhere.

## Why

Scrolling back through output is one of the most common things done in a terminal.

## Builds on

- Scrolling: `rex/src/TerminalPointer.kt` (the wheel: to the program when it tracks the mouse, otherwise `scrollViewport` by rows), and `GhosttyTerminal.scrollViewport`.
- Drawing: `tono/src/TerminalView.kt` (`drawTerminal`, `RowLayouts`, reworked in backlog-040 and 017).
- Frames: `GhosttyRenderer` on the JVM.
- backlog-047 lists Rex for Mac's "smooth scrolling" setting.

## Promote when

Now.

## Not known yet

Which of these it is, or more than one:
- the wheel's steps are too big (whole rows, several per notch, no pixel-level scrolling for touchpads);
- each scroll costs a slow frame (text layout, many runs, images);
- output arriving while scrolled back fights the scroll;
- a program that tracks the mouse (a TUI like Pi) gets the wheel, so scrolling waits on it and the server to redraw;
- the desktop toolkit delivers wheel events coarsely under XWayland.
