# backlog-009: The bell, and progress

| | |
|---|---|
| Status | done (commits `7bc086c`, `9f4fbd7`, `502da4f`, 2026-11-18) |
| Area | Knowing what happened |
| Size | small |
| Source | inv-001 |

## What

Mark a tab whose program rang the bell while you weren't looking, and draw the progress a program reports (OSC 9;4).

## Why

Both are ways a program asks for attention or says how far along it is, and Tono shows neither.

## Builds on

The server's `bell` and `progress_report` terminal events.

## Promote when

With backlog-008.

## What was built

A tab in the sidebar shows a small bell when a program in it rang the bell while the tab was
out of sight, until the tab is next looked at, and a thin bar along the bottom of its row for
the progress a program reports with OSC 9;4. The status line says the progress of the tab on
screen in words (`50%`, `working`, `paused 30%`, `failed at 60%`), for when the sidebar is
hidden. The state is in `rex/src/BellAndProgress.kt`, the drawing in
`tono/src/BellAndProgress.kt`; `RexHost.kt` gained two event cases and a call where the tab
on screen changes. Built by a worker and checked by it:

- Against the Rex server here (API 12), without a window, with the probe
  (`task probe -- FILE --watch 30`, which now prints bells and progress): a bell in a tab out
  of sight marked it, a bell in the tab on screen with the app in front (`--front`) did not,
  and each OSC 9;4 state (1, 2, 3, 4, then 0) arrived and cleared.
- In the web app, in headless Chromium: the bell mark and the bar on a background tab, the
  error bar and `failed at 60%` for the tab on screen, the bell gone after clicking the tab,
  the moving piece for progress with no percentage, and both bars gone after `9;4;0`.
- The desktop's drawing only offscreen: `TabRowRenderTest` draws the rows with Skia and
  checks the bell and each bar by their pixels, leaving pictures in `build/tab-renders/`.

Decisions made while building, none of them asked for. Rex for Mac could not be looked at
from here, so these follow Rex Rune (`src/app/activity.rs`), the second reference:

- "Looked at" means the tab on screen, on the host showing, while the window has focus. A
  bell in the tab on screen while the window is elsewhere marks it, and coming back clears it.
- The bell is latched on the tab, not the pane, and goes with the tab when it closes.
- A tab's progress is that of the pane with the most to say: failed, then paused, then
  working, and one with a percentage before one without. A percentage over 100 counts as none.
- Colors: accent for working, the error color for failed, muted for paused (the theme has no
  warning color). Progress with no percentage is a short piece moving along the track, where
  Rex Rune draws a still, dimmed bar. Tono shows no words on the tab as Rex Rune does
  ("Bell", "Paused 40%"); a tab's row says them to a screen reader instead.
- "Show Only Active Tabs" keeps a tab with an unseen bell or with progress.
- A session or host folded away shows a bell on its heading if a tab under it has one. It
  shows no progress.
- The bell makes no sound and no notification (backlog-008).

Not checked, and not known:

- The desktop app in a real window. No window was opened; only the offscreen drawing above.
- What Rex for Mac draws. Firefox and Safari.
- The bell on a folded heading, and the "Show Only Active Tabs" filter, were not looked at
  on screen (the filter has a unit test).
- The server keeps neither a bell nor progress: neither is read back on connecting, so both
  are lost when Tono reconnects, until the program reports again.
- A program that dies without sending `9;4;0` leaves its bar until the pane closes. Ghostty
  drops such a report after a while; whether Rex's own apps do was not found out.
- In a narrow window showing the sidebar, the tab "on screen" still counts as looked at,
  though the terminal is hidden, so a bell there marks nothing.
- In the browser, the app goes on being told its window has focus after another browser tab
  comes in front (seen in backlog-001), so a bell then marks nothing either.

