# spec-001: A settings window

| | |
|---|---|
| Status | done (commits fa77767, 7c5dccd, d925863, 896b8dd, 3456d5f, 96d558f, 2026-11-19) |
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

## What was built

### Phase 1 (2026-11-19)

- **The action:** `client.settings`, **Settings…**, on Ctrl+,, in the palette and in the sidebar's ⋮ menu (`tono/src/RexActions.kt`, `RexApp.kt`).
- **The settings themselves:** `tono/src/Settings.kt`. `SettingsContent` is the one composable both apps show. `settingsLayout(width)` chooses Wide from 640 dp and Narrow below. Sections: Notifications, Terminal, About.
- **The desktop window:** `desktopApp/src/main.kt` shows a second `Window`, "Tono Settings", while `Tono.settingsOpen`. `rememberTono(client, settingsInOwnWindow = true)` makes the one `RexUiState` and `RexCommands` both windows use (`tono/src/TonoApp.kt`), so there is one client and one connection.
- **The web panel:** `SettingsPanel`, over the app at the page's right edge, at most 760 dp wide and the full height. On a page narrower than that it is the whole page.
- **Observable switches:** `RexClient.sounds`, `.notifications` and `.selection` are `StateFlow`s with setters that save the workspace. `RexUiState` sets them and follows them (`followSettings`), so the palette's toggles and the settings' switches are one thing.
- **About:** `Workspace.location` says where the workspace is kept, and `Keymap.configPath` is the `config.path` of the server's `client.keymap` answer.
- **Phones:** every row, switch line and button is at least 48 dp (`TOUCH_TARGET`). The browser's back goes from a section to the list and then closes the panel (`WatchBrowserBack`, history entries of the page's own). The Notifications section says what the browser reports, offers Allow Notifications only when the browser hasn't been asked, and where there are none says why: an iPhone or iPad not opened from the Home Screen, or a page not served securely.

### Phase 2 (2026-11-19)

