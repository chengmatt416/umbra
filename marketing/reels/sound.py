"""Sound design for the Reels, synthesised from scratch (no samples, no licences): a light A-minor groove (pad, plucks, bass,
soft kick, hats), whooshes on every cut, pops on every caption, keyboard clicks while the task is typed, a thunk + ding when the
approval is denied, a success chime, a riser and an impact into the end card. Stereo, with a short reverb on the whole mix."""
import wave

import numpy as np

SR = 44100
RNG = np.random.default_rng(7)


def t_axis(sec):
    return np.arange(int(sec * SR)) / SR


def adsr(n, a, d, floor=0.0):
    t = np.arange(n) / SR
    env = np.minimum(1, t / max(a, 1e-4)) * (floor + (1 - floor) * np.exp(-t / max(d, 1e-4)))
    return env


def sine(f, sec, phase=0.0):
    return np.sin(2 * np.pi * f * t_axis(sec) + phase)


def pluck(f, sec=0.9, bright=1.0):
    t = t_axis(sec)
    x = sum(a * np.sin(2 * np.pi * f * k * t) * np.exp(-t * (3 + 4 * k) / (0.5 * bright + 0.1)) for k, a in ((1, 1.0), (2, 0.45), (3, 0.25), (4, 0.12)))
    return x * np.minimum(1, t / 0.004)


def pad_note(f, sec):
    t = t_axis(sec)
    x = sum(np.sin(2 * np.pi * f * (1 + d) * t + p) for d, p in ((0, 0), (0.003, 1.3), (-0.003, 2.1)))
    x += 0.4 * np.sin(2 * np.pi * f * 2 * t)
    env = np.minimum(1, t / 0.7) * np.minimum(1, (sec - t) / 0.6)
    return x * env / 3


def bandpass(x, lo, hi):
    spec = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    mask = np.where((f >= lo) & (f <= hi), 1.0, 0.0)
    # soften the edges
    k = int(len(x) / SR * 80) + 1
    mask = np.convolve(mask, np.ones(k) / k, mode="same")
    return np.fft.irfft(spec * mask, len(x))


def noise(sec):
    return RNG.standard_normal(int(sec * SR))


def whoosh(sec=0.6, up=True):
    n = noise(sec)
    x = bandpass(n, 400, 7000)
    t = np.linspace(0, 1, len(x))
    env = np.sin(np.pi * t) ** 2
    sweep = np.sin(2 * np.pi * np.cumsum((250 + (1400 if up else -900) * t + (900 if not up else 0)) / SR))
    return (x / np.max(np.abs(x)) * 0.8 + sweep * 0.25) * env


def pop(base=880):
    t = t_axis(0.16)
    f = base * (1 + 0.6 * np.exp(-t * 60))
    x = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 28)
    x += 0.5 * np.sin(2 * np.pi * base * 2.01 * t) * np.exp(-t * 45)
    return x


def click():
    n = bandpass(noise(0.02), 1800, 9000) * np.exp(-t_axis(0.02) * 300)
    n += 0.5 * np.sin(2 * np.pi * (1800 + RNG.integers(-250, 250)) * t_axis(0.02)) * np.exp(-t_axis(0.02) * 400)
    return n


def thunk():
    t = t_axis(0.35)
    f = 150 * np.exp(-t * 7) + 45
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 14) + 0.2 * click()[:1].sum() * 0


def ding(f=1568.0, sec=1.1):
    t = t_axis(sec)
    return (np.sin(2 * np.pi * f * t) + 0.5 * np.sin(2 * np.pi * f * 2.76 * t) * np.exp(-t * 6) + 0.3 * np.sin(2 * np.pi * f * 1.5 * t)) * np.exp(-t * 4.2) * np.minimum(1, t / 0.002)


def kick():
    t = t_axis(0.3)
    f = 120 * np.exp(-t * 28) + 46
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 11)


def hat(open_=False):
    sec = 0.18 if open_ else 0.05
    return bandpass(noise(sec), 6500, 15000) * np.exp(-t_axis(sec) * (20 if open_ else 90))


def riser(sec):
    n = bandpass(noise(sec), 700, 9000)
    t = np.linspace(0, 1, len(n))
    sweep = np.sin(2 * np.pi * np.cumsum(180 + 1500 * t ** 2) / SR)
    return (n / np.max(np.abs(n)) * 0.6 + sweep * 0.3) * t ** 2.2


