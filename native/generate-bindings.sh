#!/usr/bin/env bash
# Generates the Java bindings for libghostty-vt in ghostty/src/ffi from its C headers.
#
# The bindings are committed, so this only needs running after ghostty-vt.symbols or the
# pinned Ghostty version changes. It uses jextract, the JDK's binding generator, which it
# downloads (checksum-pinned) into native/work. Run it through `task bindings`.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
jextract_url=https://download.java.net/java/early_access/jextract/25/2/openjdk-25-jextract+2-4_linux-x64_bin.tar.gz
jextract_sha256=d0cc481abc1adb16fb9514e1c5e0bfc08d38c29228bece667fb5054ceaffaa42
package=org.example.kotlintest.ghostty.ffi
output="$here/../ghostty/src/ffi"
headers="$here/out/include"

[[ -f $headers/ghostty/vt.h ]] || { echo "Build libghostty-vt first: task native" >&2; exit 1; }

jextract="$here/work/jextract/bin/jextract"
if [[ ! -x $jextract ]]; then
  archive="$here/work/jextract.tar.gz"
  mkdir -p "$here/work/jextract"
  curl --proto '=https' --tlsv1.2 -fsSLo "$archive" "$jextract_url"
  echo "$jextract_sha256  $archive" | sha256sum --check --status ||
    { echo "Checksum mismatch for $jextract_url" >&2; exit 1; }
  tar -xzf "$archive" -C "$here/work/jextract" --strip-components=1
fi

# Every symbol jextract can see, as "--include-<kind> <name>" lines; keep the wanted ones.
all=$(mktemp) wanted=$(mktemp)
trap 'rm -f "$all" "$wanted"' EXIT
"$jextract" --dump-includes "$all" -I "$headers" "$headers/ghostty/vt.h" >/dev/null 2>&1
pattern="^($(grep -vE '^\s*(#|$)' "$here/ghostty-vt.symbols" | paste -sd'|'))\$"
awk -v pattern="$pattern" '$1 ~ /^--include-/ && $2 ~ pattern { print $1, $2 }' "$all" | sort -u > "$wanted"

rm -rf "$output"
# No --library: GhosttyLibrary.kt loads the shared library itself, from a path it chooses.
"$jextract" --output "$output.tmp" --target-package "$package" --header-class-name GhosttyVt \
  -I "$headers" "@$wanted" "$headers/ghostty/vt.h" 2>&1 | grep -v 'is not supported' || true
# jextract nests the files in package directories; the module keeps them flat.
mkdir -p "$output"
mv "$output.tmp/${package//.//}"/*.java "$output/"
rm -rf "$output.tmp"
echo "Generated $(ls "$output" | wc -l) files, $(cat "$output"/*.java | wc -l) lines, from $(wc -l < "$wanted") symbols"
