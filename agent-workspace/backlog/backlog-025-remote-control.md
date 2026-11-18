# backlog-025: Let other clients drive Tono

| | |
|---|---|
| Status | captured |
| Area | Hosts and other clients |
| Size | medium |
| Source | inv-001 |

## What

Let `rex do` and other clients ask Tono to go to a session or tab, focus or zoom a pane, scroll, or toggle the sidebar. Off by default, per host.

## Why

Agents and scripts use it to point a person at something. Tono answers every such request with a refusal (`client_perform_requested` in `rex/src/RexHost.kt`).

## Builds on

Rex Rune allows exactly eight navigation actions and nothing that types, reads the clipboard or opens a dialog (`docs/shortcuts.md`, Remote control).

## Promote when

When something wants to drive Tono.
