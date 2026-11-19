# backlog-008: Desktop notifications

| | |
|---|---|
| Status | done (commits `be372a9`, `ec41752`, 2026-11-18) |
| Area | Knowing what happened |
| Size | medium |
| Source | inv-001 |

## What

A notification when a tab you aren't looking at needs you, fails or finishes, and when a program asks for one itself (OSC 9, OSC 777).

## Why

Tono has sounds only, which say that something happened and not what or where.

## Builds on

The server's `desktop_notification` terminal event (with `title` and `body`), and the activity news Tono already gathers for sounds (`RexClient.activityNews`). Rex Rune replaces the last notification for the same tab rather than stacking them, and has a switch per kind.

## Promote when

After the clipboard work.

## Not known yet

On the web this is the browser's notification permission, which the user has to grant.

## What was built

A notification for a tab that isn't in front of the user, on the desktop and in a browser:
when its program starts waiting, fails or finishes (the same news the sounds are made from,
now `RexClient.tabNews`), and when a program asks for one with OSC 9 or OSC 777 (the
server's `desktop_notification` event). Built by a worker.

- A tab has one notification for what its program is doing and one for what its program
  said. The next of either kind takes the place of the last.
- Going to a tab takes its notifications down. Clicking one goes to its tab.
- Switches: all of them, waiting, failed, finished, and what programs ask for. They are in
  the workspace under `notifications`, with Rex Rune's names, and are always written out so
  the file shows them. Two are also in the palette, under Rex for Mac's action names: Show
  System Notifications and Terminal Notifications.
- On the desktop they are shown with `notify-send`, one waiting on each notification that
  is up, and closed with `gdbus` or `busctl`. In a browser, with its Notification API.
- A browser is asked for permission only after a tab has had news: a line appears at the
  bottom of the window with Allow Notifications and No Thanks. The palette command asks too.
- `announces()` in `tono/src/Notifications.kt` is the one place that decides whether a
  tab's news is announced at all, for sounds and notifications both. It lets everything
  through; backlog-013's mute goes there.

What was checked, and how:

- `task test`: the deciding logic (switches, wording, what replaces what), and the desktop
  path against a notification service of the tests' own on a private D-Bus session:
  replacing, a click, closing, a dismissal, no service at all. Nothing is shown on the real
  desktop by the tests. They pass without checking anything where `notify-send`, `gdbus`,
  `dbus-daemon` or Python's GObject bindings are missing.
- The web app in headless Chromium, with a stand-in for `Notification`: no question until
  there was news, the question line and the palette command, OSC 9 and OSC 777, the three
  states, nothing for the tab in front, a click going to the tab, notifications taken down
  when their tab is gone to, and Terminal Notifications off.
- The desktop app once, on a hidden workspace and never focused, with the bus watched:
  Omarchy's notification service (quickshell, not mako) took an OSC 9 message, replaced it
  under the same id for an OSC 777 one, and took a "Done". The app left no `notify-send`
  behind when it ended.

Decisions made while building, none of them asked for:

- Rex Rune lets what programs ask for pile up. Here a tab's newest message replaces its
  last, as the brief said for all kinds.
- Waiting and failed are shown as critical, as in Rex Rune, so they stay until dismissed.
- "No Thanks" on the browser's question line turns Show System Notifications off.
- Turning notifications on shows one, as turning sounds on plays one.
- A page now counts as in front only while it has the browser's focus, not just the
  window's. This also decides bells and which client claims a terminal's size, in a browser.
- News from a terminal whose tab isn't known makes a sound and no notification.
- What a program wrote is stripped of control characters, cut at 120 and 600 characters,
  and escaped, since notification services read the text as markup.
- Clicking raises the window through the toolkit only. Hyprland follows that where
  `misc:focus_on_activate` is on, as it is in Omarchy.

Not built: the status text a program gives with its state (Rex Rune shows it in the body),
a "working" switch, a test button, and health of the notification service.

Not checked: clicking a real notification on the desktop, and whether the window then comes
forward (it needs the pointer). Taking a real notification down by going to its tab in the
desktop app. A real browser's own notifications and permission prompt: Chromium was
headless with a stand-in, and Firefox and Safari were not run at all. A phone's browser,
which shows notifications only from a service worker: there showing one is expected to
fail and leave a line in the debug log, and that was not tried.
