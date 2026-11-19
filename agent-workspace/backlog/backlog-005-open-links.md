# backlog-005: Open links with Ctrl+click

| | |
|---|---|
| Status | done (commits `906cf1a`, `4c621f4`, 2026-11-18) |
| Area | Reading a terminal |
| Size | medium |
| Source | inv-001 |

## What

Ctrl+click opens a URL, whether the program marked it as a link (OSC 8) or it is plain text. Holding Ctrl over one underlines it and shows where it really goes.

## Why

URLs in output can't be followed today.

## Builds on

`ghostty_grid_ref_hyperlink_uri` in `grid_ref.h`. Rex Rune opens `http`, `https`, `ftp` and `mailto` only, never `file://`, since output may come from another machine, and shows the real target so link text can't disguise it.

## Promote when

After backlog-002, which brings the plumbing for pointing at a cell.

## What was built

Control and a click opens a link, in the desktop app and the web app, from the same code. A
link is text a program marked as one (OSC 8), or an address written out in the output,
including one the terminal wrapped over several rows. While Control is held, the link under
the pointer is underlined, the pointer becomes a hand, and after a moment the address the
click will open is shown beside it. Only `http`, `https`, `ftp` and `mailto` addresses are
links at all.

Rex Rune's `docs/terminal.md` was the reference for the behaviour. Rex for Mac could not be
looked at.

How it is put together:

- `rex/src/TerminalLinks.kt` finds the link at a cell: `openableLink` says which addresses
  may be opened, `urlsIn` finds addresses in text, and `linkAt` reads a screen. All shared,
  and tested without a terminal (`TerminalLinksTest`).
- `TerminalPointer` decides what a click is, as it does for selecting. A Control+click on a
  link opens it and the program hears nothing of that click, press, drag or release; a
  Control+click anywhere else is what it would have been.
- `TerminalSession` keeps the link under the pointer in `link` while the view says where the
  pointer is (`pointAt`), and hands clicked addresses to the view in `opened`.
- Two functions were bound from libghostty-vt, on the desktop and in `ghostty-glue.js`:
  `ghostty_grid_ref_hyperlink_uri` for a cell's OSC 8 address, and `ghostty_grid_ref_row`
  with `ghostty_row_get` for whether a row is wrapped into the next.
- `openUrl` in `TonoPlatform.kt` opens the address: `xdg-open` on the desktop (`open` on a
  Mac), found on `PATH` and run with the address as its one argument and no shell; in a
  browser, `window.open` with `noopener,noreferrer`.

Checked by the worker:

- Unit tests, on the JVM: which addresses are opened and which refused, finding addresses in
  text (punctuation after one, parentheses, quotes, wrapped rows), links read off a real
  terminal, the pointer's decisions (`TerminalPointerTest`), and the desktop's opener run
  against a stand-in script (`OpenLinkTest`). `task test` passes.
- The web app in headless Chromium, against a scratch session, with `window.open` replaced
  by a recorder: Control+click on a written address, on the same address without Control
  (nothing), on the full stop after it (nothing), on an OSC 8 link whose text read
  `https://bank.example` (opened the address the program gave, not the text), on an OSC 8
  link to `file:///etc/passwd` (nothing), and on either row of a 178-character address
  wrapped over two rows (the whole address). Over a program tracking the mouse, a
  Control+click on a link opened it and sent the program nothing, and one elsewhere reached
  the program. Screenshots showed the underline and the address under each link, none over
  the `file:` link, both going when Control came up, and both appearing when Control went
  down with the pointer at rest. Once without the recorder: a new tab opened at the link's
  address.
- The glue's two new functions on their own, in Node.
- The desktop app, from this branch's jar, on a hidden workspace, never focused: it started,
  connected and showed the scratch session, with nothing in its output.

Decisions made while building, none of them asked for:

- **A link a program made leads only where the program said.** If that is somewhere that
  isn't opened, the cell is no link, even if its text is an address that would be.
- **A link that isn't opened isn't marked.** No underline, and nothing saying why. A
  Control+click on it is an ordinary click.
- **What is refused.** Every scheme but the four; a web or FTP address that names no host;
  any address with a blank, a control character, or a character that changes the direction
  of text or is invisible (these can make the address shown read as another); anything over
  4,096 characters.
- **Where an address in text ends.** At a blank, a quote, an angle bracket, a brace, a bar, a
  backslash, a line-drawing character or a wide character. Then `. , ; : ! ? ' *` at the end
  are taken off, and a closing `)` or `]` with no opening one inside the address. The scheme
  has to start a word.
- **The address is shown after 300 ms**, as Rex Rune shows its tooltip "after a moment", so
  that Control pressed for a shortcut over a link doesn't flash it. The underline is at once.
  A quick Control+click therefore opens a link before its address was shown.
- **Shift as well as Control selects**, as Shift does everywhere, and opens nothing.
- **Only the left button opens.**
- A clicked address is written to the debug log, with how opening it went.
- In a browser nothing can tell whether the new tab was allowed. Chromium allowed it.

Not checked, or not done:

- **A real Control+click in the desktop app's window.** It needs the real pointer over a
  focused window. Not covered by anything above: the window toolkit passing Control with the
  click, the hand pointer, and the hand-off to the real `xdg-open`. Left for a check by hand.
- Firefox and Safari. Safari is the one most likely to block the new tab.
- An address broken over rows by a program that draws its own lines (Neovim, tmux, an
  agent's interface) is two addresses, since only the terminal's own wrapping is known. So
  is one that begins above the top of the view: the part in view is taken as the whole.
- Windows has no opener. Touch has no Control, so links can't be followed on a phone.
