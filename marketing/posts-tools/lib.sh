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
PM_SCALE=3.2                   # twice Omarchy's 1.6, so a 1920x1080 clip shows 1200x675 dp
tools=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo=$(cd "$tools/../.." && pwd)
posts=$repo/marketing/posts
HOME_SOCK=unix://$PM/local/rex.sock

[ "$PM_DISPLAY" != ":0" ] && [ "$PM_DISPLAY" != ":1" ] || { echo "refusing: $PM_DISPLAY is a real display" >&2; exit 1; }

# The scratch servers: name, and the port it listens on (none for the home server, which has a socket).
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
# tono_start WORKSPACE-FILE WINDOW-TITLE-PART: starts the packaged jar on the nested display, filling it.
tono_start() {
  need_display
  local jar; jar=$(ls -t "$repo"/build/tasks/_desktopApp_executableJarJvm/*.jar 2>/dev/null | head -1)
  [ -n "$jar" ] || { echo "no jar: run task package" >&2; exit 1; }
  env -u WAYLAND_DISPLAY -u HYPRLAND_INSTANCE_SIGNATURE DISPLAY=$PM_DISPLAY OMARCHY_UI_SCALE=$PM_SCALE \
    java -Dskiko.renderApi=SOFTWARE --enable-native-access=ALL-UNNAMED --add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED \
    -jar "$jar" --workspace "$1" > "$PM/tono.log" 2>&1 &
  echo $! > "$PM/tono.pid"
  WIN=
  for _ in $(seq 60); do sleep 0.5; WIN=$(xd search --name "$2" 2>/dev/null | head -1); [ -n "$WIN" ] && break; done
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
# Moves the pointer there over a moment, as a hand does. glide X Y [steps]
glide() {
  local x0 y0 X Y steps=${3:-18} i
  eval "$(xd getmouselocation --shell)"; x0=$X; y0=$Y
  for ((i = 1; i <= steps; i++)); do
    xd mousemove $(( x0 + ($1 * 2 - x0) * i / steps )) $(( y0 + ($2 * 2 - y0) * i / steps )); sleep 0.016
  done
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
  # The first frame is taken when the file starts to grow.
  until [ -s "$PM/takes/$TAKE.mkv" ]; do sleep 0.02; done
  REC_AT=$(date +%s.%N); : > "$PM/takes/$TAKE.marks"
  sleep 0.8
}
rec_stop() { sleep 0.3; kill -INT "$REC"; wait "$REC" 2>/dev/null; ffprobe -v error -show_entries format=duration -of csv=p=0 "$PM/takes/$TAKE.mkv"; }
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
mark() { awk -v now="$(date +%s.%N)" -v at="$REC_AT" -v name="$1" 'BEGIN { printf "%6.2f %s\n", now - at, name }' | tee -a "$PM/takes/$TAKE.marks"; }
# marked NAME [offset]: when that mark was made in the take just recorded, plus an offset, in seconds.
marked() { awk -v name="$1" -v off="${2:-0}" '$2 == name { printf "%.2f", $1 + off; exit }' "$PM/takes/$TAKE.marks"; }
