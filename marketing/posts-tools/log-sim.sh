#!/usr/bin/env bash
# Stands in for a service's log, for the clips: coloured lines, a few a second, for ever.
c() { printf '\033[%sm%s\033[0m' "$1" "$2"; }
paths=(/api/sessions /api/hosts /healthz /api/tabs/42 /assets/app.wasm /api/upload /api/theme)
n=0
while :; do
  n=$((n + 1)); t=$(date +%H:%M:%S); p=${paths[RANDOM % ${#paths[@]}]}; ms=$((RANDOM % 180 + 3))
  case $((RANDOM % 14)) in
    0) printf '%s %s %s\n' "$(c 2 "$t")" "$(c '1;33' WARN)" "slow response $(c 36 "$p") $(c 33 "${ms}0ms")" ;;
    1) printf '%s %s %s\n' "$(c 2 "$t")" "$(c '1;31' ERROR)" "upstream reset on $(c 36 "$p"), retrying" ;;
    2) printf '%s %s %s\n' "$(c 2 "$t")" "$(c '1;35' DEBUG)" "cache hit ratio $(c 32 "0.$((RANDOM % 20 + 80))")" ;;
    *) printf '%s %s %s %s %s\n' "$(c 2 "$t")" "$(c '1;32' INFO )" "$(c 34 GET)" "$(c 36 "$p")" "$(c 32 200) $(c 2 "${ms}ms")" ;;
  esac
  sleep "0.$((RANDOM % 6 + 2))"
done
