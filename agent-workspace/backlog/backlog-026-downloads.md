# backlog-026: Download a file from the terminal's machine

| | |
|---|---|
| Status | captured (blocked: needs server API 13) |
| Area | Hosts and other clients |
| Size | medium |
| Source | inv-001 |

## What

Save a file from the terminal's machine to this one, with progress, never overwriting and never opening it.

## Why

The other half of uploads.

## Builds on

`com.superlogical.terminal.download_begin`, which arrived in API revision 13. Rex Rune's rules are in `docs/downloads.md`.

## Promote when

When the servers here are on revision 13. They are on 12.
