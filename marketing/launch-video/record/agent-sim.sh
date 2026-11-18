#!/usr/bin/env bash
# Stands in for a coding agent, for the film: prints what one prints, and reports its state
# the way agents do under Rex (OSC 7501), which is what lights the tab's activity mark.
state() { printf '\033]7501;state=%s:app=agent\033\\' "$1"; }
say() { printf '%b\n' "$1"; sleep "${2:-0.5}"; }
clear
state working
say "\033[1;35m●\033[0m Reading \033[36mtono/src/TerminalView.kt\033[0m" 0.7
say "\033[1;35m●\033[0m Reading \033[36mrex/src/RexHost.kt\033[0m" 0.6
say "\033[1;35m●\033[0m Editing \033[36mtono/src/RexApp.kt\033[0m  \033[32m+18\033[0m \033[31m-2\033[0m" 0.9
say "\033[1;35m●\033[0m Running \033[33mtask test\033[0m" 1.2
say "  \033[32m✓\033[0m 193 tests passed" 0.6
state blocked
printf '\n\033[1;33m?\033[0m Merge into main?  \033[2m(y/n)\033[0m '
read -r -n 1 -t "${1:-8}" _answer || true
printf '\n'
sleep 0.4
state working
say "\033[1;35m●\033[0m Merging \033[36magent/program-copies\033[0m" 0.9
state done
say "\n\033[1;32m✓ Done.\033[0m"
