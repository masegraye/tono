#!/usr/bin/env bash
# The film's takes, one function each. Run one or several: scenes.sh tabs terminal palette.
# Each leaves the stage as it found it. The stage is set by setup.sh.
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
. "$here/lib.sh"
sid=$(rex ls --json | jq -r '.sessions[] | select(.label == "launch-day") | .session_id')

# Every machine in one sidebar: Control held, digits appear, and a tab on another machine is one key away.
scene_tabs() {
  key ctrl+1; sleep 1
  rec_start tabs; sleep 0.9
  # A tab on another machine takes a moment to open its terminal; the film cuts past that.
  key_state "" Control_L down; sleep 0.8
  for step in "2 1.1" "5 2.8" "6 2.8" "3 1.1"; do
    set -- $step
    key_state CTRL "$1" down; sleep 0.05; key_state CTRL "$1" up; sleep "$2"
  done
  key_state "" Control_L up
  sleep 0.9
  rec_stop
}

# A real terminal: colour, a picture drawn in the terminal, an editor.
scene_terminal() {
  key ctrl+3; shell_run shell clear; sleep 1
  rec_start terminal; sleep 0.7
  shell_type shell 'git log --oneline --graph -9' 0.035; sleep 0.25; shell_enter shell; sleep 1.7
  shell_type shell 'logo' 0.09; sleep 0.25; shell_enter shell; sleep 2.2
  key ctrl+2; sleep 1.1
  key ctrl+d; sleep 0.8; key ctrl+d; sleep 0.8; key ctrl+u; sleep 0.8
  rec_stop
  shell_run shell clear
}

# The keyboard: the palette, a split from it, another by its key, zoom and back.
scene_palette() {
  key ctrl+3; shell_run shell clear; sleep 1
  rec_start palette; sleep 0.7
  key ctrl+shift+p; sleep 0.8
  type_text split 0.1; sleep 1.0
  key Return; sleep 1.3
  local right; right=$(focused_of shell)
  shell_type "$right" fastfetch 0.06; shell_enter "$right"; sleep 1.6
  key ctrl+alt+d; sleep 1.2
  local below; below=$(focused_of shell)
  shell_type "$below" 'eza -T -L 1 --icons .' 0.035; shell_enter "$below"; sleep 1.8
  key ctrl+shift+Return; sleep 1.4
  key ctrl+shift+Return; sleep 1.2
  rec_stop
  for block in "$right" "$below"; do
    rex api call block.close "{\"session_id\":\"$sid\",\"block_id\":\"$block\"}" >/dev/null 2>&1
  done
}

# An agent at work in another tab: its mark in the sidebar, the moment it needs you, the answer, done.
scene_agents() {
  key ctrl+1; shell_run agent clear; sleep 1
  rec_start agents; sleep 0.8
  shell_run agent "$here/agent-sim.sh 9"
  sleep 5.1          # working, then it asks
  key ctrl+4; sleep 1.4
  shell_type agent y; sleep 2.8
  rec_stop
  sleep 1; shell_run agent clear
}

# The desktop's theme changes, and Tono with it.
scene_theme() {
  local was; was=$(omarchy theme current 2>/dev/null | head -1)
  key ctrl+2; sleep 1
  rec_start theme; sleep 1.2
  for theme in "Tokyo Night" "Catppuccin Latte" "Gruvbox" "${was:-Retro 82}"; do
    omarchy theme set "$theme" >/dev/null 2>&1
    sleep 3
  done
  rec_stop
}

quiet_on
trap quiet_off EXIT
for scene in "$@"; do
  echo "== $scene"
  "scene_$scene"
done
