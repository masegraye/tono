# backlog-008: Desktop notifications

| | |
|---|---|
| Status | captured |
| Area | Knowing what happened |
| Size | medium |
| Source | inv-001 |

## What

A notification when a tab you aren't looking at needs you, fails or finishes, and when a program asks for one itself (OSC 9, OSC 777).

## Why

Tono has sounds only, which say that something happened and not what or where.

## Builds on

The server's `desktop_notification` terminal event (with `title` and `body`), and the activity news Tono already gathers for sounds (`RexClient.activityNews`). Rex Rune replaces the last notification for the same tab rather than stacking them, and has a switch per kind.

## Promote when

After the clipboard work.

## Not known yet

On the web this is the browser's notification permission, which the user has to grant.
