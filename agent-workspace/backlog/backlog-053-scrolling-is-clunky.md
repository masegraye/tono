# backlog-053: Scrolling a terminal is clunky

| | |
|---|---|
| Status | done (commits `009a3fd`, `8e8f3f1`, `bc6a5af`, `46e9ed0`, `3d2eff1`, `c0ec1d7`, 2026-11-19) |
| Area | Reading a terminal |
| Size | medium |
| Source | Mase, 2026-11-19, in the installed desktop app on Linux |

## What

Scrolling up and down in a terminal with the mouse wheel or touchpad feels clunky and chunky, not smooth. It's worst with a Pi agent running in the tab, but Pi isn't the only case; any long body of text should be checked. Seen on Linux so far; it may happen elsewhere.

## Why

Scrolling back through output is one of the most common things done in a terminal.

## Builds on

- Scrolling: `rex/src/TerminalPointer.kt` (the wheel: to the program when it tracks the mouse, otherwise `scrollViewport` by rows), and `GhosttyTerminal.scrollViewport`.
- Drawing: `tono/src/TerminalView.kt` (`drawTerminal`, `RowLayouts`, reworked in backlog-040 and 017).
- Frames: `GhosttyRenderer` on the JVM.
- backlog-047 lists Rex for Mac's "smooth scrolling" setting.

## Promote when

Now.

## Not known yet

Which of these it is, or more than one:
- the wheel's steps are too big (whole rows, several per notch, no pixel-level scrolling for touchpads);
- each scroll costs a slow frame (text layout, many runs, images);
- output arriving while scrolled back fights the scroll;
- a program that tracks the mouse (a TUI like Pi) gets the wheel, so scrolling waits on it and the server to redraw;
- the desktop toolkit delivers wheel events coarsely under XWayland.

## What was found

Measured three ways, with the telemetry built first (`rex/src/ScrollTelemetry.kt`, off unless asked for: see
`docs/development.md`):

- **The desktop app on a nested X server** (`:83` on a hidden Hyprland workspace, 2376×1300, a terminal of
  156×45, software rendering, against a scratch Rex server), with `xdotool` wheel clicks.
- **Offscreen**, `tono/test@jvm/ScrollMeasureTest.kt`: wheel events through `TerminalPointer`, frames read by
  `GhosttyRenderer` and drawn by `drawTerminal`, at 3400×2000 and scale 1.6, which is a terminal of 74 rows on
  the 4K screen at Omarchy's scale.
- **The web app** in headless Chromium, 145×41, against the same scratch server.

### The causes

| Candidate | Applies? | What the numbers say |
|---|---|---|
| Frame cost | **Yes, the main one on the desktop** | After the view scrolls, libghostty-vt marks every row changed (its `render.h` says so). Every row was read again as a new object, and the text layouts were kept by object, so **every wheel event laid out the whole screen**: 45 of 45 rows on the nested desktop, 74 of 74 offscreen. For a colored log that was 26 ms at the median and 109 ms at p95 for layout alone at 4K, and no frame came in under 16.7 ms. |
| Wheel events taken one to a frame | **Yes** | The terminal's loop handled one event, drew a frame, waited 4 ms, and took the next. Thirty notches in 60 ms (a wheel flicked) went on scrolling for 110 ms after the wheel stopped in plain text, and 226 ms in a colored log. The same for a program that takes the wheel: 232 ms. |
| Step size per notch | **On the web, badly. On the desktop, no** | The desktop's toolkit says 1 for a notch, which was three rows: the usual, and Ghostty's. A browser says pixels, and the same sum made **one notch 50 rows** (it was capped there) and a touchpad's 4 pixels 12 rows: 480 pixels of two fingers scrolled 1440 rows. |
| No scrolling within a row | **Yes** | Everything moved by whole rows at once, three for a notch. Nothing drew between rows. |
| A pager got nothing | **Yes, found on the way** | `less`, `man` and the pager of `git log` use the alternate screen and don't ask for the mouse. The wheel did nothing there at all: 0 rows, 0 reports. |
| Output arriving while scrolled back | **Not in Tono** | Lines added at the bottom, a line rewritten in place, and an erased screen all leave a scrolled-back view where it is (checked against libghostty-vt). What does move it is a program that erases the history (`ESC[3J`): the view is thrown to the live screen. Pi's own TUI does that on every full redraw (its `fullRender(true)` writes `ESC[2J ESC[H ESC[3J`: on a change of size, and when its output shrinks). That is Pi's doing, and happens in any terminal. |
| The round trip of a program that takes the wheel | **No** | From the wheel to the program's answer on screen took 21 to 25 ms at the median with frames at 60 a second, and 7 to 9 ms with frames unpaced. Most of it is waiting for the next frame. Only the queueing above made it slow. |
| Coarse events under XWayland | **In part; needs the real desktop** | Java's X toolkit gets whole notches and nothing finer (`awt unit rotation=-1 precise=-1.0 amount=3` for every event). Each `xdotool` click arrived as its own event, on time. XWayland turns a touchpad's scrolling into the same clicks for a client like this, so a touchpad should move three rows at a step as well. Not seen: there is no touchpad on the nested display. |

