# backlog-050: A Mac app

| | |
|---|---|
| Status | captured |
| Area | Shipping |
| Size | large |
| Source | Mase, 2026-11-19 (big-idea-001) |

## What

Tono on macOS, installable as a normal Mac app (signed, notarised, a `.dmg` or through the App Store), with Mac conventions: Cmd for the app's keys, the menu bar, files in Application Support.

## Why

Mase works on Macs too, and Rex for Mac is the reference Tono follows; Tono on the Mac is the same client everywhere.

## Builds on

Compose for Desktop already runs on the JVM on macOS: the shortest way is packaging `desktopApp` for macOS (the Kotlin Toolchain's packaging, or jpackage), with libghostty-vt built for macOS. Other routes to weigh: the iOS app running on Apple silicon Macs ("Designed for iPad"), or a native macOS target. The build Mac is `spacepandaiii` (inv-002).

## Promote when

After the iOS pipeline is proven, or sooner if the JVM route turns out to be a small step.

## Not known yet

Which route; signing and notarisation with the same Apple account as TestFlight; how Cmd and Ctrl split between the app and the terminal (Rex for Mac's own rules are the reference).
