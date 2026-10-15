#!/usr/bin/env python3
"""The one-take score: every time comes from events.json (the composition's own __events()), the camera's air from zoom.json.
Sources: the onetake palette (synthesised, one room) + CC0 key presses (Kenney, from the /brag skill's assets). Music: a soft pad
composed here (no third-party licence). Master: -16 LUFS, -3.5 dBTP via ffmpeg loudnorm.   python3 score.py -> sfx.wav / mix.wav"""
import json, os, subprocess, sys, wave
import numpy as np
from scipy import signal
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "vendor", "onetake", "scripts"))
from sfx_palette import Score, air, glass, wood, sub, bubble, wobble, lp, hp, bp, SR  # noqa: E402

KEYS = os.path.join(HERE, "..", "brag-output", "composition", "assets", "sfx")
ev = json.load(open(os.path.join(HERE, "events.json")))
zoom = json.load(open(os.path.join(HERE, "zoom.json")))
DUR = ev["T"]
rng = np.random.default_rng(11)


def key_wav(i):
    with wave.open(os.path.join(KEYS, "keypress-00%d.wav" % (1 + i % 6))) as w:
        raw = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(float) / 32768
        ch, sr = w.getnchannels(), w.getframerate()
    x = raw.reshape(-1, ch).mean(1) if ch > 1 else raw
    if sr != SR:
        x = signal.resample(x, int(len(x) * SR / sr))
    return x


s = Score(dur=DUR, T60=1.2)
place = s.place
by = {}
for e in ev["events"]:
    by.setdefault(e["kind"], []).append(e)

# 1 · the camera's air: three noise bands crossfaded by depth, loud as the zoom is fast
v = np.abs(np.array(zoom["v"]))
n = int(DUR * SR)
env = np.interp(np.arange(n) / SR, np.arange(len(v)) / zoom["hz"], v)
env = env ** 1.3
env = np.convolve(env, np.ones(int(SR * 0.02)) / int(SR * 0.02), mode="same")
env /= env.max() + 1e-9
noise = rng.standard_normal(n)
b1, b2, b3 = bp(noise, 330, 0.7), bp(noise, 1400, 0.8), bp(noise, 5200, 0.9)
# depth from the zoom itself: reconstruct log z by integrating the velocity
logz = np.cumsum(np.interp(np.arange(n) / SR, np.arange(len(v)) / zoom["hz"], np.array(zoom["v"]))) / SR + np.log(60)
depth = np.clip(np.log(np.maximum(np.exp(logz), 1)) / np.log(1000), 0, 1)
w1, w2, w3 = np.clip(1 - depth * 2, 0, 1), 1 - np.abs(depth * 2 - 1), np.clip(depth * 2 - 1, 0, 1)
air_sig = (b1 * w1 + b2 * w2 + b3 * w3) * env
air_sig = lp(air_sig, 6500)
air_sig /= np.abs(air_sig).max() + 1e-9
pad = np.zeros(n)
for f, a in ((55.0, 1.0), (82.4, 0.7), (110.0, 0.6), (164.8, 0.35), (220.0, 0.2)):
    pad += a * np.sin(2 * np.pi * f * np.arange(n) / SR + rng.uniform(0, 6))
pad *= np.minimum(1, np.arange(n) / SR / 2.5) * np.minimum(1, (DUR - np.arange(n) / SR) / 3.0)
pad *= 0.55 + 0.45 * np.sin(2 * np.pi * 0.07 * np.arange(n) / SR)
pad /= np.abs(pad).max()
place(air_sig, 0.0, 0.62, 0.0, 0.45)
place(pad, 0.0, 0.16, 0.0, 0.5)

# 2 · the hole: a held breath (only the pad), then a lens-ring chime as the capsule appears
place(glass(392, 1.8, 0.3), 2.25, 0.07, -0.1, 0.7)

