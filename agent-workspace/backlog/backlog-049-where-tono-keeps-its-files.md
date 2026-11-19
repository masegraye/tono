# backlog-049: Keep Tono's files where each platform expects them

| | |
|---|---|
| Status | captured (investigated: inv-003, awaiting Mase's answers) |
| Area | Shipping |
| Size | medium |
| Source | Mase, 2026-11-19 |

## What

Tono keeps everything in one file, `~/.config/kotlin-test/workspace.json` (or `$XDG_CONFIG_HOME/…`), on every platform that has files. It mixes settings (notifications, selection, shortcuts, appearance, host order, client label) with state (the last host, folded sessions, the tab shown in each session, muted tabs). Files should go where each platform expects them, under Tono's name, with settings and state apart where the platform separates them.

## Why

Getting ready to ship on several platforms (big-idea-001). The directory is named after the repository, not the app, and Linux keeps configuration and state in different places (XDG config vs state), macOS in Application Support, and so on.

## Builds on

`rex/src@jvm/Platform.jvm.kt` (`defaultWorkspace`, the path), `rex/src/Workspace.kt`, the web build's `localStorage`. The agent rule "the configuration path stays as it is" (agent-workspace readme) is under review until this is decided.

## Promote when

Now; investigated in inv-003, then built from its recommendation, with a migration from the old file.
