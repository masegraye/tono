# How it works

Tono is a Kotlin Multiplatform app. Its interface is built with Compose and its terminal emulation comes from
Ghostty. The Rex client underneath has no interface of its own. The same code runs in a desktop window and in a
browser.

## The protocol

A Rex server documents its own API at `GET /llms.txt` on its socket. The [rex](../rex/src) module implements it,
using one JSON control connection per server and one binary data connection for each terminal on screen.

## Terminal emulation

A terminal's stream starts with a snapshot in Ghostty's format. Tono uses Ghostty's terminal core, libghostty-vt,
to restore that snapshot and to interpret the output that follows.

- [native/](../native) builds the library from the Ghostty source pinned in [GHOSTTY.lock](../native/GHOSTTY.lock).
- [ghostty](../ghostty/src) calls it. On the desktop it goes through Java's foreign function API, using bindings
  generated from the library's headers. In a browser the library is a WebAssembly module, described in
  [Tono in a browser](web.md#what-the-server-does-for-the-page).

## Drawing

libghostty-vt reports which rows have changed. [TerminalView.kt](../tono/src/TerminalView.kt) draws their cells on
a Compose canvas in the Omarchy font and colors, and redraws in the new colors when the desktop's theme changes.

## Typing

Tono sends key events to the server as they came from the keyboard. The server applies key bindings and encodes the
keys for the terminal. Pasted text goes through libghostty-vt's paste encoder, which makes it safe and brackets it
when the program has asked for bracketed paste.

[RexActions.kt](../tono/src/RexActions.kt) lists what the app can do and the default key for each. The server's own
actions, such as Clear Terminal, show up in the palette too.

## Images

![An image shown in a terminal with the Kitty graphics protocol](images/terminal.webp)

Programs can show images with the Kitty graphics protocol, as `kitten icat`, file manager previews and plotting
tools do. libghostty-vt stores the images and works out where each one goes. Tono decodes PNGs for it and draws
them, scaled to cover the same cells as they do for the terminal's owner. Images are included in a terminal's
snapshot, so they're still there when you switch to another tab and back.

Tono only accepts pixels sent in the output itself. The parts of the protocol that name a file or shared memory to
read are turned off, so nothing a remote program prints can make your machine read its own files.

## The mouse

Programs that track the mouse, such as Neovim, htop and tmux, turn it on by setting terminal modes, which
libghostty-vt keeps track of. Its mouse encoder turns each click, drag, movement and wheel notch into the report
the program asked for, and sends nothing if the program didn't ask. When the program under the pointer isn't
tracking the mouse, the wheel scrolls the terminal's own history.

## The clipboard

A program can put text on your clipboard by writing OSC 52. Neovim does this when it yanks to the clipboard, and so
do tmux and the copy buttons in agents. Tono follows the same rules as Rex Rune:

- Only the terminal you're using can copy. That means the pane with keyboard focus, in the tab on screen, while the
  window has focus and neither the palette nor a dialog is open. Tono drops a copy from anywhere else, so a program
  you can't see can't replace what you were about to paste.
- Tono refuses text over 8 MiB. If several copies arrive at once, it keeps the last one.
- Tono never answers a program's request to read the clipboard, so nothing a program prints can get at what you've
  copied.

A browser only lets a page write to the clipboard when the page is served securely (localhost counts) and has
focus, and some browsers only allow it right after a key press or a click. When a browser refuses, the debug log
says so.

## Connections

Tono retries a dropped connection and reloads everything when it comes back. A terminal's stream is reopened from a
fresh snapshot instead of being resumed.

A machine that goes to sleep doesn't close its connections, so they look open for as long as nobody writes to them.
To catch this, Tono asks a server for its status once it has been silent for ten seconds. Any reply counts. If
nothing arrives in the next ten seconds, Tono treats the server as down. The code is `awaitSilence` in
[RexControl.kt](../rex/src/RexControl.kt).

## The keymap

Key bindings aren't built into the app. Tono sends its actions and their default keys to your home server, which
applies your Rex configuration on top and replies with the final bindings. Changes take effect when Rex reloads its
configuration.

## Tab names

Rex stores one label per window, and until someone sets it the label is a placeholder such as `Window 89abcdef`.
Each client decides what to show in place of the placeholder. Tono builds a name from the process the server
reports for the terminal, using the foreground program of the focused pane and that program's directory.

## Where things are

| Module | What it is |
|---|---|
| [tono](../tono) | The interface, shared by the desktop and the web |
| [rex](../rex) | The Rex client, with no interface: connections, sessions and live terminals |
| [ghostty](../ghostty) | libghostty-vt, as a shared library on the desktop and as WebAssembly in a browser |
| [desktopApp](../desktopApp) | Puts Tono in a window |
| [webApp](../webApp), [server](../server) | Put Tono on a web page and serve it |
| [omarchy-theme](../omarchy-theme), `-compose`, `-ktor` | Read the Omarchy theme, turn it into a Compose theme, and serve it to web pages |

[Working on Tono](development.md) covers the rest.
