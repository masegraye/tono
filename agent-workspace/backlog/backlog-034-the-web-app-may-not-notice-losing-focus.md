# backlog-034: The web app may not notice that it lost focus

| | |
|---|---|
| Status | captured |
| Area | The web app |
| Size | small |
| Source | found by the worker on backlog-001 |

## What

Find out whether the web app goes on believing its window is focused after another page
comes in front of it, and if it does, ask the page itself, as copying already does.

## Why

Focus decides two things: which client owns a terminal's size, and whether a tab counts as
being watched (which keeps it quiet). If the web app misses losing focus, a page left open
behind another could go on claiming sizes and stay silent about activity.

## Builds on

While building backlog-001 a worker saw, in headless Chromium, that Compose's
`LocalWindowInfo.isWindowFocused` stayed true after another page came in front, although the
page's own `document.hasFocus()` was false and the browser's blur event had fired. `RexApp`
passes that value to `RexClient.setWindowFocused`. Copying was guarded on its own with
`pageHasFocus()` in `tono/src/TonoPlatform.kt`.

An earlier check in a real window found that a page loaded without focus did not claim a
terminal's size. That is a different case: never having had focus, not losing it.

## Promote when

Before relying on the web app beside another client. The first step is to reproduce it in a
real browser window, since a headless browser's idea of focus is not a real one's.

## Not known yet

Whether it happens outside a headless browser at all.
