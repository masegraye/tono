# backlog-041: In the web app, a host the relay refuses lingers at "Connecting…"

| | |
|---|---|
| Status | done (commit 1fec034, 2026-11-18) |
| Area | The sidebar |
| Size | small |
| Source | seen while building backlog-027; the relay ruled out while building backlog-037 |

## What

In the web app, a host whose address the relay refuses (such as one at `127.0.0.1`) stays at "Connecting…" for 30 seconds or more, while others at similar addresses turn red, and a host already shown red was later seen back at "Connecting…".

## Why

backlog-038 made "Connecting…" mean "the first attempt hasn't finished". A host that has been refused, or that has already failed, should not say it.

## Builds on

The relay refuses such an endpoint with close code 1008 in under 10 ms, and an unreachable tailnet address with 1013 after 5 s (checked in backlog-037), so the delay is on the page's side: how `RexHost` in `rex/src/RexHost.kt` treats a close during the first attempt, and how `reach` (backlog-038) is set when a host is recreated, for example on a host-list sync.

## Promote when

Any time; small.

## What was built

A refused host is now red within a second of appearing, and is tried again like any other host
that is down. Built by a worker.

The cause was not a timeout. The relay refuses an address by taking the socket and closing it
at once. In a browser, Ktor's socket answers a write after that close with a
`CancellationException`, though nobody cancelled anything. When the close arrived before the
page had written its hello, `RexControl.connect` threw that cancellation, and `RexHost`'s loop
passes cancellations on, as it must when the app is closing. So the loop ended for good: the
host was left in its first attempt, "Connecting…", never tried again and never marked as down,
until the page was loaded again. Whether the close or the hello came first was a race, which is
why some hosts at similar addresses turned red and others didn't. It is the same mistake the
connect timeout once made (`within` in `RexControl.kt`).

- `closedIsFailure` (`rex/src/RexControl.kt`) wraps the hello and welcome: a socket that closed
  under them is a `RexDisconnected`, with the reason the other end gave. So the host now shows
  the relay's own words ("Not a Rex server this relay will connect to") as why it is down. A
  cancellation of the caller itself is still passed on.
- `RexHost`'s loop treats any cancellation as a failure unless the host itself is being closed,
  so nothing else of this kind can end the trying.
- How a host's state moves between attempts is now two functions of `HostState`, `trying()`
  and `lost(reason)`, so that it can be tested. What they do is what the loop did before.
- The comment in `RexControl.kt` about the Rex server's "own nine minutes" now says five, as
  backlog-037 measured. Nothing in the code relied on the number.

Checked:

- `rex/test@jvm/RefusedHostTest.kt`: the state transitions (a host that failed is never
  "connecting" again through any number of attempts; one that was reached is "reconnecting"),
  `closedIsFailure`, and with real sockets a server that takes a WebSocket and closes it with
  1008, where the refusal is reported with its reason and a `RexHost` goes down, is tried again
  on `retry()` and by itself, and stays `Down` throughout.
- The web app in headless Chromium, served from the worktree on a port of its own, against a
  scratch Rex server whose host list had seven hosts at `http://127.0.0.1:777x` and one at an
  unreachable tailnet address. Before the change, three of the seven made one attempt, were
  never tried again and still said "Connecting…" 14 seconds after they appeared; this happened
  on the first try with eight hosts, and not with one. After it, all seven were red in a picture taken 0.8 seconds
  after they appeared and each was tried again at 0.5, 1, 2 and 5 seconds.
- `task test` passes.

Decisions made while building, none of them asked for:

- A host made anew starts at "Connecting…", as before. No way was found for one `RexHost` to go
  from red back to "Connecting…": `failed` is cleared only by connecting. The red host seen back
  at "Connecting…" was most likely a new one after the page loaded again (the dev server
  reloads pages on a rebuild), which then stuck as above. A host is also made anew when its
  label or address changes in the host list, and then nothing is known about the new address.

Not checked: the desktop app (it doesn't use the relay, and its sockets were not seen to cancel
a write; the loop's guard covers it all the same); a refusal seen in a real browser window.

Not built:

- `RexTerminalStream.open` has the same shape as the connect did, a write then a read on a
  socket just opened, and is not wrapped. A terminal's stream refused at once in a browser may
  hit the same cancellation.
- Listed hosts still appear together only when the slowest of them has answered the question
  "are you the home server", up to five seconds (`syncHostList` in `RexClient.kt`). With an
  unreachable host in the list, the refused ones appear five seconds late, then go red at once.
