# spec-001: A settings window

| | |
|---|---|
| Status | accepted |
| Promoted from | backlog-028 |
| Builds on | backlog-042 (Change Shortcut…), backlog-008 (notification switches), backlog-004 (selection switches) |
| Asked for by | Mase, 2026-11-19 |

## What

One place to see and change what can be chosen in Tono. It is opened from the command palette and, on the desktop, it's a window of its own that Hyprland tiles beside the main one.

## Why

Settings are palette toggles today, each found only by knowing its name. Some can't be reached at all except by editing the workspace file: the finer notification switches, for example. Shortcuts can now be changed (backlog-042), and that needs a list that can be seen whole.

## Decided

### Opening it

- An action, `client.settings`, called **Settings…** as in Rex for Mac and Rex for iOS. It's on **Ctrl+,** (the Mac has Cmd+,), and it's in the command palette.
- If Tono's sidebar has a menu (the ⋮ at its top), Settings… is in it too.
- Opening it while it's already open brings it to the front.

### The desktop: a window of its own

- **The window:**
  - It's a second top-level `Window` in the same `application`, sharing the one `RexClient`. There's no second process and no second connection.
  - Its title is **Tono Settings**, and it has the same window class (`tono`) and icon as the main window.
  - It's an ordinary window: no fixed size and no "dialog" or "utility" hint. Hyprland tiles it like any other window, normally side by side with the main window on the same workspace.
- **Closing:**
  - Closing it leaves the app running.
  - Closing the main window quits the app and takes the settings window with it.
  - Ctrl+W closes the settings window while it has focus.
- **Size:** it must look right at whatever size tiling gives it: a half-screen column, a third, or a full workspace. There's a default size for floating window managers (about 760×640 dp).
- **Layout:**
  - **Wide (about ≥ 640 dp):** a list of sections down the left, and the chosen section's settings to its right.
  - **Narrow:** one column. The section list comes first; choosing a section shows it, with a back control.
  - Both layouts scroll vertically and never horizontally.
- **Look:** it follows the Omarchy theme and font and the window scale, as the main window does (`OmarchyWindowScale`).

### What Rex for Mac's settings look like

Mase supplied two screenshots of Rex for Mac's settings, kept in `designs/`:

- **`designs/rex-mac-settings-sidebar.png`, the section list:**
  - **Search Settings** is a field at the top.
  - The first item is **Server**: the home server, with its state under it ("Running").
  - Sections follow in groups separated by space: Interface and Themes; then Terminal, Shortcuts, Integrations, Notifications and Remote Hosts; then Developer.
  - Each section has a small icon on a rounded, coloured square.
- **`designs/rex-mac-settings-remote-hosts.png`, Remote Hosts:**
  - The header has the section's name, with back and forward arrows beside it, as on the Mac.
  - The hosts are one card of rows. Each row has a drag handle (≡), the host's icon, the label over its address in a quieter line, and the status at the right ("Connected", or "Couldn't connect" with an orange warning mark). An ⓘ button opens that host's details.
  - **Add Remote Host…** is a button under the card, at the right.
  - An SSH section follows; it's backlog-024's, not this spec's.

Follow this layout, in Tono's theme, not the Mac's colours:

- **Section list:**
  - a search field at the top, which filters settings by name and keywords across sections;
  - a **Server** item first: the home server's label and its state in the sidebar's words. It holds what About holds today: Tono's version, the workspace file, the home server's API revision, and where Rex's configuration file is. About becomes this item.
  - then the sections, in the Mac's order where Tono has them: Terminal, Shortcuts, Notifications, Remote Hosts;
  - icons on rounded squares, coloured from the Omarchy theme's palette, one colour per section.
- **Names:**
  - the section is called **Remote Hosts**, and its button **Add Remote Host…**, as on the Mac;
  - a host that is down says **Couldn't connect**, with a warning mark in the theme's warning colour, and the reason in its details. The sidebar keeps 038's look.
- **Host details:** the ⓘ opens them. That's where Edit (label, addresses, icon), Try Again Now, Remove, and "where it comes from" live. Rows stay as clean as the Mac's. On a phone the whole row opens the details, and there's no separate ⓘ.
- **Navigation:** back and forward in the header, moving between sections and into and out of host details. In the narrow layout, back is the same control the Phones part describes.

### The web: a panel in the page

