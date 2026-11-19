# Helpers for shooting the clips for Tono's posts (marketing/posts, which git doesn't carry).
# Sourced by stage.sh and the shoot-*.sh scripts.
#
# Nothing here touches the real desktop or the user's Rex: Tono runs on a nested X display on
# a hidden Hyprland workspace, against scratch Rex servers that stage.sh starts, and every
# xdotool, xclip and ffmpeg call names that display. `rex hosts` is never used: it ignores
# --server and always talks to the user's own home server.
PM=${PM:-/tmp/post-media}
PM_DISPLAY=${PM_DISPLAY:-:91}
PM_W=3840 PM_H=2160            # the nested display; clips are scaled to half of it
PM_SCALE=${PM_SCALE:-3.2}     # twice Omarchy's 1.6, so a 1920x1080 clip shows 1200x675 dp; more for a closer look
tools=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo=$(cd "$tools/../.." && pwd)
posts=$repo/marketing/posts
# The home server's socket is shown in Settings, so it is where a person's would be, with this work's name in it.
HOME_SOCK=unix://${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/post-media/rex/server.sock

[ "$PM_DISPLAY" != ":0" ] && [ "$PM_DISPLAY" != ":1" ] || { echo "refusing: $PM_DISPLAY is a real display" >&2; exit 1; }

# The scratch servers: name, and the port it listens on (none for the home server, which has a socket:
# Tono takes a host for its home server only when it is on a socket).
# Names under .localhost resolve to this machine, so their addresses read like real machines'.
declare -A PORT=([studio]=7301 [buildbox]=7302 [laptop]=7303 [attic]=7304)
address() { if [ "$1" = local ]; then echo "$HOME_SOCK"; else echo "http://$1.localhost:${PORT[$1]}"; fi; }
# rx SERVER rex-arguments...: the rex command against one scratch server, and never any other.
rx() { local s=$1; shift; env -u REX_SESSION -u REX_BLOCK rex --autostart=false -S "$(address "$s")" "$@"; }

# --- the nested display ---
xd() { DISPLAY=$PM_DISPLAY xdotool "$@"; }
display_up() { DISPLAY=$PM_DISPLAY xdotool getdisplaygeometry >/dev/null 2>&1; }
need_display() { display_up || { echo "the nested display $PM_DISPLAY is not up (stage.sh up)" >&2; exit 1; }; }
shot() { DISPLAY=$PM_DISPLAY import -window root "$1"; }

