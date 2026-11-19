# Changelog

What's new in Tono, for the people who use it. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/). A line that applies to one platform only says so.

## [Unreleased]

Everything since the repository was first published.

### Added

- **Select and copy text.** Drag to select, double-click for a word, triple-click for a line; Ctrl+Shift+C copies and Ctrl+Shift+A selects everything. Shift+drag selects even over a program that uses the mouse.
- **Copy on Selection and Primary Selection**, both off by default. With Primary Selection on, the middle button pastes what was last selected. In a browser, which has no primary selection, the clipboard stands in for it.
- **Paste or drop files and images.** Pasting a screenshot or dropping files on a pane uploads them to the terminal's machine and types their paths, ready for a coding agent.
- **Open links** with Ctrl+click, whether a program marked them as links or they're plain text. Holding Ctrl underlines a link and shows where it really goes. Only web and mail links are opened.
- **Search a terminal** with Ctrl+Shift+F, through the screen and its history, with a match count. Enter and Shift+Enter go to the next and previous match.
- **Jump between shell prompts** with Ctrl+Shift+Up and Down, once the shell marks its prompts. The guide has the two lines to add for bash and zsh.
- **Desktop notifications** when a tab you aren't looking at needs you, fails or finishes, and when a program asks for one. A new notification for a tab replaces the last one, and clicking it takes you to the tab.
- **Bells and progress on tabs.** A bell in a tab out of sight marks it until you look; the progress a program reports draws a bar under its tab.
- **The window is named after the tab:** `tab - session · host`.
- **Arrange panes:** drag the line between panes to resize them, or resize, balance, swap, move and rename them from the keyboard and the palette.
- **Manage tabs and sessions:** move a tab with Ctrl+Shift+PageUp and PageDown, close the other tabs, mute a tab's sounds and notifications, and rename a session.
- **Back and forward** between the tabs you've visited, with Ctrl+Alt+[ and ] or the mouse's side buttons.
- **A menu on right-click**, or Ctrl+Shift+Space, with what can be done to a pane. Shift+right-click sends the click to a program that uses the mouse.
- **Insert a path, or go to a directory:** browse the terminal's machine with Ctrl+Alt+I and type a correctly quoted path, or `cd` to a folder, without pressing Enter.
- **Underline styles:** curly, double, dotted and dashed underlines in the colour a program asks for, and overlines. Editors' error squiggles now show as squiggles.
- **Host icons** from the Rex host list, in the sidebar and the palette.
- **Change any shortcut** from the palette (**Change Shortcut…**). Shortcuts set in Tono apply to Tono only, and take priority over Rex's configuration file.
- **Settings**, with Ctrl+, or **Settings…** in the palette. On the desktop it's a window of its own, which tiles beside the main one; in a browser, a panel. Its sections are Server, Appearance, Terminal, Shortcuts, Notifications and Remote Hosts, with search across all of them.
- **Remote hosts in Settings:** see each host's status, then edit its name, addresses and icon, reorder, add and remove hosts.
- **Server details in Settings:** what your Rex server reports about itself (version, uptime, sessions, Tailscale), with help to start it when there's no local server.
- **Built-in themes and a terminal font** for when Omarchy isn't there: all of Omarchy's stock themes, and JetBrains Mono. With Omarchy, Tono still follows the desktop unless you choose otherwise.
- **Smooth Scrolling**, off by default, in Settings › Terminal: the view glides instead of jumping a few rows at a time.
- **The mouse wheel in pagers** such as `less`, `man` and `git log`, which scroll with it now.

### Changed

- **Settings and state are kept apart, where each platform expects them.** On Linux, that's `~/.config/tono/settings.json` and `~/.local/state/tono/state.json`. The old `~/.config/kotlin-test/workspace.json` is copied there once on first start and left as a backup.
- **Typing in a dialog happens in a modal** with Save and Cancel, the same everywhere: renaming tabs, panes and sessions, and editing hosts.
- **A host being connected to has a look of its own** (dimmed, with "Connecting…"). A host that lost its connection says "Reconnecting…" and lists nothing under it.

### Fixed

- **Scrolling long output is much smoother.** It no longer lays out the whole screen on every turn of the wheel, and stops when the wheel stops.
- **Scrolling inside a program on another machine** (an editor or a coding agent) no longer stutters and lags behind the wheel.
- **Switching tabs no longer leaves a blank tab** saying the server refused the terminal stream, which only a restart could clear.
- **A settings file with a mistake in it is no longer overwritten** with defaults, and settings a newer Tono wrote are kept by an older one.
- **The keys for moving between panes** no longer type stray characters into the shell when a tab has one pane.
- **Text stays in its cells:** selection and search highlights no longer drift away from their text towards the right of a wide terminal.
- **A host the web relay refuses** turns red at once instead of saying "Connecting…" for good.
- **Connections to a server that stops answering** are closed, on the desktop and through the web relay, instead of piling up.
- **The web relay notices a page that has gone**, such as a phone asleep, so the server stops counting it as a client.
- **The keyboard focus ring on dialog buttons** follows the button's shape.
