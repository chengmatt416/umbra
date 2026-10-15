#!/usr/bin/env python3
"""Builds the Instagram Reels (1080x1920, 30 fps, H.264 + AAC), one per language: build_reel.py [en|zh-tw ...]
A persistent phone carries the real app recordings (marketing/reels/work/*.mp4, cut by `clips.py --reel`);
captions are drawn with PIL and faded in and out; an end card closes each reel. Output: marketing/reels/out/umbra-reel-<lang>.mp4"""
import math
import os
import subprocess
import sys
import sound

from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
WORK = os.path.join(HERE, "work")
OUT = os.path.join(HERE, "out")
TMP = os.path.join(HERE, "tmp")
W, H = 1080, 1920
SW, SH = 664, 1476                    # screen
BEZ = 18
PW, PH = SW + 2 * BEZ, SH + 2 * BEZ   # phone body 700 x 1512
PX, PY = (W - PW) // 2, 520
URL = os.environ.get("SITE_HOST", "umbra-agent.pages.dev")

INK, MUTED, ACCENT, ACCENT2, BASE = (50, 68, 58), (98, 120, 102), (102, 134, 109), (71, 109, 84), (233, 237, 229)

PING = "/System/Library/AssetsV2/com_apple_MobileAsset_Font7/3419f2a427639ad8c8e139149a287865a90fa17e.asset/AssetData/PingFang.ttc"
FONTS = {
    "en": {"head": ("/System/Library/Fonts/Supplemental/Georgia Bold.ttf", 0), "sans": ("/System/Library/Fonts/SFNS.ttf", 0)},
    "zh-tw": {"head": ("/System/Library/Fonts/Supplemental/Songti.ttc", 2), "sans": (PING, 10)},
}

# (clip, seconds on screen) — the screen content; captions are timed independently below.
SCREENS = [("say", 8.0, 1.4), ("operate", 6.5, 2.2), ("linux", 7.0, 2.4), ("capsule", 6.0, 2.4), ("approve", 5.2, 1.0)]  # (clip, seconds on screen, speed-up)
XF = 0.35
END = 4.0

COPY = {
    "en": {
        "caps": [  # (kicker, headline): the first is the hook, then one per screen
            ("UMBRA · FOR ANDROID", "An AI agent that\nlives on your phone."),
            ("01 · ASK", "Say it once."),
            ("02 · OPERATE", "It operates your apps."),
            ("03 · BUILD", "Real Linux. Real builds.\nReal fixes."),
            ("04 · CAPSULE", "Circle the screen.\nJust ask."),
            ("05 · TRUST", "It asks before\nanything risky."),
        ],
        "end": ("Umbra", "The agent that lives on your phone.", "Free · open source · Android 16", URL),
    },
    "zh-tw": {
        "caps": [
            ("UMBRA · ANDROID", "住在你手機裡的\nAI 自主代理"),
            ("01 · 交辦", "一句話，交給它"),
            ("02 · 操作", "它替你操作 App"),
            ("03 · 建置", "真實的 Linux\n真實建置、真實修正"),
            ("04 · 膠囊", "圈起螢幕\n直接問"),
            ("05 · 掌控", "有風險的事\n一定先問你"),
        ],
        "end": ("Umbra", "住在你手機裡的自主代理", "免費開源 · Android 16", URL),
    },
}


def font(lang, kind, size):
    path, idx = FONTS[lang][kind]
    try:
        return ImageFont.truetype(path, size, index=idx or 0)
    except OSError:
        return ImageFont.truetype(path, size)


def background():
    im = Image.new("RGB", (W, H))
    px = im.load()
    top, bot = (226, 233, 221), (210, 222, 207)
    for y in range(H):
        t = y / (H - 1)
        c = tuple(int(top[i] + (bot[i] - top[i]) * t) for i in range(3))
        for x in range(W):
            px[x, y] = c
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(glow)
    d.ellipse([W - 620, -260, W + 380, 740], fill=(197, 215, 194, 190))
    d.ellipse([-420, H - 900, 560, H + 100], fill=(197, 215, 194, 120))
    glow = glow.filter(ImageFilter.GaussianBlur(120))
    im.paste(glow, (0, 0), glow)
    return im