Why Pi was worst: its output is the most styled there is (many short runs to a row, so the most to lay out
each time), and it erases the history when it redraws everything, which throws the view to the bottom.

### The numbers

Desktop app, nested display, software rendering, 156×45. "Wheel to drawn" is from the wheel event arriving to
the frame it asked for being drawn.

| Run | | Before | After |
|---|---|---|---|
| Colored log, 10 notches, one every 100 ms | wheel to drawn, median / p95 / max | 29.8 / 33.2 / 72.4 ms | 8.2 / 21.8 / 22.0 ms |
| | rows laid out per notch | 45 | 3 |
| | drawing a frame, median / max | 13.7 / 54.5 ms | 2.9 / 3.5 ms |
| | window frames late | 10 of 75 | 0 of 82 |
| Colored log, 40 notches, one every 15 ms | wheel to drawn, median / p95 | 38.2 / 39.6 ms | 19.3 / 21.6 ms |
| | gap between window frames, median | 23.2 ms | 15.9 ms |
| Colored log, 30 notches in 60 ms | wheel to drawn, median / p95 / max | 145.7 / 226.1 / 226.4 ms | 19.2 / 20.4 / 20.7 ms |
| Plain text, 30 notches in 60 ms | wheel to drawn, median / p95 / max | 51.6 / 95.2 / 110.1 ms | 20.5 / 21.6 / 22.1 ms |
| Plain text, 10 notches, one every 100 ms | wheel to drawn, median / p95 / max | 8.5 / 19.5 / 38.7 ms | 6.9 / 7.8 / 19.5 ms |
| Full-screen program with the mouse, 30 notches in 60 ms | wheel to its answer drawn, median / p95 / max | 124.4 / 218.1 / 232.5 ms | 20.8 / 23.1 / 23.5 ms |
| The same, 10 notches, one every 100 ms | wheel to its answer drawn, median / p95 | 24.9 / 28.1 ms | 21.5 / 23.9 ms |
| `less`, 10 notches | | nothing happens | scrolls; 8.5 ms median to its answer drawn |

With the default renderer and frames unpaced (`-Dskiko.vsync.enabled=false`), which takes the screen's refresh
out of it: a colored log at one notch every 15 ms went from 27.9 / 59.8 / 63.6 ms (median / p95 / max, wheel to
drawn) to 4.2 / 5.4 / 5.8 ms, and drawing a frame from 11.7 ms to 2.5 ms.

Offscreen, 3400×2000 at 1.6, 74 rows, one notch a step. About 10 ms of each whole frame here is filling
3400×2000 pixels on the CPU, which the app's GPU renderer doesn't do.

| Scrolling | Rows laid out per notch: before → after | Laying out text, median / p95: before → after | Whole frame, median / p95 / max: before → after | Frames over 16.7 ms: before → after |
|---|---|---|---|---|
| Plain text | 74 → 3 | 2.9 / 5.4 → 0.4 / 0.5 ms | 15.2 / 18.4 / 29.8 → 12.0 / 13.1 / 13.9 ms | 8 → 0 of 50 |
| Colored log (24 runs to a row) | 74 → 3 | 26.4 / 109.1 → 1.5 / 1.9 ms | 51.7 / 138.9 / 158.4 → 28.6 / 29.8 / 30.9 ms | 50 → 50 of 50 |
| An agent's output | 74 → 2.2 | 9.7 / 38.4 → 0.5 / 0.7 ms | 23.2 / 55.5 / 60.1 → 12.9 / 13.2 / 13.6 ms | 50 → 0 of 50 |
| The same, while a spinner turns | 74 → 2.2 | 7.8 / 8.2 → 0.6 / 1.2 ms | 20.6 / 21.3 / 21.8 → 13.0 / 15.8 / 21.9 ms | 50 → 1 of 50 |
| Back down the way one came | 74 → 0 | 8.1 / 8.5 → 0 ms | 21.2 / 21.8 / 21.9 → 12.3 / 12.6 / 12.8 ms | 50 → 0 of 50 |
| A full-screen program redrawing three lines on | 74 → 4 | 10.7 / 21.0 → 0.4 / 0.5 ms | 24.1 / 34.9 / 212.7 → 12.8 / 13.2 / 14.3 ms | 50 → 0 of 50 |
| An agent's output with Smooth Scrolling, every frame of the glide | | | 9.2 / 13.2 / 14.8 ms | 0 of 700 |

The web app, headless Chromium. Its "before" already had the layout change in, so it shows the wheel's steps
and not the frame cost.

| | Before | After |
|---|---|---|
| Rows scrolled by one notch (100 pixels) | 50 | 6 |
| Rows scrolled by 480 pixels of touchpad (120 events of 4) | 1440 | 28 |
| Reports sent to a program that takes the wheel, for the same | 120 | 28 |
| Drawing a frame of the colored log, median | 22.5 ms (41 rows new each notch) | 6.2 ms |

