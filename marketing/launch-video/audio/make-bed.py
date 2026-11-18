#!/usr/bin/env python3
"""Makes the film's sound: a small synth bed at 100 beats a minute, with a swell into each
scene. Everything is computed, nothing sampled, so it can be made again and is ours to use.

    python3 audio/make-bed.py assets/audio/bed.wav
"""
import sys
import wave

import numpy as np

RATE = 44100
LENGTH = 47.4
BEAT = 0.6                      # 100 beats a minute; the film's scenes start on beats
SCENES = [3.6, 9.6, 15.6, 22.8, 29.4, 34.8, 42.6]
rng = np.random.default_rng(82)  # the same noise every time

n = int(RATE * LENGTH)
t = np.arange(n) / RATE
left = np.zeros(n)
right = np.zeros(n)


def add(signal, start, pan=0.0, gain=1.0):
    """Mixes a mono signal in at a time, panned from -1 (left) to 1 (right)."""
    i = int(start * RATE)
    if i >= n:
        return
    signal = signal[: n - i] * gain
    left[i:i + len(signal)] += signal * np.sqrt((1 - pan) / 2)
    right[i:i + len(signal)] += signal * np.sqrt((1 + pan) / 2)


def tone(freq, seconds, shape="sine"):
    x = np.arange(int(seconds * RATE)) / RATE
    if shape == "sine":
        return np.sin(2 * np.pi * freq * x)
    # A soft saw: the first few harmonics only, so it has an edge and no fizz.
    return sum(np.sin(2 * np.pi * freq * k * x) / k for k in range(1, 7)) * 0.55


def envelope(signal, attack, decay_to=None, release=0.05):
    m = len(signal)
    env = np.ones(m)
    a = max(1, int(attack * RATE))
    env[:a] = np.linspace(0, 1, a)
    if decay_to is not None:
        env *= np.exp(-np.arange(m) / (decay_to * RATE))
    r = max(1, int(release * RATE))
    env[-r:] *= np.linspace(1, 0, r)
    return signal * env


def note(name):
    names = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}
    return 440.0 * 2 ** ((names[name[0]] + 12 * (int(name[-1]) - 4) - 9 + (1 if "#" in name else 0)) / 12)


# One chord to a bar: A minor, F, C, G, round and round.
CHORDS = [("A2", ["A3", "C4", "E4"]), ("F2", ["F3", "A3", "C4"]), ("C3", ["E3", "G3", "C4"]), ("G2", ["G3", "B3", "D4"])]
BAR = BEAT * 4
bars = int(np.ceil(LENGTH / BAR))

for bar in range(bars):
    root, chord = CHORDS[bar % 4]
    start = bar * BAR
    # A pad under everything, slow to arrive.
    for i, name in enumerate(chord):
        for detune in (-0.25, 0.25):
            add(envelope(tone(note(name) * (1 + detune / 100), BAR + 0.3, "saw"), 0.5, release=0.6), start, pan=detune * 2 + (i - 1) * 0.25, gain=0.035)
    if start < SCENES[0] - 0.01:
        continue  # the opening is only the pad, the keys and the swell
    closing = start >= SCENES[-1] - 0.01
    # Bass on the eighths.
    for eighth in range(8):
        if closing and eighth not in (0, 4):
            continue
        add(envelope(tone(note(root), BEAT / 2 * 0.9) + 0.4 * tone(note(root) * 2, BEAT / 2 * 0.9), 0.005, decay_to=0.16), start + eighth * BEAT / 2, gain=0.22 if eighth % 2 == 0 else 0.13)
    # An arpeggio on the sixteenths, up and back.
    order = [0, 1, 2, 1] * 4
    for sixteenth, which in enumerate(order):
        if closing:
            break
        octave = 2 if sixteenth % 8 >= 4 else 1
        add(envelope(tone(note(chord[which]) * octave, BEAT / 4 * 1.6, "saw"), 0.003, decay_to=0.07), start + sixteenth * BEAT / 4, pan=0.5 if sixteenth % 2 else -0.5, gain=0.075)
    # A kick on the beat and a tick between.
    for beat in range(4):
        if closing and beat:
            continue
        x = np.arange(int(0.22 * RATE)) / RATE
        kick = np.sin(2 * np.pi * (45 * x + (110 / 28) * (1 - np.exp(-28 * x)))) * np.exp(-x / 0.07)
        add(kick, start + beat * BEAT, gain=0.42)
        if not closing:
            hat = rng.standard_normal(int(0.04 * RATE))
            hat = np.diff(hat, prepend=0) * np.exp(-np.arange(len(hat)) / (0.008 * RATE))
            add(hat, start + beat * BEAT + BEAT / 2, pan=0.2, gain=0.05)

# Into each scene: noise that swells, and a low thump where the scene lands.
for scene in SCENES:
    m = int(0.45 * RATE)
    swell = rng.standard_normal(m)
    swell = np.cumsum(swell) / 40          # darker noise
    swell -= np.linspace(swell[0], swell[-1], m)
    swell *= np.linspace(0, 1, m) ** 2.5
    add(swell / (np.abs(swell).max() + 1e-9), scene - 0.45, gain=0.16)
    x = np.arange(int(0.5 * RATE)) / RATE
    add(np.sin(2 * np.pi * 52 * x) * np.exp(-x / 0.16), scene, gain=0.35)

# The opening: four keys as the name is typed, then a rising chord as it appears.
for i in range(4):
    click = rng.standard_normal(int(0.018 * RATE)) * np.exp(-np.arange(int(0.018 * RATE)) / (0.003 * RATE))
    add(click, 0.45 + i * 0.13, pan=-0.2 + i * 0.13, gain=0.16)
for i, name in enumerate(["A3", "E4", "A4", "C5", "E5"]):
    add(envelope(tone(note(name), 2.2, "saw"), 0.02, decay_to=0.7, release=0.4), 1.5 + i * 0.045, pan=(i - 2) * 0.3, gain=0.07)

# The close: one chord left to ring.
for i, name in enumerate(["A2", "A3", "E4", "A4", "C5"]):
    add(envelope(tone(note(name), 4.6, "saw"), 0.03, decay_to=1.6, release=1.2), SCENES[-1] + i * 0.03, pan=(i - 2) * 0.3, gain=0.08)

mix = np.stack([left, right], axis=1)
# Out at the very end, and never past full scale.
fade = int(1.4 * RATE)
mix[-fade:] *= np.linspace(1, 0, fade)[:, None]
mix = np.tanh(mix * 1.15) / np.tanh(1.15)
mix *= 0.82 / np.abs(mix).max()

with wave.open(sys.argv[1], "wb") as out:
    out.setnchannels(2)
    out.setsampwidth(2)
    out.setframerate(RATE)
    out.writeframes((mix * 32767).astype("<i2").tobytes())
print(f"wrote {sys.argv[1]}: {LENGTH} s")
