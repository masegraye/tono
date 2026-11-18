# backlog-013: Move a tab by key, close others, mute, rename a session

| | |
|---|---|
| Status | captured |
| Area | Panes, tabs and sessions |
| Size | small |
| Source | inv-001 |

## What

Ctrl+Shift+PageUp and PageDown move the tab in its session. Close every other tab. Mute a tab's sounds and notifications on this client. Rename a session.

## Why

Tabs can be reordered only by dragging, and a session can't be renamed from Tono at all.

## Builds on

`session.move_window` (already used for dragging), `session.set_label`. Rex Rune: `client.tab.move`, `client.tab.close_others`, `client.tab.mute`, `session.rename`.

## Promote when

With backlog-012.
