# backlog-038: A host that is still being connected to looks like one that is connected

| | |
|---|---|
| Status | done (commits `bd7287d`, `DOCS`, 2026-11-18) |
| Area | The sidebar |
| Size | small |
| Source | noticed while fixing a host that stayed "connected" after its machine went away |

## What

Give a host that hasn't answered yet a look of its own, so that it can't be mistaken for a
connected host with no sessions.

## Why

A host's picture turns red once an attempt to reach it has failed. Until the first attempt
ends, which takes five to ten seconds for a machine that is off, the heading looks exactly
like a connected host's with nothing under it. That is a wrong answer to "is it there?" for
those seconds, each time the app starts or the host list is read again.

## Builds on

`HostHeader` in `tono/src/RexApp.kt` keeps a `failed` flag that `Connecting` neither sets
nor clears, and draws the same picture for `Connecting` as for `Connected`.

## Promote when

Whenever the sidebar is next worked on.

## Not known yet

What it should look like: a dimmed picture, or one that pulses.

## What was built

A host's heading in the sidebar now shows which of four things its connection is. Built by a
worker.

| | Picture | Name | Under the name |
|---|---|---|---|
| Connected | quiet color | quiet color | "No sessions", if it has none |
| Being reached for the first time | dimmed, breathing between 25% and 70% of the quiet color, 1.4 seconds each way | dimmed | "Connecting…" |
| Was connected, being tried again | error color, steady | dimmed | "Reconnecting…" |
| Never answered, or its address can't be read | error color, steady | dimmed | nothing, as before |

- `HostState` (in `rex`) gained `failed` and `reached`, and a `reach` made from them
  (`HostReach`: `Connected`, `Connecting`, `Reconnecting`, `Down`). `HostConnection` alone
  couldn't tell a first attempt from a later one. This replaces the `failed` flag `HostHeader`
  kept for itself, which was lost whenever the heading left the screen.
- The same words (`connecting`, `reconnecting`, `not connected`) are the heading's state for a
  screen reader and the host's status in the palette's list of hosts, which used to say
  "connecting" for every later try too.
- Checked in an offscreen render (`tono/test@jvm/HostHeaderRenderTest.kt`, pictures in
  `build/host-renders/`), in unit tests of `reach` (`rex/test@jvm/HostReachTest.kt`), and in the
  web app in headless Chromium: a host at a tailnet address that never answers was dimmed,
  pulsing and "Connecting…" for about ten seconds and then red; with the page's relay stopped,
  the local host, which had been connected, went red with "Reconnecting…".

Decisions made while building, none of them asked for:

- **Where the look comes from.** Rex for Mac's documentation and changelog say nothing about
  how a host being connected to looks in its sidebar (only that a failure has an explanation
  and a Retry button). Rex Rune dims a host that isn't connected and writes "Waiting for
  connection…" under it where its sessions would be, for a first attempt and a later one
  alike. Tono takes the dimming and the line of words from it, in the place "No sessions"
  goes, and adds the slow pulse, so that the first attempt reads as something going on.
- **"Reconnecting…" is Rex's word**, on the Mac's terminals and in Rex Rune's toolbar.
- **A host that is down keeps a steady picture**, as was decided before: only the first
  attempt pulses. A lost host says "Reconnecting…" for as long as it is tried again, which
  is for as long as the app runs.
- **A host that never answered still says nothing under its name.** A machine that is
  switched off shouldn't take a line of the list.

Not checked: the desktop app in a real window (the offscreen render draws it as the desktop
does), Firefox and Safari, and what a screen reader says.
