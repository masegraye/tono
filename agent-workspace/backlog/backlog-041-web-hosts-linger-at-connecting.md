# backlog-041: In the web app, a host the relay refuses lingers at "Connecting…"

| | |
|---|---|
| Status | captured |
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
