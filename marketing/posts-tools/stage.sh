#!/usr/bin/env bash
# Sets the stage for the posts' clips: scratch Rex servers that look like a person's machines,
# with sessions and tabs on them, and a nested X display on a hidden Hyprland workspace.
#   stage.sh up      start the servers (a home server on a socket, and three "machines" on loopback) and the display
#   stage.sh down    end Tono, the servers and the display
# Nothing of the user's is used: the servers have a state directory and a home of their own.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
keep=$repo/build/post-media          # what is too large for /tmp: a copy of Neovim's plugins

server_pid() { pgrep -f "rex server run .*--state-dir $PM/$1/state " | head -1; }

# A server with a home directory of its own, so its shells show ~/dev/tono and not a scratch path.
start_server() { # name
  local name=$1 home=$PM/$1/home listen
  mkdir -p "$home/.config" "$home/.local/share" "$home/.local/state" "$home/.cache" "$PM/$name/state" "$PM/$name/run"
  # The desktop's own prompt and Neovim, without the user's shell settings (which hold keys).
  cat > "$home/.bashrc" <<'RC'
[[ $- != *i* ]] && return
export OMARCHY_PATH=/usr/share/omarchy
source "$OMARCHY_PATH/default/bash/rc"
unset HISTFILE
RC
  echo '[ -f ~/.bashrc ] && . ~/.bashrc' > "$home/.bash_profile"
  cp -f ~/.config/starship.toml "$home/.config/" 2>/dev/null
  mkdir -p "$home/.config/btop"; cp -rf ~/.config/btop/. "$home/.config/btop/" 2>/dev/null
  for d in .config/nvim .local/share/nvim .local/state/nvim; do
    if [ ! -e "$keep/$d" ]; then
      mkdir -p "$(dirname "$keep/$d")"
      # -L for the configuration: it may be a link into a dotfiles directory. A link in it that leads nowhere is left out.
      if [ "$d" = .config/nvim ]; then cp -rL --reflink=auto ~/"$d" "$keep/$d" 2>/dev/null; else cp -a --reflink=auto ~/"$d" "$keep/$d"; fi
    fi
    ln -sfn "$keep/$d" "$home/$d"
  done
  # Neovim in the desktop's theme, as Omarchy sets it up.
  theme=~/.local/state/omarchy/current/theme/neovim.lua
  [ -f "$theme" ] && cp -f "$theme" "$keep/.config/nvim/lua/plugins/theme.lua"
  # Tono's own code to work on, as a repository on its main branch.
  if [ ! -d "$home/dev/tono/.git" ]; then
    mkdir -p "$home/dev/tono"
    git -C "$repo" archive HEAD -- . ':!marketing' ':!agent-workspace' | tar -x -C "$home/dev/tono"
    (cd "$home/dev/tono" && git init -q -b main && git add -A && git -c user.name=tono -c user.email=tono@example.com commit -qm "Tono" )
  fi
  if [ "$name" = local ]; then mkdir -p "$(dirname "${HOME_SOCK#unix://}")"; listen="--listen $HOME_SOCK"
  else listen="--listen http://[::1]:${PORT[$name]} --extra-listen http://127.0.0.1:${PORT[$name]} --allow-anonymous"; fi
  (cd "$home" && exec env -i HOME="$home" USER="$USER" LOGNAME="$USER" SHELL=/bin/bash LANG="${LANG:-en_US.UTF-8}" \
      PATH="/usr/local/bin:/usr/bin:/bin:$HOME/.local/share/mise/shims" XDG_RUNTIME_DIR="$PM/$name/run" COLORTERM=truecolor \
      setsid rex server run $listen --state-dir "$PM/$name/state" --restore=false --config "$PM/$name/init.lua" \
      > "$PM/$name/server.log" 2>&1 &)
  for _ in $(seq 30); do sleep 0.2; rx "$name" api call server.status >/dev/null 2>&1 && return 0; done
  echo "the $name server did not start: $(tail -2 "$PM/$name/server.log")" >&2; return 1
}

# new_tab SERVER SESSION CWD [-- COMMAND...]: a tab named by what runs in it, as Tono names one nobody named.
new_tab() {
  local server=$1 session=$2 cwd=$3; shift 3
  rx "$server" window new -s "$session" -c "$cwd" --focus=false "$@" >/dev/null
}
# Sessions and tabs that look like a person's: an editor on Tono's own code, a monitor, a log, shells.
populate() {
  local h sid server w
  empty() { [ "$(rx "$1" ls --json | jq '.sessions | length')" = 0 ]; }
  if empty local; then
    h=$PM/local/home
    sid=$(rx local new tono -c "$h/dev/tono" --json -- nvim tono/src/TonoApp.kt | jq -r .session_id)
    new_tab local "$sid" "$h/dev/tono"
    new_tab local "$sid" "$h" -- btop
  fi
  if empty studio; then
    h=$PM/studio/home; mkdir -p "$h/dev/site/bin"; cp "$tools/log-sim.sh" "$h/dev/site/bin/serve"
    sid=$(rx studio new website -c "$h/dev/site" --json | jq -r .session_id)
    new_tab studio "$sid" "$h/dev/tono" -- nvim rex/src/RexClient.kt
    rx studio send -s "$sid" "clear; bin/serve" >/dev/null; rx studio send-key -s "$sid" enter >/dev/null
  fi
  if empty buildbox; then
    h=$PM/buildbox/home; mkdir -p "$h/infra"
    sid=$(rx buildbox new ci -c "$h" --json -- btop | jq -r .session_id)
    new_tab buildbox "$sid" "$h/infra"
  fi
  if empty laptop; then
    h=$PM/laptop/home; mkdir -p "$h/notes"
    rx laptop new notes -c "$h/notes" >/dev/null
  fi
  # No tab keeps a name of its own.
  for server in local studio buildbox laptop; do
    for sid in $(rx "$server" ls --json | jq -r '.sessions[].session_id'); do
      for w in $(rx "$server" window ls -s "$sid" --json | jq -r '.windows[] | select(.label | test("^Window [0-9a-f]{8}$") | not) | .window_id'); do
        rx "$server" api call session.set_window_label "{\"session_id\":\"$sid\",\"window_id\":\"$w\",\"label\":\"\"}" >/dev/null
      done
    done
  done
}

up() {
  mkdir -p "$PM"
  for name in local studio buildbox laptop; do
    mkdir -p "$PM/$name"; : > "$PM/$name/init.lua"
    [ -n "$(server_pid "$name")" ] || start_server "$name" || exit 1
  done
  # The home server's host list is where Tono reads the other machines from. attic is listed and not running.
  if [ "$(rx local api call hosts.list | jq '.hosts | length')" = 0 ]; then
    rx local api call hosts.put "{\"host\":{\"label\":\"studio\",\"endpoints\":[\"$(address studio)\"],\"icon\":\"desktop\"}}" >/dev/null
    rx local api call hosts.put "{\"host\":{\"label\":\"buildbox\",\"endpoints\":[\"$(address buildbox)\"],\"icon\":\"server\"}}" >/dev/null
    rx local api call hosts.put "{\"host\":{\"label\":\"laptop\",\"endpoints\":[\"$(address laptop)\"]}}" >/dev/null
    rx local api call hosts.put "{\"host\":{\"label\":\"attic\",\"endpoints\":[\"$(address attic)\"],\"icon\":\"server\"}}" >/dev/null
  fi
  populate
  if ! display_up; then
    hyprctl dispatch "hl.dsp.exec_cmd(\"Xwayland $PM_DISPLAY -geometry ${PM_W}x${PM_H} -noreset -nolisten tcp\", { workspace = \"special:post-media silent\", float = true })" >/dev/null
    for _ in $(seq 30); do sleep 0.3; display_up && break; done
  fi
  need_display
  DISPLAY=$PM_DISPLAY xsetroot -solid black 2>/dev/null
  echo "up: $(xd getdisplaygeometry) on $PM_DISPLAY; servers: $(for n in local studio buildbox laptop; do printf '%s=%s ' "$n" "$(server_pid "$n")"; done)"
}

down() {
  tono_stop
  for name in local studio buildbox laptop; do
    for s in $(rx "$name" ls --json 2>/dev/null | jq -r '.sessions[].session_id'); do rx "$name" session kill "$s" >/dev/null 2>&1; done
    pid=$(server_pid "$name"); [ -n "$pid" ] && kill "$pid"
  done
  # The display last, found by its arguments: never pkill Xwayland, the desktop's own is one too.
  for pid in $(pgrep -f "^Xwayland $PM_DISPLAY "); do kill "$pid"; done
  rm -rf "$(dirname "$(dirname "${HOME_SOCK#unix://}")")"
  echo "down"
}

case ${1:-} in up) up ;; down) down ;; *) echo "usage: stage.sh up|down" >&2; exit 2 ;; esac
