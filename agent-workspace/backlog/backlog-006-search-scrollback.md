# backlog-006: Search a terminal

| | |
|---|---|
| Status | done (commits `5d3c6ce`, `eecbade`, `b940fd0`, 2026-11-18) |
| Area | Reading a terminal |
| Size | medium |
| Source | inv-001 |

## What

Ctrl+Shift+F searches the focused terminal's screen and history, with next, previous and a count of matches.

## Why

History can only be scrolled through by eye.

## Builds on

`search.h` in libghostty-vt. Rex Rune's bar floats over the terminal's corner and never resizes it; its actions are `client.search`, `client.search.next` and `client.search.previous`.

## Promote when

After backlog-002.

## What was built

Ctrl+Shift+F opens a search of the focused terminal's screen and history, in the desktop app
and the web app, from the same code. A bar floats over the terminal's top right corner and
never resizes it: the text, the selected match's place among them all ("3 of 17", or "No
matches"), and buttons for the next match, the previous one, and closing. Every match on
screen is marked in the accent color and the selected one more strongly, with a line round
it. Enter goes to the next match, Shift+Enter to the previous, both going round at the ends
and scrolling the match into view; Escape closes. Output that arrives while the bar is open
is searched too.

How it is put together:

- libghostty-vt does the searching (`search.h`). `GhosttySearch` binds it, for the desktop in
  `ghostty/src@jvm/GhosttySearch.kt` and for the web in `ghostty-glue.js`, with
  `ghostty_terminal_point_from_grid_ref` to turn a match's two ends into cells of the view.
- `rex/src/TerminalSearch.kt`: `TerminalSearching` runs one search on the terminal's thread,
  a few milliseconds at a time so a long history doesn't hold up drawing, and publishes a
  `TerminalSearch` (the count and the cells to mark) in `TerminalSession.search`.
- `tono/src/SearchBar.kt` is the bar; `TerminalView.kt` draws the marks and decides whether
  the bar's field or the terminal has the keyboard.

Checked by the worker:

- Unit tests, on the JVM: the binding against a real terminal (`GhosttySearchTest`: counting,
  either case, next and previous going round, a match in the history scrolled into view, new
  output, a wrapped match, more matches than the first buffer), the driving of it
  (`TerminalSearchTest`), where the bar goes (`SearchBarTest`), and the bar drawn offscreen as
  the desktop draws it, with keys sent to its field (`SearchBarRenderTest`). `task test` passes.
- The glue's functions on their own, in Node, with the binding's cases. Not checked in, like
  the earlier ones.
- The web app in headless Chromium, against a scratch session, by screenshots: 120 matches
  counted and marked, "1 of 120" at the newest, Enter twice to "3 of 120", Shift+Enter back,
  round from the newest to "120 of 120" with the view scrolled to the first line, and round
  again; four more matches arriving while open, "1 of 120" becoming "5 of 124" on the same
  text; Ctrl+Shift+G and Ctrl+Alt+G stepping with the field focused; "No matches"; Escape
  closing, and typing then reaching the shell; Find Next with the bar closed bringing it back
  with the keyboard still in the shell; Ctrl+Shift+F again selecting the field's text so
  typing replaced it; "Find…" run from the palette giving the field the keyboard; the bar
  moving to the bottom corner when the selected match was under it; and the bar under the
  name of a named pane, in a split. The browser was left none of the three shortcuts.

Decisions made while building, none of them asked for:

- **The actions are Rex for Mac's**, read off a connected Mac client and its reference:
  `client.find.open`, `client.find.next`, `client.find.previous`, in the category "Find", not
  Rex Rune's `client.search…` (which Rune keeps the Mac's names as aliases for). The Mac has
  them on Command+F, Command+G and Shift+Command+G. Control+F and Control+G are a shell's,
  so they are Control+Shift+F and Control+Shift+G, and Find Previous, which has Shift
  already, is Control+Alt+G, as Split Pane Down is Control+Alt+D.
- **Case.** A letter of the English alphabet matches in either case, and everything else has
  to be the same ("É" doesn't find "é"). This is what the library does, it has no setting
  for it, and Rex Rune searches with the same library. There is no exact-case search.
- **"Next" is the next older match**, upward, and counting starts from the newest: the
  library's order and Rune's. So the bar's up arrow is Find Next.
- **The newest match is selected as soon as text is typed**, and scrolled to, as a browser's
  find does. The library selects nothing until asked.
- **Find Next or Previous with the bar closed** brings it back on the text last searched for
  in that terminal, at the newest match, and leaves the keyboard in the terminal, as in
  Rune. If nothing was searched there yet, they open the bar like Find.
- **The keyboard.** The shortcuts work from the field and from the terminal. A click in the
  terminal gives it the keyboard and leaves the bar open; Escape closes the bar only from
  the field, since in the terminal it is the program's.
- **Where the bar is.** The top right corner, below a pane's name when the pane has one. It
  moves to the bottom right when the selected match would be under it, and stays there
  until one is under it there or the search is closed, as in Rune.
- Closing the search leaves the view where it was scrolled to. Typing in the terminal goes
  back to the bottom, as it always did.
- A search belongs to its terminal: it stays open, with its text, while another tab is shown.

Not checked, or not done:

- **The desktop app in a real window.** The search bar was drawn and typed into offscreen,
  and the binding is tested on the JVM, but Ctrl+Shift+F was never pressed in a focused
  desktop window: that needs the real keyboard. Left for a check by hand.
- A very long history. The tests search about a thousand lines, which is what a terminal
  made in a test keeps; how long tens of thousands of lines from a server take was not timed.
- A search does not survive a reconnect, which makes a new terminal: the bar closes. Rune's does.
- A match under the bar in both corners stays hidden. Touch: nothing opens the search on a
  phone but the palette. Firefox and Safari.
- Seen in passing, and not this work's: in the web app, the text of a long run of plain
  characters drifts left of the grid, about a cell in 120 columns, so a mark (and a
  selection, which showed the same) at the far right sits a cell right of its text.