A browser can't open a window that tiles. So the web app shows the same settings composable as a panel over the app: full height, the same width rules, and closed by Escape or a close button. There's no `window.open`.

### Phones (the web app on a phone)

A phone gets the web app's panel. On a phone it has to work by touch, at phone size:

- **Size:**
  - when the app is in its narrow, one-thing-at-a-time layout (`RexUiState.narrow`), the panel fills the whole screen rather than floating over the app;
  - it uses the narrow settings layout: the list of sections, a tap opens one, and a back control returns to the list;
  - the browser's back gesture or button goes back too. It goes from a section to the list, then closes the panel, and never leaves the page; see `webApp/resources/page-keys.js` for how the page keeps the mouse's back button today.
- **Touch:**
  - every switch, row and button is a touch target of at least 48 dp;
  - a whole row toggles its switch, not only the switch itself;
  - nothing in the panel needs hover: no controls that only appear under the pointer.
- **The on-screen keyboard:**
  - typing in the Shortcuts search field brings up the on-screen keyboard;
  - the field stays in view above it (the page already shrinks to what the keyboard leaves; see `page-keys.js` part 4);
  - the panel closes the keyboard when it closes.
- **Shortcuts without a hardware keyboard:**
  - a key chord can't be recorded on a touch-only device, so there the Shortcuts section is a read-only list of what's bound and where from, with a line saying shortcuts are changed from a device with a keyboard;
  - with a hardware keyboard attached (an iPad with a keyboard, a phone with a Bluetooth one), recording works as on the desktop;
  - deciding which applies: read-only until a real key event arrives from a keyboard. A touch-only device never sends Ctrl or Alt chords, so no device detection is needed.
- **Notification permission:**
  - the Notifications section shows what the browser reports (allowed, blocked, not asked, or unavailable), and only offers the button to ask when asking can work;
  - iOS Safari allows web notifications only to a web app added to the home screen. Where they're unavailable, say so plainly instead of offering a button that does nothing.

The native iOS app (inv-002) isn't built yet. When it is, it shows the same settings content full screen with the narrow layout. The desktop-only parts drop out: the separate window, Ctrl+W, and the primary selection.

### Changes apply at once

- **No Save or Cancel:** a change takes effect in the main window immediately, and is saved to the workspace file as the palette toggles are saved today.
- **Both ways:** a change made elsewhere shows in the settings window at once, whether made through a palette toggle, Change Shortcut…, or the other window. Where settings are plain `var`s on `Workspace` today, give them observable state (for example `StateFlow`s on `RexClient`) rather than having the window poll.

### Sections in this spec

1. **Shortcuts** (read-only on a touch-only device; see Phones):
   - every action with its title and category, its current key or keys, and where each key comes from: Tono's default, the configuration file (with file and line), or Tono;
   - a search field at the top;
   - choosing an action records a new key, using backlog-042's code (`rex/src/Shortcuts.kt`, `tono/src/ChangeShortcut.kt`). The same Set, Remove and Reset to Default options apply, and so does the same question before taking a key from another action;
   - per action: reset. For the whole list: **Reset All Shortcuts**, which asks first;
   - a short line saying that keys set here are this client's alone, sit above Rex's configuration file, and how to change them for every client (`rex.bind` in `init.lua`), linking to the Rex docs.
2. **Notifications:**
   - Play Activity Sounds;
   - Show System Notifications;
   - the finer switches that live only in the workspace file today: Blocked, Error, Done, Program Messages. Read `NotificationSwitches` for exact names and meanings;
   - Terminal Notifications, if it's a separate switch;
   - in the web app, the browser's permission state, and the button that asks for it, as the palette command does.
3. **Terminal:**
   - Copy on Selection;
   - Primary Selection, with the web note: browsers have no primary selection, so there selecting copies to the clipboard.
4. **Remote Hosts** (phase 3; see below).
5. **About** (shown as the **Server** item at the top of the section list; see "What Rex for Mac's settings look like"):
   - Tono's version, and the path of the workspace file;
   - the home server and its API revision;
   - where Rex's configuration file is, when the server says.

   This section is small and plain.

Names and wording follow Rex for Mac's settings where a setting is the same (`reference/mac-settings.md` in the Rex docs). A setting with an existing `client.setting.…` action keeps that id.

### Out of this spec

