# backlog-036: Close the connections a silent server never answered

| | |
|---|---|
| Status | captured |
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
