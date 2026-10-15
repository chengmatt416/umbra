#!/usr/bin/env python3
"""The one-take Reel: ONE uninterrupted screen recording of the real app (marketing/raw/scene_f_0.mp4, no cuts), shown through a
virtual camera that pushes in, follows and pulls back. A live clock runs the whole time, which is the point of the format.
    onetake.py [en|zh-tw ...]    ->  marketing/reels/out/umbra-onetake-<lang>.mp4  (1080x1920, 30 fps)"""
import json
import math
import os
import subprocess
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

import sound
from build_reel import FONTS, PING, URL, font, INK, ACCENT, ACCENT2

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(os.path.dirname(HERE), "raw")
OUT = os.path.join(HERE, "out")
TMP = os.path.join(HERE, "tmp")
W, H = 1080, 1920
S = 2                                  # world resolution factor
SRC_W, SRC_H = 720, 1600
PX, PY, PW, PH = 190, 330, 700, 1512   # phone body in canvas pixels
BEZ = 18
SX, SY, SW, SH = PX + BEZ, PY + BEZ, PW - 2 * BEZ, PH - 2 * BEZ
FPS = 30
TAIL = 3.2                             # seconds of end card after the take

# camera: (arrive, leave, zoom, nx, ny): ny/nx are the screen point the camera looks at (0..1 of the phone screen)
CAM = [
    (0.0, 1.2, 1.00, .50, .50), (2.8, 4.0, 1.12, .50, .55), (4.9, 8.0, 1.90, .50, .63), (8.45, 9.2, 1.55, .72, .70),
    (10.6, 11.2, 1.12, .50, .50), (12.2, 17.6, 2.15, .50, .035), (19.9, 20.6, 1.20, .50, .50), (22.2, 29.0, 1.45, .50, .45),
    (30.4, 31.4, 1.50, .50, .25), (33.0, 35.6, 1.90, .50, .80), (36.6, 37.6, 1.20, .50, .50), (39.0, 41.8, 1.70, .50, .27),
    (43.0, 44.2, 1.45, .50, .37), (44.8, 45.7, 2.10, .17, .57), (46.5, 47.5, 1.30, .50, .20), (48.2, 49.0, 1.10, .50, .50), (51.0, 53.2, 1.35, .50, .42),
    (54.6, 54.8, 1.00, .50, .50),
]
CAM_TAIL = [(0.0, 0.0, 1.0, .5, .5)]

COPY = {
    "en": {
        "caps": [  # (start, end, kicker, headline)
            (0.4, 3.8, "ONE TAKE · NO CUTS", "Real phone.\nReal app."),
            (4.3, 9.6, "01 · ASK", "Just type what\nyou want."),
            (11.4, 19.2, "02 · WATCH", "The capsule follows\nthe task."),
            (20.2, 36.0, "03 · OPERATE", "It taps through\nSettings for you."),
            (38.2, 45.0, "04 · TRUST", "A risky step?\nIt asks first."),
            (46.6, 54.2, "05 · YOU DECIDE", "Denied. Nothing\nwas removed."),
        ],
        "end": ("Umbra", "One take. Zero cuts.", URL),
        "rec": "REC",
    },
    "zh-tw": {
        "caps": [
            (0.4, 3.8, "ONE TAKE · 完全不剪", "真實手機\n真實 App"),
            (4.3, 9.6, "01 · 交辦", "想做什麼\n直接打字"),
            (11.4, 19.2, "02 · 觀看", "膠囊即時顯示\n任務進度"),
            (20.2, 36.0, "03 · 操作", "它替你一步步\n點開設定"),
            (38.2, 45.0, "04 · 信任", "有風險的步驟\n一定先問你"),
            (46.6, 54.2, "05 · 由你決定", "你拒絕了\n它什麼都沒動"),
        ],
        "end": ("Umbra", "一鏡到底，完全不剪。", URL),
        "rec": "REC",
    },
}

EVENTS = {"typing": (5.7, 8.1), "send": 8.24, "approval": 38.7, "deny": 45.6, "answer": 47.7, "tap_about": 29.4,
          "moves": [4.4, 8.5, 11.8, 19.6, 22.0, 32.8, 38.8, 42.9, 44.8, 47.8, 51.0]}


def smoother(x):
    x = min(1.0, max(0.0, x))
    return x * x * x * (x * (x * 6 - 15) + 10)


