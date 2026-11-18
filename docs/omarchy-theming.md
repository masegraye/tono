# Omarchy theming

![The same window in three Omarchy themes](images/themes-wide.webp)

Tono gets its colors and font from [Omarchy](https://omarchy.org). It follows whichever theme is active, including
themes you install later, and it restyles itself as soon as the theme changes. It doesn't need to rebuild or reload,
and terminals change color along with everything else.

## The libraries

Three modules do this. None of them depends on Tono, so another app can use them as they are:

- [omarchy-theme](../omarchy-theme) reads the files Omarchy writes for the current theme and watches them for
  changes.
- [omarchy-theme-compose](../omarchy-theme-compose) turns a theme into a Compose Material theme and sets text in
  the Omarchy font.
- [omarchy-theme-ktor](../omarchy-theme-ktor) serves the theme to web pages and pushes each change to them.

[omarchy-theme/README.md](../omarchy-theme/README.md) documents them.

## How Tono uses them

- On the desktop, the app reads Omarchy's files directly. Under Hyprland it also renders at the focused monitor's
  scale, which you can override by setting `OMARCHY_UI_SCALE` (to `1.5`, for example).
- In a browser, the page can't read the desktop's files, so the server pushes each theme change to it. The server
  also sends the desktop's monospace font.
- On Android, on iOS, and in a web build served without the server, there's no Omarchy to read from. There the app
  uses one of four stock palettes built into the library.

## The showcase

Tono started as a showcase of Compose Multiplatform. The showcase is still there, in Tono's menu and as the Android
and iOS apps. It covers Material 3 components, inputs, lists and pagers, animation, canvas drawing and gestures,
with one file per tab in [shared/src](../shared/src).

Its Theming tab lets you preview other palettes: every installed theme on the desktop, and the built-in ones on
other platforms. A preview only affects the app, and it ends when the desktop's theme changes.

```sh
./kotlin run -m androidApp
./kotlin run -m iosApp       # on a Mac
```
