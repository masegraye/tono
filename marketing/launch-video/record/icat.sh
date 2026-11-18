#!/usr/bin/env bash
# Shows a PNG with the Kitty graphics protocol, in chunks of 4096 as the protocol asks.
data=$(base64 -w0 "$1"); first=1
while [ -n "$data" ]; do
  chunk=${data:0:4096}; data=${data:4096}
  more=0; [ -n "$data" ] && more=1
  if [ $first = 1 ]; then printf '\033_Ga=T,f=100,m=%s;%s\033\\' "$more" "$chunk"; first=0
  else printf '\033_Gm=%s;%s\033\\' "$more" "$chunk"; fi
done
echo