def impact():
    t = t_axis(1.8)
    boom = np.sin(2 * np.pi * np.cumsum(70 * np.exp(-t * 2.2) + 36) / SR) * np.exp(-t * 2.4)
    shimmer = bandpass(noise(1.8), 3000, 12000) * np.exp(-t * 5) * 0.25
    return boom + shimmer


def put(buf, x, at, gain=1.0, pan=0.0):
    i = int(at * SR)
    if i < 0 or i >= buf.shape[1]:
        return
    x = x[: buf.shape[1] - i]
    buf[0, i:i + len(x)] += x * gain * (1 - max(0, pan))
    buf[1, i:i + len(x)] += x * gain * (1 + min(0, pan))


def reverb(x, sec=1.4, wet=0.2):
    n = int(sec * SR)
    ir = RNG.standard_normal((2, n)) * np.exp(-np.arange(n) / SR * 3.2)
    size = 1 << int(np.ceil(np.log2(x.shape[1] + n)))
    out = np.zeros_like(x)
    for c in range(2):
        out[c] = np.fft.irfft(np.fft.rfft(x[c], size) * np.fft.rfft(ir[c], size), size)[: x.shape[1]]
    return x + wet * out / (np.max(np.abs(out)) + 1e-9) * np.max(np.abs(x))


def note(name):
    names = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}
    return 440.0 * 2 ** ((names[name[0]] - 9 + 12 * (int(name[1]) - 4)) / 12)