These are later backlog entries, not this spec:
- the theme, font and colours: Tono takes them from Omarchy;
- the cursor style and padding: Tono has no such settings yet;
- muted tabs: the tab rows have them.

### Text entry: modals (design-001)

Decided on 2026-11-19, after phase 3 was built: anything that needs typing goes in a modal with Save and Cancel; switches and choices still apply in place. See `design/design-001-text-entry-in-modals.md`. For this spec:
- **Remote Hosts:** **Edit…** and **Add Remote Host…** open the same modal, holding the label, addresses and icon, with the tailnet list inside Add. The host details page keeps only state and actions, with no fields of its own.
- **Server:** the Client Info label is edited in a modal too.
- **Phones:** on a narrow layout the modal is a full-screen sheet with Cancel and Save at the top.

### Hosts

Asked for by Mase on 2026-11-19, as the most important part of the settings area. The model is Rex for Mac's **Settings › Remote Hosts**: add, reorder and manage remote hosts, see their connection status, and retry failures (`reference/mac-settings.md#remote-hosts-1` in the Rex docs). SSH hosts, SSH keys and port forwarding are in the Mac's section too, but they're backlog-024's and stay out of this spec.

Today the palette has Switch Host, Add Host (from the tailnet, or by address) and Remove Host. This section gathers them in one place, and adds what the palette can't do.

**The list:**
- Every host Tono connects to, in sidebar order, with:
  - its icon (backlog-027) and label;
  - its address or addresses;
  - its status in the sidebar's words (backlog-038: Connected, Connecting…, Reconnecting…, or down with the reason the last attempt gave).
- **Where each host comes from:**
  - the home server's host list, which every device following the home sees;
  - Rex's configuration file (`rex.host`, with file and line);
  - this app alone.

  This decides what a change touches, so say it on every host.
- The home server is marked, and can't be removed, as now.

**For each host:**
- **Try Again Now**, when it isn't connected.
- **Edit:**
  - the label;
  - the addresses, in the order they're tried;
  - the icon, from the seven that backlog-027 draws, or none. This is the icon picker 027 left out.

  For a host in the home server's list, an edit is a `hosts.put` with the host's `id`, so every device sees it. Editing a host declared in the configuration file stores the host the server is sent, which then takes the place of the declared one; say so before saving, as `hosts.put`'s documentation describes. Labels must be unique and non-empty: show the server's own error (`conflict`, `bad_request`) next to the field.
- **Remove**, asking first, with the words the palette uses today. Those say whether the host leaves the shared list or only this app.
- **Reorder:** by dragging, and by Move Up and Move Down buttons that work by touch and keyboard. The sidebar follows the new order. Find out whether the host list has an order the server keeps (`hosts.list`, `hosts.put`). If it does, use it; otherwise keep the order for this app in the workspace file, and say which in the spec's notes.

**Adding:**
- **Add Host…** opens the same choice as the palette's: Rex servers found on the tailnet, or an address typed in. Optionally it also takes a label and an icon.
- It goes where the palette's Add Host puts hosts today: read `RexClient` for whether that's the home list or this app's workspace. If the palette offers both, so does this.

**Live:** the list follows `hosts_changed` events, so a host added or edited on another device, or with `rex hosts`, shows up at once.

**Phones:** the same touch rules as the rest of the panel. Editing addresses works with the on-screen keyboard, and reordering by touch uses the Move Up and Move Down buttons; dragging is optional there.

## Phases

### Phase 1: the window and the switches

- `client.settings` and Ctrl+,.
- On the desktop, the second window: open, bring to front, close, Ctrl+W.
- On the web, the panel.
- The wide and narrow layouts, and the Notifications, Terminal and About sections.
- Observable state for the switches.

Both apps keep working, and the palette toggles still work.

Phase 1 includes the phone rules for the panel and the switches: full screen, touch targets, back, and notification permission.

### Phase 2: Shortcuts

The Shortcuts section on backlog-042's code: list, search, record, per-action reset, and Reset All. It's read-only on a touch-only device, and the search field must work with the on-screen keyboard.

### Phase 3: Hosts

The Hosts section as described above: the list with status and where each host comes from; Try Again Now, Edit (label, addresses, icon), Remove and reorder; and Add Host. It follows `hosts_changed`, and it works on a phone.

### Phase 4: Server (promoted from backlog-046)

