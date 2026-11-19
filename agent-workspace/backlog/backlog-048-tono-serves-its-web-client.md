# backlog-048: Tono serves its own web client over the tailnet

| | |
|---|---|
| Status | captured |
| Area | The web app |
| Size | medium |
| Source | Mase, 2026-11-19 (big-idea-001) |

## What

An option in Tono (in Settings) that, when on, makes the desktop app host the web client itself, so it can be opened from another device over the tailnet. No separate `task web` or server install is needed. The web app isn't shipped on its own at first; this is how people reach it.

## Why

The web app works, and is handy from a phone or another machine, but today it needs a checkout and `task web:tailnet` to serve it.

## Builds on

`server/` (the Ktor server that serves the web build and relays connections: `TonoRelay.kt`, `task web:tailnet`), the packaged web build, and Tailscale (the server could listen on the tailnet address, or use `tailscale serve`). The relay's rules on which hosts it reaches stay as they are.

## Promote when

After the AUR package (031), so there is an installed app to add it to.

## Not known yet

Whether the desktop app runs the server in its own process or starts it beside itself; how it's secured beyond the tailnet (tailnet identity, a pairing token); which port and address; whether it should stop when the desktop app closes.
