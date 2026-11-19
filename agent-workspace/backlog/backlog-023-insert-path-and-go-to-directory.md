# backlog-023: Insert a path, and go to a directory

| | |
|---|---|
| Status | done (commits `1614078`, `d295d9a`, `b698d4e`, 2026-11-18) |
| Area | Typing and keys |
| Size | medium |
| Source | inv-001 |

## What

Browse the files and folders of the terminal's machine from the palette, and paste a quoted path, or `cd` to a folder, without pressing Enter.

## Why

Typing a long path on another machine.

## Builds on

`com.superlogical.terminal.list_dir`. Rex Rune's rules for quoting and for refusing odd names are in `docs/terminal.md`.

## Promote when

After the clipboard work.

## What was built

**Insert Path…** (Control+Alt+I) and **Go to Directory…** are in the command palette, on the
desktop and in a browser. Each lists the machine the focused terminal runs on, starting in its
working directory, in the palette's own frame. The path is typed at the prompt quoted, or
`cd` and the quoted folder, and Enter is never pressed. The guide has a section on it.

- `rex/src/PathBrowser.kt` has the rules, with nothing of the interface in it: quoting,
  what is refused, how the field's text splits into a folder and a prefix, and the rows for
  a listing. `tono/src/PathBrowser.kt` is the browser. The palette's frame and row were
  taken out of `CommandPalette.kt` so both draw with them; two palette modes open it, as
  Move Pane to Tab does.
- The listing is `com.superlogical.terminal.list_dir`, asked with an empty path first, so
  where the terminal is comes from the server and not from `pwd_changed`. The text goes
  through RexHost's `typeText`, to the pane that had the keyboard when the browser opened.

Checked by the worker:

- Quoting, by unit tests that hand 78 awkward paths (spaces, both quotes, `$`, backticks,
  `!`, globs, braces, `~`, leading dashes, backslashes, `;&|<>#`, several scripts, emoji)
  to real shells and compare the one word read back: sh and bash always, and dash, zsh and
  fish when they are on the PATH. They aren't installed here; the run with all five used
  copies unpacked under `/tmp`. Refusal, by tests over every control character, the
  invisible formatting characters and broken surrogate pairs.
- The browser, offscreen on the desktop's Compose with keys sent to it, over a made-up
  machine (`PathBrowserRenderTest`): opening, Down, Tab, Enter, Escape, hidden entries,
  folders only, a missing folder, an old server, no terminal, a terminal that went away.
- The web app, in headless Chromium against a real bash in a scratch session: Control+Alt+I
  and the palette entry open it; Down, Tab into a folder and Enter typed
  `'/tmp/insert-path/tree/a space/inner dir'` and ran nothing; names with `$`, a leading
  dash, a backslash, `*`, `'`, a space and non-ASCII letters each came back from `ls -d`
  as themselves; a name with a newline was not listed; Go to Directory listed folders
  only, typed `cd '…'`, and after an Enter sent separately `pwd` was that folder; Escape
  and choosing both gave the keyboard back to the terminal.

Decisions made while building, none of them asked for:

- **Keys.** Rex for Mac has Insert Path on Control+Command+I and Go to Directory on
  Shift+Command+G. Insert Path is Control+Alt+I: Control+Shift+I stays a browser's, for its
  developer tools, and the page would open both. Go to Directory has no key: Control+Shift+G
  and Control+Alt+G are Find Next and Find Previous (backlog-006), and the Mac has it on the
  same key as its own Find Previous. Rex Rune leaves it unbound too.
- **No Enter after `cd`**, as the task said and as Rex Rune decided; the Mac app presses it.
- **Backslashes are quoted differently from Rex Rune.** Rune leaves them inside the single
  quotes, which fish reads wrongly when two come together or one comes before a quote. Here
  a backslash is written `\\` outside the quotes, which sh, bash, dash, zsh and fish all
  read the same. Everything else is Rune's quoting.
- **Odd names are left out, and odd typed paths refused**, by Rune's list. A lone half of a
  surrogate pair is refused as well.
- The field is filled with the working directory once, on opening. Emptying it later lists
  the working directory again without putting the text back, so a new path can be typed.
- The selected folder's row says "Tab to open", where a command shows its key.
- An edit waits 60 ms before asking, and an answer for text since changed is dropped.
  A listing has 8 seconds, as in Rune.
- The debug log records that a path was typed and how long it was, never the path.

Not built: Rune's list of recently used paths, and a way to press Enter after `cd`.

Not checked:

- **Keys in the desktop app's real window.** One try on a hidden workspace, never focused,
  with keys sent to the window by address: the unfocused window took none of them, and
  nothing was typed. Control+Alt+I, Tab and Enter on the desktop are covered only by the
  offscreen test, which is the same code the web app ran.
- Hosts other than this machine's, a listing cut short by a real server, a server too old
  to list (tests only), a slow listing running out of time.
- csh and tcsh, which read `!` inside single quotes. ksh. Firefox and Safari.
- Typing into a program that isn't a shell, or inside a quote already open at the prompt.
