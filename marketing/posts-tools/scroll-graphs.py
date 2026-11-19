#!/usr/bin/env python3
"""Post 008's still: one go at scrolling before the fix and the same go after it, one above
the other on one page, drawn by tools/scroll/graph.py from the telemetry of each run.

    scroll-graphs.py BEFORE.jsonl AFTER.jsonl [--link LINK.jsonl] [--go 3] -o compare.html

graph.py fits each picture to its own length, which makes a go that took 1.7 s and one that
took 0.2 s come out equally wide. Here both are drawn at the scale of the longer, so the
widths can be compared: that is the whole point of the picture. Screenshot the page at
1920x1080 (shoot-008.sh does).
"""
import argparse
import builtins
import html
import os
import re
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "tools", "scroll"))
import graph  # noqa: E402
from timeline import Timeline  # noqa: E402


def span_of(burst):
    """How long graph.py takes a go to be: from just before the first turn to just after the last frame."""
    ends = [w.draw_end for w in burst if w.draw_end is not None]
    return max(ends + [burst[-1].t]) + 60_000 - (burst[0].t - 20_000)


def picture(timeline, burst, linked, scale, width):
    """graph.py's picture of one go, at a given scale (pixels to a millisecond) and padded to a given width."""
    # graph.py chooses the scale itself, as max(1.2, min(8.0, fit)): answering its min() with the
    # scale wanted makes it that, for any scale of 1.2 or more.
    graph.min = lambda *a: scale if a[0] == 8.0 else builtins.min(*a)
    try:
        svg = re.search(r"<svg.*</svg>", graph.picture(timeline, burst, 0, linked), re.S).group(0)
    finally:
        del graph.min
    own = float(re.search(r'<svg[^>]* width="(\d+)"', svg).group(1))
    height = int(re.search(r'<svg[^>]* height="(\d+)"', svg).group(1))
    # Wider, to the other picture's width: the canvas, its background, and the lanes' lines.
    svg = svg.replace(f'width="{own:.0f}" height="{height}"', f'width="{width:.0f}" height="{height}"')
    svg = svg.replace(f'x2="{own - 20:.0f}"', f'x2="{width - 20:.0f}"').replace(f'<text x="{own - 22:.0f}"', f'<text x="{width - 22:.0f}"')
    # Room on the left for the longest lane's name, which graph.py cuts off, and nothing below the time's numbers.
    svg = svg.replace("<svg ", f'<svg viewBox="-36 0 {width + 36:.0f} {height - 30}" ', 1)
    svg = svg.replace(f'width="{width:.0f}" height="{height}" font-family', f'width="{width + 36:.0f}" height="{height - 30}" font-family', 1)
    svg = svg.replace(f'<rect width="{width:.0f}"', f'<rect x="-36" width="{width + 36:.0f}"', 1)
    return svg


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("before")
    parser.add_argument("after")
    parser.add_argument("--link")
    parser.add_argument("--go", type=int, default=3, help="which go at scrolling to show (1 is the first)")
    parser.add_argument("--title", default="Scrolling Neovim on another machine, over a 30 ms link")
    parser.add_argument("-o", "--output", required=True)
    args = parser.parse_args()

    runs = []
    for path in (args.before, args.after):
        timeline = Timeline(path, args.link)
        burst = timeline.bursts()[args.go - 1]
        runs.append((timeline, burst, any(w.left_client is not None for w in timeline.wheels)))
    longest = max(span_of(burst) for _, burst, _ in runs)
    scale = max(1.2, min(8.0, 1500_000.0 / longest))
    width = graph.LEFT + longest * scale / 1000.0 + 30

    rows = []
    for name, (timeline, burst, linked) in zip(("Before", "After"), runs):
        ends = [w.draw_end for w in burst if w.draw_end is not None]
        totals = [w.total for w in burst if w.total is not None]
        tail = max(ends) - burst[-1].t
        said = (f"{len(burst)} turns of the wheel in {(burst[-1].t - burst[0].t) / 1000:.0f} ms. "
                f"The screen caught up <b>{tail / 1000:.0f} ms</b> after the wheel stopped (slowest turn: {max(totals) / 1000:.0f} ms).")
        rows.append(f'<section class="{name.lower()}"><h2><span>{name}</span> {said}</h2>{picture(timeline, burst, linked, scale, width)}</section>')

    page = f"""<!doctype html><meta charset=utf-8><title>{html.escape(args.title)}</title>
<style>
html,body{{margin:0;background:#fff;color:#222;font-family:"Inter","Liberation Sans",sans-serif}}
body{{width:1920px;height:1080px;overflow:hidden;box-sizing:border-box;padding:24px 28px 0}}
h1{{font-size:32px;margin:0 0 4px;font-weight:600}}
p{{font-size:17px;color:#555;margin:0 0 12px}}
h2{{font-size:22px;font-weight:normal;margin:14px 0 4px}}
h2 span{{display:inline-block;font-weight:700;color:#fff;border-radius:6px;padding:2px 12px;margin-right:10px}}
.before h2 span{{background:#d22d2d}} .after h2 span{{background:#2e9d4f}}
svg{{display:block;width:1864px;height:auto}}
</style>
<h1>{html.escape(args.title)}</h1>
<p>Time runs across, the same scale in both. Each turn of the wheel is a line from the top row down to the frame that showed it: the further it leans, the later the answer.</p>
{"".join(rows)}
"""
    with open(args.output, "w") as file:
        file.write(page)
    print(args.output)


if __name__ == "__main__":
    main()
