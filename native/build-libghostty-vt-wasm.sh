#!/usr/bin/env bash
# Builds libghostty-vt as a WebAssembly module, for the web app, into native/out-wasm/.
#
# It builds the source pinned in GHOSTTY.lock, the same as build-libghostty-vt.sh, with
# ghostty-vt-wasm.patch applied to a copy of its own. Upstream leaves images out of this
# build; the patch puts them back, for pixels sent in the output itself, with the PNG
# decoder the library already carries. Run this through `task native:wasm`.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
lock() { sed -n "s/^$1=//p" "$here/GHOSTTY.lock"; }
version=$(lock version) url=$(lock url) sha256=$(lock sha256)

out="$here/out-wasm"
work="$here/work"
patch="$here/ghostty-vt-wasm.patch"
stamp="$out/.built-from"
# The source and the patch together are what the result is built from.
built_from="$sha256 $(sha256sum "$patch" | cut -d' ' -f1)"

if [[ -f $stamp && $(cat "$stamp") == "$built_from" && -e $out/ghostty-vt.wasm ]]; then
  echo "libghostty-vt $version for WebAssembly is already built in $out"
  exit 0
fi

zig_version=$(zig version)
if [[ $zig_version != 0.17.* ]]; then
  echo "libghostty-vt needs Zig 0.17.x, found $zig_version. Run this through \`task native:wasm\`." >&2
  exit 1
fi

mkdir -p "$work"
archive="$work/libghostty-vt-source.tar.gz"
if ! echo "$sha256  $archive" | sha256sum --check --status 2>/dev/null; then
  echo "Downloading libghostty-vt $version"
  curl --proto '=https' --tlsv1.2 -fsSLo "$archive" "$url"
  echo "$sha256  $archive" | sha256sum --check --status ||
    { echo "Checksum mismatch for $url" >&2; exit 1; }
fi

src="$work/src-wasm"
rm -rf "$src"
mkdir -p "$src"
tar -xzf "$archive" -C "$src" --strip-components=1
patch --directory "$src" --strip 1 --silent < "$patch"

echo "Building libghostty-vt $version for WebAssembly with Zig $zig_version (under a minute)"
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT
(
  cd "$src"
  # Small rather than fast: the page downloads it, and a terminal's work is light.
  zig build -Demit-lib-vt -Dtarget=wasm32-freestanding -Doptimize=ReleaseSmall \
    "-Dversion-string=$version" -Demit-xcframework=false -Demit-themes=false \
    --prefix "$build" --cache-dir "$work/zig-cache-wasm"
)
mkdir -p "$out"
install -m 644 "$build/bin/ghostty-vt.wasm" "$out/ghostty-vt.wasm"
echo "$built_from" > "$stamp"
echo "Built $out/ghostty-vt.wasm ($(du -h "$out/ghostty-vt.wasm" | cut -f1))"
