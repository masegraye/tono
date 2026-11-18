# The launch film

A 47-second film about Tono for posting: what it is, and seven things it does, each shown
in footage of the real app. It is a [HyperFrames](https://hyperframes.heygen.com) project:
`index.html` is the whole film, as HTML with a GSAP timeline, and the `hyperframes` command
renders it to MP4.

```
npm run check                                   # lint and layout checks
npx hyperframes snapshot --at 4.5,20,31.6       # frames to look at, without rendering
npx hyperframes render -o renders/tono-launch.mp4 --video-frame-format png -f 30 -q delivery
```

A render takes about twenty seconds. `AGENTS.md` here is HyperFrames' own notes for agents.

## What isn't in the repository

Three things the film is made from are left out of git, because they are large and can be
made again:

- **The footage**, `assets/footage/*.mp4`: seven screen recordings (see below).
- **The fonts**, `assets/fonts/`: JetBrains Mono Nerd Font, Regular and Bold, copied from
  `/usr/share/fonts/TTF/` on an Omarchy machine.
- **The renders**, `renders/`.

## The footage

The takes are scripted, so they can be shot again after Tono changes. The scripts in
`record/` run on the machine whose screen is recorded, which needs Tono installed, Rex, and
Omarchy (its own screen recorder is what records).

```
record/setup.sh            # a Rex session to film, and a Tono of its own showing it full screen
record/scenes.sh tabs terminal palette agents theme     # one take each, into /tmp/tono-demo/takes
TONO_WEB_URL=… WEB_WORKSPACE=… record/web.sh            # the web app in a bare browser window
record/scenes.sh web phone
record/setup.sh down; record/web.sh down
```

`setup.sh` takes `REMOTE_SESSIONS` (a session to show on another host, as JSON) and
`FOLD_ALSO` (sessions to keep folded). Everything of yours is folded away in the demo's
workspace, so the keys that go to a tab by number stay in the demo.

Things that were learned the hard way and are in the scripts:

- Keys are sent through Hyprland's own shortcut sender. `wtype` makes a keymap of its own,
  and Tono's window, which runs under XWayland, reads keys through the old one and gets
  the wrong keys.
- Notifications are held through Omarchy's shell for the length of a run, or the recorder's
  own "saved" notice lands on the next take.
- The phone take is the web app told by the browser that it is a phone, and touched rather
  than clicked (`record/phone.mjs`). It is not a real phone.
- A tab on another machine shows "Restoring…" for a second when first shown. The film cuts
  past it.

## The sound

`audio/make-bed.py` computes the bed: nothing in it is sampled, so there is nothing to
license. It is at 100 beats a minute, and the film's scenes start on its beats, at 3.6, 9.6,
15.6, 22.8, 29.4, 34.8 and 42.6 seconds. To use other music, put it at
`assets/audio/bed.mp3` and render again; a track at 100 beats a minute will land on the cuts.

## What the film says, and what was checked

Every scene is footage of the app doing the thing. Two of its claims go further than what
has been tried: "on your phone" is shown in a browser's phone mode, and no real phone has
run it; and the theme scene shows four of Omarchy's themes, not all of them.
