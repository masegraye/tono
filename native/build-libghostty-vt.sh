#!/usr/bin/env bash
# Builds libghostty-vt, Ghostty's terminal emulation core, as a shared library.
#
# Downloads the source archive pinned in GHOSTTY.lock, checks its SHA-256, and builds it
# with Zig 0.17 into native/out/ (lib/libghostty-vt.so and include/ghostty/vt.h).
# Run it through `task native`, which runs it with the Zig pinned in mise.toml.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
lock() { sed -n "s/^$1=//p" "$here/GHOSTTY.lock"; }
version=$(lock version) url=$(lock url) sha256=$(lock sha256)

out="$here/out"
work="$here/work"
stamp="$out/.built-from"

if [[ -f $stamp && $(cat "$stamp") == "$sha256" && -e $out/lib/libghostty-vt.so ]]; then
  echo "libghostty-vt $version is already built in $out"
  exit 0
fi

zig_version=$(zig version)
if [[ $zig_version != 0.17.* ]]; then
  echo "libghostty-vt needs Zig 0.17.x, found $zig_version. Run this through \`task native\`." >&2
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

src="$work/src"
rm -rf "$src" "$out"
mkdir -p "$src"
tar -xzf "$archive" -C "$src" --strip-components=1

echo "Building libghostty-vt $version with Zig $zig_version (about two minutes)"
(
  cd "$src"
  zig build -Demit-lib-vt -Doptimize=ReleaseFast -Dsimd=true -Dcpu=baseline \
    "-Dversion-string=$version" -Demit-xcframework=false -Demit-themes=false \
    --prefix "$out" --cache-dir "$work/zig-cache"
)
echo "$sha256" > "$stamp"
echo "Built $out/lib/libghostty-vt.so"