def phone_png():
    pad = 90
    base = Image.new("RGBA", (PW + 2 * pad, PH + 2 * pad), (0, 0, 0, 0))
    shadow = Image.new("RGBA", base.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([pad, pad + 40, pad + PW, pad + PH + 40], 84, fill=(25, 45, 33, 120))
    base.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(40)))
    d = ImageDraw.Draw(base)
    d.rounded_rectangle([pad, pad, pad + PW, pad + PH], 84, fill=(23, 33, 28, 255))
    d.rounded_rectangle([pad + 2, pad + 2, pad + PW - 2, pad + PH - 2], 82, outline=(255, 255, 255, 28), width=2)
    return base, pad


def screen_mask():
    m = Image.new("L", (SW, SH), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, SW - 1, SH - 1], 66, fill=255)
    return m


def wrap_center(draw, text, fnt, y, fill, width=W, spacing=14):
    lines = text.split("\n")
    for ln in lines:
        w = draw.textlength(ln, font=fnt)
        draw.text(((width - w) / 2, y), ln, font=fnt, fill=fill)
        y += fnt.size + spacing
    return y


def caption_png(lang, kicker, head, path, big=False):
    im = Image.new("RGBA", (W, 330), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    kf = font(lang, "sans", 30)
    spaced = " ".join(kicker) if lang == "en" and False else kicker
    w = d.textlength(spaced, font=kf) + len(spaced) * 5
    x = (W - w) / 2
    for ch in spaced:                      # letter-spaced kicker
        d.text((x, 6), ch, font=kf, fill=ACCENT)
        x += d.textlength(ch, font=kf) + 5
    hf = font(lang, "head", 84 if big else 76)
    wrap_center(d, head, hf, 62, INK, spacing=10)
    im.save(path)


def endcard_png(lang, path):
    name, tag, line, url = COPY[lang]["end"]
    im = background().convert("RGBA")
    d = ImageDraw.Draw(im)
    # eclipse mark
    cx, cy, r = W // 2, 640, 150
    mk = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    md = ImageDraw.Draw(mk)
    md.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(102, 134, 109, 255))
    md.ellipse([cx - r + 52, cy - r - 36, cx + r + 52 - 8, cy + r - 36 - 8], fill=(226, 233, 221, 255))
    im.alpha_composite(mk)
    wrap_center(d, name, font(lang, "head", 150), 850, INK)
    wrap_center(d, tag, font(lang, "head", 54), 1070, MUTED)
    wrap_center(d, line, font(lang, "sans", 38), 1210, ACCENT2)
    # url pill
    uf = font(lang, "sans", 46)
    tw = d.textlength(url, font=uf)
    x0, y0 = (W - tw) / 2 - 56, 1350
    d.rounded_rectangle([x0, y0, x0 + tw + 112, y0 + 110], 55, fill=(71, 109, 84, 255))
    d.text((x0 + 56, y0 + 26), url, font=uf, fill=(247, 251, 242))
    im.save(path)


