# backlog-040: Text drifts from its cells in the web app

| | |
|---|---|
| Status | done (commit `f79ba55`, 2026-11-18) |
| Area | Drawing |
| Size | small |
| Source | seen while building backlog-006 |

## What

In the web app, a long plain run of text is drawn slightly narrower than its cells: by the far right of a 120-column row it sits about one cell left of where the grid says it is. Highlights are drawn per cell, so a selection or a search match near the right edge sits a cell off its text.

## Why

Highlights that miss their text look broken, and the further right, the worse it gets.

## Builds on

`tono/src/TerminalView.kt` measures a sample (`"MMMMMMMMMMMMMMMMMMMM"`) for the cell width and then draws each run as one piece of text. If the browser's font advance differs slightly from the measured width, the run drifts. Seen in headless Chromium (`/tmp/search-terminal/24-crop.png` at the time); the desktop app has not been checked for the same.

## Promote when

Soon: selection (002) and search (006) both show it.

## Not known yet

Whether it is rounding of the measured cell width, font fallback within the run, or letter spacing; whether drawing each cell (or each run snapped to its first cell's x) fixes it without slowing frames.

## What was built

Text stays in its cells, on the desktop and in the browser. Built by a worker.

The cause was the measuring, and it was not the browser's alone. A cell was taken to be as
wide as the layout of twenty `M`s divided by twenty, and a layout's size is its text's
width rounded up to a whole pixel. At 13sp the font's advance is 7.8 pixels, twenty of them
come to 156.00006, the layout is 157 wide, and so a cell was 7.85: each glyph a twentieth of
a pixel short of its cell, six pixels by column 120. Which sizes and screen densities were
hit depended on which way the arithmetic happened to fall (13sp at densities 1, 1.5 and 2
were; 14sp at 1 was not). The desktop app drifted in the same way.

- A cell is now as wide as the font's own advance, read from where the layout puts the end
  of a sample of a hundred characters.
- A run is still laid out and drawn as one piece, and it is checked against its cells: if
  it comes out wider or narrower than they are, each of its characters is laid out by
  itself and placed in its own cell. That is for a font whose bold is a different width, or
  that lacks a character. It turned out to be needed on this machine at once: asked for by
  the name "monospace" alone, which is what the desktop falls back to when the theme names
  no font, the text engine gives a font whose space is about a third as wide as its letters.
- A row's backgrounds are all drawn before any of its text, so that the next run's
  background doesn't cut into a glyph that leans over the edge of its cell.
- `TerminalRun.oneToACell` says whether a run is plain characters, a cell each, or one
  character that takes all the run's cells (an emoji is two `Char`s and two cells, and must
  not be taken apart).
- The drawing of a frame is a function of its own, `drawTerminal`, so that a test can draw
  one: `tono/test@jvm/TerminalTextRenderTest.kt` draws rows of 130 columns offscreen and
  looks at where the ink is. It leaves its pictures in `build/terminal-text-renders/`.

Checked:

- Offscreen on the desktop: a glyph in column 128 is within half a pixel of its cell at 10
  to 16sp and densities 1, 1.25, 1.5 and 2, in regular, bold, italic and bold italic; a
  selection and a search match at the right edge cover their word; text after Chinese,
  Korean and emoji starts in its own cell. With the old measuring all five tests fail, by
  about six pixels.
- In the web app, in headless Chromium at 1500 pixels wide (158 columns): a search's marks
  on words at the right edge, in regular, bold and italic, sat a cell to the right of
  their text before and sit on it now.
- Frame times. Offscreen on the desktop, 200 by 60 cells of text, drawn in software, median
  of 300 frames: 10 ms with unchanged rows and 20 ms with every row new, ten runs to a row,
  before and after alike; 8 ms and 9.5 ms with one run to a row, before and after alike. In
  the browser a six-second flood of full-width lines kept to 16.7 ms a frame before and
  after, with none dropped.

Decisions made while building, none of them asked for:

- The check of a run against its cells goes by the layout's size, which is rounded to a
  whole pixel, so a run may end up to a pixel and a half from its last cell before it is
  taken apart. Asking the layout for the exact place cost about 7% of a frame in which
  every row was new.
- Cells are a little narrower than they were where the old measuring rounded up, so a
  window may fit a column more: 158 where it was 157.
- In a run that is taken apart, a line under or through the text is drawn glyph by glyph,
  and so has gaps where the glyphs are narrower than their cells. Left for backlog-017.

Not checked: the desktop app in a real window (offscreen renders only); Firefox and Safari;
a wide character whose glyph is wider than its two cells, which is drawn from the left of
its cells as before.
