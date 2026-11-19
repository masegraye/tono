# Shooting the clips for posts

The scripts that make the media for the posts about Tono (`marketing/posts/`, which git doesn't carry: each post's
`notes.md` says what its clip shows). They touch neither the real desktop nor your Rex: Tono runs on a nested X display
on a hidden Hyprland workspace, against scratch Rex servers of its own. Needs `rex`, `Xwayland`, `xdotool`, `xclip`,
`ffmpeg`, ImageMagick, `jq`, Chromium (for 008), and the packaged jar (`task package`).

```sh
task package
marketing/posts-tools/stage.sh up        # the scratch servers, their sessions, and the nested display
marketing/posts-tools/shoot-007.sh       # one take: records, cuts, and writes the post's media/<slug>.mp4 and .png
marketing/posts-tools/shoot-010.sh
marketing/posts-tools/shoot-004.sh; marketing/posts-tools/shoot-004.sh active
marketing/posts-tools/shoot-006.sh
marketing/posts-tools/stage.sh down      # ends Tono, the sessions, the servers and the display
marketing/posts-tools/shoot-008.sh       # a still from the scroll telemetry; needs no stage
marketing/posts-tools/trim-film.sh       # 001, 002 and 003, cut from the launch film's footage; needs no stage
```

## The stage

- **Machines.** A home server on a socket, shown as Local, and three more on loopback TCP whose addresses read like
  machines': `http://studio.localhost:7301`, `buildbox…:7302`, `laptop…:7303` (names under `.localhost` resolve to this
  machine). A fourth, `attic…:7304`, is in the host list and doesn't run, so it shows "Couldn't connect". The hosts are
  put in the home server's list with `hosts.put`. **Never `rex hosts`:** it ignores `--server` and writes to your own
  home server.
- **A home of their own.** Each server runs in a mount namespace where a scratch directory is mounted over `$HOME`, so
  the paths it shows are a person's (`~/dev/tono`, `/home/you/.local/state/rex/uploads/…/clipboard.png`) and none of
  your files can be reached from it. Nothing is mounted outside the namespace, and the server doesn't start unless it
  finds the scratch home there. Its shells get Omarchy's own prompt and not your `~/.bashrc`; Neovim runs from a copy of
  your configuration and plugins, kept in `build/post-media/`.
- **The display.** `Xwayland :91`, 3840×2160, floating on the hidden workspace `special:post-media`. Tono is drawn at
  `OMARCHY_UI_SCALE=3.2` and the take scaled to 1920×1080, so a clip shows 1200×675 dp of Tono with sharper text than a
  recording made at that size. (4 is the most the scale takes; 004 uses it, to show the tabs' marks closer.)
- **Tono's workspace** is written afresh for each take (`workspace` in `lib.sh`): the home server only, sounds and
  notifications off.

## A take

`lib.sh` has the pieces: `tono_start`, `click`/`glide`/`drag` (positions in the clip's pixels, at scale 3.2), `key`,
`typed`, `rec_start`/`mark`/`rec_stop`, and `finish`, which cuts between marks and writes the clip (H.264, `yuv420p`,
1920×1080, 60 fps, silent) and its poster. Positions are measured from a screenshot (`shot FILE`) and kept in the
script, so a change to Tono's layout means measuring again.

The agents in 004 and 006 are stand-ins (`agent-sim.sh`, `prompt-sim.sh`): scripts that print what an agent prints and
report their state as agents do under Rex. What Tono does in answer is real.
