# backlog-054: Scrolling inside a program on another machine stutters

| | |
|---|---|
| Status | done (commits `313dab0`, `f44fd24`, `COMMIT3`, 2026-11-19); the real link between omranchy and omwork is still to be recorded |
| Area | Reading a terminal |
| Size | medium |
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

## What was found

Measured on omwork on 2026-11-19, against a scratch Rex server reached three ways: its socket directly, and
through a link made slow on purpose (`tools/scroll/proxy.py`) at 5 ms ± 1 each way, and at 30 ms ± 10 each way with
and without TLS. Two drivers: the wheel turned with no window (`task probe -- … --scroll`, where a frame counts as
drawn at the next tick of a 60 a second screen), and the desktop app on a nested X server (`:84`, 2376×1300,
software rendering, a terminal of 156×45) with `xdotool` wheel clicks. Programs: Neovim with `mouse=a` on a file
of 50,000 lines, `less --mouse`, and `tools/scroll/tui.py`, which redraws its whole screen in color for every
wheel report as an agent's interface does.

### The cause: one report to a round trip

A turn of the wheel that a program takes becomes a mouse report, sent on the **control connection** as a call of
the terminal's `write` method. The server runs that method as it comes, so two calls may land in either order,
and its documentation says to wait for one answer before sending the next. Tono did: **each report waited for the
server's answer to the one before.** Reports left one to a round trip, however fast the wheel turned, and
queued in the app meanwhile.

- Against a local server a round trip is about 1 ms, so nothing queued. backlog-053 measured there.
- At 5 ms each way (a round trip of 13 ms) a wheel flicked at 30 notches in a quarter of a second queued up to 11
  reports, and scrolling went on for 135 to 150 ms after the wheel stopped.
- At 30 ms each way (a round trip of 62 ms) it queued up to 26, and went on for **1.6 to 1.7 seconds** after the
  wheel stopped. Notches 40 ms apart, an ordinary steady scroll, already fell behind: 0.5 s. A frame came every 50
  to 83 ms where the wheel turned every 8 to 40.

That is "very stuttery initially, then it sort of catches up": frames at the pace of round trips, and a tail.

A drag over a program that takes the mouse, and text from a screen keyboard, went the same way.

### What was not the cause

Each of these was measured, with the link's own log beside the app's (`--link`):

