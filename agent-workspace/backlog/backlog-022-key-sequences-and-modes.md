# backlog-022: Key sequences and modes

| | |
|---|---|
| Status | captured |
| Area | Typing and keys |
| Size | medium |
| Source | inv-001 |

## What

Shortcuts of more than one key, and modes from the Rex configuration (a resize mode, say), with the active mode named on screen.

## Why

A binding with a mode is skipped today (`parseKeymap` in `rex/src/Keymap.kt`), so a configuration that uses modes doesn't work in Tono. Sequences have not been tried.

## Builds on

The keymap the server resolves. Rex Rune shows the innermost active mode in a chip.

## Promote when

When a real `init.lua` with modes or sequences is used with Tono.

## Not known yet

The keymap has not been tried against a real `init.lua` at all.
