# backlog-039: Pane keys reach the terminal in a tab with one pane

| | |
|---|---|
| Status | captured |
| Area | Typing and keys |
| Size | small |
| Source | found while building backlog-012 |

## What

In a tab with a single pane, Ctrl+Alt+arrows (`pane.focus.*`) and Ctrl+Alt+Shift+arrows (`pane.move.*`) aren't offered as actions, so the key falls through to the terminal and the shell prints the tail of the escape sequence (`ESC[1;8D` shows as `D`). Keep these keys from the terminal whenever they are Tono's, and do nothing when there is nowhere to go.

## Why

A shortcut that sometimes types junk into the shell is worse than one that sometimes does nothing.

## Builds on

The `available` checks on `pane.focus.*` and `pane.move.*` in `tono/src/RexActions.kt`. backlog-012 fixed the same leak for `pane.move.*` at a tab's edge (commit `677d04e`) but left the single-pane case.

## Promote when

Any time; small.
