#!/usr/bin/env python3
"""A full-screen program that takes the mouse and draws its whole screen again for every
turn of the wheel, as a coding agent's interface does: styled text, boxes, a status line.

For measuring scrolling end to end (backlog-054). After each redraw it writes a mark,
`ESC ] 7777 ; n BEL`, where n counts the wheel reports it has drawn for. A terminal
ignores it, and Tono's scroll telemetry reads it, so each report can be matched with the
output that answers it.

    tui.py [--sync] [--coalesce] [--lines N] [--work MS]

--sync      bracket each redraw as a synchronized update (mode 2026)
--coalesce  draw once for all the reports read at once, not once for each
--work MS   take this long over each redraw, as a slow program does
q or Control+C ends it.
"""
import argparse
import os
import re
import select
import shutil
import sys
import termios
import time
import tty

parser = argparse.ArgumentParser()
parser.add_argument("--sync", action="store_true")
parser.add_argument("--coalesce", action="store_true")
parser.add_argument("--lines", type=int, default=50_000)
parser.add_argument("--work", type=float, default=0.0)
args = parser.parse_args()

REPORT = re.compile(rb"\x1b\[<(\d+);(\d+);(\d+)([Mm])")
COLORS = [39, 31, 32, 33, 34, 35, 36, 91, 92, 93, 94, 95, 96]


def line(number, columns):
    """One line of an agent's output: a gutter, and words in several colors and weights."""
    kind = number % 9
    if kind == 0:
        text = f"\x1b[1;38;5;{33 + number % 180}m● Step {number}\x1b[0m \x1b[2m(read 3 files, ran 1 command)\x1b[0m"
    elif kind == 4:
        text = f"\x1b[48;5;236m\x1b[38;5;114m+ {number:6d} \x1b[0m\x1b[48;5;236m\x1b[38;5;252m  val answer = compute(\x1b[38;5;215m{number}\x1b[38;5;252m, \x1b[38;5;180m\"scroll\"\x1b[38;5;252m)" + " " * 20 + "\x1b[0m"
    else:
        words = []
        for index in range(12):
            color = COLORS[(number + index) % len(COLORS)]
            weight = "1;" if (number + index) % 5 == 0 else "3;" if (number + index) % 7 == 0 else ""
            words.append(f"\x1b[{weight}{color}mword{(number * 7 + index) % 1000}\x1b[0m")
        text = f"\x1b[2m{number:6d} │\x1b[0m " + " ".join(words)
    return text


def draw(top, columns, rows, handled):
    out = []
    if args.sync:
        out.append("\x1b[?2026h")
    out.append("\x1b[H")
    for row in range(rows - 1):
        out.append(line(top + row, columns) + "\x1b[K\r\n")
    out.append(f"\x1b[7m line {top} of {args.lines}   reports {handled} \x1b[K\x1b[0m")
    if args.sync:
        out.append("\x1b[?2026l")
    out.append(f"\x1b]7777;{handled}\x07")
    if args.work:
        time.sleep(args.work / 1000.0)
    os.write(1, "".join(out).encode())


def main():
    fd = sys.stdin.fileno()
    saved = termios.tcgetattr(fd)
    tty.setraw(fd)
    os.write(1, b"\x1b[?1049h\x1b[?25l\x1b[?1000h\x1b[?1006h")
    top, handled = args.lines // 2, 0
    try:
        size = shutil.get_terminal_size()
        draw(top, size.columns, size.lines, handled)
        pending = b""
        while True:
            select.select([fd], [], [])
            pending += os.read(fd, 65536)
            if b"q" in pending or b"\x03" in pending:
                break
            size = shutil.get_terminal_size()
            moved = False
            last = 0
            for match in REPORT.finditer(pending):
                last = match.end()
                button = int(match.group(1))
                if match.group(4) != b"M" or button & 64 == 0:
                    continue
                top = max(0, min(args.lines, top + (3 if button & 1 else -3)))
                handled += 1
                moved = True
                if not args.coalesce:
                    draw(top, size.columns, size.lines, handled)
            if moved and args.coalesce:
                draw(top, size.columns, size.lines, handled)
            # What is left may be the start of a report whose end hasn't come.
            pending = pending[last:] if pending[last:].startswith(b"\x1b") else b""
    finally:
        os.write(1, b"\x1b[?1006l\x1b[?1000l\x1b[?25h\x1b[?1049l")
        termios.tcsetattr(fd, termios.TCSADRAIN, saved)


main()
