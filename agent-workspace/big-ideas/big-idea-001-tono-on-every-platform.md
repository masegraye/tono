# big-idea-001: Tono on every platform

| | |
|---|---|
| Status | accepted |
| Set by | Mase, 2026-11-19 |

## Where Tono is going

Today Tono is a desktop app for Omarchy, installed with `task install` from a checkout, plus a web app served by its own small server. The direction is one Tono, built from the same Kotlin Multiplatform code, installable wherever its user works:

| Platform | Shape | Priority | Where it stands |
|---|---|---|---|
| Linux (Omarchy first) | The desktop app as today, installable from the **AUR** | first | `task install` only; AUR entry is backlog-031 |
| iOS / iPadOS | A native app, through TestFlight first | high | investigated (inv-002); build Mac ready (`spacepandaiii`) |
| macOS | A Mac app. The desktop app (Compose for Desktop on the JVM) packaged for macOS, unless something better comes out of investigating it: an iOS app on Apple silicon, or a native macOS target | high | not started |
| Android | A native app | low (no test device) | the showcase app only |
| Web | **Not shipped on its own at first.** It keeps building and working, and Tono itself can serve it: an option in Tono that, when on, hosts the web client so it can be reached over the tailnet | keep working | backlog-048 |

## What follows from it

- **Platform conventions win over Omarchy's or Linux's habits.** Where files go, what a window looks like, which keys are the app's: each platform's own conventions, with Omarchy's look on Omarchy. Defaults must be good where Omarchy isn't there (done for themes and fonts: backlog-045).
- **No bundled Rex server.** Unlike Rex for Mac, Tono connects to Rex servers installed separately. It shows what they say and helps when there's none (spec-001 phase 4). This may need revisiting on platforms where installing a server is unusual (iOS can't run one).
- **Shared code stays shared.** Platform code is limited to what the platform makes different: files, clipboard, notifications, windows, the terminal library's binding. The `expect`/`actual` seams that exist for the desktop and the web are where iOS, macOS and Android fit.
- **The web app stays a first-class build target** even while it isn't shipped on its own: every change keeps `./kotlin build -m webApp` working and is checked in a browser.

## Steps already identified

- Where Tono keeps its files on each platform: backlog-049, being investigated (inv-003).
- The AUR package: backlog-031, no longer on hold.
- iOS: inv-002's order (prove the TestFlight pipeline on the starter app, then libghostty-vt for iOS, then the platform code).
- macOS: backlog-050.
- Android: backlog-051, low priority.
- Tono serving the web client over the tailnet: backlog-048.