### Ghostty and Rex for Mac

- **Rex for Mac** has Smooth scrolling in Settings › Terminal › Scrolling: "Move through terminal history by
  pixels instead of whole lines", for a trackpad or Magic Mouse, off by default (changelog, preview 22). It is
  for the history only: full-screen programs still move a line at a time, and there is an open request to
  change that. Its page-scrolling actions jump even with it on.
- **Ghostty**, as I remember its `Surface.scrollCallback` and not read again for this: three rows to a notch; a
  touchpad's pixels add up until they come to a row, and the view moves by whole rows; on the alternate screen
  the wheel is sent as cursor keys while mode 1007 is on; a program that takes the mouse gets one report a notch.
  It reads the touchpad's own pixels, being a Wayland client, which the desktop Tono can't.
- libghostty-vt has what smooth scrolling needs, and its `render.h` describes it: "overscan" rows past the
  view's edges, and a row id that stays with a row as it moves.

## What was built

- **A row that only moved keeps its layout.** Rows that draw the same are equal (`TerminalRow`, `TerminalRun`),
  and layouts are kept by that, for the last four screens of rows. A notch lays out the three rows it brings
  in. It helps a full-screen program too, which draws the same lines a few rows on. On both platforms, since
  it is in the shared code.
- **Everything asked of a terminal while a frame was being made is done before the next frame**, up to 256
  things. A flicked wheel stops when the wheel does.
- **The wheel in rows** (`tono/src/TerminalScroll.kt`). A touchpad's small moves add up to a row, and the rest
  is kept. In a browser, pixels are the rows they cover. A notch is still three rows on the desktop.
- **Smooth Scrolling**, a setting: Rex for Mac's name, off by default, in Settings › Terminal › Scrolling, in
  the palette (`client.setting.terminal.scrolling.smooth`), and in the settings file as
  `"terminal": {"smooth_scrolling": true}`. On, a terminal reads six rows past each edge of its view, and a
  frame that scrolled is drawn where the picture was and let out from there with each frame of the window:
  all but a third of what is left every 45 ms, so most of a notch is covered in a tenth of a second.
- **In a pager the wheel is the up and down keys**, three to a notch, as xterm's alternate scroll has it: on
  the alternate screen, when the program hasn't asked for the mouse and hasn't turned mode 1007 off.
- **The telemetry and the offscreen measure stay in**, and `docs/development.md` says how to run them.

Decisions made while building, none of them asked for:

- Smooth Scrolling is a glide after the view has moved, not the fingers' own pixels. On Linux the desktop app
  is never given those pixels, so a glide is all there is to do there. A browser does give them, and there a
  slow drag moves a row at a time, each one glided.
- In a browser a notch is the rows its pixels cover, which is about six at the usual font size, and not three.
  It follows what the browser and the desktop's own wheel settings say, as xterm.js does.
- Page up and down, a jump to a prompt, and a search's jumps don't glide, as in Rex for Mac.
- While it glides, the picture is clipped to the view's rows, and rests on whole pixels so that text stays sharp.
- `TerminalPointer` is public now, so that the measure in `tono` can drive it.
- The branch was fast-forwarded to `9bd8306`, the commit that captured this entry, which it was started without.

## Not checked, and left

- **The real desktop.** Everything on the desktop was measured on the nested display with software rendering.
  With the default renderer that display shows a frame a second, since nothing is ever shown, so the GPU path
  was only measured with frames unpaced. Whether the real window holds 60 frames a second at 4K while scrolling
  is for Mase to see. `TONO_SCROLL_TELEMETRY=/tmp/scroll.log tono` and a scroll will say: the `summary` lines
  give the window's frame gaps and how many came late.
- **A real touchpad, and a wheel that turns freely.** What the toolkit is handed for them wasn't seen. The same
  log says: `native="awt …"` on each `wheel` line, and `gap_us` between them. If a touchpad does arrive as
  notches a few milliseconds apart, it scrolls three rows at each, which is fast; Smooth Scrolling evens out
  the steps but not the speed.
- **Smooth Scrolling by hand.** That the glide's frames are drawn at the offsets meant was checked from the
  telemetry and one screenshot taken mid-glide, on the desktop and in the browser. How it feels wasn't.
- **A colored log at 4K still costs 26 to 29 ms a frame offscreen** with nothing to lay out. That is drawing
  1784 separate pieces of text on the CPU. A GPU should do better; if it doesn't, drawing a row as one piece
  of text, or keeping a row's drawing, is the next thing.
- **Pi erasing the history** when it redraws, which throws a scrolled-back view to the bottom. Nothing was
  done: it is what the program asked for.
- Firefox and Safari, a browser that counts the wheel in lines or pages (unit tests only), macOS, and the
  tail of a glide in a terminal showing images.
