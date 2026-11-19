# backlog-012: Resize, balance, swap, move and rename panes

| | |
|---|---|
| Status | done (commits `a8b405d`, `62fdd85`, 2026-11-18) |
| Area | Panes, tabs and sessions |
| Size | medium |
| Source | inv-001 |

## What

Drag the line between two panes to resize them, or do it from the keyboard. Balance a tab's panes, swap two, move one to another tab, and rename one.

## Why

Tono can split, zoom, close and focus panes, and nothing else.

## Builds on

The server's `session.resize_pane`, `session.swap_blocks`, `session.move_block` and `session.set_block_label`. Rex's action names are `pane.balance` and its neighbours in the Mac action list.

## Promote when

After the clipboard and reading work, or sooner if split layouts are in daily use.

## What was built

The line between two panes can be dragged, with a mouse or a finger, and a tab's panes can be resized, balanced,
traded, moved to another tab or a new one, and named, from the command palette and by Rex.app's action names:

| Action | In the palette | Key |
|---|---|---|
| `pane.resize` (`direction`, optional `amount`) | Resize Pane Left / Right / Up / Down | none, as in Rex.app |
| `pane.balance` | Balance Splits | none |
| `pane.move.left` … `.down`, `pane.move` (`direction`) | Move Pane Left / Right / Up / Down | Ctrl+Alt+Shift+arrows (Rex.app: Alt+Shift+Cmd+arrows) |
| `pane.move_to_tab` (optional `window_id`) | Move Pane to Tab…, then a picker | none |
| `pane.move_to_new_tab` | Move Pane to New Tab | none |
| `pane.rename` | Rename Pane… | none |

The layout rules are in `rex/src/PaneLayout.kt` and are Rex Rune's, since the Mac's aren't published: a resize
moves the border on the pane's right or under it, else the one on its left or above it, by 5% of its split;
moving a pane swaps it with the neighbour sharing the most of that edge (`session.swap_blocks`); balancing gives
each thing in a row or column an equal share. Ratios are kept between 0.1 and 0.9, here, because the server takes
anything between 0 and 1. The server has no balance method, so balancing is one `session.resize_pane` per split
that is out of place.

Checked in the web app, in headless Chromium against a scratch session, reading the result back from the server
each time: dragging both kinds of line with the mouse, the stop at 90%, a drag by touch, each palette action, the
swap key, the rename dialog and the tab picker. `rex/test@jvm/PaneLayoutTest.kt` covers the layout rules, and
`tono/test@jvm/PaneDividerDragTest.kt` drags the lines with a simulated mouse in a scene drawn as the desktop
draws it.

Decisions made while building, none of them asked for:

- **Keyboard resizing has no default key.** Neither Rex.app nor Rex Rune gives it one, and the free combinations
  with arrows all belong either to the other pane actions or to programs in the terminal. It is in the palette
  four times, and `docs/using-tono.md` shows how to bind it. Giving it keys is one `argKeys` line in
  `tono/src/RexActions.kt`.
- **Move Pane to New Tab works by a detour.** The server can't make a window around an existing block (Rex Rune
  leaves the action out for that reason). Tono opens a tab with a shell, moves the pane in beside it, and closes
  the shell, which lives for a moment and has nothing typed into it. The pane's program is not restarted.
- A pane moved to a tab goes to the right of the pane focused there, and Tono shows that tab. A pane can't leave
  its session; the picker lists only the session's other tabs.
- A dragged line is drawn where it is dragged, without waiting for the server, and only the newest position is
  sent. Once let go it stays put until the server's layout agrees, or 0.7 seconds.
- Where a line ends against another, the one that runs on is the one taken hold of.
- A pane that was given a name shows it small in its top right corner, over the terminal. Nothing in Tono showed
  a pane's label before, so a rename would otherwise have been invisible. A new pane's label (`shell`) and the
  one the server makes when a name is cleared (`Block 89abcdef`) don't count as names.
- Resizing, moving and the two "move to" actions aren't offered while a pane is zoomed; balancing is.

Not checked: the desktop app in a real window (the resize pointer over a line, and a drag with a real mouse).
`hyprctl binds` has nothing on Ctrl+Alt+Shift, so the swap keys reach Tono (checked by the orchestrator).
Firefox and Safari. Tabs with floating panes: only the tiled layer's lines can be dragged. Key modes
(`client.mode.enter`), which is how Rex's documentation suggests resizing from the keyboard, are still
unsupported.

A mistake made while testing: the first browser script searched the palette for an action that wasn't on offer,
got a "Go to Tab" entry for a tab in another session instead, went there and typed a line into it. The script now
reads the highlighted entry and the status line off the screen before every step and stops if either is wrong.
