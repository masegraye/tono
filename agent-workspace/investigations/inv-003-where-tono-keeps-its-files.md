# inv-003: Where Tono should keep its files on each platform

| | |
|---|---|
| Status | in-review |
| Date | 2026-11-19 |
| For | backlog-049, big-idea-001 |
| Looked at | `rex/src/Workspace.kt`, the `rex`, `tono`, `server`, `desktopApp` and `packaging` code, Mase's own files (listed and their keys read, nothing changed), the XDG specification, Apple's, Android's and WebKit's documentation, the Rex documentation, and the repositories and documentation of the Kotlin libraries compared below |
| Not done | Nothing was built or run. The klibs MCP server was not available in this session, so libraries were looked up in their repositories, Maven and documentation directly |

## Summary

**Recommendation: split the one file into two, under the name `tono`, in each platform's own places, with no new library.**

- **Two files.** `settings.json` holds what a person chose. `state.json` holds where they were. Both stay readable JSON with today's key names.
- **Linux:** `~/.config/tono/settings.json` and `~/.local/state/tono/state.json`, following `XDG_CONFIG_HOME` and `XDG_STATE_HOME`. This is the same split Rex makes (`~/.config/rex/`, `~/.local/state/rex/`).
- **macOS:** both files in `~/Library/Application Support/<bundle id>/`.
- **iOS:** both in the app's `Library/Application Support/`. **Android:** both in `filesDir`. **Web:** two `localStorage` keys, `tono.settings` and `tono.state`. **Windows**, if ever: `%APPDATA%\Tono\` and `%LOCALAPPDATA%\Tono\`.
- **One identifier**, chosen once, for the Apple bundle id and the Android application id. inv-002 suggested `dev.mase.tono`; this document uses it as a stand-in. It is Mase's to choose, and it can't change after the first TestFlight upload.
- **No library.** The seam already exists (`WorkspaceStorage`, `defaultWorkspace()`). Each platform needs ten to thirty lines to name its directories. No library found knows all of Tono's targets and the config/state split.
- **`--workspace FILE` stays as it is:** everything in one file, for tests, demos and subagents. No script changes.
- **Migration** copies the old file's contents into the two new ones on first start and leaves the old file untouched as the backup.

Two things found on the way that matter for "never losing a setting", both from reading the code, not from running it:

1. **A settings file with a typo in it is overwritten with defaults at the next start.** `Workspace.load` treats an unreadable file as an empty workspace (`Workspace.kt:211`), and `RexClient`'s `init` then saves at once because there are no hosts (`RexClient.kt:244`).
2. **Keys Tono doesn't know are dropped at the next save,** because `save()` writes only its own fields. An older Tono would drop a newer one's settings.

## 1. What Tono stores today

### The workspace file

One JSON file, `$XDG_CONFIG_HOME/kotlin-test/workspace.json` or `~/.config/kotlin-test/workspace.json` (`rex/src@jvm/Platform.jvm.kt:99`, `defaultFile`). It is written through a temporary file and an atomic move (`WorkspaceFile`, line 86). The web build keeps the same JSON text in `localStorage` under `tono.workspace` (`rex/src@wasmJs/Platform.wasmJs.kt:85`). The desktop takes `--workspace FILE` to use another file (`desktopApp/src/main.kt:41`).

How it behaves:

- It is read once, at start. A change made by hand while Tono runs is overwritten at the next save.
- It is saved whole on every change, including every switch of tab, session or host (`RexClient.kt:533` to `585`). So the file that holds the settings is rewritten many times an hour for reasons that have nothing to do with settings.
- The default path accepts any non-empty `XDG_CONFIG_HOME`. The specification says a relative path there must be ignored.

Its fields, and what kind each is:

