#!/usr/bin/env bash
# Post 009, Tiles like everything else: Tono alone, then Ctrl+, opens Settings as a window of its own,
# beside it half and half as Hyprland tiles two windows; a search for "theme", the Theme result, and two
# built-in themes picked in turn, Tono changing beside Settings each time.
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
tono_stop
tono_start "$(workspace 009)" || exit 1      # a fresh workspace: following Omarchy, no Settings open
click 300 980; sleep 1                       # the keyboard to Tono (an empty part of the sidebar)
# The shell in front, with output in the terminal's own colours, which a theme changes. (Neovim keeps
# its own colour scheme, so it would show the theme reaching Tono and hardly the terminal.)
run_in local tono 1 'clear; eza -1 --icons=always --color=always --group-directories-first'
click 70 260; sleep 2                        # the bash tab
away
rec_start 009; mark alone; sleep 1.2
settings_beside 1912 0 || exit 1             # Settings tiles in beside Tono (no gap here: none in the nested display either)
xd windowsize "$(win)" 1912 $PM_H
mark tiled; sleep 0.4
glide 1440 141 0.35; sleep 0.15; xd click 1; sleep 0.25
typed theme 90; mark searched; sleep 0.6
glide 1440 316 0.3; sleep 0.15; xd click 1; mark theme; sleep 0.9                  # the Theme result
glide 1148 450 0.35; sleep 0.15; xd click 1; mark gruvbox; sleep 1.3               # Gruvbox
glide 1653 1009 0.4; sleep 0.15; xd click 1; mark rose; sleep 1.6                  # Rose Pine, a light one
rec_stop
# Settings appears ~1.3 s after Ctrl+, and the take then waits ~2 s while the windows settle: keep Tono alone
# and the tile appearing, then go on from the search.
T=$PM/takes/009.mkv D=$posts/009-tiles-like-everything-else/media A=$(marked alone 0.2) B=$(marked alone 1.95) C=$(marked tiled 0.05) E=$(marked rose 1.4)
ffmpeg -loglevel error -y -i "$T" -an -filter_complex "[0:v]trim=start=$A:end=$B,setpts=PTS-STARTPTS[a];[0:v]trim=start=$C:end=$E,setpts=PTS-STARTPTS[b];[a][b]concat=n=2:v=1:a=0,scale=1920:1080:flags=lanczos,format=yuv420p[v]" \
  -map "[v]" -r 60 -c:v libx264 -preset slow -crf 16 -movflags +faststart "$D/tiles-like-everything-else.mp4"
ffmpeg -loglevel error -y -ss "$(marked gruvbox 1.0)" -i "$T" -frames:v 1 -vf scale=1920:1080:flags=lanczos "$D/tiles-like-everything-else.png"
ffprobe -v error -show_entries format=duration,size:stream=codec_name,pix_fmt,width,height -of csv=p=0 "$D/tiles-like-everything-else.mp4" | tr '\n' ' '; echo "-> $D/tiles-like-everything-else.mp4"
