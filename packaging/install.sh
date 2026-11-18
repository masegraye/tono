#!/usr/bin/env bash
# Installs Tono for the current user: the `tono` command in ~/.local/bin, an entry for the
# app launcher, and the app, the terminal library and a link to the Java that runs them in
# ~/.local/share/tono.
# Run it through `task install`, which builds the app first and provides that Java.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
data="${XDG_DATA_HOME:-$HOME/.local/share}/tono"
bin="$HOME/.local/bin"
apps="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
jar="$root/build/tasks/_desktopApp_executableJarJvm/desktopApp-jvm-executable.jar"
lib="$root/native/out/lib/libghostty-vt.so"

[[ -f $jar ]] || { echo "The app isn't built: run \`task package\`." >&2; exit 1; }
[[ -f $lib ]] || { echo "libghostty-vt isn't built: run \`task native\`." >&2; exit 1; }

mkdir -p "$data" "$bin"
# `install` writes new files rather than overwriting, so a Tono that is running keeps the
# ones it started with until it is restarted.
install -m 644 "$jar" "$data/tono.jar"
install -m 755 "$lib" "$data/libghostty-vt.so"
ln -sfn "$(dirname "$(dirname "$(command -v java)")")" "$data/jre"
install -m 755 "$root/packaging/tono" "$bin/tono"
# An entry for the desktop's app launcher, which finds `tono` on PATH.
install -D -m 644 "$root/packaging/tono.desktop" "$apps/tono.desktop"

echo "Installed $bin/tono (the app is in $data)"
case ":$PATH:" in *":$bin:"*) ;; *) echo "Note: $bin is not on your PATH." ;; esac
