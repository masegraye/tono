# backlog-018: Draw box and block characters as shapes

| | |
|---|---|
| Status | captured |
| Area | Drawing |
| Size | medium |
| Source | inv-001 |

## What

Draw the line-drawing and block characters (U+2500 to U+259F) as shapes on whole pixels rather than from the font.

## Why

Drawn from a font, borders and tables can show gaps or overlaps between cells, depending on font size and scale.

## Builds on

Ghostty and Rex Rune both do this.

## Promote when

If seams are seen. Not confirmed as a visible problem in Tono.

## Not known yet

Whether Tono shows seams at the sizes in use. Look before building.
