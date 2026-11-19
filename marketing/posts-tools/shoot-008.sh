#!/usr/bin/env bash
# Post 008, Scroll an agent on another machine: a still of the scroll telemetry's graphs, the same
# flick of the wheel before the fix and after it, one above the other. Headless Chromium; no display.
#   shoot-008.sh [directory of the runs, default /tmp/scroll-e2e]
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
runs=${1:-/tmp/scroll-e2e}
out=$posts/008-scroll-agent-other-machine/media
mkdir -p "$PM/008" "$out"
"$tools/scroll-graphs.py" "$runs/desk-before-30-nvim.jsonl" "$runs/desk-after-30-nvim.jsonl" --link "$runs/link30.jsonl" -o "$PM/008/compare.html" || exit 1
# Twice the size, for a picture that is opened and looked into; a profile of its own, and no window.
timeout 60 chromium --headless=new --no-first-run --disable-gpu --hide-scrollbars --user-data-dir="$PM/chrome" \
  --window-size=1920,1080 --force-device-scale-factor=2 --screenshot="$out/scroll-agent-other-machine.png" "file://$PM/008/compare.html" 2>/dev/null
identify "$out/scroll-agent-other-machine.png"
