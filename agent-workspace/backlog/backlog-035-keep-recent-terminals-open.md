# backlog-035: Going back to a tab shouldn't wait for it to load again

| | |
|---|---|
| Status | captured |
| Area | Reading a terminal |
| Size | medium |
| Source | seen while filming the launch film |

## What

Keep the terminals of tabs that were shown recently open for a while, so that going back to
one shows it at once. Perhaps also open the tabs next to the one on screen ahead of time.

## Why

Every time a tab is shown, its pane says "Restoring…" until its terminal has been fetched
again. On the same machine that is too quick to see. On another machine over the tailnet it
was about half a second each time in the film's footage (omranchy showing tabs on omwork),
including for a tab left a moment before. Flicking between two remote tabs, which is what
Control and a digit is for, blinks every time. The film cuts past it.

## Builds on

`RexHost` keeps a terminal open only for the panes of the tab on screen: when the tab changes
it closes the ones that left and opens the new ones (the end of the `session_view` handling
in `rex/src/RexHost.kt`, where `current.terminals.filterKeys { it !in terminals }` are
closed). Opening one is a ticket, a data connection and a snapshot to restore.

A terminal kept open goes on receiving output and costs a connection, so the number kept
should be small, and one that isn't on screen must not claim its size.

## Promote when

When remote hosts are used day to day. It is the most visible roughness in moving between
machines.

## Not known yet

How much of the half second is the connection and how much the snapshot, and so whether
keeping the connection alone would be enough.
