# backlog-037: The web relay may keep a page's connection after the page is gone

| | |
|---|---|
| Status | done (commits 0974bf2, ddb0977, 2026-11-18) |
| Area | The web app |
| Size | small |
| Source | thought of while fixing a host that stayed "connected" after its machine went away; not reproduced |

## What

Find out whether the server behind the web app goes on holding a page's connection to a Rex
server after the page has vanished without closing it (a phone that sleeps, a laptop that
leaves the network), and if it does, have the relay check that its pages are still there.

## Why

To the Rex server, the relay's connection is the client. If it outlives the page, a client
nobody is using stays listed, and could go on owning the size of a terminal it was last
typing in.

## Builds on

`server/src/TonoRelay.kt` installs Ktor's WebSockets with no ping period, so nothing asks a
page whether it is still there. The Rex server pings its clients every two minutes and
drops one after three unanswered, but the relay's client answers those itself, page or no
page. The app now notices a silent server from its side (`awaitSilence` in
`rex/src/RexControl.kt`); this is the same thing in the other direction.

## Promote when

Before relying on the web app from a phone beside another client.

## Not known yet

Whether it happens: this comes from reading the code, not from trying it. A browser may
close a page's sockets when it suspends the page, which would make it rarer.

## What was built

The relay asks each page whether it is still there, and lets go of the Rex server for one
that doesn't answer. A page that is gone is noticed within a minute. Built by a worker.

It did happen. A real page in headless Chromium, frozen with `SIGSTOP` (it answers nothing
and closes nothing, as a sleeping phone does), behind the relay as it was: its client was
still listed as connected eight minutes later, well past the server's own five, and went
only when the relay was stopped.

- **The question** is a WebSocket ping every 20 seconds, with 40 seconds to answer
  (`PAGE_ASK_MILLIS`, `PAGE_PATIENCE_MILLIS` in `server/src/TonoRelay.kt`). A browser answers
  pings itself, without the page's script, so a page that is idle or in a background tab
  still answers. The times are set on each socket, not where Ktor's plugin is installed.
- **How the server's side ends** now depends on how the page's did. A page that says goodbye
  has its close code passed on, as before. One that stopped answering, or whose connection
  broke or ended with no close message, has the relay's connection dropped with nothing
  said. Before, those were closed politely (code 1001, or 1000).

What the Rex server does, measured against the local server (0.1.0-preview.21) with small
clients made for it, since the documentation doesn't say and the changelog only that it
"waits much longer" than the 60 seconds it began with:

- A quiet client gets its first ping after two minutes, then one a minute, and is closed
  with 1001 "keepalive timeout" at five minutes, three unanswered. (The "nine minutes" in
  `rex/src/RexControl.kt` and "every two minutes" above are not what was seen.)
- A client that closes with a close message, whatever its code (1000, 1001 and 1011 tried),
  is forgotten at once. An attached client whose connection just ends is listed as `lost`
  for 60 seconds and then forgotten; in that minute it can come back with its reconnect
  token as the client it was, and the changelog says another client takes over a terminal's
  size at once. A client attached to nothing is forgotten at once either way.

Decisions made while building, none of them asked for:

- **A minute, not the server's five.** Being wrong costs little: a page cut off while it is
  still there finds its socket closed and connects again with its token. A ping queues
  behind terminal output, so a page on a very slow link in the middle of a flood could be
  cut; 40 seconds of patience is for that.
- **Dropped, not closed**, so the server sees through the relay what it would see from the
  desktop app on a machine that went to sleep: a lost client that may come back, not one
  that left. A phone that wakes inside the server's minute carries on as the same client.
- Dropping means cancelling the relay's request. Cancelling or closing Ktor's socket always
  sends a close message first.
- The other direction is as it was: a page is always told its server went, with the
  server's code if it gave one, and 1001 if not.

Checked:

- `server/test/TonoRelayGonePageTest.kt`, with a real relay asking every 300 ms and a stand-in
  server that notes whether each connection was closed or dropped. A page that stops
  reading and answering loses the server's connection, idle or with the server sending
  without pause; with the ping switched off both fail, the connection still open after five
  seconds. A page that is there and silent for three seconds (three times what a gone page
  gets) keeps it, and its goodbye is passed on as 1000. A reset connection, and one ended
  with no close message, are dropped.
- The web app in headless Chromium, served from a port of its own, attached to a scratch
  session on the real server. Idle for 106 seconds: its two sockets to the local server
  stayed open, none closed. Frozen: the client was `lost` 55 to 60 seconds later and gone
  from the list 60 seconds after that.
- A host the relay refuses (`http://127.0.0.1:…`) is closed with 1008 within milliseconds,
  so a refused host showing "Connecting…" for 30 seconds is not the relay waiting.

Not checked: a real phone or laptop going to sleep, and any browser but Chromium (that
Safari and Firefox answer pings from a suspended page is taken from how browsers work, not
seen). A network that drops packets, which was stood in for by a page that stops reading.
Whether a page that comes back inside the minute does carry on as the same client. A slow
link under heavy output. The desktop app, which the relay has no part in.
