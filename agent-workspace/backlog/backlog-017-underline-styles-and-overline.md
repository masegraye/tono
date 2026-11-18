# backlog-017: Curly, double, dotted and dashed underlines, and overline

| | |
|---|---|
| Status | captured |
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
