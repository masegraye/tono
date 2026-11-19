# backlog-015: A menu on right-click

| | |
|---|---|
| Status | done (commits `63fcaf3`, `9711fd1`, `6c24ed8`, 2026-11-18) |
| Area | Panes, tabs and sessions |
| Size | small |
| Source | inv-001 |

## What

Right-click a pane for what can be done to it: copy, paste, split, zoom, close and the rest. Shift+right-click sends the click to the program instead.

## Why

Everything is on a key or in the palette, which has to be known about first.

## Builds on

The actions that exist by then. Rex Rune also has it on Ctrl+Shift+Space (`pane.actions`).

## Promote when

After backlog-002 and backlog-012, when there is enough to put in it.

## What was built

A menu on every pane, in the desktop app and the web app from the same code. Built by a worker.

- **The right button opens it**, where the click was, on the pane clicked. **Control+Shift+Space**
  opens it on the pane with the keyboard, in that pane's corner. That key and its action,
  `pane.actions`, are Rex Rune's; Rex for Mac has the menu and no action for it.
- **What is in it**, in this order, with a line between groups:

  | Group | Rows |
  |---|---|
  | Clipboard | Copy, Paste, Select All |
  | Reading and paths | Find…, Insert Path…, Go to Directory… |
  | Layout | Split Right, Split Left, Split Down, Split Up, Zoom Pane (Unzoom Pane when zoomed), Balance Splits |
  | The pane | Move Pane to Tab…, Move Pane to New Tab, Rename Pane… |
  | Ending | Close Pane, Clear Terminal, Reset Terminal |

  Each row runs the action of that name (`pane.copy`, `pane.split.right`, …), so it does what
  the key and the palette do, Close Pane's question included. Clear and Reset are the server's
  actions (`com.superlogical.terminal.clear` and `.reset`) and are listed only if it offers them.
- **A row that can't be used is dimmed and stays in its place**: Copy with nothing selected,
  Balance Splits and Move Pane to New Tab with one pane, Move Pane to Tab… with one tab. Both
  references do this. Each row shows the key bound to it, if one is.
- **By keyboard**: Up and Down go through the usable rows and round at the ends, Home and End
  to the first and last, Enter runs, Escape closes, and so does Control+Shift+Space again. Every
  other key is swallowed while it is open, so nothing is typed into the terminal under it.
- **Shift and the right button** over a program that tracks the mouse send the press and the
  release to the program, and no menu opens. Without Shift the menu opens whatever the
  program asked, and the program hears nothing of that click. Shift over a program that
  doesn't track the mouse opens the menu as usual.
- In the web app the browser's own menu is cancelled (`webApp/resources/page-keys.js`, part 7),
  except on a text field of the page's own.

Where things are: `tono/src/PaneMenu.kt` has the menu as a list (`PANE_MENU`), what is usable
(`paneMenuRows`), where it opens (`menuPosition`) and how it is drawn;
`rex/src/TerminalPointer.kt` decides what a right press is, and tells the view through
`TerminalSession.Gesture.Menu`; `Pane` in `tono/src/RexApp.kt` opens it, also for the margin
round a terminal and for a pane with no terminal. `TerminalView.kt` gained one parameter
(`onMenu`) and remembers where the right button went down; its drawing code is untouched.

Decisions made while building:

- **The right button's rule is the opposite of the middle button's, on purpose.** A plain
  middle click goes to a program that tracks the mouse and Shift+middle pastes (backlog-004,
  as xterm and Ghostty do); a plain right click opens the menu and Shift+right goes to the
  program. This is Rex Rune's rule (`docs/terminal.md`, `src/app/panes.rs`), and what this
  entry asked for. The brief's "Known" line said the other; the orchestrator was asked and
  chose this one. The reason it reads well: the menu is the one thing only the right button
  reaches, so it shouldn't depend on what happens to be running. `docs/using-tono.md` says so.
  What Rex for Mac does over such a program isn't known: its menu is the system's.
- **Order and words follow Rex for Mac's terminal menu** (Rex Rune's record of it,
  `docs/parity-2026-11/beta/b09-overlays.md`, screenshot 094): Copy, Paste / Split Right,
  Split Left, Split Down, Split Up, Zoom Pane / Close Pane, Reset Terminal, with its short
  titles ("Copy", not "Copy from Pane"). What only Rex Rune has goes where Rex Rune has it:
  Select All after Paste, Rename Pane after the layout, Clear Terminal beside Reset. The rest
  of what Tono can do to a pane is put with what it is most like. Close Pane leads the last
  group as on the Mac; Rex Rune puts it last of all, in red.