| Key | What it is | Kind | Shared across devices? |
|---|---|---|---|
| `hosts` | Servers to connect to: a label and an endpoint each. Used only where there's no local Rex server to keep the host list (`docs/using-tono.md:18`) | settings | Yes, and already is where a Rex server keeps the list. On iOS, with no local server, this is the only list |
| `host_order` | The order hosts are listed in, once someone arranged them | settings | Could be; follows `hosts` |
| `notifications` | Five switches | settings | Could be |
| `sounds` | Activity sounds on or off | settings | Per device |
| `terminal` | Copy on selection, primary selection | settings | Per device: a Mac and a browser have no primary selection |
| `shortcuts` | Shortcuts changed in this app | settings | Per platform family: Control here, Command on a Mac |
| `appearance` | Theme, font family, font size, font weight | settings | Theme could be. Fonts are per device. Font size is also changed by the font size keys, so it is rewritten more often than the others |
| `client_label` | What this app calls itself to servers | settings | Per device, by its nature |
| `last_host` | The host that was showing | state | No |
| `sessions` | For each host, the session that was selected | state | No |
| `tabs` | For each session, the tab that was showing | state | No |
| `collapsed_hosts`, `collapsed_sessions` | What is folded away in the sidebar | state | No |
| `muted_tabs` | Tabs kept quiet, by endpoint, session and tab id; the last 256 | state | No. A person chose them, but they name tabs that come and go on a server, and the list is trimmed by the app |

Mase's own file today has seven keys: `hosts` (1), `notifications`, `terminal` and `shortcuts` (2 changed), which are settings, and `last_host`, `sessions` (3) and `tabs` (1), which are state. It is 1.2 kB, mode 644.

Nothing in it is a secret: labels, endpoints, ids. If Tono ever holds one (a pairing token for backlog-048, say), it belongs in the platform's keychain, not in these files.

### Everything else that touches the disk

| What | Where | Kind | Note |
|---|---|---|---|
| The debug log (`RexLog`) | Memory only, 600 entries | log | Nothing is written |
| Logback, in `desktopApp` and `server` | Standard output only (`resources/logback.xml`) | log | Nothing is written |
| Uploads (pasted and dropped files) | Sent to the Rex server, which keeps them in its own `~/.local/state/rex/uploads/` | not Tono's | Tono writes no copy |
| Fonts and sounds | Read from the system (fontconfig, `/usr/share/sounds`). The web build fetches them from the page's server | read only | No cache of Tono's own |
| Themes | Read from Omarchy (`~/.local/state/omarchy/current`, `~/.config/omarchy/themes`) or built in | read only | |
| The `server` module | Reads `HOST`, `PORT`, `WEB_DIR`, `TONO_ALLOW_IDENTIFIED` from the environment. Keeps nothing | none | |
| Skia's native library | Unpacked by Skiko into `~/.skiko/` at first run (the default of its `skiko.data.path`) | cache | Not Tono's code, but Tono's doing. It is in the home directory, not in `~/.cache` |
| The installed app | `~/.local/share/tono/` (jar, `libghostty-vt.so`, a link to Java), `~/.local/bin/tono`, a launcher entry and icons (`packaging/install.sh`) | program | Not user data. It means `$XDG_DATA_HOME/tono` is already the program's place under `task install` |
| Window size and position | Not kept; the window opens at 1280 by 820 | | Would be state if it ever is |

So today Tono has settings and state, mixed in one file, and no cache or log of its own.

## 2. Each platform's conventions

### Linux: the XDG Base Directory specification

