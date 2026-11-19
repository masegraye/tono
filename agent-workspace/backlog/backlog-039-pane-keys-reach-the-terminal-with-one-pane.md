# backlog-039: Pane keys reach the terminal in a tab with one pane

| | |
|---|---|
| Status | done (commits `04e3c6c`, `97fc662`, 2026-11-18) |
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

## What was built

A key bound to one of Tono's actions is Tono's whether or not the action can run just now (`takesKey` in
`tono/src/RexActions.kt`). Before, a key whose action wasn't on offer fell through to the terminal. Built with
backlog-013.

Checked in the web app, in headless Chromium: in a scratch tab with one pane, Ctrl+Alt+arrows,
Ctrl+Alt+Shift+arrows, Alt+] and Alt+[ left the terminal exactly as it was, and typing still reached it.
`tono/test@jvm/RexShortcutsTest.kt` covers the rule.

Decisions made while building, none of them asked for:

- **The rule is general, not a change to these eight actions.** The `available` checks stay as they were, so the
  palette still doesn't offer Focus Pane Left in a tab with one pane. It also covers Alt+] and Alt+[, which leaked
  the same way and started an escape sequence in the shell.
- A key handed to the server (`client.key.forward`) and a key bound only to a server action that can't run still
  go to the terminal, as before.

Not checked: the desktop app in a real window. The leak itself was not reproduced on the old build first.
