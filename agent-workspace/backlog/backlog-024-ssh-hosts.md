# backlog-024: Hosts over SSH

| | |
|---|---|
| Status | captured |
| Area | Hosts and other clients |
| Size | large |
| Source | inv-001 |

## What

Add a host as `ssh://user@machine`, reached by forwarding the Rex server's socket over SSH.

## Why

A machine that runs Rex but isn't on the tailnet can't be added. `RexEndpoint.parse` refuses `ssh://`.

## Builds on

Rex Rune's way, in `docs/hosts.md`: `ssh -L` to a private local socket, never prompting, never starting a server on its own.

## Promote when

When there is a machine to reach that way.

## Not known yet

On the web the page's server would have to make the forward.
