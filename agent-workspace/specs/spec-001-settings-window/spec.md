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

### The web: a panel in the page

A browser can't open a window that tiles. So the web app shows the same settings composable as a panel over the app: full height, the same width rules, and closed by Escape or a close button. There's no `window.open`.

### Changes apply at once

- **No Save or Cancel:** a change takes effect in the main window immediately, and is saved to the workspace file as the palette toggles are saved today.
- **Both ways:** a change made elsewhere shows in the settings window at once, whether made through a palette toggle, Change Shortcut…, or the other window. Where settings are plain `var`s on `Workspace` today, give them observable state (for example `StateFlow`s on `RexClient`) rather than having the window poll.

### Sections in this spec

1. **Shortcuts:**
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
4. **About:**
   - Tono's version, and the path of the workspace file;
   - the home server and its API revision;
   - where Rex's configuration file is, when the server says.

   This section is small and plain.

Names and wording follow Rex for Mac's settings where a setting is the same (`reference/mac-settings.md` in the Rex docs). A setting with an existing `client.setting.…` action keeps that id.

### Out of this spec

These are later backlog entries, not this spec:
- the theme, font and colours: Tono takes them from Omarchy;
- the cursor style and padding: Tono has no such settings yet;
- adding and removing hosts: the palette has it;
- muted tabs: the tab rows have them.

## Phases

### Phase 1: the window and the switches

- `client.settings` and Ctrl+,.
- On the desktop, the second window: open, bring to front, close, Ctrl+W.
- On the web, the panel.
- The wide and narrow layouts, and the Notifications, Terminal and About sections.
- Observable state for the switches.

Both apps keep working, and the palette toggles still work.

### Phase 2: Shortcuts

The Shortcuts section on backlog-042's code: list, search, record, per-action reset, and Reset All.

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
- **Mase, by hand on the real desktop:** Ctrl+,, then check the window tiles beside the main one and looks right at that size.

## Not known yet

- Whether Compose Desktop's second `Window` can be brought to the front under Hyprland on demand. `toFront()` and `requestFocus` with XWayland may need Hyprland's `focus_on_activate`, which is on here. Find out, and write down what happens.
- Whether the settings window should open on the main window's workspace when the main window is on a special workspace. Hyprland decides by itself; note what it does.
