#!/usr/bin/env bash
# Opens the web Tono in a browser window with no browser around it, for the film.
#   TONO_WEB_URL=https://… WEB_WORKSPACE='{…}' web.sh      `web.sh down` closes it.
# The workspace is put in the page's storage before the app loads, so that it opens on the
# session being filmed and never on someone's real one.
set -euo pipefail
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
. "$here/lib.sh"
profile=$DEMO/chrome
port=9347

if [ "${1:-}" = down ]; then
  pkill -f "user-data-dir=$DEMO/chrom[e]" 2>/dev/null || true
  rm -f "$DEMO/window-web"
  echo "closed"
  exit 0
fi
: "${TONO_WEB_URL:?the address of the web Tono}" "${WEB_WORKSPACE:?the workspace to open it with}"
url=${TONO_WEB_URL%/}

pkill -f "user-data-dir=$DEMO/chrom[e]" 2>/dev/null || true
rm -rf "$profile"
before=$(hyprctl clients -j | jq -r '.[].address' | sort)
hyprctl dispatch "hl.dsp.exec_cmd(\"chromium --user-data-dir=$profile --no-first-run --remote-debugging-port=$port --app=$url/styles.css\")" >/dev/null
sleep 6
node "$here/cdp-eval.mjs" $port styles.css "localStorage.setItem('tono.workspace', JSON.stringify($WEB_WORKSPACE)); location.href = '$url/'; 'opening'"
for _ in $(seq 1 30); do
  sleep 1
  new=$(comm -13 <(echo "$before") <(hyprctl clients -j | jq -r '.[].address' | sort) | head -1)
  [ -n "$new" ] && break
done
[ -n "${new:-}" ] || { echo "the browser window did not open"; exit 1; }
echo "$new" > "$DEMO/window-web"
sleep 22   # the app is a large download the first time
hyprctl clients -j | jq -r --arg a "$new" '.[] | select(.address == $a) | "window \(.address) \(.class) | \(.title) \(.size)"'
