# backlog

Things Tono could do and doesn't yet, formed enough to build later. An entry says what it is, why it is worth having, what it builds on, and what would move it up. Entries are promoted into a spec, or declined with the reason; they are never silently deleted.

Naming: `backlog-<nnn>-<slug>.md`

Same workspace rules: lowercase kebab-case, permanent numbers, a `Status` field (`captured` → `promoted` | `done` | `declined`, with a pointer when promoted or the commits when done, and a note in brackets when something outside holds it up), cross-reference by short code.

Most of these came from the audit of Rex Rune in [inv-001](../investigations/inv-001-rex-rune-feature-audit.md).

## Next up

The order proposed in inv-001, not yet agreed:

1. ~~backlog-001: Program copies reach the clipboard (OSC 52)~~ done
2. ~~backlog-002: Select and copy text~~ done
3. ~~backlog-005: Open links with Ctrl+click~~ done
4. ~~backlog-006: Search a terminal~~ done
5. ~~backlog-008: Desktop notifications~~ done
6. ~~backlog-009: The bell, and progress~~ done
7. ~~backlog-010: Name the window after the tab~~ done
8. ~~backlog-012: Resize, balance, swap, move and rename panes~~ done
9. ~~backlog-013: Move a tab by key, close others, mute, rename a session~~ done
10. ~~backlog-003: Paste or drop files and images~~ done
11. ~~backlog-023: Insert a path, and go to a directory~~ done

## Everything