The specification names four per-user places ([basedir spec](https://specifications.freedesktop.org/basedir/latest/)):

| Variable | Default | For |
|---|---|---|
| `XDG_CONFIG_HOME` | `~/.config` | configuration files |
| `XDG_STATE_HOME` | `~/.local/state` | "state data that should persist between (application) restarts, but that is not important or portable enough to the user that it should be stored in `$XDG_DATA_HOME`". Its examples: "logs, history, recently used files", and "current state of the application that can be reused on a restart (view, layout, open files, undo history)" |
| `XDG_DATA_HOME` | `~/.local/share` | data files |
| `XDG_CACHE_HOME` | `~/.cache` | non-essential data that can be made again |

Also: a variable that is unset or empty means the default; a relative path in one is invalid and ignored; `XDG_CONFIG_DIRS` (default `/etc/xdg`) is where system-wide defaults are searched.

Tono's state matches the specification's own example for `XDG_STATE_HOME` almost word for word: the view and layout to come back to.

What terminals and Rex do:

| Program | Configuration | Other |
|---|---|---|
| Rex | `$XDG_CONFIG_HOME/rex/init.lua`, or `~/.config/rex/init.lua`, on Linux and macOS; `%AppData%\rex\init.lua` on Windows; `REX_CONFIG` names another file ([Rex docs](https://www.superlogical.com/rex/docs/customize/config#file-location), `rex config --help`) | State in `~/.local/state/rex/`: on this machine `host-endpoints.json`, `host-id`, `uploads/`, `servers/`, `terminal/`, `overlay/`, `tsnet/`, the directory 700 and the files 600. Its socket is under `XDG_RUNTIME_DIR` |
| Ghostty | `$XDG_CONFIG_HOME/ghostty/config.ghostty` or `config`. On macOS also `~/Library/Application Support/com.mitchellh.ghostty/config.ghostty` ([Ghostty docs](https://ghostty.org/docs/config)) | |
| kitty | `~/.config/kitty/kitty.conf`; `KITTY_CONFIG_DIRECTORY` or `--config` for another ([kitty docs](https://sw.kovidgoyal.net/kitty/conf/)) | Cache in `~/.cache/kitty`, or `~/Library/Caches/kitty` on macOS (`KITTY_CACHE_DIRECTORY`) |
| Alacritty | `$XDG_CONFIG_HOME/alacritty/alacritty.toml`, then `~/.config/alacritty/alacritty.toml`, `~/.alacritty.toml`; `%APPDATA%\alacritty\alacritty.toml` on Windows ([man page](https://github.com/alacritty/alacritty/blob/master/extra/man/alacritty.5.scd)) | |
| WezTerm | `~/.wezterm.lua`, or `$XDG_CONFIG_HOME/wezterm/wezterm.lua` ([WezTerm docs](https://wezterm.org/config/files.html)) | |
| foot | `$XDG_CONFIG_HOME/foot/foot.ini`, then `/etc/xdg/foot/foot.ini` ([man page](https://codeberg.org/dnkl/foot/src/branch/master/doc/foot.ini.5.scd)) | |

All of them use the program's name in lower case as the directory. None of them writes its configuration file itself; a person does. Tono differs: its settings are mostly written by its Settings window. That is the stronger reason to keep state out of that file.

### macOS

Apple's [File System Programming Guide](https://developer.apple.com/library/archive/documentation/FileManagement/Conceptual/FileSystemProgrammingGuide/FileSystemOverview/FileSystemOverview.html) says:

- **`~/Library/Application Support/`**: "app-created data files, configuration files, templates", "in a custom subdirectory whose name is that of your app's bundle identifier or your company".
- **`~/Library/Preferences/`**: "You should not create files in this directory yourself. Instead, use the NSUserDefaults class". A property list, not made for editing by hand.
- **`~/Library/Caches/`**: what can be made again. `~/Library/Logs/` for logs.
- A sandboxed app (the Mac App Store) gets the same directories inside its own container, `~/Library/Containers/<bundle id>/Data/`.

macOS has no separate place for state. What programs do there:

- Terminals from the Unix world use `~/.config` on a Mac too (kitty, Alacritty, WezTerm, and Rex's `init.lua`). Ghostty reads both `~/.config` and Application Support.
- JVM apps have no Java call for these directories. They build the path from `user.home`. JetBrains' IDEs use `~/Library/Application Support/JetBrains/`, `~/Library/Caches/JetBrains/` and `~/Library/Logs/JetBrains/` ([IDE directories](https://www.jetbrains.com/help/idea/directories-used-by-the-ide-to-store-settings-caches-plugins-and-logs.html)). Google's DataStore page says the same for a desktop app: "On macOS, you can instead use the `~/Library/Application Support/[your-app]` folder".
- **Rex for Mac:** the documentation gives only `init.lua`'s place. Where the Mac app keeps its own preferences is not documented.

### iOS

Each app has a container of its own (same guide):

- **`Documents/`** is for what the user made and should see; it can be shown in the Files app. Not for settings.
- **`Library/Application Support/`** is for the app's own files, hidden from the user and backed up. It may need creating.
- **`Library/Caches/`** is not backed up and may be emptied by the system.
- **NSUserDefaults** is the usual home for small preferences. **iCloud key-value storage** (`NSUbiquitousKeyValueStore`) syncs up to 1 MB and 1,024 keys across a person's devices ([Apple](https://developer.apple.com/documentation/foundation/nsubiquitouskeyvaluestore)).

### Android

- **`context.filesDir`** is the app's private storage; **`cacheDir`** may be emptied; **`noBackupFilesDir`** is left out of backups ([app-specific storage](https://developer.android.com/training/data-storage/app-specific)).
- Google recommends **DataStore** over SharedPreferences for preferences ([DataStore](https://developer.android.com/topic/libraries/architecture/datastore)).
- Auto Backup copies `filesDir` and preferences to the person's Google account, up to 25 MB ([Auto Backup](https://developer.android.com/identity/data/autobackup)).

### Web

- **`localStorage`**: text, about 5 MB for a site, read and written at once with no waiting. **IndexedDB**: larger, structured, and every call waits ([MDN](https://developer.mozilla.org/en-US/docs/Web/API/Storage_API/Storage_quotas_and_eviction_criteria)). Tono's 1 kB of text suits `localStorage`, and `WorkspaceStorage` reads and writes without waiting.
- Storage belongs to the page's address. The same Tono opened as `http://127.0.0.1:8080` and as `https://machine.tailnet.ts.net` has two separate stores.
- Safari deletes everything a site's scripts stored if the site isn't used for seven days ([WebKit, 2020](https://webkit.org/blog/10218/full-third-party-cookie-blocking-and-more/)). A web Tono opened now and then from an iPhone could lose its settings this way.
- **Should settings live on Tono's own server (backlog-048)?** Not at first. On the web, the hosts already come from the serving machine's Rex server, so the setting that matters most arrives without storing anything. The rest (theme, switches, shortcuts) is small and differs by device. A server-side store means a page being able to write files on the desktop, which backlog-048 has yet to decide how to secure. If Safari's seven days turns out to hurt, the step after is the serving Tono keeping each browser's settings under its own state directory. That is for backlog-048 to take up.

### Windows, briefly

`%APPDATA%` (Roaming) follows a person between machines on a domain and is where configuration goes (Alacritty and Rex both use it). `%LOCALAPPDATA%` stays on the machine and is for state and caches ([known folders](https://learn.microsoft.com/en-us/windows/win32/shell/knownfolderid)). The directory is usually the app's name with a capital.

## 3. What the Kotlin Multiplatform ecosystem offers

Tono's modules build for `jvm` and `wasmJs` today; iOS (`iosArm64`, `iosSimulatorArm64`), Android and perhaps a native macOS target come later. With the Kotlin Toolchain, any of these libraries is a line in `libs.versions.toml` and one in a `module.yaml`; nothing about the toolchain favours one. What matters is that a library used from `rex/src` must exist for every platform the module lists.

| Library | What it is | Targets | Upkeep | Fit for Tono |
|---|---|---|---|---|
| [multiplatform-settings](https://github.com/russhwolf/multiplatform-settings) (russhwolf) | Key-value settings over each platform's own store: NSUserDefaults, SharedPreferences, `localStorage`, and on the JVM `java.util.prefs` or a `Properties` object | All of Tono's, wasmJs included | 2,200 stars. Last release 1.3.0, November 2024; last commit June 2025 | **Poor.** Not a file a person can read. On the JVM its usual backing, `java.util.prefs`, writes XML under `~/.java/.userPrefs` on Linux: neither XDG nor Tono's name. Nested data (hosts, shortcuts) would have to be flattened into keys |
| [androidx DataStore](https://developer.android.com/kotlin/multiplatform/datastore) | Google's store for preferences or typed objects, read as a Flow | 1.3.0 publishes android, jvm, iOS, macOS, Linux, js and wasm-js; the web side keeps to `localStorage` or `sessionStorage` | Google's, stable, current | **Poor.** The preferences kind writes a protobuf file (`.preferences_pb`), not readable. It gives no directory: the path is yours to supply on every platform, and Google's own desktop example uses the temporary directory. Everything waits (suspend), where Tono's workspace is read and written directly. It brings okio |
| [KStore](https://github.com/xxfast/KStore) (xxfast) | Saves a `@Serializable` object as JSON in a file (kotlinx-io), or in `localStorage` on the web | All of Tono's | 670 stars, 1.1.0 in June 2026, active | **Closest, but not needed.** It does what `WorkspaceFile`'s twelve lines do. It wants `@Serializable` models, where Tono builds and reads its JSON by hand so that a wrong value falls back alone. It gives no directory either; its documentation points to harawata's appdirs |
| [kotlinx-io](https://github.com/Kotlin/kotlinx-io) `SystemFileSystem` | Files in common code | All except the browser | JetBrains', 0.9.3, marked Alpha; the file part is marked experimental | **Useful later, and already here:** `rex` gets kotlinx-io-core 0.9.1 through Ktor (`./kotlin show dependencies -m rex`). It could read and write the file on iOS. No help with where |
| [okio](https://github.com/square/okio) `FileSystem` | The same idea, older and stable | All except the browser | Very active | Would be a second I/O library beside the one Ktor already brings |
| [harawata/appdirs](https://github.com/harawata/appdirs) | Names of the per-user directories | JVM only | 1.5.0, October 2025 | No state directory. JVM only. Brings JNA |
| [Kotlin-Multiplatform-AppDirs](https://github.com/Syer10/Kotlin-Multiplatform-AppDirs) (Syer10) | The same, for KMP | android, jvm, js, Linux, macOS and Windows native. **No iOS, no wasmJs** (its source sets) | 110 stars, 2.0.0 in June 2025 | Knows `XDG_STATE_HOME` only as the place for logs. Puts configuration on a Mac in `Application Support/config/<author> <name>`, which is nobody's convention |
| [FileKit](https://github.com/vinceglb/FileKit) | File pickers, plus `filesDir` and `cacheDir` | All; the directories aren't available on the web | 1,600 stars, very active (0.17.1 this week) | Its `filesDir` on Linux is `XDG_DATA_HOME`: no configuration or state directory. On iOS it is `Documents/`. A large library to take for two paths |
| [directories-jvm](https://codeberg.org/dirs/directories-jvm) | The `dirs` family for the JVM | JVM only | Moved to Codeberg; the GitHub repository is archived | JVM only |

**What JetBrains recommends:** nothing about where files go. I found no guidance on user data directories in the Kotlin Multiplatform or Compose Multiplatform documentation; the pages on packaging desktop apps don't mention them. For storing settings, JetBrains' [list of samples](https://kotlinlang.org/docs/multiplatform/multiplatform-samples.html) shows multiplatform-settings in most of them, and Google documents DataStore for KMP. Both treat settings as key-value data the app owns, not as a file a person might open. Tono's wish for a readable, hand-editable file, as Rex's `init.lua` is, puts it outside what those two are for.

**No library found does the one thing needed here:** name a configuration directory and a state directory on Linux, Application Support on Apple's platforms, and the app's private directory on Android. The code that does it is short and has no moving parts.

## 4. Recommendation

### The name

- **`tono`**, lower case, wherever a directory is named after the program: Linux, as every terminal above and Rex do.
- **The bundle identifier** on Apple's platforms, as Apple's guide says and Ghostty does. The same identifier is the Android application id. Written `dev.mase.tono` below. It replaces `org.example.kotlintest` in `iosApp` and `androidApp`, which inv-002 already lists as a step.
- **`Tono`**, capitalised, on Windows.

### The paths

| Platform | Settings | State | Cache and logs (nothing today) |
|---|---|---|---|
| Linux | `$XDG_CONFIG_HOME/tono/settings.json`, default `~/.config/tono/settings.json` | `$XDG_STATE_HOME/tono/state.json`, default `~/.local/state/tono/state.json` | `$XDG_CACHE_HOME/tono/`; logs in the state directory |
| macOS | `~/Library/Application Support/dev.mase.tono/settings.json` | `~/Library/Application Support/dev.mase.tono/state.json` | `~/Library/Caches/dev.mase.tono/`, `~/Library/Logs/Tono/` |
| iOS | `Library/Application Support/settings.json` in the app's container | `Library/Application Support/state.json` | `Library/Caches/` |
| Android | `filesDir/settings.json` | `filesDir/state.json` | `cacheDir` |
| Web | `localStorage["tono.settings"]` | `localStorage["tono.state"]` | none |
| Windows | `%APPDATA%\Tono\settings.json` | `%LOCALAPPDATA%\Tono\state.json` | `%LOCALAPPDATA%\Tono\Cache\` |

Details:

- **Linux:** an XDG variable counts only when it is an absolute path; otherwise the default. Directories are made on first write, mode 700 for the state directory, as Rex's is.
- **macOS uses Application Support, not `~/.config`.** big-idea-001 says platform conventions win, and backlog-050 already expects it. Tono's settings are written by its Settings window more than by hand, which is what Application Support is for. The Settings window shows the path, so a person who wants to edit the file can find it. Reading `~/.config/tono/` on a Mac as well, as Ghostty does, is possible later and not recommended now: two places to look is one more thing to explain.
- **iOS uses Application Support, not `Documents/`** (which is for the user's own files) and not NSUserDefaults (which would make iOS the one platform with a different format). No subdirectory is needed inside an app's own container.
- **Nothing is ever written beside the program:** not under `/usr`, not in `~/.local/share/tono/`, not in the app bundle.

### The split

`settings.json`: `hosts`, `host_order`, `notifications`, `sounds`, `terminal`, `shortcuts`, `appearance`, `client_label`.

`state.json`: `last_host`, `sessions`, `tabs`, `collapsed_hosts`, `collapsed_sessions`, `muted_tabs`.

- The key names and shapes stay exactly as they are, so each file is a subset of today's and the documentation's examples stay true.
- `settings.json` is then written only when a setting changes. Switching tabs writes `state.json` alone.
- Losing `state.json` costs nothing but the place you were. It can be deleted to start fresh.
- Font size sits with the other appearance settings although the font size keys change it. It is shown in Settings and a person expects it kept. If it proves a nuisance in a hand-kept file, it can move to state later.
- Two repairs belong with the split, because they are what "never losing a setting" needs:
  1. **A file that can't be read is never overwritten.** Tono starts with defaults, says so, and writes nothing to that file until it has been set aside (renamed with `.unreadable` and the time) or fixed.
  2. **Keys Tono doesn't know are kept** when it rewrites a file.

### The design: `expect`/`actual`, no library

The seam is already there, and stays:

- `WorkspaceStorage` (read text, write text, a location to show) is the interface; `defaultWorkspace()` is the `expect`.
- `Workspace` stays one object in memory. It takes two storages, one for settings and one for state, and `save()` writes whichever changed. Given one storage, it keeps both in it, as today: that is `--workspace FILE`, every existing test, and the format the migration reads.
- Each platform's `actual` names its two places:
  - **JVM:** one function from the environment and `os.name` to the directories, for Linux, macOS and Windows. It takes the environment as a parameter, as `defaultFile` does today, so it can be tested without touching anyone's files.
  - **wasmJs:** the two `localStorage` keys.
  - **iOS:** `NSFileManager`'s Application Support directory, with the file read and written through kotlinx-io or Foundation.
  - **Android:** `filesDir`, which needs the app's `Context` handed in at start.
- If files in common code are wanted later (a log, a cache), kotlinx-io is already among `rex`'s dependencies through Ktor.

### How Rex's configuration relates

- **`init.lua` is the Rex server's**, read by the server, and applies to every client of that server. Tono shows its path (Settings, from the server's answer) and never writes it. It stays in `~/.config/rex/`.
- **`settings.json` is this Tono's alone** and sits above it: a shortcut changed in Tono wins over `init.lua` for this client only (spec-001).
- **Hosts** belong to the Rex server's host list where there is a local server. Tono's `hosts` is the fallback, and on iOS the only list.
- Tono keeps to its own directories, `~/.config/tono/` beside `~/.config/rex/`, and writes nothing in Rex's.
- JSON stays. It is what Rex Rune uses for these fields, Tono has no interpreter to run a Lua file, and the file is one Tono itself writes.

## 5. Migration

On the desktop, at start, when `--workspace` isn't given:

1. **If `settings.json` exists in the new place, use the new files.** The old file is not looked at again.
2. **Otherwise, if `~/.config/kotlin-test/workspace.json` exists** (found the old way, through `XDG_CONFIG_HOME`):
   1. read it with the reader there is today;
   2. if it can't be read, leave it alone, start with defaults, and say so;
   3. write `state.json`, then `settings.json`, each through a temporary file. `settings.json` is written last because its presence is what marks the move as done: stopped halfway, the next start does it again;
   4. read both back and check they hold what the old file held, unknown keys included (they go to `settings.json`);
   5. **leave the old file exactly as it is.** It is the backup, and an older Tono still starts with it. Put a short `moved.txt` beside it saying where the settings went and that the directory can be deleted;
   6. write a line in the debug log, and show both paths in Settings.
3. **Otherwise** start fresh in the new places.

On the web the same, from `tono.workspace` to `tono.settings` and `tono.state`, leaving the old key. A page that had `tono.workspace` put there for it (the launch video's `web.sh`) still opens where it was told to.

Tono never deletes the old directory. `docs/install.md` says how to.

Tests: the migration is a function of an environment and a directory, so it is tested in a temporary directory: an old file alone, an old file that can't be read, new files already there, a stop between the two writes, keys Tono doesn't know.

### The AUR package

- The package owns `/usr/bin/tono`, the jar and `libghostty-vt.so` under `/usr/lib/tono/`, the launcher entry and icons under `/usr/share/`. The launcher script points there instead of `$XDG_DATA_HOME/tono`.
- **Nothing is written under `/usr` at run time,** and the package touches nothing in anyone's home: no install script, no default files. Tono makes `~/.config/tono/` and `~/.local/state/tono/` itself, on first write. Removing the package leaves them, as pacman does for every program.
- **Skiko** unpacks its native library into `~/.skiko/` by default. A package should ship the library and start Java with `-Dskiko.library.path=/usr/lib/tono`, so nothing is unpacked into the home directory. To be tried when the package is built (backlog-031).
- System-wide defaults in `/etc/xdg/tono/` are not needed.
- **`--workspace FILE` stays, meaning what it means now:** settings and state together in FILE, nothing read from or written to the usual places, no migration. Its help text says it is for tests and demos. A person who wants a second profile can use it too.

## 6. What changes elsewhere

When this is built:

| Where | What |
|---|---|
| `agent-workspace/readme.md`, the rule on renaming | Replace "The configuration path (`~/.config/kotlin-test/`) is under review…" with the decision: Tono's files are under its own name in each platform's places (this document), and the old path is only read once, to move from |
| `docs/using-tono.md` lines 18, 119, 280, 379 | `~/.config/kotlin-test/workspace.json` becomes `settings.json` in its new place, with a short section saying where the files are on each platform |
| `docs/install.md` lines 37, 40, 57 | The two files; what uninstalling leaves behind (`~/.config/tono`, `~/.local/state/tono`, and the old `~/.config/kotlin-test`) |
| `docs/development.md` lines 50 and 51 | The settings directory is no longer `kotlin-test`; the package name still is |
| `desktopApp/src/main.kt` lines 29 and 30, `packaging/tono` line 3 | The comments on `--workspace` |
| `packaging/install.sh`, `Taskfile.yml` (`uninstall`) | No change for `task install`. The AUR's launcher is a separate script |
| `rex/test@jvm/RexProtocolTest.kt` lines 210 and 211 | They assert the old default path; they become tests of the new places and of the migration |
| Other tests (`rex/test@jvm`, `tono/test@jvm`: thirteen files) | No change: they load a workspace from one file, which stays |
| `marketing/launch-video/record/setup.sh`, `agent-workspace/ops/subagents.md`, `Taskfile.yml` line 24 | No change: they pass `--workspace FILE` |
| `marketing/launch-video/record/web.sh` | Works unchanged through the migration; better changed to set `tono.settings` and `tono.state` |
| `tono/src/SettingsServer.kt` line 117 | The "Workspace" row shows one location; it should show both files |
| `iosApp` (bundle id, product name), `androidApp/module.yaml` (`applicationId`) | The identifier, when chosen (inv-002's step) |
| backlog-049, 031, 048, 050, 051 | Point here; 049 is built from this |

## Steps to build it

Each is backlog-sized. The first three are backlog-049 itself and could be one piece of work.

1. **Split the workspace in two, in the code only.** `Workspace` takes a settings storage and a state storage, writes each when it changed, and still works with one. Keep unknown keys. Never overwrite a file that couldn't be read. No path changes yet.
2. **The new places on Linux, and the migration.** The JVM function for the directories (Linux, with macOS and Windows written and tested from an environment, not yet run there), the move from `~/.config/kotlin-test/`, both paths in Settings.
3. **The web: two `localStorage` keys,** with the same migration.
4. **Documentation and the agent rule** (the table above).
5. **Choose the identifier** (`dev.mase.tono` or another). Mase's decision; needed before the first TestFlight upload and before macOS.
6. **In the AUR package (backlog-031):** the launcher pointing at `/usr/lib/tono`, and Skiko loaded from there.
7. **On each new platform, its `actual`:** iOS (with inv-002's platform work), macOS (backlog-050: check where `user.home` points for a sandboxed app), Android (backlog-051).
8. **Later, if wanted:** settings kept by the serving Tono for browsers (backlog-048); reading `settings.json` again when it changes on disk; settings shared across devices.

## Open questions for Mase

- **The identifier.** `dev.mase.tono`, something under `dev.mg` as the theme libraries are, or another.
- **macOS: Application Support only, or `~/.config/tono/` too?** Recommended: Application Support only.
- **Font size:** a setting (recommended) or state.