def ambient(path, seconds):
    """A soft generated pad (no samples, no licences): slow chord, gentle tremolo, fades."""
    sr = 44100
    n = int(sr * seconds)
    freqs = [(110.0, .5), (164.81, .35), (220.0, .3), (277.18, .22), (329.63, .16)]
    with wave.open(path, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(sr)
        frames = bytearray()
        for i in range(n):
            t = i / sr
            trem = 0.82 + 0.18 * math.sin(2 * math.pi * 0.16 * t)
            v = sum(a * math.sin(2 * math.pi * f * t + 0.6 * math.sin(2 * math.pi * 0.07 * t)) for f, a in freqs) * trem
            fade = min(1, t / 2.0, (seconds - t) / 2.5)
            s = int(max(-1, min(1, v * 0.16 * fade)) * 32767)
            frames += struct.pack("<hh", s, s)
        w.writeframes(bytes(frames))


def build(lang):
    os.makedirs(TMP, exist_ok=True); os.makedirs(OUT, exist_ok=True)
    copy = COPY[lang]
    starts, t = [], 0.0
    for _, d, _sp in SCREENS:
        starts.append(t); t += d - XF
    t += XF
    total = t + END
    timed = []
    for i, (k, h) in enumerate(copy["caps"]):
        if i == 0:
            timed.append((0.3, 3.2, k, h))
        else:
            j = i - 1
            a = starts[j] + (3.4 if j == 0 else 0.4)
            b = starts[j] + SCREENS[j][1] - 0.15
            timed.append((a, b, k, h))
    bg = os.path.join(TMP, "bg.png")
    if not os.path.exists(bg):
        background().save(bg)
    body, pad = phone_png(); body.save(os.path.join(TMP, "phone.png"))
    screen_mask().save(os.path.join(TMP, "mask.png"))
    caps = []
    for i, (a, b, k, h) in enumerate(timed):
        p = os.path.join(TMP, "cap_%s_%d.png" % (lang, i)); caption_png(lang, k, h, p, big=(i == 0)); caps.append((a, b, p))
    end = os.path.join(TMP, "end_%s.png" % lang); endcard_png(lang, end)
    aud = os.path.join(TMP, "ambient.wav")
    sound.make(aud, total, total - END, starts, [a for a, _, _, _ in timed], typing=(0.9, 4.3),
               lasso_at=starts[3] + 1.7, deny_at=starts[4] + 3.3, success_at=starts[2] + SCREENS[2][1] - 2.0)

    # 1) the screen strip: clips cross-faded
    ins, fl, prev, off = [], [], None, 0.0
    for i, (name, dur, sp) in enumerate(SCREENS):
        ins += ["-i", os.path.join(WORK, name + ".mp4")]
        fl.append("[%d:v]trim=0:%.2f,setpts=(PTS-STARTPTS)/%s,fps=30[s%d]" % (i, dur * sp, sp, i))
    cur = "s0"; off = SCREENS[0][1] - XF
    for i in range(1, len(SCREENS)):
        out = "x%d" % i
        fl.append("[%s][s%d]xfade=transition=fade:duration=%.2f:offset=%.2f[%s]" % (cur, i, XF, off, out))
        cur = out; off += SCREENS[i][1] - XF
    strip = os.path.join(TMP, "strip_%s.mp4" % lang)
    subprocess.run(["ffmpeg", "-v", "error", "-y", *ins, "-filter_complex", ";".join(fl), "-map", "[%s]" % cur, "-an", "-c:v", "libx264", "-crf", "14", "-pix_fmt", "yuv420p", strip], check=True)

    # 2) compose
    cmd = ["ffmpeg", "-v", "error", "-y", "-loop", "1", "-framerate", "30", "-t", "%.2f" % total, "-i", bg,
           "-loop", "1", "-framerate", "30", "-t", "%.2f" % total, "-i", os.path.join(TMP, "phone.png"),
           "-i", strip, "-loop", "1", "-i", os.path.join(TMP, "mask.png")]
    for a, b, p in caps:
        cmd += ["-loop", "1", "-framerate", "30", "-t", "%.2f" % total, "-i", p]
    cmd += ["-loop", "1", "-framerate", "30", "-t", "%.2f" % total, "-i", end, "-i", aud]
    n_caps = len(caps)
    fc = ["[0:v]format=rgba[bg]",
          "[1:v]format=rgba[ph]",
          "[2:v]scale=%d:%d,format=rgba[sc]" % (SW, SH),
          "[3:v]scale=%d:%d,format=gray[mk]" % (SW, SH),
          "[sc][mk]alphamerge[scm]",
          "[bg][ph]overlay=%d:%d[b1]" % (PX - pad, PY - pad),
          "[b1][scm]overlay=%d:%d:eof_action=pass[b2]" % (PX + BEZ, PY + BEZ)]
    cur = "b2"
    for i, (a, b, p) in enumerate(caps):
        idx = 4 + i
        fc.append("[%d:v]format=rgba,fade=t=in:st=%.2f:d=0.4:alpha=1,fade=t=out:st=%.2f:d=0.35:alpha=1[c%d]" % (idx, a, b - 0.35, i))
        fc.append("[%s][c%d]overlay=0:'250+max(0,(1-(t-%.2f)/0.45))*34':shortest=0[o%d]" % (cur, i, a, i))
        cur = "o%d" % i
    ei = 4 + n_caps
    es = total - END
    fc.append("[%d:v]format=rgba,fade=t=in:st=%.2f:d=0.6:alpha=1[e]" % (ei, es))
    fc.append("[%s][e]overlay=0:0[v]" % cur)
    fc.append("[%d:a]afade=t=out:st=%.2f:d=2,aformat=sample_fmts=fltp:channel_layouts=stereo[a]" % (ei + 1, total - 2.2))
    out = os.path.join(OUT, "umbra-reel-%s.mp4" % lang)
    cmd += ["-filter_complex", ";".join(fc), "-map", "[v]", "-map", "[a]", "-t", "%.2f" % total, "-r", "30", "-c:v", "libx264", "-preset", "slow", "-crf", "18",
            "-pix_fmt", "yuv420p", "-profile:v", "high", "-level", "4.2", "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart", out]
    subprocess.run(cmd, check=True)
    print("wrote", out, "%.1fs" % total)


if __name__ == "__main__":
    for lang in (sys.argv[1:] or ["en", "zh-tw"]):
        build(lang)
