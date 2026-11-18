# backlog-016: Floating panes

| | |
|---|---|
| Status | captured |
| Area | Panes, tabs and sessions |
| Size | large |
| Source | earlier sessions |

## What

Show a session's floating panes, and float, move and minimise them.

## Why

Rex has them and the Mac app's tab corner has a button for them. Tono has no way to make or manage one.

## Builds on

The server's layers: `session.resize_layer`, `session.set_layer_state`, `session.close_layer`. `rex/src/SessionView.kt` already reads whether a layer is floating.

## Promote when

When a floating pane is actually used from another client and Tono needs to show it.

## Not known yet

What Tono does today with a tab that has a floating pane. Not checked.
