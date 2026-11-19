# backlog-042: Change a shortcut in Tono itself

| | |
|---|---|
| Status | done (commits `3717af4` and the one after it, 2026-11-18) |
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

## What was built

**Change Shortcut…** (`client.shortcuts.edit`, no key of its own, as in Rex for Mac) in the command palette. It
lists every action with its keys and where they come from (`default`, `configuration file (init.lua:12)`, `Tono`).
Choosing one opens a panel that records the next keys pressed and offers Set Shortcut, Remove Shortcut and Reset to
Default. What is changed is this app's alone: kept in the workspace under `shortcuts` (bindings and removals), sent
to the home server as one `overrides` layer named `tono` in `client.keymap`, and resolved again at once.

The storage and the editing are in `rex/src/Shortcuts.kt` (`ShortcutOverrides`: `set`, `remove`, `reset`,
`conflicts`, and `shortcutList`), with `RexClient.shortcutList`, `setShortcut`, `removeShortcut`, `resetShortcut`,
`shortcutConflicts` and the `shortcuts` flow over them. The settings window (backlog-028) can use those as they
are; `RexCommands.shortcutTargets` gives it the list of things a key can be bound to.

Checked by the worker, both apps, in a scratch session:

- In the web app, in headless Chromium: Change Session… moved to Ctrl+Shift+K through the command. Then, with text
  at a prompt and the cursor at its start, Ctrl+K cut the text (before the change it opened the session list), and
  Ctrl+Shift+K opened the session list. The change was there after a reload. Setting keys that Find… has asked
  first, changed nothing on Cancel, and moved them on Replace. Reset brought Ctrl+K back. The page cancelled each
  of the keys (`defaultPrevented`), including Ctrl+R once it was bound, which then ran its action instead of
  reloading. A layer edited by hand to remove a key nothing is bound to, and to bind an unknown action, put the
  server's two warnings in the debug log.
- In the desktop app, on a nested X server with real key presses: the same change, the same Ctrl+K and
  Ctrl+Shift+K, still there after the app was restarted, and Reset.

Decisions made while building, none of them asked for:

- Set Shortcut replaces: the action ends with that one shortcut, and its other keys come off (New Session's two
  default keys both go). Rex for Mac's own flow isn't described in its documentation beyond the command's name, so
  the panel is this app's design: one panel, keys recorded as soon as it opens, the choices listed beneath.
- A key that types (a letter, alone or with Shift; Enter, Tab, Space, Backspace) isn't recorded. Bare Enter, Escape
  and the up and down arrows work the panel.
- Taking a key from another action is that action's shortcut removed, by name. So the other action shows as
  changed in Tono and is reset on its own; resetting the first doesn't give the key back.
- Reset also takes off anything this app bound to the action's default keys, after asking, or the default would
  come back beneath it and never run.
- A removal's arguments are kept in the workspace beside it and not sent, so that Ctrl+1 (Go to Tab, `index` 1)
  can be reset without touching the other eight.
- A key the server named by position (`ctrl+[KeyK]`) is removed by that spelling, which is the only one that
  matches.
- With no server to ask (before it answers, or while it is down) the app resolves its defaults and its own layer
  itself, so a removed key is free from the first moment. The configuration file's bindings aren't known then.
- Only the server's warnings about the `tono` layer go to the debug log.

Not checked: Firefox and Safari. A configuration file with bindings of its own (the machine has none, and adding
one would change every client): a key taken from the file, a key bound there to a Lua function, and a file-and-line
source in the list are covered by unit tests only. Key sequences and modes can't be recorded. The pane menu and
tooltips weren't looked at after a change; they read the same keymap as the palette, which was.
