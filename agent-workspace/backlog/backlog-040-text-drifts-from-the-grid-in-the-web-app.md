# backlog-040: Text drifts from its cells in the web app

| | |
|---|---|
| Status | captured |
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
