# Helpers for recording the launch film's footage. Sourced by the scene scripts, on the
# machine whose screen is being recorded. Keys go to the demo Tono window through the
# compositor (wtype), and the screen is recorded with Omarchy's own recorder.
DEMO=/tmp/tono-demo
TAKES=$DEMO/takes
mkdir -p "$TAKES"

# The demo's own Tono window, as opposed to one the user has open.
tono_window() { cat "$DEMO/window" 2>/dev/null; }
focus_tono() {
  hyprctl dispatch "hl.dsp.focus({ window = \"address:$(tono_window)\" })" >/dev/null 2>&1
  sleep 0.3
  [ "$(hyprctl activewindow -j | jq -r .address)" = "$(tono_window)" ]
}
# Out of the picture: the bottom-right corner.
cursor_away() { hyprctl dispatch "hl.dsp.cursor.move({ x = 1535, y = 863 })" >/dev/null 2>&1; }

# key ctrl+shift+p | key Return | key ctrl+3. Only while the demo window has the keyboard.
key() {
  focus_tono || { echo "the demo window doesn't have the keyboard; key $1 not sent" >&2; return 1; }
  local parts args=() mods=()
  IFS=+ read -r -a parts <<<"$1"
  local name=${parts[-1]}
  unset 'parts[-1]'
  for m in "${parts[@]}"; do args+=(-M "$m"); mods=(-m "$m" "${mods[@]}"); done
  wtype "${args[@]}" -k "$name" "${mods[@]}"
}
# Holds Control long enough for the sidebar's digits to fade in, presses each digit in turn, and lets go.
tabs_by_number() { # pause-ms digit...
  focus_tono || return 1
  local pause=$1 args=(-M ctrl -s 600); shift
  for d in "$@"; do args+=(-k "$d" -s "$pause"); done
  wtype "${args[@]}" -m ctrl
}
# Types as a person would.
type_text() { focus_tono || return 1; wtype -d "${2:-55}" "$1"; }

# rec_start NAME ... rec_stop: one take, saved as $TAKES/NAME.mp4.
rec_start() {
  TAKE=$1
  makoctl mode -a do-not-disturb >/dev/null 2>&1   # no notification over the picture
  cursor_away
  OMARCHY_SCREENRECORD_DIR=$TAKES omarchy screenrecord --fullscreen >/dev/null 2>&1 &
  sleep 1.6
}
rec_stop() {
  sleep 0.6
  local saved
  saved=$(OMARCHY_SCREENRECORD_DIR=$TAKES omarchy screenrecord --stop-recording 2>/dev/null | tail -1)
  sleep 0.5
  makoctl mode -r do-not-disturb >/dev/null 2>&1
  # The recorder names its file by the time; the newest one is this take.
  local newest
  newest=$(find "$TAKES" -maxdepth 1 -name 'screenrecording-*.mp4' -printf '%T@ %p\n' | sort -rn | head -1 | cut -d' ' -f2-)
  [ -n "$newest" ] && mv -f "$newest" "$TAKES/$TAKE.mp4"
  ffprobe -v error -show_entries format=duration:stream=width,height,r_frame_rate -of csv=p=0 "$TAKES/$TAKE.mp4" | tr '\n' ' '
  echo "-> $TAKES/$TAKE.mp4"
}
