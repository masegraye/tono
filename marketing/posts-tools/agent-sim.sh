#!/usr/bin/env bash
# Stands in for a coding agent, for the clips (after marketing/launch-video/record/agent-sim.sh): prints
# what one prints, and reports its state the way agents do under Rex (OSC 7501), which is what Tono's
# sidebar marks follow. What Tono does in answer is real; the agent and its work are not.
#   agent-sim.sh PLAN [ASK-FILE]
# It works through PLAN's lines, slowly, for as long as it is left to. When ASK-FILE appears it finishes
# its tests, stops and asks its question (state blocked), and after a key goes on to done.
state() { printf '\033]7501;state=%s:app=agent\033\\' "$1"; }
say() { printf '%b\n' "$1"; }
dot='\033[1;35m●\033[0m'
read_() { say "$dot Reading \033[36m$1\033[0m"; }
edit_() { say "$dot Editing \033[36m$1\033[0m  \033[32m+$2\033[0m \033[31m-$3\033[0m"; }
run_() { say "$dot Running \033[33m$1\033[0m"; }
note_() { say "  \033[2m$1\033[0m"; }

case ${1:-scroll} in
  scroll)
    task='Make the wheel keep up over a slow link'
    steps=('read_ tono/src/TerminalScroll.kt' 'read_ rex/src/RexTerminalStream.kt' 'read_ rex/src/ScrollTelemetry.kt'
      'note_ "Reports wait for each answer: one per round trip."' 'edit_ rex/src/RexControl.kt 41 12' 'run_ "tools/scroll/analyze.py /tmp/scroll.jsonl"'
      'note_ "wheel -> drawn: p50 113.6 ms, was 827.1"' 'edit_ tono/src/TerminalView.kt 23 9' 'read_ tono/test@jvm/ScrollMeasureTest.kt'
      'run_ "./kotlin test -m tono -p jvm"' 'edit_ docs/development.md 14 2' 'run_ "task test"')
    question='Merge agent/smooth-scroll into main?'; tests=193 ;;
  icons)
    task='Give each remote host an icon'
    steps=('read_ tono/src/SettingsHosts.kt' 'read_ rex/src/RexClient.kt' 'edit_ tono/src/HostIcons.kt 64 0'
      'edit_ tono/src/SettingsHosts.kt 38 6' 'run_ "./kotlin test -m tono -p jvm"' 'edit_ CHANGELOG.md 1 0')
    question='Merge agent/host-icons into main?'; tests=201 ;;
  paste)
    task='Paste an image into a terminal on another machine'
    steps=('read_ tono/src/Uploads.kt' 'read_ rex/src/Upload.kt' 'read_ tono/src@jvm/TonoPlatform.jvm.kt'
      'edit_ rex/src/Upload.kt 52 7' 'note_ "A failed upload types nothing."' 'run_ "./kotlin test -m rex -p jvm"'
      'edit_ tono/src/Uploads.kt 31 4' 'read_ tono/test@jvm/UploadsTest.kt' 'edit_ tono/test@jvm/UploadsTest.kt 47 0'
      'run_ "./kotlin test -m tono -p jvm"' 'edit_ docs/using-tono.md 9 0' 'run_ "task test"')
    question='Merge agent/paste-images into main?'; tests=188 ;;
esac
ask=${2:-}

clear
state working
say "\033[1m>\033[0m $task\n"
i=0
while :; do
  [ -n "$ask" ] && [ -e "$ask" ] && break
  if [ $i -lt ${#steps[@]} ]; then eval "${steps[$i]}"; i=$((i + 1)); fi
  # A step every second or two, looked for the sign to ask ten times a second.
  for _ in $(seq $((RANDOM % 12 + 9))); do [ -n "$ask" ] && [ -e "$ask" ] && break; sleep 0.1; done
done
run_ "task test"; sleep 0.5
say "  \033[32m✓\033[0m $tests tests passed"; sleep 0.3
state blocked
printf '\n\033[1;33m?\033[0m %s  \033[2m(y/n)\033[0m ' "$question"
read -r -n 1 _answer
printf '\n'
sleep 0.3
state working
say "$dot Merging \033[36m${question#Merge }\033[0m" | sed 's/ into main?//'
sleep 0.9
state done
say "\n\033[1;32m✓ Done.\033[0m"
sleep 3600
