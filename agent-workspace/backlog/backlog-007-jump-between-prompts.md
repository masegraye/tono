# backlog-007: Jump between shell prompts

| | |
|---|---|
| Status | done (commits `960d6cb`, `758cb77`, 2026-11-18) |
| Area | Reading a terminal |
| Size | small |
| Source | inv-001 |

## What

Ctrl+Shift+Up and Down move the view to the previous and next shell prompt, without typing anything.

## Why

The quick way back to the start of a long command's output.

## Builds on

libghostty-vt marks prompt rows (`GHOSTTY_ROW_DATA_SEMANTIC_PROMPT` in `screen.h`), from the OSC 133 marks shells send. Rex Rune: `client.prompt.previous` and `client.prompt.next`.

## Promote when

With backlog-006, since both move the view through history.

## What was built

Ctrl+Shift+Up moves the focused terminal's view to the prompt of the command above its first
row, and Ctrl+Shift+Down to the next one below, ending at the live screen. The prompt gone to
becomes the view's first row. Nothing is typed. It works in the desktop app and the web app,
from the same code, and both are in the command palette.

How it is put together:

- libghostty-vt already has the jump: `GHOSTTY_SCROLL_VIEWPORT_DELTA_PROMPT`, a fifth way to
  call `ghostty_terminal_scroll_viewport`, which was bound from the start. So nothing was
  added to `native/ghostty-vt.symbols` and the bindings were not regenerated.
  `GhosttyTerminal.scrollByPrompts(count)` calls it, on the desktop in
  `ghostty/src@jvm/GhosttyTerminal.kt` and on the web through `terminalScrollByPrompts` in
  `ghostty-glue.js`.
- `TerminalSession.jumpToPrompt(older)` sends it to the terminal's thread, as scrolling does.
- `tono/src/RexActions.kt` has the two actions. `TerminalView.kt` and `page-keys.js` needed
  no change: a key bound to an action is already the app's, and the page already keeps
  every key with Control from the browser.
- `docs/using-tono.md` has a section on it, with how to turn the marks on.

Checked by the worker:

- Unit tests, on the JVM (`GhosttyPromptsTest`), which feed the library OSC 133 marks and read
  the rows in view: up lands on each older prompt in turn and stops at the oldest; down on
  each newer one and ends at the live screen; from the middle of a command's output; no marks,
  nothing moves; unmarked prompts among marked ones are passed over; the marks bash sends with
  the two lines from the documentation (read off a real bash in a pty) are enough; and a
  terminal restored from a snapshot keeps its marks. `RexShortcutsTest` has the names, titles
  and keys, and its clash test passes. `task test` passes.
- The glue's function on its own, in Node, against the WebAssembly library: the same rows as
  the JVM test. Not checked in, like the earlier ones.
- The web app in headless Chromium, against a scratch session, by screenshots: four marked
  commands of 60 lines each printed into a real shell, Ctrl+Shift+Up four times landing on
  the fourth, third, second and first prompt as the first row, a fifth press staying; Down
  landing on the second, third and fourth. The ten keys changed nothing in the server's copy
  of the terminal (`rex capture` before and after), and every one arrived cancelled, so the
  browser was left none. "Jump to Next Prompt" is in the palette with its key.
- The desktop app in a window, on a nested X server on a hidden workspace, with real key
  presses (`xdotool key ctrl+shift+Up`): the same four prompts up, a fifth press staying,
  then down through them to the live screen, and a press more staying there. The marked
  output was printed before the app started, so those marks came in the server's snapshot.
  Nothing changed on the server.

Decisions made while building, none of them asked for:

- **The actions are Rex for Mac's**: `client.prompt.previous`, "Jump to Previous Prompt", and
  `client.prompt.next`, "Jump to Next Prompt", in the category "Pane", with its search words.
  Rex Rune has the same names. The Mac has them on Command+Up and Command+Down. Control with
  an arrow is the program's (a shell moves by a word, an editor by a paragraph), so Shift
  is added, which is also what Rune did.
- **The library decides where a jump lands**, as in Rune, which calls the same operation.
  That gives three things to know. The jump counts from the view's first row, so a prompt
  lower in the view is not "the previous one". Past the newest prompt is the live screen,
  because the newest prompt is normally on it. And when the newest prompt has itself
  scrolled out of view, under a running command's output, Down from it does nothing: it does
  not go to the bottom. Shift+PageDown and typing do. There is a test for this, so a change in
  the library will show.
- **The keys are taken even where they do nothing**: in a terminal without marks, and in a
  full-screen program. A program that wanted Control+Shift+Up no longer gets it. Rune made
  the same choice. The key can be unbound or rebound in Rex's configuration file.
- **Turning marks on is left to the user and written down**, in `docs/using-tono.md`. Rex's
  documentation has no shell integration to point to (it names OSC 133 only for program
  status), and the Rex server injects none: a plain bash in a scratch session sent no marks.
  The documentation gives two lines for bash, four for zsh, says fish 4 does it unasked, and
  that any terminal's published integration script sends the same marks.

Not checked, or not done:

- The zsh lines in the documentation: zsh isn't installed here. They are the usual
  `precmd`/`preexec` form of the bash lines, which were tried. Fish wasn't tried either.
- A real shell with its integration on, used through Tono. The checks printed the marks
  with `printf` from a shell that doesn't send them, and a test replays what a marked bash
  sent. Prompts that take several lines, and a right-hand prompt, were not tried.
- No marks, in the browser: the one screenshot of it was taken as the terminal changed size,
  so it shows the live screen after the key and nothing more. The unit test covers it.
- Touch: nothing jumps on a phone but the palette. Firefox and Safari.
- Nothing shows that a jump did nothing because there are no marks. A user who hasn't turned
  them on gets a key that does nothing, and the documentation is the only place that says why.
