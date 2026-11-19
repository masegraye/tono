#!/usr/bin/env python3
"""Says where the time goes between a turn of the wheel and its effect being drawn.

    analyze.py scroll.jsonl [--link link.jsonl]

scroll.jsonl is what Tono wrote with TONO_SCROLL_TELEMETRY=scroll.jsonl. --link is the log
of tools/scroll/proxy.py, if the connection went through it.
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from timeline import Timeline, ms, percentile  # noqa: E402


def row(name, values, note=""):
    values = [v for v in values if v is not None]
    if not values:
        return f"  {name:<46} {'-':>7} {'-':>7} {'-':>7} {'-':>7}   n=0  {note}"
    return (f"  {name:<46} {ms(percentile(values, 50)):>7} {ms(percentile(values, 90)):>7} {ms(percentile(values, 99)):>7} "
            f"{ms(max(values)):>7}   n={len(values)}  {note}")


def between(a, b):
    return None if a is None or b is None else b - a


def histogram(values, edges, labels):
    counts = [0] * len(labels)
    for value in values:
        for index, edge in enumerate(edges):
            if value < edge:
                counts[index] += 1
                break
        else:
            counts[-1] += 1
    most = max(counts) or 1
    return "\n".join(f"  {label:>14}  {count:5d}  {'#' * round(40 * count / most)}" for label, count in zip(labels, counts))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("telemetry")
    parser.add_argument("--link")
    args = parser.parse_args()
    timeline = Timeline(args.telemetry, args.link)
    wheels = timeline.wheels
    if not wheels:
        print("No turn of the wheel in this file.")
        return
    reports = [w for w in wheels if w.report]
    local = [w for w in wheels if not w.report and w.local_rows is not None]
    out = []
    span = (wheels[-1].t - wheels[0].t) / 1e6
    out.append(f"{len(wheels)} turns of the wheel over {span:.1f} s: {len(reports)} sent to a program as reports, {len(local)} scrolled the view")
    by = {}
    for wheel in reports:
        by[wheel.matched_by] = by.get(wheel.matched_by, 0) + 1
    if reports:
        how = {"mark": "by the program's own marks", "order": "in order (a guess: the program left no marks)", "last output": "by the last output after them (the program drew once for several)", None: "never answered"}
        out.append("  reports matched with their output: " + "; ".join(f"{count} {how[kind]}" for kind, count in by.items()))
    out.append("")
    out.append(f"  {'milliseconds':<46} {'p50':>7} {'p90':>7} {'p99':>7} {'max':>7}")

    if local:
        out.append("The view scrolled:")
        out.append(row("wheel -> drawn", [w.total for w in local]))
        out.append(row("  wheel -> the terminal's thread took it", [between(w.t, w.handled) for w in local]))
        out.append(row("  -> frame read", [between(w.handled, w.frame) for w in local]))
        out.append(row("  -> drawing began (the window's turn)", [between(w.frame, w.draw) for w in local]))
        out.append(row("  -> drawn", [between(w.draw, w.draw_end) for w in local]))
        out.append("")
    if reports:
        linked = any(w.left_client is not None for w in reports)
        out.append("A program took the wheel:")
        out.append(row("wheel -> the program's answer drawn", [w.total for w in reports]))
        if any(w.toolkit_age is not None for w in reports):
            out.append(row("  (the toolkit's event -> Tono: whole ms)", [w.toolkit_age for w in reports]))
        out.append(row("  wheel -> the terminal's thread took it", [between(w.t, w.handled) for w in reports]))
        out.append(row("  -> asked to be sent", [between(w.handled, w.queued) for w in reports]))
        out.append(row("  -> its turn on the control connection", [between(w.queued, w.started) for w in reports], "<- waiting behind the reports before it"))
        out.append(row("  -> handed to the socket", [between(w.started, w.written) for w in reports]))
        if linked:
            out.append(row("  -> left this machine (the link saw it)", [between(w.written, w.left_client) for w in reports]))
            out.append(row("  -> across the link", [between(w.left_client, w.reached_server) for w in reports]))
            out.append(row("  -> the server and the program answered", [between(w.reached_server, w.server_answered) for w in reports], "<- not Tono's"))
            out.append(row("  -> back across the link", [between(w.server_answered, w.reached_client) for w in reports]))
            out.append(row("  -> read off the socket by Tono", [between(w.reached_client, w.rx) for w in reports]))
        else:
            out.append(row("  -> its output read off the socket", [between(w.written, w.rx) for w in reports], "<- the link, the server and the program"))
        out.append(row("  -> given to the terminal (its thread's turn)", [between(w.rx, w.feed) for w in reports]))
        out.append(row("  -> the terminal had taken it in", [between(w.feed, w.feed_end) for w in reports]))
        out.append(row("  -> frame read", [between(w.feed_end, w.frame) for w in reports]))
        out.append(row("  -> drawing began (the window's turn)", [between(w.frame, w.draw) for w in reports]))
        out.append(row("  -> drawn", [between(w.draw, w.draw_end) for w in reports]))
        out.append(row("request written -> the server's answer to it", [between(w.written, w.acked) for w in reports], "<- one round trip"))
        client = []
        for w in reports:
            if w.total is None:
                continue
            away = between(w.left_client, w.reached_client) if linked else between(w.written, w.rx)
            if away is not None:
                client.append(w.total - away)
        out.append(row("Tono's share of wheel -> drawn", client, "<- all but the link, the server and the program"))
        out.append("")
        depth = [w.depth for w in reports if w.depth is not None]
        flight = [w.in_flight for w in reports if w.in_flight is not None]
        if depth:
            out.append(f"  requests waiting on the control connection when a report was asked to be sent: median {percentile(depth, 50)}, most {max(depth)}")
            out.append(f"  reports sent and not yet answered at that moment: median {percentile(flight, 50)}, most {max(flight)}")
        shared = [w.shared for w in reports if w.written is not None]
        requests = len({getattr(w, 'carrier', w.seq) for w in reports if w.written is not None})
        if shared:
            out.append(f"  requests that carried them: {requests} (most reports in one: {max(shared)})")
        never = [w for w in reports if w.draw_end is None]
        if never:
            out.append(f"  {len(never)} reports whose answer was never seen drawn")
        out.append("")

    out.append("Each go at scrolling (turns with no pause over 0.6 s):")
    out.append(f"  {'#':>2} {'turns':>5} {'lasted ms':>10} {'apart ms p50':>13} {'to drawn p50':>13} {'p90':>7} {'max':>7} {'went on after the wheel stopped':>33}")
    for index, burst in enumerate(timeline.bursts()):
        gaps = [b.t - a.t for a, b in zip(burst, burst[1:])]
        totals = [w.total for w in burst if w.total is not None]
        ends = [w.draw_end for w in burst if w.draw_end is not None]
        tail = max(ends) - burst[-1].t if ends else None
        out.append(f"  {index + 1:>2} {len(burst):>5} {ms(burst[-1].t - burst[0].t):>10} {ms(percentile(gaps, 50)) if gaps else '-':>13} "
                   f"{ms(percentile(totals, 50)) if totals else '-':>13} {ms(percentile(totals, 90)) if totals else '-':>7} {ms(max(totals)) if totals else '-':>7} {ms(tail):>30} ms")
    out.append("")

    # Frames drawn while scrolling went on: the gaps between them, beside the gaps between the turns of the
    # wheel, are what is seen as smooth or not. A wheel turned evenly should be answered by frames as even.
    draws = timeline.of("draw")
    gaps, turned, late = [], [], []
    for burst in timeline.bursts():
        ends = [w.draw_end for w in burst if w.draw_end is not None]
        if not ends:
            continue
        inside = [d["t"] for d in draws if burst[0].t <= d["t"] <= max(ends) and timeline.changed(d)]
        gaps += [b - a for a, b in zip(inside, inside[1:])]
        turned += [b.t - a.t for a, b in zip(burst, burst[1:])]
        shown = [w for w in burst if w.draw_end is not None]
        # How much later than the wheel's own rhythm each answer came: the frame's gap less the wheel's.
        late += [(b.draw_end - a.draw_end) - (b.t - a.t) for a, b in zip(shown, shown[1:])]
    edges = [12_000, 20_000, 37_000, 54_000, 100_000, 200_000]
    labels = ["under 12 ms", "12-20 (60/s)", "20-37 (30/s)", "37-54", "54-100", "100-200", "over 200"]
    if gaps:
        out.append(f"Gaps between turns of the wheel ({len(turned)}): median {ms(percentile(turned, 50))}, max {ms(max(turned))} ms")
        out.append(histogram(turned, edges, labels))
        out.append(f"Gaps between frames drawn that showed a change, while scrolling ({len(gaps)}): median {ms(percentile(gaps, 50))}, p90 {ms(percentile(gaps, 90))}, max {ms(max(gaps))} ms")
        out.append(histogram(gaps, edges, labels))
        out.append(f"  over 16.7 ms by a fifth or more: {sum(1 for g in gaps if g > 20_000)}; over 33 ms: {sum(1 for g in gaps if g > 37_000)}; over 100 ms: {sum(1 for g in gaps if g > 100_000)}")
    if late:
        out.append(f"An answer drawn later than the wheel's own rhythm (its frame's gap less its turn's gap, {len(late)}): "
                   f"p50 {ms(percentile(late, 50))}, p90 {ms(percentile(late, 90))}, max {ms(max(late))} ms; over 17 ms late: {sum(1 for v in late if v > 17_000)}; over 33 ms: {sum(1 for v in late if v > 33_000)}")
        out.append("")
    ticks = [t["gap_us"] for t in timeline.of("tick")]
    if ticks:
        usual = percentile(ticks, 50)
        out.append(f"The window's own frames while the wheel turned ({len(ticks)}): median {ms(usual)}, p99 {ms(percentile(ticks, 99))}, max {ms(max(ticks))} ms; "
                   f"late by half or more: {sum(1 for t in ticks if t > usual * 1.5)}")
    frames = timeline.of("frame")
    if frames:
        out.append(f"Frames read: {len(frames)}, reading one took median {ms(percentile([f['build_us'] for f in frames], 50))}, max {ms(max(f['build_us'] for f in frames))} ms; "
                   f"read in the middle of a synchronized update: {sum(1 for f in frames if f.get('sync'))}")
        skipped = sum(d.get("frames_skipped", 0) for d in draws)
        out.append(f"Frames drawn: {len(draws)}; read and never drawn, a later one being drawn in their place: {skipped}")
    feeds = timeline.of("feed")
    if feeds:
        out.append(f"Output: {len(timeline.of('rx'))} chunks, {sum(f['bytes'] for f in feeds)} bytes, given to the terminal in {len(feeds)} lots "
                   f"(most chunks in one: {max(f['chunks'] for f in feeds)}); feeding a lot took median {ms(percentile([f['feed_us'] for f in feeds], 50))}, max {ms(max(f['feed_us'] for f in feeds))} ms; "
                   f"a chunk waited for the terminal's thread median {ms(percentile([f['waited_us'] for f in feeds if f['waited_us'] >= 0], 50))}, max {ms(max([f['waited_us'] for f in feeds if f['waited_us'] >= 0] or [0]))} ms")
        begins, ends = sum(f["sync_begin"] for f in feeds), sum(f["sync_end"] for f in feeds)
        out.append(f"Synchronized updates (mode 2026) the program began: {begins}, ended: {ends}")
    actions = timeline.of("action")
    if actions:
        out.append(f"Requests on the control connection that waited or ran over 1 ms while the wheel turned: {len(actions)}; "
                   f"waited median {ms(percentile([a['waited_us'] for a in actions], 50))}, max {ms(max(a['waited_us'] for a in actions))} ms; "
                   f"ran median {ms(percentile([a['ran_us'] for a in actions], 50))}, max {ms(max(a['ran_us'] for a in actions))} ms")
    lags = [e["blocked_us"] for e in timeline.of("ui_lag")]
    if lags:
        out.append(f"The drawing thread kept new work waiting over 8 ms {len(lags)} times while the wheel turned: median {ms(percentile(lags, 50))}, max {ms(max(lags))} ms")
    held = len(timeline.of("held"))
    if held:
        out.append(f"Output after which no frame was read, the program being in the middle of a synchronized update: {held} times")
    stalls = timeline.of("stall")
    for stall in stalls[:12]:
        out.append(f"Stall: {stall['thread']} for {ms(stall['blocked_us'])} ms at {(stall['t'] - wheels[0].t) / 1000:.0f} ms: " + " < ".join(stall["stack"][:6]))
    if len(stalls) > 12:
        out.append(f"... and {len(stalls) - 12} more stalls")
    if timeline.of("truncated"):
        out.append("The file was cut short at its most lines.")
    print("\n".join(out))


if __name__ == "__main__":
    main()
