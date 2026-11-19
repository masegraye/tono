# backlog-054: Scrolling inside a program on another machine stutters

| | |
|---|---|
| Status | captured (being measured) |
| Area | Reading a terminal |
| Size | unknown |
| Source | Mase, 2026-11-19, on omranchy, after backlog-053 |

## What

On omranchy (Omarchy, the installed desktop app at `67a9aa4`), scrolling with the wheel inside a program that takes the mouse, on a terminal served by another machine (omwork's Rex server, over the tailnet), stutters badly:
- Pi, on the kotlin-test session;
- Neovim in a large buffer: "very stuttery initially, then it sort of catches up".

Rex for Mac, on the same network and the same server, is smooth. So the network alone doesn't explain it.

## Why

Scrolling a coding agent's output or an editor is what Tono is used for most.

## Builds on

backlog-053 fixed scrolling Tono's own view (layout reuse, draining queued commands, wheel step sizes) and added `rex/src/ScrollTelemetry.kt` (`TONO_SCROLL_TELEMETRY=<file>`). In a program that tracks the mouse, the wheel doesn't scroll the view: each notch becomes a mouse report.
1. On omranchy, Tono sends the report.
2. It crosses the tailnet to omwork's Rex server.
3. The program redraws, and the server streams the bytes back.
4. Tono decodes them (the stream, `RexTerminalStream`, `TerminalSession`), feeds libghostty-vt on the terminal thread, and draws a frame.

backlog-053 measured that round trip only against a local server (about 21 ms median).

## Promote when

Now.

## Not known yet

Where the time goes, and why it comes in bursts:
- reports queued behind other work on the control connection;
- one report per notch rather than coalesced;
- output arriving in bursts and applied all at once;
- the terminal thread or frame loop busy, decoding or feeding, while input waits;
- Nagle or a flush missing on the socket;
- something Rex for Mac does differently (its client is the reference).
