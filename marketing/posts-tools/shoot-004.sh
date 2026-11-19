#!/usr/bin/env bash
# Post 004, Know which agent needs you: three stand-in agents (agent-sim.sh) at work in tabs on another
# machine while a shell is in view; one stops to ask, and its tab's mark changes; a click on it, and
# its question is on screen; an answer, and it finishes.
#   shoot-004.sh            every tab listed
#   shoot-004.sh active     with Show Only Active Tabs on
# The marks are small, so this one is shot closer than the others: 960 dp across, not 1200 (4 is the most OMARCHY_UI_SCALE takes), with
# the machines and sessions that have no part in it folded away.
PM_SCALE=4
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
variant=${1:-all}
tono_stop
# The agents: a session of their own on studio, a tab each, named after its task as workers' tabs are.
old=$(session_id studio agents); [ -n "$old" ] && rx studio session kill "$old" >/dev/null
cp "$tools/agent-sim.sh" "$PM/studio/home/bin/agent"
home=$HOME                          # studio's home, as it sees it
rm -f "$PM/004-ask"
sid=$(rx studio new agents --window smooth-scroll -c "$home/dev/tono" --json -- "$home/bin/agent" scroll | jq -r .session_id)
rx studio window new -s "$sid" host-icons -c "$home/dev/tono" --focus=false -- "$home/bin/agent" icons "$PM/004-ask" >/dev/null
rx studio window new -s "$sid" paste-images -c "$home/dev/tono" --focus=false -- "$home/bin/agent" paste >/dev/null
tono_start "$(workspace 004 ".collapsed_hosts = [\"buildbox\", \"laptop\"] | .collapsed_sessions = [\"$(session_id studio website)\"]")" || exit 1
click 1100 700; sleep 2
key ctrl+2; sleep 1.5              # the shell, on this machine
run_in local tono 1 'clear; eza --icons --group-directories-first'; sleep 1
# Positions are for this scale. The toolbar's button takes the keyboard, so a click gives it back to the terminal.
if [ "$variant" = active ]; then click 100 60; sleep 1; click 1150 640; sleep 1; row=326; slug=which-agent-needs-you-active
else row=694; slug=which-agent-needs-you; fi
[ -n "${LOOK:-}" ] && { shot "$PM/004-look.png"; exit 0; }
at 1150 640
rec_start "004-$variant"; mark start; sleep 1.2
touch "$PM/004-ask"; mark asked; sleep 2.6                        # it runs its tests, then asks: the mark changes
glide 150 $row 0.5; sleep 0.25; xd click 1; mark shown; sleep 1.8  # its tab, and its question
typed y; mark answered; sleep 2.6                                 # the answer; it merges and is done
rec_stop
# From a second before it is told to ask to a moment after it is done; the poster is its question, with its mark beside it.
finish "004-$variant" 004-which-agent-needs-you "$slug" "$(marked asked -1)" "$(awk -v a="$(marked asked -1)" -v b="$(marked answered 2.3)" 'BEGIN { print b - a }')" "$(marked shown 1.3)"
rx studio session kill "$sid" >/dev/null; rm -f "$PM/004-ask"
run_in local tono 1 clear
