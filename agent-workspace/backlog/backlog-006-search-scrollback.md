# backlog-006: Search a terminal

| | |
|---|---|
| Status | captured |
| Area | Reading a terminal |
| Size | medium |
| Source | inv-001 |

## What

Ctrl+Shift+F searches the focused terminal's screen and history, with next, previous and a count of matches.

## Why

History can only be scrolled through by eye.

## Builds on

`search.h` in libghostty-vt. Rex Rune's bar floats over the terminal's corner and never resizes it; its actions are `client.search`, `client.search.next` and `client.search.previous`.

## Promote when

After backlog-002.
