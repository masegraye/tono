# backlog-044: Switching tabs sometimes says the server refused the terminal stream (reason 2)

| | |
|---|---|
| Status | captured |
| Area | Connecting to servers |
| Size | unknown |
| Source | Mase, 2026-11-19, in the installed desktop app |

## What

When switching tabs, a message sometimes appears: "The server refused the terminal stream (reason 2)". It doesn't happen every time.

## Why

It is shown to the user, and it may mean a tab that doesn't show its terminal, or one that is opened twice, or a stream opened for a terminal that is already gone.

## Builds on

The message comes from `rex/src/RexTerminalStream.kt` (line ~38): the server answered the stream's opening with its reject byte and reason code 2. What code 2 means, and when a server sends it, is in the server's protocol (`rex api`, GET /llms.txt on the socket, the Rex docs). Switching tabs opens streams for the panes of the tab shown and may close those of the tab left (`RexHost`, `TerminalSession`). backlog-035 (keep recent terminals open) is related.

## Promote when

Now: a user-visible error.

## Not known yet

What reason 2 means; whether it's a race (a stream opened for a pane being closed or moved, or opened twice for the same block, or before an earlier one is closed); whether the app recovers by itself or the tab stays blank; whether it happens on remote hosts or only Local; whether other Rex clients do the same.
