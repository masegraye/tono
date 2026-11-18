# backlog-012: Resize, balance, swap, move and rename panes

| | |
|---|---|
| Status | captured |
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