- **Left out**: Float Pane (Mac), Copy Pane ID, Download File and Reconnect Terminal (Rune),
  which Tono can't do. And on purpose: Focus, Move and Resize Pane by direction, Scroll Page,
  Jump to Prompt, Find Next and Previous. Each is a step repeated several times, which a key
  is for and a menu that closes after each step isn't. They stay on their keys and in the palette.
- **Shortcuts are shown**, as in Rex Rune. Rex for Mac's menu shows none, but this entry's
  reason for a menu is that keys have to be known about first, and the menu is where to learn them.
- **No title row and no red Close**, which Rex Rune has and the Mac hasn't.
- **Pane Actions… is in the palette**, which is where its key can be found. Rex Rune keeps it
  out of its Commands, since the rows are there anyway.
- **The menu acts on the pane clicked by giving it the keyboard first** (a request to the
  server), since actions act on the pane with the keyboard. Until the server says that pane
  has it, every row is dimmed; on this machine that was too short to see.
- **It opens when the button goes down**, to the right of and below the pointer; to the left
  near the right edge, above near the bottom, and moved up to fit where there is room neither
  way. Opened by key, its first usable row is selected; under a pointer, none is until the
  pointer moves onto one.
- **Rows are 24 points tall**, the Mac's figure. At 28 the menu was taller than the window the
  app opens with (1280×820 pixels at Mase's scale of 1.6, so 512 points), and a menu can't
  reach outside its window: the first desktop screenshot had its last row cut off. In a
  window shorter still it is as tall as the window and scrolls.
- **Whether Copy has anything to copy is asked once, as the menu opens**, by reading the
  selection, since nothing cheaper says. Binding a new library function for it would have
  collided with the other workers.
- A right press never changes the selection, and one made while the left button is selecting
  is ignored, as a middle press is.
- While the menu is open it counts as something open over the terminals: a program's copy is
  ignored and dropped files aren't taken, as with the palette.

What was checked, and how:

- `task test`, 210 tests: the pointer's rule (`TerminalPointerTest`, ten new cases, with the
  bytes a program gets for Shift+right), and the menu's contents, order, groups, dimming,
  the server's rows, the arrow keys and where it opens (`PaneMenuTest`).
- **The web app**, in headless Chromium against a scratch session, read back from the server
  and the browser's clipboard: Copy by clicking its row put a double-clicked word on the
  clipboard; Paste pasted it and typing went on in the terminal; letters typed into the open
  menu reached nothing, and Escape gave the keyboard back; Control+Shift+Space opened it with
  Paste selected, five Downs and Enter split the pane right, and the key closed it again; Up
  from nothing skipped a dimmed row; Clear Terminal cleared; Rename Pane… opened its dialog
  and the name showed. A plain right click over `cat -v` with tracking on gave the program
  nothing and opened the menu; Shift+right gave it `ESC[<6;23;17M` and `m` and no menu. A right
  click on the pane without the keyboard gave it the keyboard, and Zoom Pane zoomed that one.
  Near the bottom right corner the menu opened inside the window. The page's `contextmenu`
  event was cancelled every time, and there were no page errors.
- **The desktop app**, on a nested Xwayland display of its own with real buttons and keys from
  xdotool: Copy by a click on its row (that display's clipboard read with xclip), Paste by
  Down, Down, Enter, Escape and typing after it, Control+Shift+Space with five Downs and Enter
  splitting the pane, the plain right click and the Shift one over a tracking program
  (`ESC[<6;13;11M` and `m`), the menu on the pane without the keyboard and Zoom Pane on it,
  and Close Pane, which asked and then closed the pane clicked and not the other.

Not checked, and not done:

- **A real, visible window**, on the desktop or in a browser. That the browser's menu stays
  away was read from the event being cancelled, in headless Chromium, and not seen.
- **Firefox and Safari** were not run. Firefox is known to open its own menu on Shift and a
  right click whatever a page asks, so there Shift+right should reach the program with
  Firefox's menu over it.
- **Touch.** There is no long press for the menu; on a phone it is out of reach, and the
  palette is what there is.
- Rows run from the menu on the desktop: only Copy, Paste, Split Right, Zoom Pane and Close
  Pane. Find…, Insert Path…, Go to Directory…, Balance Splits, the two Move rows and Reset
  Terminal were run from the menu nowhere; they are the actions the palette runs.
- A right click on the margin round a terminal, on a pane with no terminal and on one still
  opening; the menu's pane closing while the menu is open; a host slow enough for the dimmed
  moment before the pane has the keyboard to show. All written, none seen.
- A window too short for the menu: it should scroll, but the arrow keys don't bring the
  selected row into view.
- A second right click while the menu is open closes it and opens nothing; it takes a third.
