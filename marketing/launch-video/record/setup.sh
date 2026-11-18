#!/usr/bin/env bash
# Sets the stage on the machine being recorded: a Rex session to film, with a tab for each
# thing the film shows, and a Tono of its own showing it full screen. `setup.sh down` strikes it.
set -euo pipefail
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
. "$here/lib.sh"
repo=$(cd "$here/../../.." && pwd)
SESSION=launch-day

if [ "${1:-}" = down ]; then
  [ -n "$(tono_window)" ] && hyprctl dispatch "hl.dsp.window.close({ window = \"address:$(tono_window)\" })" >/dev/null 2>&1 || true
  rex session kill "$SESSION" >/dev/null 2>&1 || true
  rm -f "$DEMO/window" "$DEMO/workspace.json"
  echo "struck"
  exit 0
fi

rex session kill "$SESSION" >/dev/null 2>&1 || true
sid=$(rex session new "$SESSION" --window monitor -c "$repo" --json -- btop | jq -r .session_id)
rex window new -s "$sid" editor -c "$repo" --focus=false -- nvim tono/src/TonoApp.kt >/dev/null
rex window new -s "$sid" shell -c "$repo" --focus=false >/dev/null
rex window new -s "$sid" agent -c "$repo" --focus=false >/dev/null
# Which terminal is which, for the scenes to type into, before the names are taken off.
mkdir -p "$DEMO"
rex window ls -s "$sid" --json | jq -r '.windows[] | "\(.label) \(.focused_block_id) \(.window_id)"' | while read -r label block window; do
  echo "$block" > "$DEMO/block-$label"
  echo "$window" > "$DEMO/win-$label"
done
# A command for the film to type that draws a picture in the terminal.
mkdir -p "$DEMO/bin"
printf '#!/bin/sh\nexec "%s/icat.sh" "%s/webApp/resources/icon-512.png"\n' "$here" "$repo" > "$DEMO/bin/logo"
chmod +x "$DEMO/bin/logo"
rex send -s "$sid" -b "$(cat "$DEMO/block-shell")" "export PATH=$DEMO/bin:\$PATH; clear" >/dev/null
rex send-key -s "$sid" -b "$(cat "$DEMO/block-shell")" enter >/dev/null
# The tabs are named by what runs in them, as Tono does for a tab nobody named: only the
# agent's keeps a name, since what runs there is a script.
for w in $(rex window ls -s "$sid" --json | jq -r '.windows[] | select(.label != "agent") | .window_id'); do
  rex api call session.set_window_label "{\"session_id\":\"$sid\",\"window_id\":\"$w\",\"label\":\"\"}" >/dev/null 2>&1 || true
done

# Everything of the user's is folded away, so the keys that go to a tab by number stay in the demo.
folded=$(rex ls --json | jq -c --arg s "$sid" '[.sessions[] | select(.session_id != $s) | .session_id]')
extra=${FOLD_ALSO:-[]}
jq -n --arg s "$sid" --argjson folded "$folded" --argjson extra "$extra" --argjson remote "${REMOTE_SESSIONS:-{\}}" \
  '{hosts: [{label: "Local", endpoint: "unix:///run/user/\(env.UID // "1000")/rex/server.sock"}], last_host: "Local",
    sessions: ({Local: $s} + $remote), collapsed_sessions: ($folded + $extra)}' > "$DEMO/workspace.json"

before=$(hyprctl clients -j | jq -r '.[] | select(.class == "tono") | .address' | sort)
hyprctl dispatch "hl.dsp.exec_cmd(\"tono --workspace $DEMO/workspace.json\")" >/dev/null
for _ in $(seq 1 40); do
  sleep 1
  now=$(hyprctl clients -j | jq -r '.[] | select(.class == "tono") | .address' | sort)
  new=$(comm -13 <(echo "$before") <(echo "$now") | head -1)
  [ -n "$new" ] && break
done
[ -n "${new:-}" ] || { echo "the demo Tono did not open"; exit 1; }
echo "$new" > "$DEMO/window"
sleep 6
fullscreen on
echo "session $sid, window $new"
