# backlog-036: Close the connections a silent server never answered

| | |
|---|---|
| Status | done (commits 1a7ab4f, 775fe55, 2026-11-18) |
| Area | Connecting to servers |
| Size | small |
| Source | found while fixing a host that stayed "connected" after its machine went away |

## What

When a server accepts a connection and then says nothing, close the attempt when its ten
seconds run out, not when the server next speaks.

## Why

Each attempt that times out this way leaves its socket open, and the host is tried again
every ten seconds or so. A server stuck like that for days would use up the app's open
files. It needs a server that takes connections and answers none, which a machine that is
merely off or asleep is not: that one refuses or ignores the connection, and nothing is
left behind.

## Builds on

Seen on 2026-11-18 with a server reached through a small relay that was then stopped
(`kill -STOP`): the app's open connections to it went from one to four over 55 seconds, one
more for each attempt, and all were closed within seconds of the relay carrying on. The
attempt is `client.rexWebSocket` inside `within(…)` in `rex/src/RexControl.kt`; cancelling
it does not close the socket that Ktor's CIO engine opened for it.

The connections of a server that was connected and then went silent are closed properly;
this is only about attempts.

## Promote when

Before Tono is left running for weeks against servers that may hang.

## Not known yet

Whether an engine setting closes them (a socket or request timeout), or the engine has to
be told to close the connection by hand. Whether the browser build has the same leak
through the page's relay.

## What was built

An attempt on a server that takes the connection and never answers is closed when its ten
seconds run out, on the desktop and in a browser. Built by a worker.

There were three leaks, not one:

- **The attempt itself.** Ktor's `webSocketSession` makes its request in a coroutine of the
  client's own, so cancelling the caller stops nothing. `openWebSocket` in
  `rex/src/OpenWebSocket.kt` is that function with the request cancelled when the wait is.
- **A server that makes the WebSocket and then never says its first word.** The socket was
  open by then and nothing held it, so the timeout on the welcome dropped it unclosed. The
  control connection and the terminal stream now drop the socket if their first exchange
  fails for any reason (`orDrop`).
- **The relay.** It waited on the server for as long as the server liked, whatever the page
  did. It now ends its attempt when the page closes its socket (`server/src/TonoRelay.kt`).
  The browser build did have the leak, in the second form: the relay takes the page's
  socket before it tries the server, so the page always got as far as waiting for a welcome.

No engine setting does it. CIO's request timeout is switched off for WebSockets, its connect
timeout ends once the server has accepted, and its socket timeout would also close a
connected terminal that had nothing to say.

Checked:

- `rex/test@jvm/SilentConnectionTest.kt`: a local TCP server that accepts and stays silent,
  or answers the upgrade and then stays silent, counting its open connections. Three attempts
  leave none open; a cancelled attempt leaves none. With the two closing lines taken out, all
  four tests fail with three left open (one for the cancelled attempt).
- `server/test/TonoRelayTest.kt`: the same server behind a real relay. Failed with three left
  open before the relay's change. Also that a message sent before the server answered still
  arrives, since the relay now waits in a different way.
- The web app in headless Chromium, served on a port of its own with `REX_SERVER` pointed at
  a silent server, for 55 seconds. Before: the relay's open connections to it went 1, 2, 3,
  4, 5, and with a server that never answers the upgrade they stayed open after the browser
  was closed. After: never more than one, and none once the browser was closed.

Decisions made while building, none of them asked for:

- `RexControl.connect` and `RexTerminalStream.open` take the timeout as a parameter, so the
  tests needn't wait ten seconds an attempt. Nothing else passes it.
- The relay has no timeout of its own. It follows the page, which has one.
- A first exchange that fails drops the socket without a close frame, as a connected server
  that goes silent is dropped.

Not checked: the desktop app or the web app against a real Rex server (the tests and the
relay's echo test cover an ordinary connection through the new code; no window was opened and
no real session was attached). TLS and Unix-socket servers that go silent. Firefox and Safari.