def make(path, total, end_start, starts, cap_starts, typing=(0.9, 4.3), deny_at=None, success_at=None, lasso_at=None):
    n = int(total * SR)
    music = np.zeros((2, n))
    fx = np.zeros((2, n))
    bpm = 104
    beat = 60 / bpm
    bar = 4 * beat
    chords = [("A3", "E4", "A4", "C5"), ("F3", "C4", "F4", "A4"), ("C4", "G4", "C5", "E5"), ("G3", "D4", "G4", "B4")]
    bass = ["A2", "F2", "C3", "G2"]
    arp = [0, 2, 3, 1, 3, 2, 3, 1]
    t = 0.0
    k = 0
    while t < end_start + 0.3:
        c = chords[k % 4]
        for nm in c:
            put(music, pad_note(note(nm), bar + 0.6), t, 0.10)
        # plucks start after the hook's first beat; gentle at first
        for i, step in enumerate(arp):
            at = t + i * beat / 2
            if at >= 0.6 and at < end_start:
                put(music, pluck(note(c[step]) * (2 if step == 3 else 1), 0.7), at, 0.17 if at > 3 else 0.11, pan=(-0.25 if i % 2 else 0.25))
        put(music, np.sin(2 * np.pi * note(bass[k % 4]) * t_axis(bar)) * adsr(int(bar * SR), 0.01, 0.9) * 0.5, t, 0.30)
        t += bar
        k += 1
    # groove: soft kick from the first caption's end, hats from the first cut, nothing on the end card
    b = 0
    while b * beat < end_start - 0.1:
        at = b * beat
        if at >= cap_starts[1] - 0.4:
            put(music, kick(), at, 0.55)
        if at >= starts[1] - 0.5:
            put(music, hat(), at + beat / 2, 0.08, pan=0.2)
            if b % 4 == 3:
                put(music, hat(True), at + beat * 0.75, 0.05, pan=-0.2)
        b += 1
    # sound effects
    for i, s in enumerate(starts[1:], 1):
        put(fx, whoosh(0.55, up=(i % 2 == 1)), s - 0.22, 0.42, pan=(0.3 if i % 2 else -0.3))
    for i, cs in enumerate(cap_starts):
        put(fx, pop(880 if i else 660), cs, 0.30)
        put(fx, pop(1320 if i else 990), cs + 0.07, 0.14)
    a, bb = typing
    at = starts[0] + a
    while at < starts[0] + bb:
        put(fx, click(), at, 0.4, pan=RNG.uniform(-0.2, 0.2))
        at += RNG.uniform(0.05, 0.14)
    if lasso_at is not None:
        put(fx, riser(0.9) * 0.35, lasso_at, 0.5)
        put(fx, pop(1100), lasso_at + 1.0, 0.3)
    if deny_at is not None:
        put(fx, thunk(), deny_at, 0.55)
        put(fx, ding(1175.0, 0.9), deny_at + 0.3, 0.22)
        put(fx, ding(1568.0, 1.2), deny_at + 0.42, 0.22)
    if success_at is not None:
        for j, f in enumerate((784.0, 988.0, 1175.0, 1568.0)):
            put(fx, ding(f, 0.8), success_at + j * 0.09, 0.2)
    put(fx, riser(1.9), end_start - 1.9, 0.5)
    put(fx, impact(), end_start, 0.9)
    for j, f in enumerate((note("A4"), note("C5"), note("E5"), note("B5"))):
        put(fx, ding(f, 2.4), end_start + 0.05 + j * 0.07, 0.17, pan=(-0.3 + 0.2 * j))
    # end-card bed: the pad holds, the groove is gone
    for nm in ("A3", "E4", "A4", "C5"):
        put(music, pad_note(note(nm), total - end_start + 0.5), end_start, 0.11)
    mix = music + fx
    mix = reverb(mix, 1.3, 0.16)
    fade = np.minimum(1, np.arange(n) / SR / 0.8) * np.minimum(1, (total - np.arange(n) / SR) / 2.2)
    mix *= fade
    mix /= np.sqrt(np.mean(mix ** 2)) / 0.16          # about -16 dBFS RMS: loud enough for a phone
    mix = np.tanh(mix * 1.1) / np.tanh(1.1)
    mix *= 0.89 / np.max(np.abs(mix))
    pcm = (mix.T * 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def make_onetake(path, total, end_start, events):
    """A calmer bed for the one-take: pad + sparse plucks, a soft pulse once the task is sent, and sound for every real event.
    events: dict of name -> time (seconds): typing=(a,b), send, approval, deny, answer, moves=[times of big camera moves]."""
    n = int(total * SR)
    music = np.zeros((2, n))
    fx = np.zeros((2, n))
    bpm = 92
    beat = 60 / bpm
    bar = 4 * beat
    chords = [("A3", "E4", "A4", "C5"), ("F3", "C4", "F4", "A4"), ("C4", "G4", "C5", "E5"), ("G3", "D4", "G4", "B4")]
    bass = ["A2", "F2", "C3", "G2"]
    t, k = 0.0, 0
    while t < total:
        c = chords[k % 4]
        for nm in c:
            put(music, pad_note(note(nm), bar + 0.8), t, 0.11)
        if t >= 1.5:
            for i in (0, 3, 4, 6):
                at = t + i * beat / 2
                if at < end_start:
                    put(music, pluck(note(c[(i + k) % 4]), 0.9), at, 0.12 if at < events["send"] else 0.16, pan=(-0.3 if i % 2 else 0.3))
        if t >= 3:
            put(music, np.sin(2 * np.pi * note(bass[k % 4]) * t_axis(bar)) * adsr(int(bar * SR), 0.02, 1.2) * 0.5, t, 0.24)
        t += bar
        k += 1
    b = 0
    while b * beat < end_start:
        at = b * beat
        if at >= events["send"] and b % 2 == 0:
            put(music, kick(), at, 0.32)
        if at >= events["send"] + 4:
            put(music, hat(), at + beat / 2, 0.05, pan=0.25)
        b += 1
    a, bb = events["typing"]
    at = a
    while at < bb:
        put(fx, click(), at, 0.42, pan=RNG.uniform(-0.2, 0.2))
        at += RNG.uniform(0.07, 0.16)
    put(fx, pop(1100), events["send"], 0.35)
    put(fx, ding(1318.5, 0.7), events["send"] + 0.05, 0.12)
    for m in events["moves"]:
        put(fx, whoosh(0.5, up=True) * 0.6, m - 0.1, 0.22, pan=RNG.uniform(-0.3, 0.3))
    put(fx, pop(990), events["tap_about"], 0.28)
    put(fx, ding(1760.0, 0.5), events["approval"], 0.2)
    put(fx, ding(1318.5, 0.9), events["approval"] + 0.18, 0.2)
    put(fx, pop(880), events["deny"] - 0.05, 0.3)
    put(fx, thunk(), events["deny"], 0.5)
    put(fx, ding(1175.0, 0.9), events["deny"] + 0.3, 0.18)
    for j, f in enumerate((784.0, 988.0, 1175.0)):
        put(fx, ding(f, 0.8), events["answer"] + j * 0.1, 0.16)
    put(fx, riser(1.6), end_start - 1.6, 0.35)
    put(fx, impact(), end_start, 0.7)
    for j, f in enumerate((note("A4"), note("E5"), note("A5"))):
        put(fx, ding(f, 2.2), end_start + 0.05 + j * 0.08, 0.15, pan=(-0.25 + 0.25 * j))
    mix = reverb(music + fx, 1.3, 0.16)
    fade = np.minimum(1, np.arange(n) / SR / 1.0) * np.minimum(1, (total - np.arange(n) / SR) / 2.0)
    mix *= fade
    mix /= np.sqrt(np.mean(mix ** 2)) / 0.15
    mix = np.tanh(mix * 1.1) / np.tanh(1.1)
    mix *= 0.89 / np.max(np.abs(mix))
    pcm = (mix.T * 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def make_brag(path, total, slams, end_start, hook_at=0.0):
    """A harder 120 BPM groove for the brag reel: kick on every beat, offbeat bass, 16th hats, a riser into every slam, an impact
    on every number, and rapid rising ticks while the numbers count up."""
    n = int(total * SR)
    music = np.zeros((2, n))
    fx = np.zeros((2, n))
    beat = 0.5
    bar = 4 * beat
    chords = [("A3", "E4", "A4", "C5"), ("F3", "C4", "F4", "A4"), ("C4", "G4", "C5", "E5"), ("G3", "D4", "G4", "B4")]
    bass = ["A1", "F1", "C2", "G1"]
    t, k = 0.0, 0
    while t < end_start:
        c = chords[k % 4]
        for nm in c:
            put(music, pad_note(note(nm), bar + 0.4), t, 0.09)
        for i in range(8):
            at = t + i * beat / 2
            if at >= 0.5 and at < end_start:
                put(music, pluck(note(c[[0, 2, 3, 2, 1, 3, 2, 3][i]]) * 2, 0.35, 1.3), at, 0.10, pan=(-0.3 if i % 2 else 0.3))
        for i in (0.5, 1.5, 2.5, 3.5):      # off-beat bass
            at = t + i * beat
            if at < end_start:
                put(music, np.sin(2 * np.pi * note(bass[k % 4]) * 2 * t_axis(0.3)) * adsr(int(0.3 * SR), 0.005, 0.18) * 0.9, at, 0.34)
        t += bar
        k += 1
    b = 0
    while b * beat < end_start:
        at = b * beat
        put(music, kick(), at, 0.8)
        for j in range(2):
            put(music, hat(), at + j * beat / 2 + beat / 4, 0.07, pan=0.25)
        if b % 2 == 1:
            put(music, bandpass(noise(0.12), 1500, 6000) * np.exp(-t_axis(0.12) * 38), at, 0.30)   # snare-ish clap
        b += 1
    put(fx, impact() * 0.9, hook_at, 0.8)
    for i, s in enumerate(slams):
        put(fx, riser(0.9) * 0.55, s - 0.9, 0.55)
        put(fx, impact(), s, 0.8)
        put(fx, pop(660 + 40 * i), s + 0.02, 0.25)
        for j in range(14):                  # count-up ticks
            put(fx, pop(900 + 60 * j) * 0.4, s + 0.08 + j * 0.075, 0.12)
    put(fx, riser(1.4), end_start - 1.4, 0.5)
    put(fx, impact(), end_start, 0.9)
    for j, f in enumerate((note("A4"), note("C5"), note("E5"), note("A5"))):
        put(fx, ding(f, 2.4), end_start + 0.05 + j * 0.08, 0.16, pan=(-0.3 + 0.2 * j))
    mix = reverb(music + fx, 1.1, 0.12)
    fade = np.minimum(1, np.arange(n) / SR / 0.1) * np.minimum(1, (total - np.arange(n) / SR) / 1.8)
    mix *= fade
    mix /= np.sqrt(np.mean(mix ** 2)) / 0.17
    mix = np.tanh(mix * 1.25) / np.tanh(1.25)
    mix *= 0.89 / np.max(np.abs(mix))
    pcm = (mix.T * 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def make_brag_bed(path, total=22.5):
    """The /brag bed (polished tone): A minor, 96 BPM, 2.5 s bars. Pad + sparse plucks; a soft pulse only in the reveal (5-10 s);
    holds its breath at the approval (10-15 s); pad only for the punchline (15-19.25 s); swells and fades under the lockup."""
    n = int(total * SR)
    mix = np.zeros((2, n))
    beat = 60 / 96
    bar = 4 * beat
    chords = [("A3", "E4", "A4", "C5"), ("F3", "C4", "F4", "A4"), ("C4", "G4", "C5", "E5"), ("G3", "D4", "G4", "B4")]
    bass = ["A2", "F2", "C3", "G2"]
    t, k = 0.0, 0
    while t < total:
        c = chords[k % 4]
        for nm in c:
            put(mix, pad_note(note(nm), bar + 0.9), t, 0.16 if t < 19.2 else 0.22)
        if t < 19.2:
            steps = (0, 2, 4, 6) if t < 10 or t >= 15 else (0, 4)
            for i in steps:
                at = t + i * beat / 2
                if at > 0.4:
                    put(mix, pluck(note(c[(i // 2 + k) % 4]), 1.0, 0.8), at, 0.15, pan=(-0.25 if i % 4 else 0.25))
        if 5.0 <= t < 10.0:
            put(mix, kick(), t, 0.42)
            put(mix, kick(), t + 2 * beat, 0.34)
            put(mix, np.sin(2 * np.pi * note(bass[k % 4]) * t_axis(bar)) * adsr(int(bar * SR), 0.02, 1.4) * 0.5, t, 0.26)
        elif 10.0 <= t < 19.2:
            put(mix, np.sin(2 * np.pi * note(bass[k % 4]) * t_axis(bar)) * adsr(int(bar * SR), 0.05, 2.0) * 0.5, t, 0.20)
        t += bar
        k += 1
    mix = reverb(mix, 1.6, 0.2)
    sec = np.arange(n) / SR
    mix *= np.minimum(1, sec / 0.8) * np.minimum(1, (total - sec) / 2.2)
    mix /= np.sqrt(np.mean(mix ** 2)) / 0.10
    mix = np.tanh(mix * 1.1) / np.tanh(1.1)
    mix *= 0.8 / np.max(np.abs(mix))
    pcm = (mix.T * 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def make_tour_bed(path, total=55.0, bpm=109.0909):
    """The /brag feature-tour bed: A minor, 6-beat scenes (3.3 s). Pad + plucks throughout; a soft pulse in acts 1-2 (4.4-34.1 s);
    hats join in act 2 (20.9 s); act 3 (34.1-47.3 s, the dark colour) drops the pulse and holds its breath; pad only for the punchline;
    swell and fade under the lockup."""
    n = int(total * SR)
    mix = np.zeros((2, n))
    beat = 60 / bpm
    bar = 4 * beat
    chords = [("A3", "E4", "A4", "C5"), ("F3", "C4", "F4", "A4"), ("C4", "G4", "C5", "E5"), ("G3", "D4", "G4", "B4")]
    bass = ["A2", "F2", "C3", "G2"]
    t, k = 0.0, 0
    while t < total:
        c = chords[k % 4]
        act3 = 34.1 <= t < 47.3
        quiet = t >= 47.3
        for nm in c:
            put(mix, pad_note(note(nm), bar + 0.9), t, 0.15 if not quiet else 0.2)
        if t < 47.3:
            steps = (0, 2, 4, 6) if not act3 else (0, 4)
            for i in steps:
                at = t + i * beat / 2
                if at > 0.4:
                    put(mix, pluck(note(c[(i // 2 + k) % 4]), 1.0, 0.8), at, 0.14, pan=(-0.25 if i % 4 else 0.25))
        if 4.4 <= t < 34.1:
            put(mix, kick(), t, 0.42); put(mix, kick(), t + 2 * beat, 0.34)
            put(mix, np.sin(2 * np.pi * note(bass[k % 4]) * t_axis(bar)) * adsr(int(bar * SR), 0.02, 1.4) * 0.5, t, 0.24)
            if t >= 20.9:
                for j in range(4):
                    put(mix, hat(), t + j * beat + beat / 2, 0.06, pan=0.2)
        elif act3:
            put(mix, np.sin(2 * np.pi * note(bass[k % 4]) * t_axis(bar)) * adsr(int(bar * SR), 0.05, 2.0) * 0.5, t, 0.2)
        t += bar
        k += 1
    mix = reverb(mix, 1.6, 0.2)
    sec = np.arange(n) / SR
    mix *= np.minimum(1, sec / 0.8) * np.minimum(1, (total - sec) / 2.4)
    mix /= np.sqrt(np.mean(mix ** 2)) / 0.10
    mix = np.tanh(mix * 1.1) / np.tanh(1.1)
    mix *= 0.8 / np.max(np.abs(mix))
    pcm = (mix.T * 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(pcm.tobytes())
