# Installing

## What you need

- **Omarchy.** Tono reads its colors and font from the files Omarchy keeps for the current theme. It hasn't been
  tried on any other desktop.
- **A Rex server** on this machine. Tono connects to it when it starts, and learns your other machines from it.
- **[mise](https://mise.jdx.dev)**, which fetches the tools the build uses: Task, Java and Zig. They are installed
  for this directory only.

## Install

```sh
git clone https://github.com/masegraye/tono && cd tono
mise trust && mise install
task install
```

`task install` builds the app as one jar and installs:

| What | Where |
|---|---|
| The `tono` command | `~/.local/bin/tono` |
| The app, the terminal library, and a link to the Java that runs them | `~/.local/share/tono` |
| An entry for the app launcher, and its icon | `~/.local/share/applications`, `~/.local/share/icons` |

The first build downloads Ghostty's source and builds its terminal core, which takes about two minutes. Later builds
skip that.

Once installed, Tono no longer needs the checkout.

## Run

```sh
tono                    # or pick it in the app launcher
tono --workspace FILE   # keep this window's state in another file
```

Tono remembers which machines are folded and which tab you were on in `~/.config/kotlin-test/workspace.json`. Its
windows have the class `tono`, which is what a Hyprland window rule matches.

## Update

```sh
git pull && task install
```

Quit Tono before updating, and open it again after.

## Remove

```sh
task uninstall
```

This removes the command, the app, the launcher entry and the icons. It leaves `~/.config/kotlin-test` alone.

## Run it without installing

```sh
task desktop:steady
```

See [Working on Tono](development.md) for the rest of the tasks.
