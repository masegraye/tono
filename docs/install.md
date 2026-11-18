# Installing

## What you need

- Omarchy. Tono reads its colors and font from the files Omarchy keeps for the current theme, and it hasn't been
  tried on any other desktop.
- A Rex server running on this machine. Tono connects to it at startup and gets the list of your other machines
  from it.
- [mise](https://mise.jdx.dev), which installs the tools the build needs (Task, Java and Zig). It installs them for
  this directory only.

## Install

```sh
git clone https://github.com/masegraye/tono && cd tono
mise trust && mise install
task install
```

`task install` builds the app as a single jar and puts these in place:

| What | Where |
|---|---|
| The `tono` command | `~/.local/bin/tono` |
| The app, the terminal library, and a link to the Java that runs them | `~/.local/share/tono` |
| An entry for the app launcher, and its icon | `~/.local/share/applications`, `~/.local/share/icons` |

The first build downloads Ghostty's source and builds its terminal core, which takes about two minutes. Later builds
skip that step.

After installing, you don't need the checkout to run Tono.

## Run

```sh
tono                    # or pick it in the app launcher
tono --workspace FILE   # keep this window's state in another file
```

Tono saves which machines are collapsed and which tab you were on in `~/.config/kotlin-test/workspace.json`. Its
windows have the class `tono`, if you want to match them in a Hyprland window rule.

## Update

```sh
git pull && task install
```

Quit Tono before you update, then open it again.

## Remove

```sh
task uninstall
```

That removes the command, the app, the launcher entry and the icons. Your settings in `~/.config/kotlin-test` are
left in place.

## Run it without installing

```sh
task desktop:steady
```

[Working on Tono](development.md) lists the other tasks.