- **The Shortcuts section** (`ShortcutSettings` in `tono/src/Settings.kt`), first in the list: a search field, the line about whose keys these are with a link to the Rex documentation's Keyboard Shortcuts page, how many were changed in Tono beside **Reset All Shortcuts**, and every action by category with its keys and where they come from (`Shortcut.sourceShown()`).
- **Recording** is backlog-042's panel, now `ShortcutRecorder` in `tono/src/ChangeShortcut.kt`, which the palette's Change Shortcut… and the settings both open. It is drawn in the settings' own window, and so are the questions it asks.
- **Reset:** a changed action has a reset button on its line (`resetShortcutAsking`, the recorder's own Reset to Default). `RexClient.resetAllShortcuts()` empties this client's layer, after `resetAllShortcutsQuestion`.
- **Search:** `filterShortcuts` matches every word against title, category, action name, keywords, keys as shown and source.
- **Read-only without a keyboard:** `RexUiState.keyboardSeen` is true on the desktop, and in a browser becomes true at the first key pressed with Ctrl, Alt or the system's key. Until then no line can be pressed and Reset All isn't offered.

### The layout after Rex for Mac's (2026-11-19, after phase 2)

- **Search Settings** is over the list of sections (`SETTING_ENTRIES`, `searchSettings`): a word lists the settings it finds, each leading to its section.
- **Server is first,** with the home server's label and state under it. It holds what About held; there is no About.
- **Then Terminal, Shortcuts, Notifications, Remote Hosts,** each with a drawing on a rounded square in one of the theme's colors (`SectionIcon`: the accent, green, blue, red, magenta).
- **Back and forward arrows** are in the heading where the list is beside the section (`SettingsState.back`, `.forward`). Where there is one column there is one way back, a step out at a time (`SettingsState.up`), which is also what the browser's back does on a phone.

### Phase 3: Remote Hosts (2026-11-19)

- **The section** is `tono/src/SettingsHosts.kt`: one card of lines (handle, picture, label over address, status, ⓘ), **Add Remote Host…** under it, and a host's details, which are a place within the section (`SettingsPlace.detail`), so the arrows and the phone's back go in and out of them. Editing and adding were inline here at first, and moved into a modal afterwards: see below.
- **Status** is "Connected", "Connecting…", "Reconnecting…" or "Couldn't connect" with a warning mark; the reason is in the details (`HostState.lastFailure`, new, which outlives the pause between tries).
- **Where a host comes from** is `RexClient.sourceOf`: the home server, its host list, the configuration file (with file and line), or this app. `RexClient.hostListings` carries what the list has for each host shown: id, every address, icon, source.
- **Edit** is `RexClient.changeHost`. A listed host is sent back whole by `hosts.put` with its id, with the fields this app doesn't read (`changedHostEntry`), and the server's refusal is shown under the label in its own words. A host of this app's alone is changed in the workspace.
- **Add** is `RexClient.addHostNow`, which the palette's Add Host… also runs, now with an icon.
- **Remove** asks with `removeHostQuestion`, the palette's words, now one function.

### Phase 4: Server (2026-11-19)

- **What a server says about itself:** `rex/src/ServerInfo.kt` reads `server.status`, `server.version` and `server.whoami` together (`RexHost.serverInfo`). `ServerDetails` in `tono/src/SettingsServer.kt` shows it for the home server in the Server section, and for any connected host in its details.
- **No local server:** when the home server is this machine's usual socket and an attempt on it has failed (`isMissingLocalServer`), the desktop app looks for `rex` on `PATH` and asks `systemctl --user` whether `rex.socket` is enabled and active (`localServerSetup`). `localServerOffers` picks Start Server, Start at Login, or the link to installing Rex on Linux. `runRex` runs the command only when its button is pressed, with nothing on its input, and what it printed is shown if it fails. A browser can't look, and offers nothing.
- **Tailscale:** state, name, address and account. **Sign In…** is offered while the device is `needs_login`, for the home server only, since the server takes `server.tailscale.login` over its own socket alone.
- **Client Info:** `RexClient.clientLabel`, kept as `client_label` in the workspace and sent with `client.set_label` to every connected server, and to each as it connects.

### Text entry in a modal (design-001), and Appearance (2026-11-19, after phase 4)

- **One modal for anything typed:** `FormDialog` and `FormField` in `tono/src/FormDialog.kt`. A dialog about 480 dp wide where there is room; a sheet over the whole window, with Cancel at its top left and Save at its top right, in the settings' narrow layout and in a narrow main window. Enter saves, Escape cancels, the first field has the keyboard, Save is greyed until the form can be saved, and cancelling a changed form asks first.
- **Renaming** a tab, a pane or a session is the same modal with one field (`tono/src/RenameTab.kt`).
- **Remote Hosts:** `HostForm` is the one form for **Edit…** on a host's details and for **Add Remote Host…**: label, addresses (one field each, with Add Another Address for a host in the home server's list), icon, and for Add the tailnet's servers inside it. The server's refusal goes under the field it is about (`fieldOfRefusal`) and the form stays open. The details page has no fields: status, where it comes from, addresses to read, Move Up and Move Down, Edit…, Remove Host….
- **Client Info:** the label is read in the Server section and changed in `ClientLabelForm`.
- **Phones:** the browser's back is Cancel while a form is open (`settingsBack`, one more step for `WatchBrowserBack`).
- **Appearance** (backlog-045's `AppearanceSettings`) is a section after Server, where the Mac has Interface and Themes, with its own drawing and search words. `TonoSettings` draws inside `TonoTheme`, so the settings window follows the theme chosen.

### Decisions made while building, none of them asked for

- **A changed form asks "Discard what you changed?" in itself,** where Cancel and Save were, not in a second dialog on top: Enter discards, Escape goes on editing. A second dialog over a form with a text field in it didn't get the keyboard in a browser.
- **Add takes one address.** More are added by editing the host afterwards, which is where the home server's list has an id to store them under.
- **Start Server is offered only for this machine's usual socket** (`REX_SERVER`, or the per-user one). `rex server start` starts the account's server at the per-user socket whatever `REX_SERVER` says, so with `REX_SERVER` pointing elsewhere the button starts a server this app won't find. Not handled.
- **A server's session count is the live one** this app already follows, not the one in the status it asked for once.
- **The rest of a server's details are asked for when they are shown,** and again when the connection is made anew. They don't follow `tailscale_status_changed`.
- **An empty client label goes back to the label the app started with:** in a browser, the name of the device the page is on.
- **The order is this app's.** `hosts.list` says its hosts are "sorted by label, ignoring case, then by id", and `hosts.put` takes no place, so the server keeps no order. It is kept as `host_order` in the workspace file (`RexClient.hostOrder`), and `sidebarOrder` follows it: the home server first, then the hosts as arranged, reachable or not, then any that weren't there to arrange, by the old rule.
- **A found tailnet server fills in the form; it isn't added at once,** unlike the palette. A mistaken press on a page where lines lead somewhere should add no machine. (A test click of mine did exactly that: see "What went wrong".)
- **The home server can't be edited here.** Its details say what it is and where it is reached.
- **A host of this app's alone has one address and no icon:** the workspace keeps a label and an endpoint.
- **The warning mark is in the theme's error color,** which is what the sidebar draws a host that is down in. The Material scheme the theme makes has no warning color of its own.
- **Where there is one column, a host's line has no handle and no ⓘ:** the whole line opens the details, and Move Up and Move Down are there. Dragging is for the wide layout.
- **Adding a host goes to it in the main window,** as the palette's Add Host… does.

- **A desktop browser starts read-only too,** until the first such key, which Ctrl+, itself is. Opened with the mouse, the list says "Press one on a keyboard here, and this list can be changed."
- **The search field isn't given the keyboard when the section opens,** so that a phone's keyboard doesn't come up unasked.
- **Escape with a recorder or a question open closes that, not the panel.**

- **Terminal Notifications is Program Messages.** `NotificationSwitches.programMessages` is what the action `client.setting.notifications.macos.terminal_notifications` already switched, so there is one switch, under Rex for Mac's name.
- **The kinds are under the master switch.** With Show System Notifications off, Blocked, Error, Done and Terminal Notifications are faded and can't be turned, and still show how they are set. That is how `NotificationSwitches.want` reads them.
- **Groups and wording** are Rex for Mac's where it has the setting: Program Activity, System Notifications, Selection, "Automatically copy selected terminal text to the clipboard."
- **Turning a switch on from the settings does what the palette does:** a sound is played, a notification is shown, or the browser is asked.
- **Escape closes the panel and not the window; Ctrl+W closes the window and not the panel.** In a browser tab Ctrl+W never reaches the page.
- **The window opens at 760×640 dp times the monitor's scale,** since the toolkit counts in pixels and `OmarchyWindowScale` draws at the monitor's scale. The main window's 1280×820 isn't scaled so, and was left alone.
- **No row is wider than 720 dp,** so that on a full workspace a switch stays near its name.
- **The section chosen is remembered** while the app runs, so opening the settings again shows it.
- **Closing the panel over a narrow window's sidebar leaves the sidebar,** where the palette closing goes to the terminals.
- **The browser's back closes the panel in any browser,** not only on a phone.

### What was checked in phase 1

- **Unit and render tests:** `rex/test@jvm/SettingSwitchesTest.kt`, `tono/test@jvm/SettingsTest.kt`, `SettingsRenderTest.kt`. The pictures, each section at 420, 700 and 1100 wide, are in `build/settings-renders/` and were looked at.
- **Desktop, nested Xwayland, real keys and clicks:**
  - Ctrl+, in the main window opened one window titled "Tono Settings", class `tono`, 760×640, with no window type and no transient-for hint;
  - Ctrl+, again, with the main window raised over it, opened no other and brought it back on top;
  - a click on Copy on Selection wrote `copy_on_selection: true` to the workspace file, and the main window's palette then said "Terminal · on"; choosing that entry in the palette turned the switch off in the settings window as it stood open;
  - Ctrl+W closed the settings window and left the main one. So did WM_DELETE_WINDOW sent to it. Sent to the main window, it ended both and the process;
  - About showed the workspace file, the home server, API revision 12 and `~/.config/rex/init.lua`.
- **Web, headless Chromium, 1400 wide:** Ctrl+, reached the page (`defaultPrevented`) and opened the panel; a click on Play Activity Sounds wrote `sounds: false` to the browser's storage, the palette said "Notifications · off", and it was still off after a reload; Escape closed the panel.
- **Phone size, Chromium's phone emulation (390×844, scale 3, touch):** Settings… chosen in the palette filled the screen with the list; a tap on Terminal opened it; a tap on the words of Copy on Selection turned its switch; the browser's back went to the list, and again closed the panel, on the same page. After closing with the panel's own ✕, one more back left the page, as it would have before the settings were opened.

### What was checked for the modal and Appearance

- **Unit and render tests:** which field a refusal goes under, a form's keys being its own and back cancelling it (`SettingsTest`); the forms as a dialog and as a sheet at 420, 700 and 1100; as a sheet, Escape closing an unchanged form, asking for a changed one, Enter saving and the details following the new label, and Escape then Enter discarding (`SettingsHostsRenderTest`); the settings' own window drawn in a chosen light and a chosen dark theme (`SettingsRenderTest`).
- **Web app, headless Chromium 1400 wide, scratch home server, a made-up tailnet list:**
  - Add Remote Host… opened the dialog with the address field focused; choosing a found server filled address and label; an icon and Enter stored it in the scratch list with that icon;
  - Edit… opened with the label selected and Save greyed; a label another host has was refused under the label in the server's words and the dialog stayed; a new label saved and the details followed it; Add Another Address and a second address saved, both under the same id;
  - Escape on a changed form asked in the form, Escape again went on editing, and Escape then Enter discarded, with nothing stored;
  - Client Info's Edit…, a label typed and Enter: `rex client ls` on the scratch server showed Tono labelled so.
- **Phone size, Chromium's phone emulation:** Edit… on a host's details opened a sheet with Cancel and Save at the top; after typing, the browser's back asked "Discard what you changed?" there, a tap on Discard closed it, and back again went to the list of hosts, on the same page.
- **Desktop app, nested Xwayland, scratch home server, stand-ins only on `PATH`:** Edit… opened the dialog inside the settings window; a label typed and Enter stored it on the scratch server and the details followed. Appearance listed the themes, and choosing Catppuccin Latte redrew the settings window and the main window in it, and wrote `"appearance": {"theme": "catppuccin-latte"}` to the test's workspace file.

Not checked: the rename dialogs as a sheet in a narrow main window (they are the same `FormDialog`, drawn; not pressed through); a dialog's keys in an offscreen picture, which doesn't hand a dialog the keyboard.

### What was checked in phase 4

- **Unit tests:** the three answers read into one, from what the real Local server answered on 2026-11-19 with the names changed, and from a server that says less; the client label kept and cleared (`rex/test@jvm/SettingSwitchesTest.kt`); what is offered for each state of the machine, its wording, when a server counts as missing, listeners and process in a line (`SettingsTest`).
- **Render tests:** a server's details from a made-up answer with Tailscale on, and the Server section with no rex, with rex and the unit off, and with the unit on, at 420, 700 and 1100. Pictures `server-*.png` were looked at.
- **The real Local server, by the command line only:** `rex api call server.status`, `server.version` and `server.whoami` gave the fields the parser reads, in the shape the unit test has. The app was not run against it: an app whose home is the real server connects to every host in Mase's list.
- **Web app, headless Chromium, scratch home server:** the Server section showed its version, API revision, start time, account and process, sessions, listener, Tailscale off, and its configuration file's path and generation.
- **Desktop app, nested Xwayland, with a `PATH` holding only stand-ins of mine for `rex`, `systemctl` and `tailscale`:**
  - home on a socket nobody listened on, and no `rex` on `PATH`: "No Rex Server Here … Rex isn't installed", and only the link to installing it;
  - the same with the stand-in `rex` and a `systemctl` that says the unit is off: both buttons, and nothing run by looking (the log of calls had the two `systemctl` questions and no `rex`);
  - Start at Login, whose stand-in fails: "It didn't work. rex said:" and its line;
  - Start Server, whose stand-in runs a scratch `rex server run` on that socket: within seconds the section said Connected and showed the new server's details, and the card was gone. Mase's server kept its pid and its sessions throughout.
- **Desktop app, same display, scratch home with a second scratch server as a connected remote:** the layout after the Mac's, Remote Hosts with a connected host, that host's details with what its server says of itself, and a host dragged by its handle with the mouse to the top, which wrote `host_order` to the workspace file and reordered the sidebar.

Not checked in phase 4: Tailscale Sign In… (no scratch server had Tailscale on; only the reading of `needs_login` and its link is tested); the real `rex server start` and `rex server install`, on purpose; a machine without systemd.

### What was checked in phase 3

- **Unit tests:** moving a host before another, up and down; the order heard, kept and read back; a host of this app's alone renamed and readdressed, keeping its place and staying selected; refusals said in words; a changed host sent back whole (`rex/test@jvm/HostChangesTest.kt`); the sidebar's order with and without an arrangement, status and source wording, icon names, the places within the section (`SettingsTest`).
- **Render tests** (`SettingsHostsRenderTest`): a host's line in each of the four states and what each of the four sources says, at 420 and 700; the section, a host's details, the home server's details and the add form at 420, 700 and 1100. Pictures `hosts-*.png` were looked at.
- **Web app, headless Chromium 1400 wide, against a scratch Rex server of my own** (`rex server run --listen unix:///tmp/settings-window/rex/home.sock`, its own state directory and an `init.lua` declaring one host; the page's server started with `REX_SERVER` pointing at it):
  - the list showed the declared host and one stored with `hosts.put`, with their icons;
  - in a host's details, a new label, a second address and another icon were saved, and `hosts.list` on the scratch server had all three under the same id; the details stayed with the host under its new name;
  - a label another host has was refused in the server's words: `conflict: the label "declared" is already used by the host at … declared at init.lua:2`;
  - Move Up wrote `host_order` to the page's storage and the sidebar followed; dragging a host by its handle to the top did the same;
  - a host stored from the command line (`rex api call hosts.put`) appeared in the open list and in the sidebar at once;
  - Add Remote Host… with a found server chosen (from a stubbed tailnet list), an icon picked and Add Host pressed stored it in the scratch list with that icon;
  - Remove Host… asked in the palette's words and, on Enter, the host left the scratch list.
- **Phone size, Chromium's phone emulation:** Remote Hosts in the list of sections, a tap on a host's line opening its details (no handle, no ⓘ), the declared host's details saying so, and the browser's back returning to the list.

Not checked in phase 3: the desktop app in a window (done with phase 4's desktop check, above); a connected remote host in the web app, which the relay refuses on a loopback address; saving a change to a host the configuration file declares; editing addresses with a real on-screen keyboard; Mase's own host list, on purpose.

### What went wrong

During the phase 3 web check a test click meant for the form's address field landed on a line of "On Your Tailnet", which was the real tailnet. The form added that server (spacepandaiii) to the scratch host list and the test page connected to the real machine for two or three seconds. Nothing was typed there and nothing of Mase's was changed; the orchestrator confirmed its session was intact. Since then a found server only fills in the form, and every test page is given a made-up tailnet list.

### What was checked in phase 2

- **Unit and render tests:** search and sources, Reset All and its question, read-only until a key is seen (`SettingsTest`); a click on a line, Ctrl+J and Enter recording a shortcut in an offscreen settings picture, and no line answering while read-only (`SettingsRenderTest`). Pictures `shortcuts-*.png` were looked at.
- **Desktop, nested Xwayland:** in the settings window, searching "ui showcase", clicking its line, Ctrl+J and Enter wrote the binding to the workspace file, and the main window's palette showed UI Showcase with Ctrl+J. A click on the line's reset button emptied the layer again.
- **Web, headless Chromium, 1400 wide:** the same recording, kept in the browser's storage; Ctrl+K recorded for the same action asked "…is already a shortcut" and changed nothing on Escape; the line's reset, and Reset All Shortcuts answered with Enter, each emptied the layer.
- **Phone size, Chromium's phone emulation:** Shortcuts opened read-only and no tap on a line did anything; a tap on the search field put the page's focus in a text input, and typing "split" filtered the list; after Ctrl+J was sent as a key, the line with Reset All appeared and a tap on a line opened the recorder.

Not checked in phase 2: a real on-screen keyboard (emulation has none, so "the field stays in view above it" rests on the field being pinned over the list and on `page-keys.js` part 4); a configuration file with bindings of its own, as in backlog-042; opening the documentation link.

### Not known yet, as far as this found out

- **Bringing the window to the front:** on an X server with no window manager, `toFront()` raised the settings window over the main one. Whether Hyprland lets it take the focus (`focus_on_activate`) wasn't seen: that needs the real desktop.
- **A special workspace:** not tried.

### Left for Mase, on the real desktop

- Ctrl+,: that the settings window tiles beside the main one and looks right at a half, a third and a whole workspace.
- Ctrl+, again with the main window focused: whether the settings window takes the focus.
- Tailscale Sign In…, on a server whose device is waiting for it.

### Commits

On `agent/settings-window`, on main at 270ecf1:

- fa77767: phase 1, the window, the panel and the switches;
- 7c5dccd: phase 2, Shortcuts;
- d925863: the layout after Rex for Mac's;
- 896b8dd: phase 3, Remote Hosts;
- 3456d5f: phase 4, Server;
- the commit after it, which this note is part of: the modal for text entry, and the Appearance section.

Rebasing them onto main once more gives them new ids.
