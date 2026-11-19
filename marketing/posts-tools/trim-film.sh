#!/usr/bin/env bash
# Posts 001, 002 and 003: clips cut from the launch film's footage (marketing/launch-video/assets/footage,
# which git doesn't carry), with a poster each. Nothing is recorded here.
#
# tabs.mp4 is one take: Control held and the digits fading in over btop (to 3.0 s), Neovim (to 4.17), a
# shell on another machine (4.45 to 7.02), btop there (7.43 to 9.88), a shell back on this one. Between
# machines a tab says "Restoring…" for a third of a second; the cuts go past it, as the film's do.
# theme.mp4 changes the desktop's theme at 2.98, 6.25, 9.53 and 12.83 s.
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
footage=${FOOTAGE:-$repo/marketing/launch-video/assets/footage}

# cut SOURCE POST-DIR SLUG POSTER-TIME-IN-SOURCE START:END...   The pieces, in order, joined.
cut() {
  local source=$footage/$1 dir=$posts/$2/media slug=$3 poster=$4 filter= names= n=0 piece
  shift 4
  for piece in "$@"; do
    filter+="[0:v]trim=start=${piece%:*}:end=${piece#*:},setpts=PTS-STARTPTS[v$n];"; names+="[v$n]"; n=$((n + 1))
  done
  mkdir -p "$dir"
  ffmpeg -loglevel error -y -i "$source" -an -filter_complex "${filter}${names}concat=n=$n:v=1:a=0,format=yuv420p[out]" -map "[out]" \
    -r 60 -c:v libx264 -preset slow -crf 16 -movflags +faststart "$dir/$slug.mp4"
  ffmpeg -loglevel error -y -ss "$poster" -i "$source" -frames:v 1 "$dir/$slug.png"
  ffprobe -v error -show_entries format=duration,size:stream=codec_name,pix_fmt,width,height,r_frame_rate -of csv=p=0 "$dir/$slug.mp4" | tr '\n' ' '; echo "-> $dir/$slug.mp4"
}

# 001 Meet Tono: the window whole, the digits, Neovim, then two tabs on another machine.
cut tabs.mp4 001-meet-tono meet-tono 3.6  0.6:4.15 4.45:7.0 7.45:9.25
# 002 Every machine in one sidebar: the digits already fading in, and a hop a second or two: this machine, the other, and back.
cut tabs.mp4 002-every-machine-one-sidebar every-machine-one-sidebar 2.6  1.5:4.15 4.45:6.5 7.45:9.05 9.9:11.4
# 003 It follows your Omarchy theme: three changes of theme, uncut.
cut theme.mp4 003-follows-omarchy-theme follows-omarchy-theme 7.6  2.0:11.0
