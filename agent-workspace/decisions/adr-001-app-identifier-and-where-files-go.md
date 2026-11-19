# adr-001: Tono's app identifier, and where it keeps its files

| | |
|---|---|
| Status | accepted |
| Date | 2026-11-19 |
| Decided by | Mase |
| Informed by | inv-003 (where Tono keeps its files), inv-002 (iOS and TestFlight), big-idea-001 |

## Decisions

1. **The app identifier is `dev.mg.tono`.**
   - Where it's used:
     - the bundle id on iOS and macOS;
     - the application id on Android;
     - the name of Tono's directory where a platform names directories by id (macOS's Application Support).
   - It sits beside the theme libraries' `dev.mg` namespace.
   - It can't change after the first TestFlight upload. inv-002 and inv-003 used `dev.mase.tono` as a stand-in, and this replaces it.
2. **On macOS, Tono's files go in Application Support only:** `~/Library/Application Support/dev.mg.tono/`. Nothing goes in `~/.config/tono/` there, even though some terminals use it.
3. **Font size is a setting,** kept in `settings.json` with the rest of the appearance, even though the zoom keys change it often.

Everything else follows inv-003's recommendation as written:
- two files, `settings.json` and `state.json`;
- on Linux, `~/.config/tono/` and `~/.local/state/tono/` (under `XDG_CONFIG_HOME` and `XDG_STATE_HOME`);
- on iOS, the app's Library/Application Support; on Android, `filesDir`; on the web, two `localStorage` keys;
- no new library;
- `--workspace FILE` unchanged;
- a one-time migration that leaves the old file as a backup.

## Consequences

- The agent rule that "the configuration path stays as it is" ends. The rule becomes: Tono's files live where adr-001 says.
- `iosApp`'s Xcode project and `androidApp`'s `applicationId` (both `org.example.kotlintest` today) move to `dev.mg.tono` when each platform is worked on. Moving them is part of that work, not this decision.