Rex for Mac bundles its own server and manages it in Settings › Server. Tono doesn't bundle one, so its Server item shows what can be learned, and helps when there's no local server. Read backlog-046 for the reasons.

- **What the server says about itself** (`server.status`, `server.version`, `server.whoami`):
  - version and API revision;
  - running since (from `started_at`), the process id, and the account it runs as;
  - the number of sessions;
  - its listeners: the Unix socket, any TCP address, the login server;
  - Tailscale: state, its name on the tailnet, its HTTPS address, and the account;
  - the configuration file's generation.

  This is for the home server at the top of the section list. The same details for any connected host can be reached from its host details (phase 3's ⓘ).
- **No local server:** when the home server is the local socket and nothing answers, say so plainly, not just "Couldn't connect". Then:
  - find out whether `rex` is on `PATH`, and whether the `rex.socket` user unit is enabled or active (`systemctl --user is-enabled` / `is-active rex.socket`);
  - offer what fits: **Start Server** (`rex server start`), **Start at Login** (`rex server install`), or, if `rex` isn't installed, a link to the Rex docs for installing on Linux;
  - run nothing without the person pressing the button, and show the command's output if it fails;
  - desktop only; the web app's server runs beside a Rex server already.
- **Tailscale:** show its state. When it's signed out, offer **Sign In** (`server.tailscale.login` gives a link, opened in the browser). Turning Tailscale on or off and renaming it are later.
- **Client Info:** the label Tono gives itself in `rex client ls` (`client.set_label`), editable and kept in the workspace file. It's the Mac's Settings › Server › Client Info.
- **Checking:** run against the Local server, read-only, for the details. For "no local server", use a `--workspace` file whose home is a socket path that doesn't exist, with `rex` hidden from `PATH` and then present. Never stop, start or install Mase's real server: test **Start Server** only with a scratch server (`rex server run`/`start` with its own `--listen` socket and state directory).

Each phase is committed separately, with its tests, and leaves both apps working.

## How it is checked

- **Unit tests:** the layout choice by width, observable switches changing in both directions, and the shortcut list (search and sources).
- **Offscreen render tests** (as `tono/test@jvm/*RenderTest.kt`):
  - the settings content at about 420, 700 and 1100 dp wide, in each section;
  - one picture per section and width, kept under `build/` and looked at.
- **Desktop, with the nested Xwayland method** in `agent-workspace/ops/subagents.md`:
  - Ctrl+, sent to the main window opens a second window titled "Tono Settings" (`xdotool search --name`);
  - Ctrl+, again doesn't open a third;
  - a switch flipped in it shows in the main window's palette state, and in the workspace file;
  - closing it leaves the main window;
  - closing the main window ends both.

  There's no window manager in there, so tiling itself isn't checked there. Mase checks it by eye, on the real desktop.
- **Web, headless Chromium:** Ctrl+, opens the panel; switches change what the palette shows and are kept across a reload; Escape closes it.
- **Phone size, Chromium's phone emulation** (touch events, a 390×844 viewport, scale 3):
  - opening Settings… from the palette fills the screen;
  - tapping a section opens it, and back (the in-panel control, and the browser's back) returns, then closes, never leaving the page;
  - a tap on a row toggles its switch;
  - Shortcuts is read-only until a key chord has been seen;
  - every target measures at least 48 dp in the screenshot.

  A real phone is backlog-029's and not required here.
- **Hosts:**
  - test against a scratch Rex server of your own, never Mase's host list, as backlog-027 did: `rex server run --listen unix:///tmp/<you>/rex.sock` with its own state directory, and `REX_SERVER` or a `--workspace` file pointed at it;
  - check: add a host, edit its label, addresses and icon (seen by `rex hosts` from the CLI against the scratch server), reorder it (and see the sidebar follow), remove it, and see an edit made with `rex hosts` appear in the open settings at once;
  - run render tests of the list with hosts in each status and from each source.
- **Mase, by hand on the real desktop:** Ctrl+,, then check the window tiles beside the main one and looks right at that size.

## Not known yet

- Whether Compose Desktop's second `Window` can be brought to the front under Hyprland on demand. `toFront()` and `requestFocus` with XWayland may need Hyprland's `focus_on_activate`, which is on here. Find out, and write down what happens.
- Whether the settings window should open on the main window's workspace when the main window is on a special workspace. Hyprland decides by itself; note what it does.
