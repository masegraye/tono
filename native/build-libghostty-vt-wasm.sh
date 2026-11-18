#!/usr/bin/env bash
# Builds libghostty-vt as a WebAssembly module, for the web app, into native/out/wasm/.
#
# It builds the same pinned source as build-libghostty-vt.sh, which downloads and unpacks
# it, so that script runs first. Run this through `task native:wasm`.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
lock() { sed -n "s/^$1=//p" "$here/GHOSTTY.lock"; }
version=$(lock version) sha256=$(lock sha256)

out="$here/out/wasm"
src="$here/work/src"
stamp="$out/.built-from"

if [[ -f $stamp && $(cat "$stamp") == "$sha256" && -e $out/ghostty-vt.wasm ]]; then
  echo "libghostty-vt $version for WebAssembly is already built in $out"
  exit 0
fi
[[ -f $src/build.zig ]] || { echo "The source isn't unpacked: run \`task native\` first." >&2; exit 1; }

echo "Building libghostty-vt $version for WebAssembly (under a minute)"
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT
(
  cd "$src"
  # Small rather than fast: the page downloads it, and a terminal's work is light.
  zig build -Demit-lib-vt -Dtarget=wasm32-freestanding -Doptimize=ReleaseSmall \
    "-Dversion-string=$version" -Demit-xcframework=false -Demit-themes=false \
    --prefix "$build" --cache-dir "$here/work/zig-cache-wasm"
)
mkdir -p "$out"
install -m 644 "$build/bin/ghostty-vt.wasm" "$out/ghostty-vt.wasm"
echo "$sha256" > "$stamp"
echo "Built $out/ghostty-vt.wasm ($(du -h "$out/ghostty-vt.wasm" | cut -f1))"
