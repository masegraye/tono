# backlog-027: Per-host icons

| | |
|---|---|
| Status | done (commits `d0c2aaa`, `1662da1`, 2026-11-18) |
| Area | Hosts and other clients |
| Size | small |
| Source | earlier sessions |

## What

Show the icon each host has in the home server's host list.

## Why

The sidebar tells this machine from a remote one and nothing more.

## Builds on

The `icon` field of `hosts.list`.

## Promote when

When the sidebar is next worked on.

## What was built

A host shows the icon the home server's host list gives it: in its heading in the sidebar, and
in the palette's Switch Host and Remove Host lists. Built by a worker.

What `icon` holds, read from the server's description of `hosts.put`: "the name of an icon for
clients to show next to the label. Rex doesn't interpret it." So a name, and not an emoji or
an image. Rex for Mac's Add Remote Host form offers seven and names them `globe`,
`server.rack`, `cloud`, `macbook`, `macmini`, `macstudio` and `desktopcomputer` (the
identifiers of its menu's items, in the parity captures kept with Rex Rune's source; no host
written by the Mac was at hand to read the stored value from). Rex Rune doesn't read the field:
it draws a monitor for a Unix socket and a server for anything else.

- `RexClient.hostIcons` is each host's icon by label, read again on every `hosts_changed`, so
  an icon changed elsewhere shows without a restart. A listed host that is the home server
  under another address gives its icon to the home server.
- `tono/src/HostIcons.kt` has the seven pictures, drawn in lines like the two there were
  (Material's core icons have none of them), and the names each answers to.
- The heading's states are as backlog-038 left them: dimmed and breathing while connecting,
  the error color when down, for every picture.

Checked:

- Offscreen renders on the desktop's toolkit (`HostHeaderRenderTest`): every picture in every
  state of a connection, that the seven differ, the fallback, and the palette's line.
- The web app in headless Chromium, against a scratch Rex server of the worker's own with a
  host list made for it: the pictures in the sidebar and in Switch Host, the home server
  taking the icon of the entry that is itself, hosts that were down and being connected to,
  and an icon changed and removed with `hosts.put` showing within three seconds.
- `task probe` against the same server, for the desktop's side of reading the list, with a
  remote host connected.

Decisions made while building, none of them asked for:

- Names are read loosely: case and punctuation are set aside, and plain words (`server`,
  `laptop`, `mini`, `studio`, `desktop`) are taken as well as the Mac's.
- An unknown name, an emoji or an address falls back to the picture hosts had before: a screen
  for this machine, a rack for any other.
- In the palette, hosts have pictures only in the lists that are all hosts. "Switch to Host: …"
  among the commands has none, since one line in ten would.
- A host down is drawn in the error color in the palette too.
- `task probe` prints each host's icon.

Not checked: the desktop app in a real window (its drawing is covered by the offscreen renders
only); a connected remote host in the web app, because the web relay refuses loopback
addresses, which is where the scratch servers were; a host list written by Rex for Mac.
Not built: choosing an icon when adding a host in Tono.
