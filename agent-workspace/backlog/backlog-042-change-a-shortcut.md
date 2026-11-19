# backlog-042: Change a shortcut in Tono itself

| | |
|---|---|
| Status | captured |
| Area | Typing and keys |
| Size | medium |
| Source | Mase, 2026-11-19: Ctrl+K opens the session switcher instead of killing the line |

## What

Rebind or unbind any of Tono's shortcuts from inside Tono, stored by this client and sent to the server as the `overrides` layer of `client.keymap`, above the Rex configuration file. Rex for Mac's **Change Shortcut…** (`client.shortcuts.edit`) is the model. The first use: Ctrl+K back to the shell (kill line), and Change Session… on Ctrl+Shift+K.

## Why

Tono's defaults take keys that shells and editors use (Ctrl+K is kill-line in readline). The Rex configuration file can change them, but it applies to every client of that server; other Rex clients let a person change a shortcut for that client alone.

## Builds on

`client.keymap`'s `overrides` field ("layers of shortcuts a person recorded in the client … above the configuration file and this client's scripts"); Tono declares its `defaults` in `rex/src/Keymap.kt` and `RexClient.declareKeymap`. The settings window (backlog-028) will show and edit the same overrides.

## Promote when

Now.
