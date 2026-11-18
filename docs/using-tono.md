# Using Tono

When Tono starts it connects to every Rex server you have, all at once, and puts you back where you were on each.
It creates and closes things only when you ask.

## Machines

Your machines are the local Rex server, plus every host in its host list. That list is where Rex itself keeps hosts
(`rex hosts`), so the two agree: a host you add in Tono is known to the `rex` command, and one you add with
`rex hosts add` appears in Tono at once.

- **Add Host…** (in the palette, the sidebar's menu, or at the end of the Switch Host list) offers the Rex servers
  on your tailnet that aren't hosts yet. Pick one, or type any address. A server that is down can be added too.
- **Remove Host…** takes one off the list.
- An address is `https://host` for a server reached over the network, or `unix:///path` for a socket. A Rex server
  with built-in Tailscale is a device on your tailnet, so its `https://` address works from any machine on it.
- Hosts listed in `~/.config/kotlin-test/workspace.json` are connected to as well. A host goes there only when no
  local server can keep it.

A machine that can't be reached shows its picture in red and nothing else. Tono tries it again every few seconds
until it answers; hover it for a button to try now. The reason is in the debug log.

A machine that goes to sleep or drops off the network doesn't say goodbye. Tono notices anyway: a server that has
been silent for ten seconds is asked whether it is there, and after ten more with no answer it is marked as down.
When it comes back, everything is read again.

## The sidebar

![The sidebar, with Control held so each tab shows its digit](images/sidebar.webp)

The sidebar is three levels of heading:

1. **Machines**: this one first, then the connected ones by name, then the ones that are down. Each has a small
   picture, a screen for this computer and a rack unit for the others.
2. **Sessions**, as small quiet headings.
3. **Tabs** under each session. A tab is what Rex calls a window, and tabs are what you go to, so they are the
   brightest thing there.

What you can do with it:

- **Click** a machine or a session to fold it away.
- **Hover** one and a plus fades in: a new session on that machine, or a new tab in that session.
- **Drag** a tab to move it within its session, or a session by its heading to move it among its machine's
  sessions. The new order is the server's, so every client sees it.
- The **buttons along the top** hide the sidebar, show only the tabs whose programs are active, and open the
  palette. Rest the pointer on one for its name and key. With the sidebar hidden, a button to bring it back appears
  when the pointer is in the window's top left corner.

## Tabs

A tab is called by the program in the foreground of its focused pane, with that program's directory beside it:
`nvim ~/dev/rex`. The name follows along as commands start and finish.

To name a tab yourself, double-click it or use **Rename Tab…** in the palette. That name shows in every client and
stays until you clear it. Save an empty name and the tab goes back to naming itself.

A session is never left with no tabs. Closing its last tab closes the session, as in Rex's Mac app, and Tono shows
the session next to it.

## Activity and sounds

![A tab whose program is working shows three dots in the sidebar](images/agents.webp)

Three dots beside a tab show what its program says it is doing:

- **rippling** while it works,
- **steady, in the accent color** while it waits for you,
- **in the error color** after a failure.

A folded machine or session shows the dots for whatever is inside it. Programs report this themselves, with the
escape sequence Rex defines for it (OSC 7501).

A sound plays when a program in a tab you aren't looking at starts waiting for you, fails, or finishes. They are
your desktop's own sounds, played with `mpv` as Omarchy plays them. The tab on screen stays quiet while the window has focus, and a burst of news makes one
sound, the most pressing. **Play Activity Sounds** in the palette turns them off and on.

## The line along the bottom

It says where you are, from the outside in: the machine, the session, the tab, and the size of its terminal. At its
right end are small buttons to split the pane right or down, and to zoom it.

## Shortcuts and the command palette

![The command palette](images/palette.webp)

Everything Tono can do is a command, and the commands, their names and their keys are those of Rex's Mac app, with
Control in place of Command, and Shift added where Control alone belongs to the terminal.

| Keys | What it does |
| --- | --- |
| Ctrl+Shift+P | Command palette: every command, plus sessions, tabs and machines to go to |
| Ctrl+K | Change session, on any machine |
| Ctrl+Shift+H | Switch machine |
| Ctrl+1 … Ctrl+8, Ctrl+9 | Go to that tab; Ctrl+9 is the last tab |
| Ctrl+Tab / Ctrl+Shift+Tab, Ctrl+PageDown / PageUp | Next / previous tab |
| Ctrl+Shift+T / Ctrl+Shift+N, or Ctrl+Alt+T / Ctrl+Alt+N | New tab / new session |
| Ctrl+Shift+D / Ctrl+Alt+D | Split the pane right / down |
| Ctrl+Alt+arrows | Focus the pane in that direction |
| Alt+] / Alt+[ | Focus the next / previous pane |
| Ctrl+Shift+Enter | Zoom the pane, or restore the layout |
| Ctrl+Shift+W / Ctrl+Alt+W | Close the pane / the tab, after asking |
| Ctrl+Shift+V | Paste |
| Shift+PageUp / Shift+PageDown | Scroll through history |
| Ctrl+= / Ctrl+- / Ctrl+0 | Larger / smaller / default text |
| Ctrl+Shift+/ | Show or hide the sidebar |
| Ctrl+Shift+. | Show only the tabs whose programs are active, or every tab again |
| Ctrl+` | Show or hide the debug log |

**Tabs are one list**, in sidebar order, across every session and machine. Control and a digit goes to that tab
whichever session it is in, and next and previous carry on from one session into the next. Hold Control and each tab
shows its digit. Tabs folded away are left out of the numbering, so folding what you aren't using brings the rest
within reach.

**Your own keys.** A bound key does not reach the terminal; every other key does. The keys aren't fixed in the app:
Tono tells your home server what it can do and what its defaults are, and the server answers with what each key
does, after applying your Rex configuration. So a key you bind in Rex's `init.lua` works here as it does in the Mac
app.

**The debug log** (Ctrl+`) is a panel along the bottom showing what Tono is doing: connections, what servers
report, the requests that change something, and the commands run. It never records what you type.

## Sharing a terminal with other clients

**Size.** A terminal takes its size from one client at a time, and the client someone is using is the one whose
size should count. So Tono claims the terminals on screen while its window has focus, and when you type, click or
move the pointer in one. If another client takes a terminal back, Tono leaves it alone until you use it again, so
two clients never fight over one. Without focus Tono only looks: the terminal is drawn at its owner's size.

**Which tab is showing** is each client's own choice. Two clients on one session share its terminals, so each sees
what the other types, but going to a tab in Rex's Mac app doesn't move Tono, nor the other way round.

## Not there yet

- Selecting text with the mouse and copying it.
- SSH hosts.
- Floating panes drawn as windows.
- Animated pictures (they show their first frame).
- Key sequences and modes in the keymap.
- Keys are named for a US layout. On other layouts some shortcuts may be read as the wrong key; what you type is
  still right.

The full list of what's planned is [the backlog](../agent-workspace/backlog/readme.md).
