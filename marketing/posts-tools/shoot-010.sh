#!/usr/bin/env bash
# Post 010, Remote Hosts, your way: Settings beside Tono, as Hyprland tiles them. Four machines, one that
# couldn't be reached; the laptop gets its icon (ⓘ, Edit…, the laptop, Save) and the sidebar follows;
# then it is dragged to the top by its handle.
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
tono_stop
# As at the start: the laptop has no icon of its own.
laptop=$(rx local api call hosts.list | jq -c '.hosts[] | select(.label == "laptop") | {id, label, endpoints}')
rx local api call hosts.put "{\"host\":$laptop}" >/dev/null
tono_start "$(workspace 010)" || exit 1
click 300 980; sleep 2
settings_beside 1280 || exit 1
click 810 569; sleep 1.5          # Remote Hosts
at 1500 800
rec_start 010; mark list; sleep 0.8
glide 1849 424 0.35; sleep 0.15; xd click 1; mark info; sleep 0.6            # the laptop's ⓘ
glide 1700 800 0.2; xd click --repeat 12 --delay 20 5; sleep 0.3            # down to the foot of its page
glide 1602 1000 0.25; sleep 0.15; xd click 1; mark edit; sleep 0.55         # Edit…
glide 1284 668 0.35; sleep 0.15; xd click 1; mark icon; sleep 0.55          # the laptop
glide 1580 806 0.3; sleep 0.15; xd click 1; mark save; sleep 1.0            # Save, and the sidebar follows
glide 1045 54 0.4; sleep 0.15; xd click 1; mark back; sleep 0.45            # back to the list
drag 1061 424 1061 215; mark dragged; sleep 2
rec_stop
# From the list to a moment after the laptop lands at the top; the poster is the list with its statuses.
finish 010 010-remote-hosts-your-way remote-hosts-your-way "$(marked info -0.9)" "$(awk -v a="$(marked info -0.9)" -v b="$(marked dragged 1.15)" 'BEGIN { print b - a }')" "$(marked dragged 1.1)"
