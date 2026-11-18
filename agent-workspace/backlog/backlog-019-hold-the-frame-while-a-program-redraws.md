# backlog-019: Hold the frame while a program redraws

| | |
|---|---|
| Status | captured |
| Area | Drawing |
| Size | small |
| Source | inv-001 |

## What

While a program has said it is in the middle of an update (mode 2026), keep showing the last finished frame.

## Why

Without it a full-screen program can flash blank as it redraws after a resize.

## Builds on

Rex Rune holds the frame for up to a second, as Ghostty does.

## Promote when

If flashes are seen.

## Not known yet

Whether Tono already gets this from libghostty-vt's render state. Not checked.
