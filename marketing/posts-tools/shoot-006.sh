#!/usr/bin/env bash
# Post 006, Paste a screenshot into an agent (the paste take): a stand-in agent's prompt (prompt-sim.sh)
# in a tab on another machine; a picture on the clipboard; Ctrl+Shift+V, and Tono uploads it to that
# machine and the path is typed at the prompt; Enter, and the stand-in reads the file's size from it.
# The clipboard is the nested display's own, which the desktop's never sees.
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
tono_stop
old=$(session_id studio agents); [ -n "$old" ] && rx studio session kill "$old" >/dev/null
cp "$tools/prompt-sim.sh" "$PM/studio/home/bin/agent-prompt"
sid=$(rx studio new agents --window sidebar-fix -c "$HOME/dev/tono" --json -- "$HOME/bin/agent-prompt" | jq -r .session_id)
tono_start "$(workspace 006)" || exit 1
click 1100 700; sleep 2
key ctrl+6; sleep 2                 # the agent's tab, on studio
# The picture: a region of Tono itself, as a screenshot tool would leave it on the clipboard.
shot "$PM/006-screen.png"; magick "$PM/006-screen.png" -crop 1500x1000+0+150 +repage "$PM/006-clipboard.png"
DISPLAY=$PM_DISPLAY xclip -selection clipboard -t image/png -i "$PM/006-clipboard.png"
away
MOUSE=0 rec_start 006; mark start; sleep 0.6
typed 'why is this row clipped? ' 65; mark typed; sleep 0.5
key ctrl+shift+v; mark pasted; sleep 2.2
key Return; mark entered; sleep 2.4
rec_stop
finish 006 006-paste-screenshot-into-agent paste-screenshot-into-agent "$(marked start -0.3)" "$(awk -v a="$(marked start -0.3)" -v b="$(marked entered 2.1)" 'BEGIN { print b - a }')" "$(marked pasted 1.8)"
rx studio session kill "$sid" >/dev/null
for pid in $(pgrep -f "^xclip -selection clipboard -t image/png -i $PM/"); do kill "$pid"; done
