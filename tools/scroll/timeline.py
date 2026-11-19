"""Reads what Tono's scroll telemetry wrote (TONO_SCROLL_TELEMETRY=<file>, see
rex/src/ScrollTelemetry.kt) and follows each turn of the wheel through to the frame that
showed its effect. Used by analyze.py and graph.py.

All times are microseconds on the telemetry's own clock (`t`). A link's log (proxy.py
--log) is moved onto that clock with the `mono_us` of the telemetry's `start` line.
"""
import json
import re


class Wheel:
    """One turn of the wheel, and when it reached each stop on its way. None where it never did, or it wasn't seen."""

    def __init__(self, event):
        self.id = event["id"]
        self.t = event["t"]
        self.event = event
        age = re.search(r"age_ms=(-?\d+)", event.get("native") or "")
        self.toolkit_age = int(age.group(1)) * 1000 if age else None
        self.handled = None      # the terminal's thread took it
        self.local_rows = None   # rows the view scrolled, when the program didn't take it
        self.report = False
        self.seq = None
        self.queued = self.started = self.written = self.acked = None
        self.depth = self.in_flight = None
        self.shared = 1          # how many reports went in the request that carried this one
        # The link, if its log was given
        self.left_client = self.reached_server = self.server_answered = self.reached_client = None
        # The output that answers it
        self.rx = self.feed = self.feed_end = None
        self.rx_bytes = 0
        self.frame = self.draw = self.draw_end = None
        self.frame_n = None
        self.matched_by = None

    @property
    def total(self):
        return None if self.draw_end is None else self.draw_end - self.t