| Suspect | What the numbers say |
|---|---|
| Nagle, or a flush missing on the socket | No. A report was on the wire 0.2 to 0.5 ms after it was handed to the socket (p50 to p90; with TLS 0.5 to 1.1 ms), one WebSocket frame of about 260 bytes each. |
| TLS (Ktor's CIO) buffering | No. The same queueing with and without it; TLS adds about 0.4 ms each way. Checked against a self-signed certificate on the slow link, not against Tailscale's. |
| Reports queued behind other traffic on the control connection | No other request waited or ran while the wheel turned. They queued behind each other. |
| Output applied late or in batches | No. A chunk was read off its socket 0.3 to 0.6 ms after it reached the machine, waited 0.1 ms (most 1.2) for the terminals' thread, and was taken in in 0.1 to 0.2 ms (most 3). The server's chunks arrive as it sent them, one WebSocket frame each, up to 30 kB. |
| The terminals' thread or the drawing thread blocked | No stall over 25 ms, but for one of 28 ms in drawing at startup. With software rendering the drawing thread is busy 9 ms of every 16. |
| Frames not asked for | A frame is read when output lands. But the terminals' thread slept 4 ms after every frame, and a turn of the wheel that came in that time waited for it: up to 10 ms before its report was even asked to be sent. Fixed, below. |
| Synchronized updates (mode 2026) | Neovim brackets every redraw with them. In these runs each redraw arrived whole, in one chunk, so no frame was read in the middle of one. But libghostty-vt doesn't hold a frame for them (checked: a frame read between the begin and the end shows the screen half drawn), so a redraw that arrives in two pieces would have shown. Fixed, below; this is backlog-019. |

### Rex Rune and Rex for Mac

No Rex client that draws a terminal runs on this machine (Rex Rune isn't installed, and the `rex` command has
nothing that attaches to a terminal and draws it), so there is no baseline measured beside Tono's. Rex Rune's
source (github.com/shuv1337/rex-rune, `main` on 2026-11-19, read and not run):

- it sets `TCP_NODELAY` on its sockets (`src/transport.rs`);
- it sends the wheel as `block.event` with type `wheel`, a method the server runs in order, one event a line and
  no more than 8 a turn (`src/app/term.rs`, `send_wheel`);
- its connection task waits for each answer too (`src/connection.rs`, `request(...).await?`), so as read it also
  sends one event to a round trip;
- it holds a frame during a synchronized update for up to a second (`src/terminal.rs`, `render_held`).

How Rex for Mac sends input isn't known: its source isn't available. The server's own description of its API
(`GET /llms.txt`, "Order of Requests") is what the fix rests on.

## What was built

- **Typed input no longer waits for the answer to the one before** (`RexHost.type`). Mouse reports, the keys a
  wheel becomes in a pager, and a screen keyboard's text are sent as `client.action` with the action
  `com.superlogical.terminal.write`, which the server runs one at a time in the order they arrive, among the keys
  typed before and after. A server that refuses that is sent it the old way from then on, what it refused included.
- **What is asked of a terminal is done as it comes.** The terminals' thread no longer sleeps after a frame. Frames
  are still read no closer than 4 ms apart, which is what the sleep was for.
- **A program in the middle of a synchronized update is not shown half done** (backlog-019). The last finished
  frame stays up until the update ends, or for a second, as in Ghostty and Rex Rune.
- **The telemetry** (`TONO_SCROLL_TELEMETRY=<file>`, now JSON Lines) follows each turn of the wheel through every
  stop, and `tools/scroll/analyze.py` and `graph.py` read it. `proxy.py` is the slow link and its log, `tui.py`
  the test program, `task probe -- … --scroll` the wheel with no window. `docs/development.md`, "Measuring
  scrolling".

### Before and after

From the wheel to the program's answer drawn, in milliseconds. The desktop app, nested display, 90 notches:
10 at 200 ms apart, 20 at 143, and two flicks of 30 at 11.

| Link, program | | Before: median / p90 / worst | After |
|---|---|---|---|
| Direct, tui | all turns | 21 / 24 / 36 | 22 / 24 / 36 |
| 5 ms each way, Neovim | all turns | 52 / 117 / 135 | 33 / 82 / 98 |
| | went on after a flick stopped | 132 to 134 | 19 to 20 |
| 30 ms ± 10 each way, tui | all turns | 452 / 1418 / 1662 | 82 / 86 / 98 |
| | a flick: median / worst | 851 / 1661 | 82 / 98 |
| | went on after a flick stopped | 1661 | 84 to 86 |
| | waiting for the control connection, p90 / worst | 1338 / 1576 | 0.1 / 0.2 |
| | answers over 17 ms later than the wheel's own rhythm | 61 of 86 | 1 of 86 |
| 30 ms ± 10, Neovim | all turns | 465 / 1431 / 1671 | 100 / 164 / 211 |
| | went on after a flick stopped | 1667 to 1671 | 69 to 84 |
| 30 ms ± 10 with TLS, tui | all turns | 480 / 1473 / 1720 | 81 / 88 / 102 |
| | went on after a flick stopped | 1717 to 1720 | 73 to 85 |

After, what is left is the link: two crossings and the server and program between them come to 62 ms at the
median on the slow link, and Tono's own share is 13 to 14 ms (p90 19 to 21), of which 9 is waiting for the
window's next frame. Neovim's larger numbers in a flick are its own: it draws once for several reports (26 of 90
were answered by a redraw made for a later one), and took 20 ms at the median, and up to 122, to answer.

With no window (60 notches: 10 at 100 ms, 20 at 40, 30 at 8), the same, for every program and link:

| | Before: median / p90 / worst | After |
|---|---|---|
| Direct, tui | 16 / 24 / 49 | 13 / 21 / 32 |
| Direct, Neovim | 22 / 52 / 69 | 13 / 21 / 32 |
| 5 ms, tui | 31 / 112 / 138 | 26 / 31 / 49 |
| 5 ms, Neovim | 31 / 124 / 150 | 26 / 32 / 49 |
| 30 ms ± 10, tui | 376 / 1282 / 1634 | 77 / 91 / 99 |
| 30 ms ± 10, Neovim | 358 / 1292 / 1591 | 89 / 156 / 182 |
| 30 ms ± 10, `less --mouse` | 369 / 1330 / 1665 | 93 / 144 / 169 |
| 30 ms ± 10, tui with synchronized updates | 410 / 1301 / 1653 | 78 / 86 / 100 |
| 30 ms ± 10 with TLS, tui | 422 / 1334 / 1669 | 78 / 90 / 99 |
| 30 ms ± 10 with TLS, Neovim | 401 / 1342 / 1657 | 93 / 161 / 179 |

The recordings, their analyses and their graphs are in `/tmp/scroll-e2e/` on omwork: `desk-before-30-nvim.html`
beside `desk-after-30-nvim.html` shows it best, and `desk-*` and `before-*`, `after-*` are the rest
(`.jsonl`, `.txt`, `.html`), with the links' logs (`link5.jsonl`, `link30.jsonl`, `linktls.jsonl`).

## To record the real case

On omranchy, with this branch installed:

```sh
TONO_SCROLL_TELEMETRY=/tmp/tono-scroll.jsonl tono
# scroll in Neovim and in Pi on omwork for a few seconds each, then, from the repository:
tools/scroll/analyze.py /tmp/tono-scroll.jsonl
tools/scroll/graph.py /tmp/tono-scroll.jsonl        # writes /tmp/tono-scroll.html
```

The file is written a few times a second, so the app needn't be closed first. What to read: "its turn on the
control connection" should be under a millisecond, "request written -> the server's answer to it" is the round
trip to omwork, and "its output read off the socket" is the link, the server and the program together. If
scrolling still stutters, the lines after those say whether it is in Tono (the terminal's thread, frames read in
the middle of a synchronized update, the window's turn, drawing, stalls), and `TONO_SCROLL_JFR=/tmp/tono.jfr`
beside the first variable records what the threads were doing.

## Decisions made while building, none of them asked for

- Input goes as `client.action` and not as `block.event` with the server's own `wheel` and `mouse` events, which
  is what Rex Rune sends. Tono's terminal works the report out itself, pixels and modifiers included, and this
  keeps that: only the way it is carried changed.
- Reports are not merged. Nothing waits now, so each leaves as the wheel turns, and the program sees the wheel's
  own rhythm.
- A paste still waits for its answer, as before.
- The telemetry's lines changed from words to JSON, and a file gets the lines of a run added after the last
  run's. backlog-053's `summary` line is still there, as one of them.
- With the telemetry on, each chunk of a terminal's stream passes through one more coroutine, which notes when it
  came off the socket. Off, the stream is read as before.

## Not checked, and left

- **The real link.** Nothing here ran between omranchy and omwork, or over Tailscale. The proxy's delays are
  even and its TLS is Python's; a tailnet that relays, drops or reorders wasn't made. If the recording there
  shows time that isn't a queue, it is new.
- **Pi.** Not run. `tui.py` stands in for it. Pi's redraws are larger than Neovim's, so they are the likelier to
  arrive in pieces, which is where holding a synchronized update shows, if Pi brackets its redraws.
- **A redraw arriving in pieces, on a real link.** The hold is tested (`SynchronizedUpdateTest`) and the telemetry
  counts frames read in the middle of an update, but no run here produced one.
- **A server that refuses `client.action` for typing.** The way back is tested against a stand-in
  (`TypingOrderTest`), not against an old server.
- **When a frame reaches the screen.** The telemetry ends at the frame drawn. Skiko's swap and the screen's
  refresh after it aren't timed, and the desktop runs used software rendering on a display nobody sees.
- **The web app.** It builds. Its scrolling wasn't measured again; the same code sends its input.
- **Rex for Mac and Rex Rune**, running. Neither was measured.
- `proxy.py --tls-upstream`, to put the link's log beside a recording of a real https host, was not tried.