def camera(t):
    """(zoom, centre x, centre y) in canvas pixels at time t."""
    kfs = CAM
    if t <= kfs[0][0]:
        k = kfs[0]
        z, nx, ny = k[2], k[3], k[4]
    else:
        z = nx = ny = None
        for a, b in zip(kfs, kfs[1:]):
            if a[1] <= t < b[0]:
                u = smoother((t - a[1]) / (b[0] - a[1]))
                z = a[2] + (b[2] - a[2]) * u; nx = a[3] + (b[3] - a[3]) * u; ny = a[4] + (b[4] - a[4]) * u
                break
            if b[0] <= t <= b[1]:
                z, nx, ny = b[2], b[3], b[4]
                break
        if z is None:
            k = kfs[-1]
            z, nx, ny = k[2], k[3], k[4]
    cx = SX + nx * SW
    cy = SY + ny * SH
    # handheld life: a slow drift that grows a little with the zoom
    cx += math.sin(t * 0.9) * 2.5 * z + math.sin(t * 2.3 + 1) * 1.2
    cy += math.cos(t * 0.7) * 2.5 * z + math.sin(t * 1.9) * 1.2
    cy += 0.11 * (H / z)               # look slightly above the target: the caption card sits in the lower third
    return z, cx, cy


def bg_world():
    base = Image.new("RGB", (W * S, H * S))
    arr = np.zeros((H * S, W * S, 3), dtype=np.uint8)
    top, bot = np.array((226, 233, 221)), np.array((208, 220, 205))
    ys = np.linspace(0, 1, H * S)[:, None, None]
    arr[:] = (top + (bot - top) * ys).astype(np.uint8)
    base = Image.fromarray(arr)
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(glow)
    d.ellipse([W - 640, -300, W + 360, 700], fill=(197, 215, 194, 200))
    d.ellipse([-440, H - 800, 560, H + 200], fill=(197, 215, 194, 130))
    glow = glow.filter(ImageFilter.GaussianBlur(120)).resize((W * S, H * S))
    base.paste(glow, (0, 0), glow)
    # phone body + shadow
    shadow = Image.new("RGBA", (W * S, H * S), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([PX * S, (PY + 46) * S, (PX + PW) * S, (PY + PH + 46) * S], 84 * S, fill=(25, 45, 33, 120))
    shadow = shadow.filter(ImageFilter.GaussianBlur(44 * S / 2))
    base.paste(shadow, (0, 0), shadow)
    body = Image.new("RGBA", (W * S, H * S), (0, 0, 0, 0))
    bd = ImageDraw.Draw(body)
    bd.rounded_rectangle([PX * S, PY * S, (PX + PW) * S, (PY + PH) * S], 84 * S, fill=(23, 33, 28, 255))
    bd.rounded_rectangle([PX * S + 2, PY * S + 2, (PX + PW) * S - 2, (PY + PH) * S - 2], 82 * S, outline=(255, 255, 255, 28), width=2)
    base.paste(body, (0, 0), body)
    return base


def screen_mask():
    m = Image.new("L", (SW * S, SH * S), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, SW * S - 1, SH * S - 1], 66 * S, fill=255)
    return m


def card_layer(lang, kicker, head):
    """Caption card: translucent night-green, rounded, in the lower third."""
    w, h = 880, 330
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([0, 0, w - 1, h - 1], 46, fill=(38, 55, 47, 226))
    kf = font(lang, "sans", 30)
    x = 54
    for ch in kicker:
        d.text((x, 38), ch, font=kf, fill=(143, 176, 151)); x += d.textlength(ch, font=kf) + 5
    hf = font(lang, "head", 72)
    y = 96
    for ln in head.split("\n"):
        d.text((54, y), ln, font=hf, fill=(244, 249, 240)); y += 88
    return im


def end_layer(lang):
    name, tag, url = COPY[lang]["end"]
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, W, H], fill=(226, 233, 221, 238))
    cx, cy, r = W // 2, 700, 140
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(102, 134, 109, 255))
    d.ellipse([cx - r + 50, cy - r - 34, cx + r + 42, cy + r - 42], fill=(226, 233, 221, 255))
    hf = font(lang, "head", 150)
    tw = d.textlength(name, font=hf); d.text(((W - tw) / 2, 880), name, font=hf, fill=INK)
    sf = font(lang, "head", 56)
    tw = d.textlength(tag, font=sf); d.text(((W - tw) / 2, 1090), tag, font=sf, fill=(98, 120, 102))
    uf = font(lang, "sans", 46)
    tw = d.textlength(url, font=uf); x0 = (W - tw) / 2 - 56
    d.rounded_rectangle([x0, 1260, x0 + tw + 112, 1370], 55, fill=(71, 109, 84, 255))
    d.text((x0 + 56, 1286), url, font=uf, fill=(247, 251, 242))
    return im


