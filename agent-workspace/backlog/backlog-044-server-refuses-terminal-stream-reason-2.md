# backlog-044: Switching tabs sometimes says the server refused the terminal stream (reason 2)

| | |
|---|---|
| Status | done (commit `60bd745`, 2026-11-19) |
| Area | Connecting to servers |
| Size | unknown |
| Source | Mase, 2026-11-19, in the installed desktop app |

## What

When switching tabs, a message sometimes appears: "The server refused the terminal stream (reason 2)". It doesn't happen every time. The tab is then blank except for that message. Sometimes it stays wedged: switching away and back doesn't bring the terminal back, and only relaunching Tono does. So there are two problems: the refusal, and Tono never trying again.

Added by Mase the same day: when it happens the tab is blank with only that message. Sometimes it stays that way: switching away and back doesn't bring the terminal back, and only starting Tono again does.

## Why

It is shown to the user, and it may mean a tab that doesn't show its terminal, or one that is opened twice, or a stream opened for a terminal that is already gone.

## Builds on

The message comes from `rex/src/RexTerminalStream.kt` (line ~38): the server answered the stream's opening with its reject byte and reason code 2. What code 2 means, and when a server sends it, is in the server's protocol (`rex api`, GET /llms.txt on the socket, the Rex docs). Switching tabs opens streams for the panes of the tab shown and may close those of the tab left (`RexHost`, `TerminalSession`). backlog-035 (keep recent terminals open) is related.

## Promote when

Now: a user-visible error.

## Not known yet

What reason 2 means; whether it's a race (a stream opened for a pane being closed or moved, or opened twice for the same block, or before an earlier one is closed); whether the app recovers by itself or the tab stays blank; whether it happens on remote hosts or only Local; whether other Rex clients do the same.

## What was found

**What the reasons mean.** From the server's own description (GET /llms.txt on its socket, "Handshake Messages"; `rex api describe data.connect` mentions 1 and 7):

| Reason | Meaning |
|---|---|
| 1 | The ticket or token is unknown, expired, already used or someone else's, or its session no longer exists. |
| 2 | This client already has a connection to the same data source of that block, and may have only one at a time. |
| 3 | The stream isn't available, such as when its block has closed. |
| 4 | Opening the connection would pass a limit; try again later. (This server doesn't send it yet.) |
| 5 | The handshake was malformed or late. |
| 6 | The server failed. |
| 7 | The ticket was issued for a different subprotocol. |

So reason 2 says Tono itself still had a stream of that terminal open. The server was right; this is not a server bug.

**What caused it.** `RexHost.showView` opened and closed terminals inside `mutableState.update { … }`. A session's layout reaches `showView` from three coroutines at once: the session's events (`session_view_changed`, which the server sends after every tab switch, since a switch makes the tab the session's active window), the queue of what the user asked for (`showTab`, `selectSession`), and the first reading of every session. The state is also changed from the UI thread (`setShown`, `look`). `update` runs its block again whenever another change got in first, so on the desktop, where these run on several threads, a terminal was sometimes opened twice. One of the two went into the state. The other was referred to by nothing and never closed, and it held its stream for as long as the app ran. Which of the two the server welcomed was chance:

- the lost one was refused: nothing to see, and nothing left over;
- the one in the state was refused: the tab showed the message, and the lost one held the stream from then on. Going away and back made a new terminal, which was refused again. That is the tab that only a restart brought back.

The candidates in the entry that turned out not to matter, measured against the Local server with a plain client: opening a new stream right after closing the old one without waiting for the close (0 refused of 200), and right after dropping the old socket with no close (0 of 50). The server takes a stream down fast enough.

**Reproduced** against the Local server, in a scratch session with two tabs, with a `RexHost` not in front (so it claimed and resized nothing) calling `showTab` back and forth 400 times, 5 ms apart: 68 streams refused with reason 2, and afterwards one tab's terminal was refused every time it was shown, at 1.5 s between switches. With the fix, the same run at 0, 5 and 30 ms: none refused, both tabs live.

Only the desktop app can do this. A browser runs the web app on one thread, where an update is never run twice. Local or remote makes no difference. Other Rex clients weren't looked at.

## What was built

- `RexHost.matchTerminals`: the open terminals are made to match the tab on screen under a lock, one caller at a time, and outside any update of the state. The update in `showView` now only computes. The two other places that close every terminal (the session shown was destroyed; the connection was lost) take the same lock.
- `RexStreamRefused` (in `RexTerminalStream.kt`) carries the reason, and its message says what the reason means: "The server refused the terminal stream (reason 2: this client already has it open)".
- `TerminalSession` asks again for a stream refused for a reason that passes (2 and 4), each time with a new ticket, after 50, 100, 200, 400, 800, 1600, 3000 ms and then every 5 s, for as long as the terminal is on screen. The first five refusals, about a second and a half, show "Restoring…" as before. After that the pane shows the message with "Trying again…" (`Status.Retrying`), and goes back to "Restoring…" when the stream opens. Leaving the tab closes the terminal and ends the asking.
- A terminal whose stream ended for any other reason is replaced with a new one when its tab is shown again. That was already so; there is now a test for it.
- `rex/test@jvm/TerminalStreamsTest.kt`, against a stand-in server (Ktor, real sockets) that keeps the one-stream rule: 400 quick switches open no terminal twice and leave no stream behind (fails without the fix, three runs of three); a stream refused three times opens by itself; one refused seven times says so and then opens; one that failed for good opens afresh when its tab is shown again. The `rex` module's JVM tests now depend on `ktor-server-cio` and `ktor-server-websockets`.

## Decisions made

- Reasons 1, 3, 5, 6 and 7 are not asked about again: none of them passes by waiting, and 3 means the terminal is gone. Showing the tab again still tries afresh.
- The asking doesn't stop while the terminal is on screen. It costs one request and one connection every five seconds, and the message stays up meanwhile.
- No RESUME. The protocol offers it for a connection that dropped; Tono closes its streams and starts new ones.

## Not checked

- No window was looked at, desktop or web. The cause and the fix are in the client core, checked through `RexHost` against the Local server and the stand-in. The "Trying again…" text in a pane was not seen drawn.
- The web app was built (`./kotlin build -m webApp`) but not run.
- A remote host.
- A terminal still being opened by a connection that has since been replaced (after a reconnect) was left as it was.