class Timeline:
    def __init__(self, path, link=None):
        self.events = []
        with open(path) as file:
            for line in file:
                line = line.strip()
                if not line.startswith("{"):
                    continue
                try:
                    self.events.append(json.loads(line))
                except ValueError:
                    pass
        self.start = None
        self._take_last_run()
        self.events.sort(key=lambda e: e["t"])
        self.by = {}
        for event in self.events:
            self.by.setdefault(event["ev"], []).append(event)
        self.link = self._read_link(link) if link else []
        self.wheels = [Wheel(e) for e in self.by.get("wheel", [])]
        self._follow()
        if self.link:
            self._follow_link()

    def of(self, name):
        return self.by.get(name, [])

    def changed(self, draw):
        """Whether a frame that was drawn, or one skipped for it, showed anything the one before didn't."""
        if not hasattr(self, "_changed"):
            self._changed = {f["n"]: f["rows_changed"] > 0 or f["rows_fresh"] > 0 for f in self.of("frame")}
        return any(self._changed.get(n, True) for n in range(draw["frame"] - draw.get("frames_skipped", 0), draw["frame"] + 1))

    def _take_last_run(self):
        """A file may hold several runs of the app, one after another; the last is the one read."""
        starts = [index for index, event in enumerate(self.events) if event["ev"] == "start"]
        if starts:
            self.start = self.events[starts[-1]]
            self.events = self.events[starts[-1]:]

    def _read_link(self, path):
        if not self.start or "mono_us" not in self.start:
            raise SystemExit("The telemetry has no `mono_us` in its start line, so a link's log can't be laid beside it.")
        offset = self.start["mono_us"] - self.start["t"]
        first, last = self.events[0]["t"], self.events[-1]["t"]
        events = []
        with open(path) as file:
            for line in file:
                try:
                    event = json.loads(line)
                except ValueError:
                    continue
                event["t"] = event.pop("mono_us") - offset
                events.append(event)
        paths = {e["conn"]: e["path"] for e in events if e["ev"] == "path"}
        for event in events:
            event["path"] = paths.get(event.get("conn"), "")
        return [e for e in events if first - 5_000_000 <= e["t"] <= last + 1_000_000]

    # --- From the wheel to the frame ---

    def _follow(self):
        wheels = {w.id: w for w in self.wheels}
        for event in self.of("handled"):
            wheel = wheels.get(event["id"])
            if wheel:
                wheel.handled = event["t"]
                if "report_bytes" in event:
                    wheel.report = True
                else:
                    wheel.local_rows = event.get("scrolled_rows", 0)
        by_seq = {}
        for event in self.of("send_queued"):
            wheel = wheels.get(event["wheel"])
            if wheel:
                wheel.seq, wheel.queued, wheel.depth, wheel.in_flight = event["seq"], event["t"], event["depth"], event["in_flight"]
                by_seq[event["seq"]] = wheel
        for event in self.of("send_start"):
            if event["seq"] in by_seq:
                by_seq[event["seq"]].started = event["t"]
        for event in self.of("send_written"):
            carried = range(event["seq"], event.get("last_seq", event["seq"]) + 1)
            for seq in carried:
                if seq in by_seq:
                    by_seq[seq].written = event["t"]
                    by_seq[seq].shared = len(carried)
                    by_seq[seq].carrier = event["seq"]
        for event in self.of("send_acked"):
            for wheel in by_seq.values():
                if getattr(wheel, "carrier", None) == event["seq"]:
                    wheel.acked = event["t"]

        frames = self.of("frame")
        draws = self.of("draw")
        frame_by_n = {f["n"]: f for f in frames}

        def show(wheel, frame):
            """The frame that holds a wheel's effect, and the first draw of it or of a later one."""
            wheel.frame, wheel.frame_n = frame["t"], frame["n"]
            for draw in draws:
                if draw["frame"] >= frame["n"]:
                    wheel.draw_end = draw["t"]
                    wheel.draw = draw["t"] - draw.get("draw_us", 0)
                    break

        # A view that scrolled by itself: the frame says which turns it holds.
        for frame in frames:
            for wheel_id in frame.get("wheels", []):
                if wheel_id in wheels:
                    show(wheels[wheel_id], frame)

        # A report: the output that answers it, then the frame read after that output was fed.
        reports = sorted((w for w in self.wheels if w.report and w.seq is not None), key=lambda w: w.seq)
        feeds = self.of("feed")
        rx = {(e["stream"], e["n"]): e for e in self.of("rx")}

        def answered(wheel, feed, how, mark_index=None):
            wheel.matched_by = how
            wheel.feed = feed["t"]
            wheel.feed_end = feed["t"] + feed["feed_us"]
            chunks = [rx.get((feed["stream"], n)) for n in range(feed["first_n"], feed["first_n"] + feed["chunks"])]
            chunks = [c for c in chunks if c]
            if chunks:
                # Without knowing which chunk held the mark, the last is the one that completed the redraw.
                pick = chunks[-1] if mark_index is None else chunks[min(len(chunks) - 1, mark_index)]
                wheel.rx = pick["t"]
                wheel.rx_chunk = (pick["stream"], pick["n"])
                wheel.rx_bytes = sum(c["bytes"] for c in chunks)
            for frame in frames:
                if frame["t"] >= wheel.feed_end and frame.get("output"):
                    show(wheel, frame)
                    break

        marked = [f for f in feeds if f.get("marks")]
        if marked and reports:
            # A test program counts the reports it has drawn for. The first mark seen after the first report is that report's.
            first = next((f for f in marked if reports[0].written is not None and f["t"] >= reports[0].written), None)
            if first:
                base = min(first["marks"]) - 1
                by_mark = {}
                for feed in marked:
                    for index, mark in enumerate(feed["marks"]):
                        by_mark.setdefault(mark, (feed, index, len(feed["marks"])))
                for index, wheel in enumerate(reports):
                    found = by_mark.get(base + index + 1)
                    if found:
                        feed, at, count = found
                        # Several redraws in one feed: this one's chunk is found in proportion, which is right when a redraw is a chunk.
                        answered(wheel, feed, "mark", mark_index=None if count == 1 else int((at + 1) * feed["chunks"] / count) - 1)
        else:
            # Any other program: each lot of output after a report was written answers the oldest report still
            # waiting, and when the wheel stops, whatever is left was answered by the last output there was.
            waiting = []
            pending = list(reports)
            last_feed = None
            for feed in feeds:
                while pending and pending[0].written is not None and pending[0].written <= feed["t"]:
                    waiting.append(pending.pop(0))
                if last_feed is not None and feed["t"] - last_feed["t"] > 250_000:
                    for wheel in [w for w in waiting if w.written < last_feed["t"]]:
                        answered(wheel, last_feed, "last output")
                        waiting.remove(wheel)
                if waiting:
                    answered(waiting.pop(0), feed, "order")
                last_feed = feed
            if last_feed is not None:
                for wheel in waiting:
                    answered(wheel, last_feed, "last output")

    # --- What the link saw ---

    def _follow_link(self):
        control = [e for e in self.link if e["path"].endswith("/control")]
        sent = [e for e in control if e["ev"] == "ws" and e["dir"] == "c2s"]
        passed = {(e["conn"], e["chunk"]): e["t"] for e in self.link if e["ev"] == "s_write"}
        delivered = {(e["conn"], e["chunk"]): e["t"] for e in self.link if e["ev"] == "c_write"}
        # Every request that carried typed input, a wheel's or not (a click is two): each is a frame from the client.
        writes = sorted(self.of("send_written"), key=lambda e: e["t"])
        most = max((e.get("last_seq", e["seq"]) - e["seq"] + 1 for e in writes), default=1)

        def match(sizes):
            """Each such request with the first frame from the client that left after it began to be written."""
            at = 0
            found = {}
            for event in writes:
                began = event["t"] - event.get("write_us", 0)
                # A few milliseconds are allowed for: the two clocks were read a moment apart when the app started.
                while at < len(sent) and (sent[at]["t"] < began - 3_000 or (sizes and not sizes[0] <= sent[at]["len"] <= sizes[1])):
                    at += 1
                if at < len(sent):
                    found[event["seq"]] = sent[at]
                    at += 1
            return found

        # Other requests cross the same connection. These are told from them by their size, which is
        # found by matching once without it.
        lengths = sorted(frame["len"] for frame in match(None).values())
        middle = lengths[len(lengths) // 2] if lengths else 0
        found = match((middle - 10, middle + 10 + 14 * most)) if lengths else {}
        for wheel in self.wheels:
            frame = found.get(getattr(wheel, "carrier", wheel.seq))
            # One that left long after it was written is some other request's frame.
            if frame and wheel.written is not None and frame["t"] - wheel.written < 500_000:
                wheel.left_client = frame["t"]
                wheel.reached_server = passed.get((frame["conn"], frame["chunk"]))
        # A terminal's stream: its chunks are the binary frames from the server, in order, after the one that opened it.
        streams = {}
        for event in self.link:
            if event["ev"] == "ws" and event["dir"] == "s2c" and event["path"].endswith("/data") and event["op"] == 2:
                streams.setdefault(event["conn"], []).append(event)
        self.rx_link = {}
        for rx in self.of("rx"):
            for conn, frames in streams.items():
                # Chunk n is frame n + 1: the first frame is the server's welcome.
                if rx["n"] < len(frames) and frames[rx["n"]]["len"] == rx["bytes"] and abs(frames[rx["n"]]["t"] - rx["t"]) < 2_000_000:
                    frame = frames[rx["n"]]
                    self.rx_link[(rx["stream"], rx["n"])] = (frame["t"], delivered.get((conn, frame["chunk"])))
                    break
        for wheel in self.wheels:
            found = self.rx_link.get(getattr(wheel, "rx_chunk", None))
            if found:
                wheel.server_answered, wheel.reached_client = found

    # --- Goes at scrolling ---

    def bursts(self, gap=600_000):
        """The turns of the wheel in groups with no pause longer than [gap] in them."""
        groups = []
        for wheel in self.wheels:
            if groups and wheel.t - groups[-1][-1].t <= gap:
                groups[-1].append(wheel)
            else:
                groups.append([wheel])
        return groups


def percentile(values, percent):
    values = sorted(values)
    if not values:
        return None
    return values[int((len(values) - 1) * percent / 100)]


def ms(micros):
    return "-" if micros is None else f"{micros / 1000:.1f}"
