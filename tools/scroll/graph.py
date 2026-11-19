#!/usr/bin/env python3
"""Draws what Tono's scroll telemetry wrote as a page: time runs across, and each turn of
the wheel is followed down through every stop to the frame that showed its effect.

    graph.py scroll.jsonl [--link link.jsonl] [-o scroll.html] [--title TEXT]

Open the page in a browser. Each go at scrolling is a picture of its own. A line that
leans far to the right is a turn that was answered late, and its color says how late.
"""
import argparse
import html
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from timeline import Timeline, ms, percentile  # noqa: E402

LANE = 34
LEFT = 190
TOP = 150


def color(total):
    """How late: green within three frames, through amber, to red past a fifth of a second."""
    if total is None:
        return "#888"
    late = total / 1000.0
    if late <= 50:
        return "#2e9d4f"
    if late <= 100:
        return "#c9a400"
    if late <= 200:
        return "#e07b00"
    return "#d22d2d"


def gap_color(gap, rhythm=0):
    """How much longer a gap between frames is than the wheel's own rhythm, or than a frame of the screen if that is longer."""
    late = (gap - max(rhythm, 16_700)) / 1000.0
    return "#2e9d4f" if late <= 4 else "#c9a400" if late <= 20 else "#e07b00" if late <= 83 else "#d22d2d"


