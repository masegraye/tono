# backlog-004: Copy on select, and middle-click paste

| | |
|---|---|
| Status | done (commits `ed5d329`, `e8ed49f`, 2026-11-18) |
| Area | Clipboard |
| Size | small |
| Source | inv-001 |

## What

Two options, both off unless turned on: copy a selection as soon as it is made, and paste the desktop's primary selection with the middle button.

## Why

Common terminal habits on Linux.

## Builds on

backlog-002. Rex Rune has both as settings, default off.

## Promote when

After backlog-002, if wanted at all.

## Not known yet

The primary selection doesn't exist in a browser.

## What was built

Two switches, both off until turned on, in the desktop app and the web app from the same
code. Built by a worker.

- **Copy on Selection** (`copy_on_selection`): a selection the pointer has finished goes to
  the clipboard when the button comes up. A drag, a double click and a triple click count.
  A click that selects nothing, Select All and a search's matches copy nothing.
- **Primary Selection** (`primary_selection`): the selection becomes the desktop's PRIMARY
  selection, the clipboard is left alone, and a middle click pastes PRIMARY. The paste goes
  the way Ctrl+Shift+V goes (`RexHost.paste`, bracketed where the program asked, and the
  upload path of backlog-003 if what is there is files or an image).
- These are Rex Rune's two settings, with its names and its defaults, and middle click
  uses PRIMARY and never the clipboard, as there. They are in the workspace file under
  `terminal`, always written out so the file shows them, and in the palette: Copy on
  Selection under Rex for Mac's id `client.setting.terminal.selection.copy_on_select`, and
  Primary Selection under `client.setting.terminal.selection.primary_selection`, which Rex
  for Mac doesn't have, a Mac having no PRIMARY.
- A program that tracks the mouse gets the middle click. With Shift held it pastes instead
  and the program hears nothing of that click, as Shift does for selecting (backlog-002).
- `rex/src/TerminalPointer.kt` decides what a middle click is and says when a selection is
  finished; `tono/src/SelectionCopy.kt` decides where the text goes.

**In a browser there is no PRIMARY**, and a page can neither set nor read the desktop's.
With Primary Selection on, the clipboard stands in: selecting copies to the clipboard,
overwriting it, and a middle click pastes the clipboard exactly as Ctrl+Shift+V does. The
orchestrator chose this over waiting for the browser's own paste event, which Chrome on
Linux gives only to editable fields and so never to the terminal's canvas. With both
switches off a middle click in the web app does nothing, and its press is cancelled so the
browser does nothing with it either (`webApp/resources/page-keys.js`).

What was checked, and how:

- `task test`: the pointer's decisions (`TerminalPointerTest`), where a selection goes for
  each pair of switches and each kind of desktop (`SelectionCopyTest`), and the workspace
  file (`TabNewsTest`).
- The web app in headless Chromium, against a scratch session, reading the browser's own
  clipboard and the terminal from the server. Both off: a drag left the clipboard, a middle
  click pasted nothing and its press was cancelled, Ctrl+Shift+C still copied. Copy on
  Selection: a click copied nothing, a drag and a double click copied, Select All didn't, a
  middle click pasted nothing. Primary Selection: a drag copied, a middle click pasted it,
  a middle drag pasted nothing, a program tracking the mouse got the click (`ESC[<1;…M`),
  and Shift+middle pasted there instead. Both palette commands, each read before Enter,
  changed the stored workspace and showed their state.
- The desktop app on a nested Xwayland display of its own, with real buttons from xdotool
  and that display's own PRIMARY and CLIPBOARD read with xclip, both switches on: a middle
  click pasted text another X program owned as PRIMARY; a drag over a word made it both
  PRIMARY and CLIPBOARD; with the clipboard then changed, a middle click pasted PRIMARY and
  not the clipboard; a program tracking the mouse got the click; Shift+middle pasted there.

Decisions made while building, none of them asked for:

- Shift+middle pastes over a program that has the mouse, as the brief said and as xterm
  and Ghostty do. Rex Rune never pastes there, Shift or not.
- With Primary Selection off, the middle button is as it was: a program tracking the mouse
  gets it, Shift or not, and otherwise nothing happens.
- A middle click pastes when the button comes up within 3 pixels of where it went down
  (Rex Rune's rule), not on the press. A middle press while the left button is selecting
  is ignored.
- Nothing is copied while the palette or a dialog is open over the terminals.
- Where the toolkit has no PRIMARY (a Mac, Windows), the switch stays in the palette and
  says "unavailable"; Rex Rune hides it there. In a browser it says "on, with the clipboard".
- What was copied by selecting leaves a line in the debug log, with its length and never
  the text.

Not checked:

- **Whether PRIMARY crosses between XWayland and Wayland on Mase's desktop.** The desktop
  app is an X program (AWT's `Toolkit.getSystemSelection()`), run through XWayland under
  Hyprland, and the nested display is plain X, so it says nothing about this. Finding out
  needs the real PRIMARY and a focused window, which the worker was told to leave alone.
  To check by hand: turn Primary Selection on, select in Tono and middle-click in a Wayland
  program (and `wl-paste --primary`), then the other way round.
- On the desktop: each switch on its own and both off (unit tests and the web app only),
  the palette commands, a double click, and anything in a real, visible window.
- In a real browser window: that cancelling the middle press stops Chrome's scrolling on
  Windows, and what Firefox on Linux does, which is believed to send a page its own
  paste event on a middle click (the app ignores a paste event that carries text). Firefox and Safari were
  not run. The clipboard was headless Chromium's, with permission granted beforehand; a
  browser that asks before a page reads the clipboard will ask on the first middle click.
- A middle click in a pane that doesn't have the keyboard, with several panes in a tab: the
  paste goes to the pane clicked, and whether that pane then takes the keyboard was not looked at.
- A selection of many megabytes: Rex Rune refuses a copy over 8 MiB, and nothing here does.
