# backlog-013: Move a tab by key, close others, mute, rename a session

| | |
|---|---|
| Status | done (commits `04e3c6c`, `97fc662`, 2026-11-18) |
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

## What was built

A tab can be moved within its session from the keyboard, the session's other tabs closed, a tab muted on this
client, and a session renamed. All are in the command palette, under Rex.app's action names:

| Action | In the palette | Key |
|---|---|---|
| `client.tab.move.backward`, `client.tab.move.forward` | Move Tab Backward / Forward | Ctrl+Shift+PageUp / PageDown, as in Rex.app |
| `client.tab.move` (`direction`) | not listed, as it needs a direction | none |
| `client.tab.close_others` | Close Other Tabs | none, as in Rex.app |
| `client.tab.mute` | Mute Tab, with "muted" or "not muted" beside it | none |
| `session.rename` | Rename Session… | none |

Each acts on the tab or session on screen. The rules are in `rex/src/TabManagement.kt` and are Rex Rune's
(`src/app/tabs.rs`), since Rex.app's documentation gives names and keys and nothing about behaviour.

Checked in the web app, in headless Chromium against a scratch session, reading the result back from the server
each time: the two keys through a session of three tabs and at both ends, the two palette entries, muting and
unmuting (a muted tab's OSC 777 made no notification where another tab's did), the rename dialog (a name, Escape,
and an empty name), Close Other Tabs (Escape, then Enter), and that the page cancels Ctrl+Shift+PageUp and PageDown.
Checked in the desktop app, in a window on a hidden workspace that was never focused: it reads muted tabs from its
workspace file, forgets one whose tab isn't there, and forgets another when its tab is closed. Tests:
`rex/test@jvm/TabManagementTest.kt`, and additions to `tono/test@jvm/RexShortcutsTest.kt`, `NotificationsTest.kt`
and `TabRowRenderTest.kt`.

Decisions made while building, none of them asked for:

- **Close Other Tabs asks first.** Rex.app's documentation doesn't say whether it does, and it couldn't be tried.
  Rex Rune asks, and Tono already asks before closing one tab. The tabs that close are the ones there when the
  question was put: one opened meanwhile stays, and if the tab that was to stay has gone, nothing closes.
- **Mute is kept in the workspace file, and nowhere else**, as Rex Rune keeps it in its settings: under
  `muted_tabs`, each with its server's address, session and tab id, in Rune's shape. It survives a restart, is
  forgotten when the tab closes or its session ends, and is capped at 256. The server never hears of it, so other
  clients still hear from the tab. Unlike Rune, a host being removed doesn't drop its muted tabs: hosts from the
  home server's list arrive late at startup, and would look removed.
- **A muted tab keeps its activity dots and progress bar**, where Rune hides them. They make no noise, and the
  entry asked for sounds and notifications. Its bell mark is replaced by a struck-through bell, since a muted tab's
  bell isn't news. Muting a tab takes down the notifications it already has up.
- **`client.tab.move` takes `backward`/`forward`, `left`/`right` and `up`/`down`.** Rex.app doesn't document the
  values; Rune takes the first two pairs, and the third is how tabs run in a sidebar.
- The move keys are taken wherever there is a tab, and do nothing at the ends of the session.
- An empty session name goes back to the one the server made up (`session.set_label` with an empty label). A
  session always has a name, so the dialog starts with the current one and can't tell a given name from the server's.
- Rex.app's titles are kept, with `…` added to Rename Session as Tono's other rename actions have it.

Not checked: the desktop app with the keyboard (the keys, the dialogs and the muted mark in a real, focused
window; the key spelling and the mark are covered by tests that run on the JVM). A real Chrome tab: the page
cancels Ctrl+Shift+PageUp and PageDown, and Chrome is not known to keep them from a page as it keeps Ctrl+PageUp,
but a headless browser has no tab strip to move. Firefox and Safari. Sounds for a muted tab, by ear: they share the
one check with notifications (`announces` in `tono/src/Notifications.kt`), which is tested. Muted tabs on a host
other than this machine's.
