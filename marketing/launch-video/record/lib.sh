# Helpers for recording the launch film's footage. Sourced by the scene scripts, on the
# machine whose screen is being recorded. Keys go to the demo Tono window through the
# compositor (wtype), and the screen is recorded with Omarchy's own recorder.
DEMO=/tmp/tono-demo
TAKES=$DEMO/takes
mkdir -p "$TAKES"

# The demo's own Tono window, as opposed to one the user has open.
tono_window() { cat "$DEMO/window" 2>/dev/null; }
focus_tono() {
  # Focusing moves the pointer to the middle of the window, so it is done only when needed,
  # and the pointer is put away again afterwards.
  [ "$(hyprctl activewindow -j | jq -r .address)" = "$(tono_window)" ] && return 0
  hyprctl dispatch "hl.dsp.focus({ window = \"address:$(tono_window)\" })" >/dev/null 2>&1
  sleep 0.3
  cursor_away
  [ "$(hyprctl activewindow -j | jq -r .address)" = "$(tono_window)" ]
}
# Out of the picture: the bottom-right corner.
cursor_away() { hyprctl dispatch "hl.dsp.cursor.move({ x = 1535, y = 863 })" >/dev/null 2>&1; }

# Keys go through the compositor's own shortcut sender, which uses the real keymap. (wtype
# makes a keymap of its own, and a window running under XWayland, as Tono's does, reads
# its keys through the old one and gets the wrong keys.)
#
# key ctrl+shift+p | key Return | key ctrl+3. Only while the demo window has the keyboard.
key() {
  focus_tono || { echo "the demo window doesn't have the keyboard; key $1 not sent" >&2; return 1; }
  local parts
  IFS=+ read -r -a parts <<<"$1"
  local name=${parts[-1]}
  unset 'parts[-1]'
  local mods=${parts[*]^^}
  hyprctl dispatch "hl.dsp.send_shortcut({ mods = \"$mods\", key = \"$name\", window = \"address:$(tono_window)\" })" >/dev/null 2>&1
  # The window is never told the modifiers were let go, and Tono shows its tab numbers
  # while it believes Control is down. A tap of Shift on its own sets that straight.
  if [ -n "$mods" ]; then key_state "" Shift_L down; key_state "" Shift_L up; fi
}
key_state() { hyprctl dispatch "hl.dsp.send_key_state({ mods = \"$1\", key = \"$2\", state = \"$3\" })" >/dev/null 2>&1; }
# Holds Control long enough for the sidebar's digits to fade in, presses each digit in turn, and lets go.
tabs_by_number() { # pause-seconds digit...
  focus_tono || return 1
  local pause=$1; shift
  key_state "" Control_L down
  sleep 0.7
  for d in "$@"; do
    key_state CTRL "$d" down; sleep 0.05; key_state CTRL "$d" up
    sleep "$pause"
  done
  key_state "" Control_L up
}
# Types into whatever in Tono has the keyboard, a key at a time, as a person would.
type_text() {
  focus_tono || return 1
  local text=$1 delay=${2:-0.06} i char name
  for ((i = 0; i < ${#text}; i++)); do
    char=${text:i:1}
    case $char in
      ' ') name=space ;; '-') name=minus ;; '.') name=period ;; '/') name=slash ;;
      *) name=$char ;;
    esac
    hyprctl dispatch "hl.dsp.send_shortcut({ mods = \"\", key = \"$name\", window = \"address:$(tono_window)\" })" >/dev/null 2>&1
    sleep "$delay"
  done
}
# Types into a terminal of the demo session through Rex, a character at a time: for shell
# commands, which can hold any character. shell_type TAB-LABEL 'text'; shell_enter TAB-LABEL.
# A tab is named as setup.sh made it (monitor, editor, shell, agent); a pane by its block id.
block_of() { case $1 in block:*) echo "$1" ;; *) cat "$DEMO/block-$1" ;; esac; }
shell_type() {
  local block text=$2 i
  block=$(block_of "$1")
  for ((i = 0; i < ${#text}; i++)); do rex send -s launch-day -b "$block" "${text:i:1}" >/dev/null; sleep "${3:-0.03}"; done
}
shell_enter() { rex send-key -s launch-day -b "$(block_of "$1")" enter >/dev/null; }
# Runs something in a terminal at once, for what happens off screen.
shell_run() { rex send -s launch-day -b "$(block_of "$1")" "$2" >/dev/null; shell_enter "$1"; }
# The pane that has the keyboard in a tab, which after a split is the new one.
focused_of() { rex window ls -s launch-day --json | jq -r --arg w "$(cat "$DEMO/win-$1")" '.windows[] | select(.window_id == $w) | .focused_block_id'; }

# No notification may land on a take, the recorder's own "saved" included, so they are held
# for as long as a script that records is running.
# Omarchy's shell shows notifications; older Omarchy uses mako. Both are told.
quiet_on() {
  WAS_QUIET=$(omarchy-shell notifications dndState 2>/dev/null || echo off)
  omarchy-shell notifications setDnd true >/dev/null 2>&1
  makoctl mode -a do-not-disturb >/dev/null 2>&1
  clear_popups
}
quiet_off() {
  sleep 6   # the recorder's "saved" notice comes a moment after the last take
  clear_popups
  [ "$WAS_QUIET" = on ] || omarchy-shell notifications setDnd false >/dev/null 2>&1
  omarchy-shell -q omarchy.indicators refresh >/dev/null 2>&1
  makoctl mode -r do-not-disturb >/dev/null 2>&1
}
clear_popups() { omarchy-shell notifications dismissAll >/dev/null 2>&1; makoctl dismiss -a >/dev/null 2>&1; true; }

# rec_start NAME ... rec_stop: one take, saved as $TAKES/NAME.mp4.
rec_start() {
  TAKE=$1
  clear_popups                                     # nothing left over the picture
  focus_tono
  cursor_away
  OMARCHY_SCREENRECORD_DIR=$TAKES omarchy screenrecord --fullscreen >/dev/null 2>&1 &
  sleep 1.6
}
rec_stop() {
  sleep 0.6
  local saved
  saved=$(OMARCHY_SCREENRECORD_DIR=$TAKES omarchy screenrecord --stop-recording 2>/dev/null | tail -1)
  sleep 0.5
  # The recorder names its file by the time; the newest one is this take.
  local newest
  newest=$(find "$TAKES" -maxdepth 1 -name 'screenrecording-*.mp4' -printf '%T@ %p\n' | sort -rn | head -1 | cut -d' ' -f2-)
  [ -n "$newest" ] && mv -f "$newest" "$TAKES/$TAKE.mp4"
  ffprobe -v error -show_entries format=duration:stream=width,height,r_frame_rate -of csv=p=0 "$TAKES/$TAKE.mp4" | tr '\n' ' '
  echo "-> $TAKES/$TAKE.mp4"
}