def picture(timeline, burst, number, linked):
    start = burst[0].t - 20_000
    ends = [w.draw_end for w in burst if w.draw_end is not None]
    end = max(ends + [burst[-1].t]) + 60_000
    span = end - start
    scale = max(1.2, min(8.0, 1500_000.0 / span)) / 1000.0  # pixels to a microsecond
    width = LEFT + span * scale + 30

    lanes = ["wheel turned", "terminal's thread took it", "report: asked to be sent", "report: handed to the socket"]
    if linked:
        lanes += ["link: left this machine", "link: reached the server", "link: the answer left the server", "link: the answer reached this machine"]
    lanes += ["output read off the socket", "output given to the terminal", "frame read", "frame drawn"]
    y = {name: TOP + index * LANE for index, name in enumerate(lanes)}
    height = TOP + len(lanes) * LANE + 70

    def x(t):
        return LEFT + (t - start) * scale

    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width:.0f}" height="{height}" font-family="sans-serif" font-size="11">']
    out.append(f'<rect width="{width:.0f}" height="{height}" fill="#fff"/>')
    # The time's grid: a line every 16.7 ms would be too many for a long go, so every 50 or 100 ms.
    step = 50_000 if span < 1_500_000 else 100_000 if span < 4_000_000 else 500_000
    t = (start // step + 1) * step
    while t < end:
        out.append(f'<line x1="{x(t):.1f}" y1="{TOP - 12}" x2="{x(t):.1f}" y2="{height - 60}" stroke="#eee"/>')
        out.append(f'<text x="{x(t):.1f}" y="{height - 46}" text-anchor="middle" fill="#888">{(t - burst[0].t) / 1000:.0f} ms</text>')
        t += step
    for name in lanes:
        out.append(f'<line x1="{LEFT}" y1="{y[name]}" x2="{width - 20:.0f}" y2="{y[name]}" stroke="#ddd"/>')
        out.append(f'<text x="{LEFT - 8}" y="{y[name] + 4}" text-anchor="end" fill="#333">{name}</text>')

    # How late each turn was answered, as bars above: the shape of the stutter at a glance.
    totals = [w.total for w in burst if w.total is not None]
    worst = max(totals + [50_000])
    bar = 100.0 / worst
    out.append(f'<text x="{LEFT - 8}" y="{TOP - 110}" text-anchor="end" fill="#333">wheel to drawn</text>')
    out.append(f'<text x="{LEFT - 8}" y="{TOP - 96}" text-anchor="end" fill="#888">(most {ms(worst)} ms)</text>')
    for level in (16_700, 50_000, 100_000, 200_000, 500_000, 1_000_000):
        if level < worst:
            out.append(f'<line x1="{LEFT}" y1="{TOP - 20 - level * bar:.1f}" x2="{width - 20:.0f}" y2="{TOP - 20 - level * bar:.1f}" stroke="#eee" stroke-dasharray="3 3"/>')
            out.append(f'<text x="{width - 22:.0f}" y="{TOP - 22 - level * bar:.1f}" text-anchor="end" fill="#aaa">{level / 1000:.0f} ms</text>')
    for wheel in burst:
        if wheel.total is not None:
            out.append(f'<rect x="{x(wheel.t) - 1.5:.1f}" y="{TOP - 20 - wheel.total * bar:.1f}" width="3" height="{wheel.total * bar:.1f}" fill="{color(wheel.total)}"><title>turn {wheel.id}: {ms(wheel.total)} ms from the wheel to drawn</title></rect>')
        else:
            out.append(f'<text x="{x(wheel.t):.1f}" y="{TOP - 22}" text-anchor="middle" fill="#888">?</text>')

    # Each turn's way down.
    for wheel in burst:
        stops = [("wheel turned", wheel.t), ("terminal's thread took it", wheel.handled)]
        if wheel.report:
            stops += [("report: asked to be sent", wheel.queued), ("report: handed to the socket", wheel.written)]
            if linked:
                stops += [("link: left this machine", wheel.left_client), ("link: reached the server", wheel.reached_server),
                          ("link: the answer left the server", wheel.server_answered), ("link: the answer reached this machine", wheel.reached_client)]
            stops += [("output read off the socket", wheel.rx), ("output given to the terminal", wheel.feed)]
        stops += [("frame read", wheel.frame), ("frame drawn", wheel.draw_end)]
        points = [(x(t), y[name]) for name, t in stops if t is not None]
        shade = color(wheel.total)
        told = f"turn {wheel.id}: " + ", ".join(f"{name} +{ms(t - wheel.t)}" for name, t in stops[1:] if t is not None)
        out.append(f'<polyline fill="none" stroke="{shade}" stroke-width="1.2" opacity="0.8" points="{" ".join(f"{px:.1f},{py}" for px, py in points)}"><title>{html.escape(told)}</title></polyline>')
        for px, py in points:
            out.append(f'<circle cx="{px:.1f}" cy="{py}" r="2.2" fill="{shade}"/>')
        # The wait for the control connection: from asked to handed over.
        if wheel.report and wheel.queued is not None and wheel.written is not None and wheel.written - wheel.queued > 2_000:
            out.append(f'<line x1="{x(wheel.queued):.1f}" y1="{y["report: asked to be sent"] + 6}" x2="{x(wheel.written):.1f}" y2="{y["report: asked to be sent"] + 6}" stroke="{shade}" stroke-width="2" opacity="0.35"/>')

    # Everything that happened, whichever turn it was for.
    first, last = start, end
    for event in timeline.of("rx"):
        if first <= event["t"] <= last:
            radius = max(1.5, min(6.0, (event["bytes"] ** 0.5) / 16))
            out.append(f'<circle cx="{x(event["t"]):.1f}" cy="{y["output read off the socket"]}" r="{radius:.1f}" fill="none" stroke="#5577cc"><title>{event["bytes"]} bytes</title></circle>')
    for event in timeline.of("feed"):
        if first <= event["t"] <= last:
            sync = " inside a synchronized update" if event["sync_begin"] > event["sync_end"] else ""
            out.append(f'<rect x="{x(event["t"]):.1f}" y="{y["output given to the terminal"] - 5}" width="{max(1.5, event["feed_us"] * scale):.1f}" height="10" fill="#5577cc" opacity="0.6"><title>{event["chunks"]} chunks, {event["bytes"]} bytes, {ms(event["feed_us"])} ms{sync}</title></rect>')
    for event in timeline.of("frame"):
        if first <= event["t"] <= last:
            changed = event["rows_changed"] > 0 or event["rows_fresh"] > 0
            out.append(f'<line x1="{x(event["t"]):.1f}" y1="{y["frame read"] - 6}" x2="{x(event["t"]):.1f}" y2="{y["frame read"] + 6}" stroke="{"#333" if changed else "#bbb"}"><title>frame {event["n"]}: {event.get("cause", "")}, {event["rows_changed"]} rows changed, read in {ms(event["build_us"])} ms</title></line>')
    draws = [d for d in timeline.of("draw") if first <= d["t"] <= last]
    base = y["frame drawn"]
    for event in draws:
        out.append(f'<rect x="{x(event["t"] - event.get("draw_us", 0)):.1f}" y="{base - 7}" width="{max(2.0, event.get("draw_us", 0) * scale):.1f}" height="14" fill="{"#333" if timeline.changed(event) else "#ccc"}"><title>frame {event["frame"]} drawn in {ms(event.get("draw_us", 0))} ms</title></rect>')
    # The gaps between frames drawn, which are what is seen: colored, and written out where they are long.
    draws = [d for d in draws if timeline.changed(d)]
    rhythm = percentile([b.t - a.t for a, b in zip(burst, burst[1:])], 50) or 0
    for a, b in zip(draws, draws[1:]):
        gap = b["t"] - a["t"]
        out.append(f'<line x1="{x(a["t"]):.1f}" y1="{base + 14}" x2="{x(b["t"]):.1f}" y2="{base + 14}" stroke="{gap_color(gap, rhythm)}" stroke-width="4"><title>{ms(gap)} ms between frames</title></line>')
        if gap > 37_000 and gap * scale > 26:
            out.append(f'<text x="{(x(a["t"]) + x(b["t"])) / 2:.1f}" y="{base + 30}" text-anchor="middle" fill="{gap_color(gap, rhythm)}">{gap / 1000:.0f}</text>')
    out.append(f'<text x="{LEFT - 8}" y="{base + 18}" text-anchor="end" fill="#888">gaps between frames, ms</text>')
    for event in timeline.of("stall"):
        if first <= event["t"] <= last:
            out.append(f'<rect x="{x(event["t"] - event["blocked_us"]):.1f}" y="{TOP - 14}" width="{event["blocked_us"] * scale:.1f}" height="6" fill="#d22d2d"><title>{html.escape(event["thread"])} stuck {ms(event["blocked_us"])} ms: {html.escape(" < ".join(event["stack"][:8]))}</title></rect>')
    out.append("</svg>")

    gaps = [b.t - a.t for a, b in zip(burst, burst[1:])]
    tail = (max(ends) - burst[-1].t) if ends else None
    caption = (f"Go {number}: {len(burst)} turns in {ms(burst[-1].t - burst[0].t)} ms"
               + (f", {ms(percentile(gaps, 50))} ms apart" if gaps else "")
               + (f". Wheel to drawn: median {ms(percentile(totals, 50))}, most {ms(max(totals))} ms" if totals else ". No answer was seen drawn")
               + (f". Went on for {ms(tail)} ms after the wheel stopped." if tail is not None else "."))
    return f"<h2>{html.escape(caption)}</h2>\n<div class=scroll>{''.join(out)}</div>"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("telemetry")
    parser.add_argument("--link")
    parser.add_argument("-o", "--output")
    parser.add_argument("--title")
    args = parser.parse_args()
    timeline = Timeline(args.telemetry, args.link)
    linked = any(w.left_client is not None for w in timeline.wheels)
    title = args.title or os.path.basename(args.telemetry)
    parts = [picture(timeline, burst, index + 1, linked) for index, burst in enumerate(timeline.bursts())]
    page = f"""<!doctype html><meta charset=utf-8><title>{html.escape(title)}</title>
<style>body{{font-family:sans-serif;margin:16px;color:#222}}h1{{font-size:18px}}h2{{font-size:13px;font-weight:normal;margin:22px 0 4px}}
.scroll{{overflow-x:auto;border:1px solid #ddd}}p{{font-size:12px;color:#555;max-width:980px}}</style>
<h1>{html.escape(title)}</h1>
<p>Time runs across. Each turn of the wheel is a line from the top row down to the frame that showed its effect; the further it leans,
the later the answer, and it is green within 50 ms, amber within 100, orange within 200 and red past that. The bars above are the same
lateness for each turn. Blue rings are chunks of output as they came off the socket, blue bars the terminal taking them in, ticks are
frames read (grey: nothing changed), black bars frames drawn (grey: of a frame in which nothing had changed), and the colored line under them the gaps between frames: green where
frames came as often as the wheel turned (or the screen shows them), amber, orange and red the longer they took beyond that, with the long
ones written out. Hold the pointer over anything for its numbers.</p>
{"".join(parts) if parts else "<p>No turn of the wheel in this file.</p>"}
"""
    output = args.output or os.path.splitext(args.telemetry)[0] + ".html"
    with open(output, "w") as file:
        file.write(page)
    print(output)


if __name__ == "__main__":
    main()
