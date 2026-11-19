# inv-002: What it takes to ship Tono on iOS through TestFlight

| | |
|---|---|
| Status | done |
| Date | 2026-11-18 |
| Looked at | `iosApp/`, the module files, the `rex`/`ghostty`/`tono` platform code, the Kotlin Toolchain 0.14 CLI, the tailnet, this machine |

## Summary

Shipping to TestFlight is mostly solved by the toolchain: `./kotlin publish testflight -m iosApp` builds, signs (automatic signing) and uploads, given macOS with Xcode and an App Store Connect API key. The work is elsewhere:

1. **A Mac to build on.** Nothing iOS builds on Linux: `./kotlin build -m shared -p iosArm64` fails at once in the toolchain's `xcodeEnvironment` task (`xcode-select` doesn't exist).
2. **An Apple developer account and an app record.** Mase's to do.
3. **Porting Tono itself.** `iosApp` today is the Compose showcase the project started from. `tono`, `rex` and `ghostty` are built for `jvm` and `wasmJs` only, and each needs an iOS side. The largest piece is the terminal library, libghostty-vt, which needs building for iOS and binding through cinterop.
4. **Typing on a phone.** This is the riskiest part.

A TestFlight build of the existing showcase app could go up within an hour of having a Mac and an account. That would prove signing and upload before any porting starts.

## What exists

- `iosApp/` is an `ios/app` module: a SwiftUI shell (`iosApp.swift`) hosting `ComposeUIViewController { App() }`, where `App()` is the showcase from `shared`, not Tono. It has an Xcode project (`module.xcodeproj`) with automatic signing, `DEVELOPMENT_TEAM = ""`, bundle id `org.example.kotlintest`, product name `kotlin-test`, iOS 18.0 minimum, and Tono's 1024 px icons (light and dark).
- `shared`, `omarchy-theme` and `omarchy-theme-compose` already list `iosArm64` and `iosSimulatorArm64`.
- `tono`, `rex` and `ghostty` list only `[jvm, wasmJs]`.

## What the toolchain gives us

- `./kotlin run -m iosApp`: runs in the simulator (on a Mac).
- `./kotlin package -f ipa`: makes an .ipa.
- `./kotlin publish testflight -m iosApp`:
  - uploads to App Store Connect and TestFlight;
  - needs macOS, Xcode and an App Store Connect API key, given as `KOTLIN_TOOLCHAIN_ASC_KEY_PATH`, `_ID` and `_ISSUER_ID`, or through an interactive first-run wizard that saves `iosApp/app-store-connect-auth.properties` (keep that file out of git);
  - supports only automatic signing "in straightforward setups"; anything more needs fastlane.
- **cinterop** is supported. The CLI has a `NativeCInteropGenerateKlibTask` that picks up `*.def` files, so a `.def` naming libghostty-vt's headers and static library can bind it for Kotlin/Native. Where the toolchain expects the `.def` (which source directory) is still to be confirmed, from the toolchain's docs or by trying it on a Mac.

## Build machines

| Machine | OS | State today |
|---|---|---|
| this one (omarchy) | Linux | no Xcode; can't build iOS at all |
| neocrypter | macOS, on the tailnet | SSH refused (port 22 closed) |
| spacepandaiii | macOS, on the tailnet | answers SSH, but its host key isn't in `known_hosts` here; not tried further |
| ipad172, iphone182 | iOS, on the tailnet | offline for weeks; would be test devices |

Either Mac would do once Remote Login is on and Xcode is installed. GitHub Actions' macOS runners are the alternative for CI. The repository is `masegraye/tono` on GitHub.

## The porting work

### 1. libghostty-vt for iOS (large)

- **Today:** `native/build-libghostty-vt.sh` builds it with Zig 0.17, with `-Demit-xcframework=false`. Ghostty's build can emit an xcframework when run on a Mac, so the first thing to try is the same pinned source (`native/GHOSTTY.lock`) with that flag on, for `aarch64-ios` and `aarch64-ios-simulator`. Failing that, two static libraries built with Zig.
- **Then:** a `.def` file binding `ghostty/vt.h` and the static library, and a `src@ios` side for `ghostty` that mirrors `src@jvm`. That's about 1,100 lines today across Terminal, Renderer, Selection, Mouse and Images, written against Java's foreign function API. Porting it to cinterop is mechanical but long.
- **Upkeep:** every new function bound for the desktop and web (`native/ghostty-vt.symbols`, `ghostty-glue.js`) then needs an iOS binding too: a third place to keep in step.

### 2. rex on iOS (small)

`rex/src/Platform.kt` has about a dozen `expect`s. Their iOS sides:

