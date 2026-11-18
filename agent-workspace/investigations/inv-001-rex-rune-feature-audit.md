# inv-001: What Rex Rune does that Tono doesn't

| | |
|---|---|
| Status | in-review |
| Date | 2026-11-18 |
| Asked for | "A feature audit of rex-rune to see if there's anything we can improve. Investigate and propose only." |
| Outcome | Backlog entries 001 to 028, apart from 016, 020 and 027, which came from earlier sessions |

## What was compared

- **[Rex Rune](https://github.com/shuv1337/rex-rune)** at commit `2612ce6` (after 0.10.1): its `README.md`, `CHANGELOG.md`, and `docs/terminal.md`, `docs/shortcuts.md`, `docs/configuration.md`, `docs/hosts.md`, `docs/downloads.md`, `docs/server-api13.md` and `docs/server-preview14.md`, with `src/connection/clipboard.rs` read for how copies arrive.
- **Tono** at commit `76336f0`: its actions (`tono/src/RexActions.kt`), what it reads from the server (`rex/src/RexHost.kt`), and how it draws (`tono/src/TerminalView.kt`, the renderers in `ghostty/`).
- **The Rex server here**, API revision 12, and its documentation of methods and events.
- **libghostty-vt's headers** as built for Tono, in `native/out/include/ghostty/vt/`, for what the library can already do.

## How far to trust it

This is a reading of documents and code. Rex Rune was not run. Three of the findings about Tono are marked as unchecked below, where a gap was inferred and not seen.

## The clipboard

The largest gap, and the one Mase called out.

| | Rex Rune | Tono |
|---|---|---|
| A program copies (OSC 52) | Put on the clipboard, from the focused pane only. Reads are never answered | Ignored. The server sends a `clipboard_written` event, and `RexHost.kt` reads only `program_status_changed`, `process_changed` and `program_status_removed` from a terminal |
| Selecting text | Drag, word, line, autoscroll, Shift+drag over programs that take the mouse, Select All | None |
| Copy | Ctrl+Shift+C | None |
| Paste text | Ctrl+Shift+V, bracketed when the program asks | The same |
| Paste or drop a file or image | Uploaded to the terminal's machine, path typed | None |
| Copy on select, middle-click paste | Options, off by default | None |

libghostty-vt has selection built in (`selection.h`), so Tono doesn't have to work out what a word or a wrapped line is. What it has to do is bind those functions on the desktop and in the browser, and draw the highlight.

Backlog: 001 (program copies), 002 (select and copy), 003 (files and images), 004 (the two options).

## Reading a terminal

| | Rex Rune | Tono | Backlog |
|---|---|---|---|
| Links | Ctrl+click opens `http`, `https`, `ftp`, `mailto`; a tooltip shows the real target | None | 005 |
| Search | Ctrl+Shift+F over screen and history | None | 006 |
| Prompts | Ctrl+Shift+Up and Down move between shell prompts | None | 007 |

The library has what each needs: `grid_ref.h` for a cell's link, `search.h`, and prompt marks on rows in `screen.h`.

## Knowing what happened

| | Rex Rune | Tono | Backlog |
|---|---|---|---|
| Notifications | For tabs out of sight that need you, fail or finish, and for programs that ask (OSC 9, OSC 777) | Sounds only | 008 |
| The bell | Marks the tab | Nothing | 009 |
| Progress (OSC 9;4) | Drawn | Nothing | 009 |
| Window title | `tab - session · host` | Always "Tono" | 010 |
| Activity | A list by priority or by session, with ages and a count | A sidebar filter | 011 |

The server sends `desktop_notification`, `bell` and `progress_report` as terminal events. Tono reads none of them.

## Panes, tabs and sessions

| | Rex Rune | Tono | Backlog |
|---|---|---|---|
| Panes | Split five ways, resize, balance, swap, move to a tab, rename, zoom, close, focus | Split four ways, zoom, close, focus | 012 |
| Tabs | Move by key, close others, mute, rename | Drag to move, rename, close | 013 |
| Sessions | Rename, close, new | Close, new | 013 |
| History | Back and forward through visited tabs | None | 014 |
| Pane menu | Right-click, or Ctrl+Shift+Space | None | 015 |

The server has `session.resize_pane`, `session.swap_blocks`, `session.move_block`, `session.set_block_label` and `session.set_label`.

## Drawing

| | Rex Rune | Tono | Backlog |
|---|---|---|---|
| Underlines | Single, double, curly, dotted, dashed, in their own colour; overline | A single line in the text's colour | 017 |
| Box and block characters | Drawn as shapes on whole pixels | Drawn from the font | 018 |
| A program mid-redraw (mode 2026) | Last frame held | Not checked | 019 |
| Program icons | About 150, on tabs and pane headers | None | 021 |

Whether Tono shows seams between box characters, and whether it already holds a frame through the library, were not looked at. Both entries say to look before building.

## Typing, hosts and other clients

| | Rex Rune | Tono | Backlog |
|---|---|---|---|
| Key sequences and modes | Both, with the active mode named | Modes skipped; sequences untried | 022 |
| Insert path, go to directory | Browse the terminal's machine from the palette | None | 023 |
| SSH hosts | `ssh://` by socket forwarding | Refused | 024 |
| Remote control | Eight navigation actions, off by default | Every request refused | 025 |
| Downloads | From the terminal's machine, API 13 | None; the servers here are on 12 | 026 |
| Settings | A screen with search | A few palette actions | 028 |

## What Rex Rune has that Tono shouldn't copy

- Its own updater, and installing agent integrations: Tono is installed from a checkout, and Rex installs its own integrations.
- The pairing QR code, the server page and Stop server: these are about running Rex, not about using terminals.
- Remembering the window's size, interface zoom, pane density and toolbar visibility: the window manager and the desktop's theme decide these for Tono.
- macOS and Windows support.

## What Tono has that Rex Rune doesn't

For balance, and because it bears on what to build: Tono runs in a browser and on a phone, and takes its theme and font from the Omarchy desktop as they change. This audit looked for what Tono lacks, so this list is short because it wasn't looked for, not because it is complete.

## Proposed order

1. Program copies, then selecting and copying (001, 002).
2. Links and search, which use the same plumbing as selection (005, 006).
3. Notifications, the bell, progress and the window title (008, 009, 010).
4. Pane resize, balance and swap, and tab keys (012, 013).
5. Files and images, then inserting a path (003, 023).

This order has not been agreed.
