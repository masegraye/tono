# backlog

Things Tono could do and doesn't yet, formed enough to build later. An entry says what it is, why it is worth having, what it builds on, and what would move it up. Entries are promoted into a spec, or declined with the reason; they are never silently deleted.

Naming: `backlog-<nnn>-<slug>.md`

Same workspace rules: lowercase kebab-case, permanent numbers, a `Status` field (`captured` → `promoted` | `declined`, with a pointer when promoted, and a note in brackets when something outside holds it up), cross-reference by short code.

Most of these came from the audit of Rex Rune in [inv-001](../investigations/inv-001-rex-rune-feature-audit.md).

## Next up

The order proposed in inv-001, not yet agreed:

1. backlog-001: Program copies reach the clipboard (OSC 52)
2. backlog-002: Select and copy text
3. backlog-005: Open links with Ctrl+click
4. backlog-006: Search a terminal
5. backlog-008: Desktop notifications
6. backlog-009: The bell, and progress
7. backlog-010: Name the window after the tab
8. backlog-012: Resize, balance, swap, move and rename panes
9. backlog-013: Move a tab by key, close others, mute, rename a session
10. backlog-003: Paste or drop files and images
11. backlog-023: Insert a path, and go to a directory

## Everything

| # | Entry | Area | Size | Status |
|---|-------|------|------|--------|
| 001 | [Program copies reach the clipboard (OSC 52)](backlog-001-program-copies-reach-the-clipboard.md) | Clipboard | small | captured |
| 002 | [Select and copy text](backlog-002-select-and-copy-text.md) | Clipboard | large | captured |
| 003 | [Paste or drop files and images](backlog-003-paste-files-and-images.md) | Clipboard | medium | captured |
| 004 | [Copy on select, and middle-click paste](backlog-004-copy-on-select-and-middle-click-paste.md) | Clipboard | small | captured |
| 005 | [Open links with Ctrl+click](backlog-005-open-links.md) | Reading a terminal | medium | captured |
| 006 | [Search a terminal](backlog-006-search-scrollback.md) | Reading a terminal | medium | captured |
| 007 | [Jump between shell prompts](backlog-007-jump-between-prompts.md) | Reading a terminal | small | captured |
| 008 | [Desktop notifications](backlog-008-desktop-notifications.md) | Knowing what happened | medium | captured |
| 009 | [The bell, and progress](backlog-009-bell-and-progress.md) | Knowing what happened | small | captured |
| 010 | [Name the window after the tab](backlog-010-window-title.md) | Knowing what happened | small | captured |
| 011 | [Activity as a list](backlog-011-activity-list.md) | Knowing what happened | medium | captured |
| 012 | [Resize, balance, swap, move and rename panes](backlog-012-pane-resize-balance-swap-move.md) | Panes, tabs and sessions | medium | captured |
| 013 | [Move a tab by key, close others, mute, rename a session](backlog-013-tab-and-session-management.md) | Panes, tabs and sessions | small | captured |
| 014 | [Back and forward](backlog-014-navigation-history.md) | Panes, tabs and sessions | small | captured |
| 015 | [A menu on right-click](backlog-015-pane-menu.md) | Panes, tabs and sessions | small | captured |
| 016 | [Floating panes](backlog-016-floating-panes.md) | Panes, tabs and sessions | large | captured |
| 017 | [Curly, double, dotted and dashed underlines, and overline](backlog-017-underline-styles-and-overline.md) | Drawing | small | captured |
| 018 | [Draw box and block characters as shapes](backlog-018-box-drawing-as-shapes.md) | Drawing | medium | captured |
| 019 | [Hold the frame while a program redraws](backlog-019-hold-the-frame-while-a-program-redraws.md) | Drawing | small | captured |
| 020 | [Animated images](backlog-020-animated-images.md) | Drawing | medium | captured |
| 021 | [Program icons on tabs](backlog-021-program-icons.md) | Drawing | medium | captured |
| 022 | [Key sequences and modes](backlog-022-key-sequences-and-modes.md) | Typing and keys | medium | captured |
| 023 | [Insert a path, and go to a directory](backlog-023-insert-path-and-go-to-directory.md) | Typing and keys | medium | captured |
| 024 | [Hosts over SSH](backlog-024-ssh-hosts.md) | Hosts and other clients | large | captured |
| 025 | [Let other clients drive Tono](backlog-025-remote-control.md) | Hosts and other clients | medium | captured |
| 026 | [Download a file from the terminal's machine](backlog-026-downloads.md) | Hosts and other clients | medium | captured (blocked: needs server API 13) |
| 027 | [Per-host icons](backlog-027-per-host-icons.md) | Hosts and other clients | small | captured |
| 028 | [A settings screen](backlog-028-settings-screen.md) | The app | large | captured |
| 029 | [Try the web app in Safari and on real phones](backlog-029-safari-and-real-devices.md) | The web app | small | captured |
| 030 | [Serve fallback fonts ourselves](backlog-030-fallback-fonts-from-our-own-server.md) | The web app | medium | captured |
| 031 | [An AUR package](backlog-031-aur-package.md) | Shipping | medium | captured (on hold until asked for) |
| 032 | [A tab in a window of its own](backlog-032-pop-out-tab-windows.md) | Shipping | medium | captured (on hold: not wanted yet) |
| 033 | [Publish the Omarchy theme libraries](backlog-033-publish-the-theme-libraries.md) | Shipping | medium | captured (on hold: shelved) |
