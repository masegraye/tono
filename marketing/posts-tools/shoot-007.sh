#!/usr/bin/env bash
# Post 007, Super stays Hyprland's: Ctrl+K in bash opens Tono's session switcher (the clash);
# Change Shortcut… moves it to Ctrl+Shift+K for Tono alone; Ctrl+K then cuts the line, as bash means it to.
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
tono_stop
tono_start "$(workspace 007)" || exit 1
click 1100 500; sleep 2          # the keyboard goes to the window; there is no window manager
key ctrl+2; sleep 1              # the shell tab
run_in local tono 1 clear; sleep 1
away
MOUSE=0 rec_start 007
typed 'git log --oneline --since=yesterday -- tono/src rex/src' 60; mark typed; sleep 0.5
key ctrl+a; mark ctrl+a; sleep 0.6
key ctrl+k; mark clash; sleep 1.3
key Escape; mark escape; sleep 0.4
key ctrl+shift+p; mark palette; sleep 0.4
typed shortcut 55; sleep 0.5
key Return; mark actions; sleep 0.6
typed 'change ses' 55; sleep 0.5
key Return; mark press; sleep 0.7
key ctrl+shift+k; mark pressed; sleep 1.1
key Return; mark set; sleep 0.7
key ctrl+k; mark cut; sleep 1.8
rec_stop
run_in local tono 1 clear
# From just before the command is whole to a second after the line is cut; the poster is the new keys, pressed.
finish 007 007-super-stays-hyprlands super-stays-hyprlands "$(marked typed -0.3)" 10 "$(marked pressed 0.7)"
