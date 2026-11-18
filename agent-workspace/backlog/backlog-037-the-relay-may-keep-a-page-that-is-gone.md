# backlog-037: The web relay may keep a page's connection after the page is gone

| | |
|---|---|
| Status | captured |
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
