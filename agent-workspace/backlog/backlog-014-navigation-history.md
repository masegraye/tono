# backlog-014: Back and forward

| | |
|---|---|
| Status | done (commits `0dfb3a8`, `b27b4a6`, `d6f5a67`, 2026-11-18) |
| Area | Panes, tabs and sessions |
| Size | small |
| Source | inv-001 |

## What

Go back to the tab you were on before, and forward again, across sessions and hosts. On the mouse's back and forward buttons.

## Why

Moving between two tabs far apart in the sidebar takes a search each time.

## Builds on

Rex Rune: `client.history.back` and `client.history.forward`.

## Promote when

With backlog-013.

## What was built

Tono remembers the tabs its window has shown and goes back and forward through them, across
sessions and hosts: Control+Alt+[ and Control+Alt+], the mouse's back and forward buttons,
and "Back in History" and "Forward in History" in the palette. The actions are
`client.history.back` and `client.history.forward`. The logic is `tono/src/NavigationHistory.kt`,
with its tests in `tono/test@jvm/NavigationHistoryTest.kt`.

What counts as a step, decided as Rex Rune does (its `src/app/navigation.rs` was read):

- A step is the tab on screen changing, however it came about: the sidebar, a key, the
  palette, a notification, a new tab opening, a tab closing under you and its neighbour
  showing. Moving between panes is not a step. Seeing the same tab twice running is one visit.
- A tab or session that has closed is dropped from the history, so going back passes over
  it, and two visits to one tab that this brings together become one. A place on a host that
  isn't connected is passed over and kept, for when the host is back; a removed host's go.
- Going anywhere new from the middle forgets what was ahead, as a browser does. That
  includes the tab that takes a closed tab's place. Going back or forward adds nothing.
- At either end the keys and buttons do nothing, and the keys still don't reach the terminal.
- Places passed through on the way are not steps: going to a tab on another host shows that
  host's last tab for a moment. A place asked for is waited for, up to 30 seconds.
- 128 places are remembered. The history is the window's own: no server is told, and it
  isn't kept between runs.
- The mouse buttons do nothing while the palette or a dialog is open, as in Rune.

Checked:

- Web app, headless Chromium, in scratch sessions: keys and both mouse buttons, both ends, a
  tab closed from the CLI passed over, a new tab forgetting what was ahead, the buttons with
  the palette open and with the pointer over the sidebar, steps across two sessions with no
  place between recorded, two presses at once, and a killed session leaving the history.
  After every step the page was still the app. A control showed the check means something:
  the same button press on a plain page took the browser back, and on the app without the
  change to `page-keys.js` it left the app.
- Desktop app, on a nested X server on a hidden workspace (see ops/subagents.md), with real
  key presses and X buttons 8 and 9: keys and buttons, both ends, and X buttons 6 and 7 (the
  wheel tilted sideways) going nowhere. Read from the server's active tab, since the hidden
  window draws late.
- `task test` passes.

Decisions made while building, none of them asked for:

- **The keys.** Rex for Mac has no back and forward (its client's action list was read), and
  Rune gives them no key, only the mouse buttons. Control+Alt+[ and ] were chosen: brackets
  already mean next and previous tab (with Control+Shift) and pane (with Alt), and a terminal
  has no use for them with Control+Alt. Alt+Left and Alt+Right, a browser's keys, move by a
  word in a shell and were left to it.
- **The titles** are Rune's, capitalised as Tono's are. They are listed under Tab.
- **On the desktop the buttons are read from the window toolkit**, not from Compose. Under
  X11 the side buttons are the toolkit's sixth and seventh, which Compose drops; its "Back"
  and "Forward" there are the wheel tilted left and right, which a touchpad sends when
  scrolling sideways. Both were seen. On other systems the fourth and fifth are taken, from
  reading the toolkit's code.
- **A host says when it is done showing what it was asked to** (`RexHost.moving`). Two
  presses in a row made the second tab show, then the first, then the second again, and the
  flicker was being recorded as a visit. The flicker itself is older than this and is still
  there, for digits too.

Not checked: two hosts with a live app (unit tests only); a host that disconnects and comes
back (unit tests only); a real mouse on a real focused window; Firefox and Safari, which may
act on a different event than Chromium (press, release and click are all cancelled); macOS
and Windows button numbers; what the desktop app's window looks like while doing any of this.

Not done: opening a new tab or session on another host records that host's last tab on the
way, since nothing names the place being gone to until the server has made it.