# 3 · keys (the phone's, the terminal's, the app's): the CC0 presses, velocity varies, deeper levels are a little further away
for e in by.get("key", []):
    t = e["t"]; lvl = 0 if t < 8 else (1 if t < 15 else 2)
    k = key_wav(int(rng.integers(0, 6)))
    k = lp(k, 9000 if lvl == 0 else 5200)
    place(k, t + rng.uniform(0, 0.004), 0.22 * e["v"] * rng.uniform(0.7, 1.2) * (1.0 if lvl == 0 else 0.8), rng.uniform(-0.15, 0.15), 0.12 + 0.12 * lvl)
# 4 · the contacts
for e in by.get("send", []):
    place(wood(240, 0.08), e["t"] - 0.02, 0.32, 0.25, 0.2); place(wood(190, 0.07), e["t"] + 0.05, 0.22, 0.25, 0.2); place(glass(880, 0.9, 0.5), e["t"] + 0.03, 0.10, 0.2, 0.55)
for e in by.get("capsule", []):
    place(bubble(520, 0.24), e["t"], 0.2, 0.0, 0.35)
for e in by.get("tick", []):
    place(glass(659 if e["t"] < 12.5 else 784, 0.5, 0.7), e["t"], 0.10, -0.1, 0.4)
for e in by.get("built", []):
    place(sub(62, 0.7), e["t"], 0.34, 0.0, 0.4); place(glass(784, 1.2, 0.5), e["t"] + 0.01, 0.11, -0.05, 0.6); place(glass(1175, 1.4, 0.4), e["t"] + 0.04, 0.08, 0.05, 0.6)
for e in by.get("press", []):
    place(wood(230, 0.08), e["t"], 0.3, 0.0, 0.2); place(wood(185, 0.07), e["t"] + 0.07, 0.2, 0.0, 0.2)
for e in by.get("welcome", []):
    for f, d in ((698, 0.0), (880, 0.05), (1047, 0.10), (1319, 0.15)):
        place(glass(f, 1.8, 0.4), e["t"] + d, 0.09, (f - 900) / 2200, 0.7)
    place(sub(55, 1.0), e["t"], 0.26, 0.0, 0.45)
for e in by.get("land", []):
    place(sub(52, 0.9), e["t"], 0.30, 0.0, 0.5); place(air(0.5, 2400, 260, 1.3, 0.3), e["t"] - 0.05, 0.25, 0.0, 0.5, pan_to=-0.3)
for e in by.get("deny", []):
    place(wood(210, 0.09), e["t"], 0.38, -0.3, 0.2); place(wood(170, 0.08), e["t"] + 0.07, 0.24, -0.3, 0.2)
for e in by.get("denied", []):
    place(glass(523, 1.4, 0.4), e["t"] + 0.05, 0.12, -0.1, 0.6); place(glass(784, 1.6, 0.3), e["t"] + 0.12, 0.09, 0.1, 0.6); place(sub(46, 1.2), e["t"] + 0.02, 0.22, 0.0, 0.5)
for e in by.get("name", []):
    place(glass(440, 2.4, 0.3), e["t"], 0.12, 0.0, 0.8); place(glass(659, 2.4, 0.3), e["t"] + 0.08, 0.09, 0.1, 0.8); place(sub(46, 1.6), e["t"], 0.30, 0.0, 0.5)

# the rests are silent (verify wants >= 15 % of frames under -40 dBFS): the whole mix ducks to nothing in the held frames
REST = [(0.0, 1.3), (9.2, 10.45), (19.1, 19.65), (24.9, 26.3), (27.95, DUR)]
m = s.mix(-6.0)
gate = np.ones(len(m))
tt = np.arange(len(m)) / SR
for a, b in REST:
    gate *= 1 - (1 - 1e-4) * (np.clip((tt - a) / 0.08, 0, 1) * np.clip((b - tt) / 0.08, 0, 1))
m = m * gate[:, None]
with wave.open(os.path.join(HERE, "sfx.wav"), "wb") as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes((m * 32767).astype(np.int16).tobytes())
print("sfx.wav: %d events, rests %s" % (len(s.events), REST))
subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", os.path.join(HERE, "sfx.wav"), "-af", "loudnorm=I=-16:TP=-3.5:LRA=9", "-ar", "48000", os.path.join(HERE, "mix.wav")], check=True)
print("mix.wav ready")
