# backlog-017: Curly, double, dotted and dashed underlines, and overline

| | |
|---|---|
| Status | done (commit `ede9d9b`, 2026-11-18) |
| Area | Drawing |
| Size | small |
| Source | inv-001 |

## What

Draw the underline a program asked for, in the colour it asked for, and overlines.

## Why

Editors mark errors with a curly underline. Tono draws every underline as a single line in the text's colour.

## Builds on

The style is already read (`GhosttySgrUnderline` in the renderer), and reduced to on or off in `TerminalRun.underline`.

## Promote when

Any time.

## What was built

A program's underline is drawn the way it asked: single, double, curly, dotted or dashed
(SGR 4 and 4:1 to 4:5, and 21), in a color of its own if it set one (SGR 58, as RGB or from the
palette; 59 drops it), and an overline (SGR 53, 55). Built by a worker.

- The kind of underline, its color and the overline are carried in `TerminalRun`, read from
  the library's style on the desktop (`GhosttyRenderer.kt`) and through the browser's frame
  format (`ghostty-glue.js`: a run has seven fields now, the underline's kind in the flags
  and its color a field of its own). No new library functions were bound.
- The lines are drawn by `tono/src/TerminalLines.kt`, a run's cells at a time, and no
  longer by the text layout. So they have no gaps where a run is laid out a character at a
  time, they run under blanks, and blanks under a line need no layout. Strikethrough is drawn
  the same way. Underlines go under the text, overline and strikethrough over it, as in Ghostty.
- Where the lines go is worked out once per cell size (`CellLines`): from the font's own
  underline and strikeout position and thickness where the font can be found, and otherwise
  from the font size (a fourteenth of it thick, a tenth of it under the baseline,
  strikethrough halfway up the small letters). Straight lines are on whole pixels and all
  lines stay inside their cell.
- A curly underline is a sine wave one cell long, starting at each cell's edge, so it is
  the same in every cell and carries on from run to run, also across a change of color.

Checked with offscreen render tests (`tono/test@jvm/TerminalLinesRenderTest.kt`: every kind
at 10 to 20sp and densities 1, 1.25, 1.5 and 2, with and without the font's measures; its
pictures are left in `build/terminal-line-renders/`), a test of what the library reads
(`GhosttyTerminalTest`), and the web app in headless Chromium at scales 1, 1.5 and 2, with
`printf` samples of every kind in a scratch session.

Drawing time, a 200 by 60 screen drawn offscreen in software, the best of five runs of 100
frames: plain text 9.3 ms before and 9.2 ms after; half the runs underlined 10.0 to 11.3 ms
before and 9.2 ms after; half struck through 10.0 before, 9.1 after. The new kinds, with
half of all runs (600) carrying one: curly 11.2 ms, dotted 11.9 ms.

Decisions made while building, none of them asked for:

- The wave is drawn once per cell size, as a strip the length of a row, and each run copies
  its piece of it tinted. Stroking the curve under every run took 30 ms a frame in the
  measurement above; copying takes 11 ms.
- Thickness is rounded up to whole pixels, as Ghostty does, so a line is two pixels on a
  dense screen. The wave is half a pixel thicker than a straight line, or it looks fainter.
- A dotted line has a whole number of dots to a cell and a dashed line one dash in the
  middle of each cell, each put on whole pixels, so they keep step along a row.
- Overline and strikethrough are the text's color; only the underline takes the color a
  program sets for it.
- `TerminalRun.underline` is now a `TerminalUnderline`, not a Boolean.
- The line under a link the pointer is on (Control held) is left as it was.

Not checked: the desktop app in a real window (the drawing is the same code the offscreen
tests run, and reading styles is tested apart; the two were not run together on the
desktop). Whether the font's own measures are found in the browser, or the fractions are
used there; the result looked right either way. Firefox and Safari. Frame time in the
browser and on a graphics card. An underlined cell with no character in it draws no line
unless it has a background, as before.