- **Networking:** Ktor's Darwin engine for HTTP and WebSockets.
- **Concurrency:** a single-thread dispatcher for terminals, since libghostty handles aren't thread-safe; concurrent collections or locks.
- **Storage:** the workspace file kept in the app's own Documents directory.

How it reaches servers:

- **Unix sockets:** none, so no "Local" host.
- **Tailnet servers:** reached directly as `https://<name>-rex.<tailnet>.ts.net`, as the desktop does. Unlike the web app, no relay is needed, because a native app sends no `Origin` and the server only turns away browser pages from other sites.
- **On the phone:** needs the Tailscale app connected.
- **Finding servers:** `tailnetRexServers()` reads `tailscale status --json`, which an iOS app can't run. For a first version, hosts are typed in. Later, ask a known server or the Tailscale API.

### 3. tono on iOS (medium)

About 400 lines of `actual`s, after `src@jvm` and `src@wasmJs`:

| Need | On iOS | Notes |
|---|---|---|
| Key codes | Compose key events | |
| Clipboard | `UIPasteboard` | |
| Sounds | `AVAudioPlayer` | the Ogg files may need converting |
| Opening links | `UIApplication.openURL` | |
| Notifications | `UNUserNotificationCenter` | local only: iOS suspends a backgrounded app's sockets within seconds, so they fire while the app is open or just left. Notifications for a closed app would need the server to send push, which Rex doesn't. |
| Focus / in front | the app's active state | |

`iosApp` then shows Tono's app instead of the showcase.

### 4. Typing (medium, and the riskiest)

- **iPhone:** the web app brings up the on-screen keyboard when a terminal is touched and adds a row with Escape, Tab, Ctrl and the arrows, all in JavaScript (`page-keys.js`). Compose on iOS needs its own text-input session attached to the terminal view, feeding characters to the terminal and keeping autocorrect and smart punctuation off.
- **iPad:** a hardware keyboard should come through as Compose key events. Whether Ctrl and Cmd combinations reach the app before iPadOS takes them needs trying on a device.

### 5. App Store details (small)

- **Bundle id and team:** choose a real bundle id to replace `org.example.kotlintest`, and set `DEVELOPMENT_TEAM`.
- **Info.plist:** set `CFBundleDisplayName` to `Tono`, since the product name is `kotlin-test`. Add `ITSAppUsesNonExemptEncryption = false`, as TLS alone qualifies for the exemption, so uploads don't stop at the export question.
- **Privacy manifest:** add a `PrivacyInfo.xcprivacy`.
- **Build number:** raise it for every upload.
- **Testers:** internal testers (up to 100, on the team) need no review. External testers need Beta App Review once per version.

## What Mase needs to do

1. **Join the Apple Developer Program** ($99 a year), if not already a member.
2. **Get a Mac building:**
   - turn on Remote Login on neocrypter or spacepandaiii;
   - install Xcode and accept its licence;
   - confirm spacepandaiii's host key before it goes in `known_hosts`;
   - or say to use GitHub Actions instead.
3. **Pick the bundle id** (e.g. `dev.mase.tono`).
4. **In App Store Connect**, create the app record and an API key (Admin or App Manager role). Keep the `.p8` file on the Mac, not in the repository.

## Proposed order

1. **Prove the pipeline:**
   - on the Mac, run `./kotlin run -m iosApp` in the simulator, then `./kotlin publish testflight -m iosApp` with the showcase as it is;
   - set the team, bundle id, display name and encryption key first.
   - This settles signing, upload and the account before any porting.
2. **libghostty-vt for iOS**, with a small cinterop smoke test: create a terminal, feed it bytes, read a row.
3. **The iOS sides for `ghostty`, `rex` and `tono`**, and switch `iosApp` to Tono. Typed-in hosts only.
4. **Typing on iPhone and iPad.** Then the first real TestFlight build, tried on the iPad and iPhone on the tailnet.
5. **Later:** finding tailnet servers on the phone, notifications, sounds.

Steps 2 to 4 can go to workers, but only on a Mac. A worker here on Linux could write the iOS code, but couldn't compile or check any of it.

## Not known yet

- Where the toolchain wants `.def` files, and whether it links a static library or an xcframework into the app without help.
- Whether Ghostty's xcframework option works for the vt-only build at the pinned commit.
- How Compose on iOS handles text input for a canvas-drawn view such as the terminal.
- Whether a TestFlight app holding long-lived WebSockets to tailnet hosts behaves well alongside the Tailscale VPN when it goes to the background and comes back. Reconnecting already works, using `reconnect_token`.