def render(lang):
    os.makedirs(OUT, exist_ok=True); os.makedirs(TMP, exist_ok=True)
    src = os.path.join(RAW, "scene_f_0.mp4")
    dur = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", src], capture_output=True, text=True).stdout)
    take = dur
    total = take + TAIL
    nframes = int(total * FPS)
    aud = os.path.join(TMP, "onetake_%s.wav" % lang)
    ev = dict(EVENTS)
    sound.make_onetake(aud, total, take - 0.2, ev)

    base = bg_world()
    mask = screen_mask()
    caps = [(a, b, card_layer(lang, k, h)) for a, b, k, h in COPY[lang]["caps"]]
    endl = end_layer(lang)
    recf = font(lang, "sans", 34)
    out = os.path.join(OUT, "umbra-onetake-%s.mp4" % lang)
    dec = subprocess.Popen(["ffmpeg", "-v", "error", "-i", src, "-vf", "fps=%d" % FPS, "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
    enc = subprocess.Popen(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "%dx%d" % (W, H), "-r", str(FPS), "-i", "-", "-i", aud,
                            "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p", "-profile:v", "high", "-level", "4.2",
                            "-c:a", "aac", "-b:a", "160k", "-shortest", "-movflags", "+faststart", out], stdin=subprocess.PIPE)
    last = None
    fsz = SRC_W * SRC_H * 3
    for i in range(nframes):
        t = i / FPS
        buf = dec.stdout.read(fsz) if t < take else b""
        if len(buf) == fsz:
            last = Image.frombytes("RGB", (SRC_W, SRC_H), buf)
        if last is None:
            continue
        world = base.copy()
        scr = last.resize((SW * S, SH * S), Image.BILINEAR)
        world.paste(scr, (SX * S, SY * S), mask)
        if t < take:
            z, cx, cy = camera(t)
        else:                                   # after the take: a slow pull-back under the end card
            z0, cx0, cy0 = camera(take - 0.01)
            u = smoother((t - take) / TAIL)
            z, cx, cy = z0 + (1.0 - z0) * u, cx0 + (W / 2 - cx0) * u, cy0 + (H / 2 - cy0) * u
        bw, bh = W / z, H / z
        cx = min(max(cx, bw / 2), W - bw / 2); cy = min(max(cy, bh / 2), H - bh / 2)
        box = ((cx - bw / 2) * S, (cy - bh / 2) * S, (cx + bw / 2) * S, (cy + bh / 2) * S)
        frame = world.transform((W, H), Image.EXTENT, box, Image.BICUBIC).convert("RGBA")
        # caption card
        for a, b, layer in caps:
            if a <= t <= b:
                fin = smoother((t - a) / 0.45); fout = smoother((b - t) / 0.4)
                al = min(fin, fout)
                lay = layer.copy()
                lay.putalpha(lay.getchannel("A").point(lambda v, al=al: int(v * al)))
                frame.alpha_composite(lay, (100, int(1210 + (1 - fin) * 40)))
        # live clock: the proof that nothing was cut
        if t < take + 0.05:
            sec = int(min(t, take))
            chip = Image.new("RGBA", (330, 74), (0, 0, 0, 0))
            cd = ImageDraw.Draw(chip)
            cd.rounded_rectangle([0, 0, 329, 73], 37, fill=(38, 55, 47, 205))
            on = int(t * 2) % 2 == 0
            cd.ellipse([26, 24, 52, 50], fill=(214, 69, 69, 255 if on else 90))
            cd.text((68, 18), "%s  %02d:%02d" % (COPY[lang]["rec"], sec // 60, sec % 60), font=recf, fill=(244, 249, 240))
            frame.alpha_composite(chip, ((W - 330) // 2, 205))
        if t > take:
            u = smoother((t - take - 0.15) / 1.0)
            lay = endl.copy(); lay.putalpha(lay.getchannel("A").point(lambda v, u=u: int(v * u)))
            frame.alpha_composite(lay)
        enc.stdin.write(frame.convert("RGB").tobytes())
        if i % 150 == 0:
            print("frame %d/%d" % (i, nframes), flush=True)
    enc.stdin.close(); enc.wait(); dec.stdout.close(); dec.wait()
    print("wrote", out)


if __name__ == "__main__":
    for lang in (sys.argv[1:] or ["en", "zh-tw"]):
        render(lang)