# --- Tono ---
# tono_start WORKSPACE-FILE [WINDOW-TITLE-PATTERN]: starts the packaged jar on the nested display, filling it.
tono_start() {
  need_display
  local jar; jar=$(ls -t "$repo"/build/tasks/_desktopApp_executableJarJvm/*.jar 2>/dev/null | head -1)
  [ -n "$jar" ] || { echo "no jar: run task package" >&2; exit 1; }
  env -u WAYLAND_DISPLAY -u HYPRLAND_INSTANCE_SIGNATURE DISPLAY=$PM_DISPLAY OMARCHY_UI_SCALE=$PM_SCALE \
    XCURSOR_THEME="${PM_CURSOR:-Adwaita}" XCURSOR_SIZE=64 \
    java -Dskiko.renderApi=SOFTWARE --enable-native-access=ALL-UNNAMED --add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED \
    -jar "$jar" --workspace "$1" > "$PM/tono.log" 2>&1 &
  echo $! > "$PM/tono.pid"
  WIN=
  for _ in $(seq 60); do sleep 0.5; WIN=$(xd search --name "${2:-Local\$}" 2>/dev/null | head -1); [ -n "$WIN" ] && break; done
  [ -n "$WIN" ] || { echo "Tono's window did not open" >&2; return 1; }
  echo "$WIN" > "$PM/tono.win"
  sleep 1
  xd windowmove "$WIN" 0 0; xd windowsize "$WIN" $PM_W $PM_H
  sleep 2
}
tono_stop() {
  [ -f "$PM/tono.pid" ] && kill "$(cat "$PM/tono.pid")" 2>/dev/null
  rm -f "$PM/tono.pid" "$PM/tono.win"
  sleep 1
}
win() { cat "$PM/tono.win"; }
# Positions are in clip pixels (1920x1080), doubled for the display.
at() { xd mousemove $(( $1 * 2 )) $(( $2 * 2 )); }
click() { at "$1" "$2"; sleep 0.15; xd click 1; }
# Moves the pointer there over a moment, as a hand does: quick away, slow to arrive. glide X Y [seconds]
glide() {
  local X Y
  eval "$(xd getmouselocation --shell)"
  # One xdotool for the whole move, a step a frame: starting a process for each step is too slow.
  xd $(awk -v x0="$X" -v y0="$Y" -v x1=$(( $1 * 2 )) -v y1=$(( $2 * 2 )) -v t="${3:-0.4}" 'BEGIN {
    n = int(t * 60); if (n < 2) n = 2
    for (i = 1; i <= n; i++) { p = i / n; e = 1 - (1 - p) ^ 3; printf "mousemove %d %d sleep 0.0167 ", x0 + (x1 - x0) * e, y0 + (y1 - y0) * e }
  }')
}
# drag X1 Y1 X2 Y2: the left button held from one place to the other.
drag() {
  glide "$1" "$2" 0.35; sleep 0.15; xd mousedown 1; sleep 0.15
  glide "$1" $(( $2 - 6 )) 0.08; glide "$3" "$4" "${5:-0.6}"; sleep 0.3; xd mouseup 1
}
away() { xd mousemove $(( PM_W - 1 )) $(( PM_H - 1 )); }      # the pointer out of the picture
key() { xd key --clearmodifiers "$@"; }
typed() { xd type --delay "${2:-75}" "$1"; }

# --- recording ---
# rec_start NAME ... rec_stop: the whole display at 60 frames a second, kept as it is; cut and scaled by finish.
rec_start() {
  need_display
  TAKE=$1; mkdir -p "$PM/takes"
  ffmpeg -loglevel error -y -f x11grab -draw_mouse "${MOUSE:-1}" -framerate 60 -video_size ${PM_W}x${PM_H} -i "$PM_DISPLAY" \
    -c:v libx264 -preset ultrafast -qp 0 -pix_fmt yuv444p "$PM/takes/$TAKE.mkv" &
  REC=$!
  : > "$PM/takes/$TAKE.clock"
  sleep 1.5
}
# The recorder starts and writes when it likes, so a mark is kept by the clock, and when the take ends
# they are counted back from its end, which is known: the moment the recorder was told to stop.
rec_stop() {
  sleep 0.3
  local end length
  end=$(date +%s.%N); kill -INT "$REC"; wait "$REC" 2>/dev/null
  length=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$PM/takes/$TAKE.mkv")
  awk -v end="$end" -v len="$length" '{ printf "%6.2f %s\n", len - (end - $1), $2 }' "$PM/takes/$TAKE.clock" | tee "$PM/takes/$TAKE.marks"
  echo "$length s"
}
# finish TAKE POST-DIR SLUG START DURATION POSTER-TIME [ffmpeg video filter before scaling]
# Writes media/SLUG.mp4 (H.264, yuv420p, 1920x1080, 60 fps, silent) and media/SLUG.png beside it.
finish() {
  local take=$1 dir=$posts/$2/media slug=$3 start=$4 length=$5 poster=$6 pre=${7:+$7,}
  mkdir -p "$dir"
  ffmpeg -loglevel error -y -ss "$start" -t "$length" -i "$PM/takes/$take.mkv" -an \
    -vf "${pre}scale=1920:1080:flags=lanczos,format=yuv420p" -r 60 -c:v libx264 -preset slow -crf 16 -movflags +faststart "$dir/$slug.mp4"
  ffmpeg -loglevel error -y -ss "$poster" -i "$PM/takes/$take.mkv" -frames:v 1 -vf "${pre}scale=1920:1080:flags=lanczos" "$dir/$slug.png"
  ffprobe -v error -show_entries format=duration,size:stream=codec_name,pix_fmt,width,height,r_frame_rate -of csv=p=0 "$dir/$slug.mp4" | tr '\n' ' '; echo "-> $dir/$slug.mp4"
}

# --- a take's stage ---
# workspace NAME [jq filter]: a workspace file for one take, holding only the scratch home server, with
# sounds and notifications off. The other machines come from that server's host list.
workspace() {
  jq -n --arg home "$HOME_SOCK" '{hosts: [{label: "Local", endpoint: $home}], last_host: "Local", sounds: false,
    notifications: {system_notifications: false, blocked: false, error: false, done: false, program_messages: false},
    host_order: ["Local", "studio", "buildbox", "laptop", "attic"]} | '"${2:-.}" > "$PM/ws-$1.json"
  echo "$PM/ws-$1.json"
}
# A session's id, its nth tab's window, and that tab's terminal, on a scratch server.
session_id() { rx "$1" ls --json | jq -r --arg l "$2" '.sessions[] | select(.label == $l) | .session_id'; }
tab_block() { rx "$1" window ls -s "$(session_id "$1" "$2")" --json | jq -r ".windows[$3].focused_block_id"; }
# run_in SERVER SESSION TAB-INDEX 'command': typed at once into that tab's shell, for what happens off screen.
run_in() { local sid; sid=$(session_id "$1" "$2"); rx "$1" send-key -s "$sid" -b "$(tab_block "$1" "$2" "$3")" ctrl+c >/dev/null; sleep 0.2; rx "$1" send -s "$sid" -b "$(tab_block "$1" "$2" "$3")" "$4" >/dev/null; rx "$1" send-key -s "$sid" -b "$(tab_block "$1" "$2" "$3")" enter >/dev/null; }
# mark NAME: when in the take something happened, for choosing where to cut.
mark() { echo "$(date +%s.%N) $1" >> "$PM/takes/$TAKE.clock"; }
# marked NAME [offset]: when that mark was made in the take just recorded, plus an offset, in seconds.
marked() { awk -v name="$1" -v off="${2:-0}" '$2 == name { printf "%.2f", $1 + off; exit }' "$PM/takes/$TAKE.marks"; }
# settings_beside MAIN-WIDTH: opens Settings (a window of its own) and puts it beside the main window,
# which is given MAIN-WIDTH of the display's pixels, the two filling the display as tiled windows do.
settings_beside() {
  local main=$1 gap=${2:-16}
  key ctrl+comma
  SETTINGS=
  for _ in $(seq 20); do sleep 0.3; SETTINGS=$(xd search --name '^Tono Settings$' 2>/dev/null | head -1); [ -n "$SETTINGS" ] && break; done
  [ -n "$SETTINGS" ] || { echo "Settings did not open" >&2; return 1; }
  xd windowsize "$(win)" "$main" $PM_H; xd windowmove "$(win)" 0 0
  xd windowsize "$SETTINGS" $(( PM_W - main - gap )) $PM_H; xd windowmove "$SETTINGS" $(( main + gap )) 0
  sleep 2
}