| # | Entry | Area | Size | Status |
|---|-------|------|------|--------|
| 001 | [Program copies reach the clipboard (OSC 52)](backlog-001-program-copies-reach-the-clipboard.md) | Clipboard | small | done |
| 002 | [Select and copy text](backlog-002-select-and-copy-text.md) | Clipboard | large | done |
| 003 | [Paste or drop files and images](backlog-003-paste-files-and-images.md) | Clipboard | medium | done |
| 004 | [Copy on select, and middle-click paste](backlog-004-copy-on-select-and-middle-click-paste.md) | Clipboard | small | done |
| 005 | [Open links with Ctrl+click](backlog-005-open-links.md) | Reading a terminal | medium | done |
| 006 | [Search a terminal](backlog-006-search-scrollback.md) | Reading a terminal | medium | done |
| 007 | [Jump between shell prompts](backlog-007-jump-between-prompts.md) | Reading a terminal | small | done |
| 008 | [Desktop notifications](backlog-008-desktop-notifications.md) | Knowing what happened | medium | done |
| 009 | [The bell, and progress](backlog-009-bell-and-progress.md) | Knowing what happened | small | done |
| 010 | [Name the window after the tab](backlog-010-window-title.md) | Knowing what happened | small | done |
| 011 | [Activity as a list](backlog-011-activity-list.md) | Knowing what happened | medium | captured |
| 012 | [Resize, balance, swap, move and rename panes](backlog-012-pane-resize-balance-swap-move.md) | Panes, tabs and sessions | medium | done |
| 013 | [Move a tab by key, close others, mute, rename a session](backlog-013-tab-and-session-management.md) | Panes, tabs and sessions | small | done |
| 014 | [Back and forward](backlog-014-navigation-history.md) | Panes, tabs and sessions | small | done |
| 015 | [A menu on right-click](backlog-015-pane-menu.md) | Panes, tabs and sessions | small | done |
| 016 | [Floating panes](backlog-016-floating-panes.md) | Panes, tabs and sessions | large | captured |
| 017 | [Curly, double, dotted and dashed underlines, and overline](backlog-017-underline-styles-and-overline.md) | Drawing | small | done |
| 018 | [Draw box and block characters as shapes](backlog-018-box-drawing-as-shapes.md) | Drawing | medium | captured |
| 019 | [Hold the frame while a program redraws](backlog-019-hold-the-frame-while-a-program-redraws.md) | Drawing | small | captured |
| 020 | [Animated images](backlog-020-animated-images.md) | Drawing | medium | captured |
| 021 | [Program icons on tabs](backlog-021-program-icons.md) | Drawing | medium | captured |
| 022 | [Key sequences and modes](backlog-022-key-sequences-and-modes.md) | Typing and keys | medium | captured |
| 023 | [Insert a path, and go to a directory](backlog-023-insert-path-and-go-to-directory.md) | Typing and keys | medium | done |
| 024 | [Hosts over SSH](backlog-024-ssh-hosts.md) | Hosts and other clients | large | captured |
| 025 | [Let other clients drive Tono](backlog-025-remote-control.md) | Hosts and other clients | medium | captured |
| 026 | [Download a file from the terminal's machine](backlog-026-downloads.md) | Hosts and other clients | medium | captured (blocked: needs server API 13) |
| 027 | [Per-host icons](backlog-027-per-host-icons.md) | Hosts and other clients | small | done |
| 028 | [A settings screen](backlog-028-settings-screen.md) | The app | large | promoted (spec-001) |
| 029 | [Try the web app in Safari and on real phones](backlog-029-safari-and-real-devices.md) | The web app | small | captured |
| 030 | [Serve fallback fonts ourselves](backlog-030-fallback-fonts-from-our-own-server.md) | The web app | medium | captured |
| 031 | [An AUR package](backlog-031-aur-package.md) | Shipping | medium | captured (on hold until asked for) |
| 032 | [A tab in a window of its own](backlog-032-pop-out-tab-windows.md) | Shipping | medium | captured (on hold: not wanted yet) |
| 033 | [Publish the Omarchy theme libraries](backlog-033-publish-the-theme-libraries.md) | Shipping | medium | captured (on hold: shelved) |
| 034 | [The web app may not notice that it lost focus](backlog-034-the-web-app-may-not-notice-losing-focus.md) | The web app | small | captured |
| 035 | [Going back to a tab shouldn't wait for it to load again](backlog-035-keep-recent-terminals-open.md) | Reading a terminal | medium | captured |
| 036 | [Close the connections a silent server never answered](backlog-036-close-connections-a-silent-server-never-answered.md) | Connecting to servers | small | done |
| 037 | [The web relay may keep a page's connection after the page is gone](backlog-037-the-relay-may-keep-a-page-that-is-gone.md) | The web app | small | done |
| 038 | [A host that is still being connected to looks like one that is connected](backlog-038-a-host-being-connected-to-looks-connected.md) | The sidebar | small | done |
| 039 | [Pane keys reach the terminal in a tab with one pane](backlog-039-pane-keys-reach-the-terminal-with-one-pane.md) | Typing and keys | small | done |
| 040 | [Text drifts from its cells in the web app](backlog-040-text-drifts-from-the-grid-in-the-web-app.md) | Drawing | small | done |
| 041 | [In the web app, a host the relay refuses lingers at "Connecting…"](backlog-041-web-hosts-linger-at-connecting.md) | The sidebar | small | done |
| 042 | [Change a shortcut in Tono itself](backlog-042-change-a-shortcut.md) | Typing and keys | medium | done |
| 043 | [A relay test sometimes fails under load](backlog-043-relay-gone-page-test-is-flaky.md) | Tests | small | captured |
| 044 | [Switching tabs sometimes says the server refused the terminal stream (reason 2)](backlog-044-server-refuses-terminal-stream-reason-2.md) | Connecting to servers | small | done |
| 045 | [Look right without Omarchy: built-in themes and a terminal font](backlog-045-look-right-without-omarchy.md) | The app | medium | captured |
| 046 | [Living without a bundled server](backlog-046-living-without-a-bundled-server.md) | Connecting to servers | medium | promoted (spec-001, phase 4) |
| 047 | [Other settings Rex for Mac has that Tono may want](backlog-047-other-rex-for-mac-settings.md) | The app | medium | captured |
