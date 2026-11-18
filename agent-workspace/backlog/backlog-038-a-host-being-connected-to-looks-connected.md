# backlog-038: A host that is still being connected to looks like one that is connected

| | |
|---|---|
| Status | captured |
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
