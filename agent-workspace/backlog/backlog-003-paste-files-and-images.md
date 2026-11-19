# backlog-003: Paste or drop files and images

| | |
|---|---|
| Status | done (commits `f0ef2b4`, `42d48a1`, `847c864`, 2026-11-18) |
| Area | Clipboard |
| Size | medium |
| Source | inv-001 |

## What

Pasting an image, or dropping a file on a pane, uploads it to the terminal's machine and types its path. This is how a screenshot gets into an agent.

## Why

Tono pastes text only.

## Builds on

`com.superlogical.terminal.upload_begin`, in the API since revision 11 (the servers here are on 12). Rex Rune's behaviour is in `docs/terminal.md` under Uploading files: nothing is typed unless every file arrived, a pasted image becomes `clipboard.png`, text wins when the clipboard has both.

## Promote when

After backlog-002, or sooner if pasting screenshots into agents is wanted first.

## Not known yet

Reading an image from the clipboard and taking a dropped file both work differently in a browser.

## What was built

Dropping files on a pane, and pasting copied files or an image, uploads them to the machine the pane's terminal
runs on, and their quoted paths appear at its prompt. On the desktop and in the web app. Described for users in
`docs/using-tono.md`, "Pasting and dropping files and images".

- `rex/src/Upload.kt`: a drop is one batch. Every file is reserved with `upload_begin`, then each goes up a data
  connection of its own (`rex.data.bytes.v1`) in 64 KiB messages, ending with an empty one. `RexHost.upload`
  starts it, and `RexHost.uploads` is what a pane shows. The desktop sends with Ktor; the web app sends from
  JavaScript, so a file's bytes go from the browser to its socket without passing through the app, and no
  faster than the socket takes them.
- `server/src/TonoRelay.kt` relays the upload stream for the web app.
- `tono/src/Uploads.kt` and its two platform files: what a paste makes of the clipboard, the drop target, and
  the highlight, progress and failure a pane shows. The drop target went on the pane in `RexApp.kt`, not into
  `TerminalView.kt`, which is unchanged.

What the server does, found by trying it on API revision 12 and not written in its documentation:

- **The server types the paths, and quotes them.** A client sends bytes and types nothing. So the quoting
  function from backlog-023 isn't used here, and no path is ever typed in part.
- Files land in `~/.local/state/rex/uploads/<server>/<block>/<upload>/<name>` and are removed when the block closes.
- A file may be 100 MiB at most (`too_large`, status 413). A data message over 64 KiB ends the connection.
- A file that is short, or long, of its promised size closes with status 4006, `data stream broke a rule`.
- Each connection closes with status 1000 once its own file is complete, not when the batch is typed.
- A batch whose files aren't all reserved within about a minute is aborted (`conflict`, 409), and a ticket lasts
  a minute. A connection that is open and idle for 100 seconds is still good.
- A batch with a file missing types nothing and keeps nothing.

Decisions made while building, none of them asked for:

- **All of a drop's files are reserved first and sent at once**, as in Rex Rune, because of the minute above.
  Each needs a connection, and a browser allows a page only so many, so a drop is 100 files at most.
- **The size limit is checked here too**, before anything is reserved, so the message names the file.
- **In the web app text always wins**, even over copied files: a page can't tell a copied file from a picture.
  On the desktop the order is Rex Rune's: copied files, then text, then an image.
- **The web app pastes two ways.** Control+Shift+V reads the clipboard itself (`navigator.clipboard.read`, then
  `readText` where that is missing or refused). A `paste` event with files and no text, which is what the
  browser's own menu sends, is taken as well. Text in a `paste` event is left alone, as before.
- Every drop of files on the page is taken, wherever it lands, because a browser's own answer is to leave the
  page and show the file.
- A failure is shown in the pane for twelve seconds, or until clicked, and says that nothing was typed.
  Progress is shown only after half a second.
- A file's name stays out of the debug log.
- Uploading claims the terminal, as pasting does.

Checked:

- `task test`: 15 tests of the batch, 10 of the desktop's sending against a small WebSocket server, 11 of
  what a paste or a drop makes of a clipboard, and 3 of the relay carrying an upload and ending it at the
  server when its page breaks off.
- The web app, in headless Chromium, against the Local server: a drop of three files with spaces, a quote and a
  `$` in their names arrived with the right bytes, and `sha256sum` given the typed paths printed each file's
  hash; a pasted image arrived as `clipboard.png` both ways; text and an image together pasted the text; a file
  over the limit, and one of two files failing part way, typed nothing and left nothing on the server; 90 MB
  arrived intact in about two seconds; a drop under the palette went nowhere.
- The desktop app, on a nested Xwayland with real keys: Control+Shift+V with only an image on that server's
  clipboard typed a `clipboard.png` path, and with text pasted the text. Both arrived seconds late, as
  everything does in a window that is never shown.

Not checked:

- **A drop on the desktop app.** In the nested Xwayland a drag from a Swing window ended with the drop refused,
  and nothing says whether that is the app or the set-up (no window manager, a window never drawn). It needs
  trying once with a real pointer and a file manager.
- A real phone, Firefox and Safari. Copied files on a desktop clipboard other than in unit tests. A server on
  API revision 10 or 11. A terminal on another machine: everything was the Local server.
