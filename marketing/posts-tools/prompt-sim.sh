#!/usr/bin/env bash
# Stands in for a coding agent waiting at its prompt, for post 006: a prompt called agent>, which
# says what it was given. A path to a picture is looked at for real (its size is read from the file),
# so the clip shows the file did arrive on this machine. Nothing else about it is an agent.
state() { printf '\033]7501;state=%s:app=agent\033\\' "$1"; }
clear
printf '\033[1;35m●\033[0m Ready in \033[36m%s\033[0m\n\n' "${PWD/#$HOME/\~}"
while IFS= read -r -e -p $'\001\033[1;35m\002agent>\001\033[0m\002 ' line; do
  [ -n "$line" ] || continue
  state working
  # The path comes last, quoted for a shell where it needs to be, with a space after it.
  line=${line%"${line##*[! ]}"}
  file=$(eval "printf '%s' ${line##* }" 2>/dev/null)
  if [ -f "$file" ] && size=$(identify -format '%w×%h' "$file" 2>/dev/null); then
    sleep 0.4; printf '\033[1;35m●\033[0m Looking at \033[36m%s\033[0m  \033[2m%s, %s\033[0m\n\n' "$(basename "$file")" "$size" "$(du -h "$file" | cut -f1)"
  else
    sleep 0.4; printf '\033[1;35m●\033[0m Thinking\n\n'
  fi
  state done
done
