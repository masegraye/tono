# backlog-010: Name the window after the tab

| | |
|---|---|
| Status | done (commits 1eb16be, 2026-11-18) |
| Area | Knowing what happened |
| Size | small |
| Source | inv-001 |

## What

The window's title is `tab - session · host` rather than always "Tono".

## Why

It is what the window list, the workspace overview and a browser tab show.

## Builds on

Tab names already exist (`rex/src/TabNames.kt`). Rex Rune uses the same form.

## Promote when

Any time: it is small and stands alone.

## What was built

The desktop window's title and the browser tab's title say where the app is, and change as
that changes: `first - lunar-basin · Local`. The wording is in `tono/src/WindowTitle.kt`.
Built by a worker, who checked:

- On the desktop, a real window on a hidden workspace, never focused, its title read from
  the window manager: it named the scratch session's tab, session and host from the first
  moment, and followed a tab rename, a session rename, and the move to another session when
  the one showing was killed.
- In the web app, in headless Chromium, `document.title`: the same, and also going to another
  tab with Control+2, and a tab whose name was cleared (`bash - /tmp/x - session · Local`).

How the references do it: Rex Rune's title is `<tab> - <session> · <host>`, with the host
always there, and `Rex · <host>` before a session is chosen. Rex for Mac's is
`<tab> - <session>`, with no host (from Rex Rune's parity notes; the Mac app itself was not
looked at). This follows Rex Rune, as the entry asked.

Decisions made while building, none of them asked for:

- The host is always in the title, even when there is only one, as in Rex Rune.
- A host with no session chosen, as while it connects, is `Tono · host`. Only with no host
  at all is the title `Tono` alone.
- The tab is called what the sidebar calls it. A tab nobody named is its program and
  directory, written `vim - ~/rex` as Rex Rune writes that pair.
- Rex Rune names an unnamed tab by the title its program set (`vim notes`) unless told to
  prefer the program and directory. Tono doesn't read program-set titles for tab names
  anywhere yet, so the window title doesn't either; when the sidebar learns to, the window
  title follows, since both ask `HostState.nameOf`.
- The title does not change while the palette, a dialog or the showcase is open.

Not checked: Firefox and Safari; an installed web app's window; a window list or overview
looked at by eye (the title was read from Hyprland, not seen); more than one connected host
on screen.
