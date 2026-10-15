#!/usr/bin/env python3
"""The brag Reel: big counting numbers slammed on the beat (120 BPM), a ticker tape, a tilted phone playing real footage under each
number. Every number is measured from the repository (see STATS and the command that produced it); nothing is rounded up.
    brag.py [en|zh-tw ...]  ->  marketing/reels/out/umbra-brag-<lang>.mp4 (1080x1920, 30 fps, 30 s)"""
import math
import os
import subprocess
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

import sound
from build_reel import PING, URL, font

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
TMP = os.path.join(HERE, "tmp")
CLIPS = os.path.join(os.path.dirname(os.path.dirname(HERE)), "website", "public", "clips")
W, H, FPS = 1080, 1920, 30
BEAT = 0.5
HOOK, SLOT, END = 2.0, 3.0, 4.0

NIGHT, SAGE, INKC, ACC, ACC2, PALE = (38, 55, 47), (226, 233, 221), (50, 68, 58), (102, 134, 109), (71, 109, 84), (197, 215, 194)

# value, shown as: (prefix, number, suffix); source = how it was measured on 2026-10-11
STATS = [
    dict(n=52745, suffix="", clip="linux", ss=2.0, src="git ls-files '*.kt' | xargs cat | wc -l",
         en=("lines of Kotlin", "329 files · every one open source"), zh=("行 Kotlin 程式碼", "329 個檔案 · 全部開源")),
    dict(n=23, suffix="", clip="resources", ss=3.0, src="settings.gradle.kts: include(...)",
         en=("Gradle modules", "agent · safety · Linux · nodes · vault"), zh=("個 Gradle 模組", "代理 · 安全 · Linux · 節點 · 保險庫")),
    dict(n=797, suffix="", clip="approve", ss=0.5, src="scripts/test-summary.py: tests=797 failures=0 skipped=36", extra=("0", "failures", "失敗"),
         en=("automated tests", "last recorded run · 36 need a real device"), zh=("個自動化測試", "最近一次紀錄 · 36 個需要真機")),
    dict(n=23, suffix="", clip="plan", ss=1.0, src="ls docs/adr | wc -l",
         en=("design decisions written down", "architecture decision records, one per hard call"), zh=("份設計決策紀錄", "每個困難決定都有 ADR")),
    dict(n=19, suffix="/22", clip="evidence", ss=1.0, src="docs/traceability.md AC-001..AC-022: 19 met, 1 partly, 2 not built",
         en=("spec acceptance criteria met", "the other three are listed openly"), zh=("項規格驗收條件已達成", "其餘三項公開列出")),
    dict(n=24, suffix="", clip="operate", ss=0.5, src="distinct android_* tool names in the sources",
         en=("Android tools for the agent", "tap · type · scroll · logs · packages · displays…"), zh=("個 Android 工具", "點擊 · 輸入 · 捲動 · 日誌 · 套件 · 顯示器…")),
    dict(n=7, suffix="", clip="nodes", ss=0.5, src="Security & Evidence screen: EVIDENCE CHAIN · 7 JOURNALS",
         en=("hash-chained evidence journals", "tamper-evident, anchored off the phone"), zh=("份雜湊鏈證據日誌", "防竄改，並可錨定在手機之外")),
    dict(n=637, suffix=" MiB", clip="background", ss=4.0, src="umbra-0.1.0.apk = 667,746,797 bytes",
         en=("a whole Linux inside the APK", "Debian, bash, git, Python, Bun — no network needed"), zh=("整個 Linux 都在 APK 裡", "Debian、bash、git、Python、Bun，安裝不需網路")),
]

COPY = {
    "en": dict(hook=("UMBRA · BY THE NUMBERS", "Let's\nbrag."), end=("Umbra", "Now it's your turn.", "Free · open source · Android 16"),
               ticker="OPERATES APPS ● REAL LINUX ● ASKS FIRST ● RESUMABLE ● OPEN SOURCE ● NO CLOUD LOCK-IN ● ", fail="failures"),
    "zh-tw": dict(hook=("UMBRA · 用數字說話", "來炫耀\n一下。"), end=("Umbra", "換你試試。", "免費開源 · Android 16"),
                  ticker="操作 APP ● 真實 LINUX ● 先問再做 ● 可續跑 ● 開源 ● 沒有雲端綁定 ● ", fail="失敗"),
}


def smoother(x):
    x = min(1.0, max(0.0, x))
    return x * x * x * (x * (x * 6 - 15) + 10)


def ease_out_expo(x):
    return 1.0 if x >= 1 else 1 - 2 ** (-10 * max(0.0, x))


_fonts = {}


def num_font(size):
    size = int(size // 6 * 6)
    if size not in _fonts:
        f = ImageFont.truetype("/System/Library/Fonts/SFNS.ttf", size)
        f.set_variation_by_axes([100, 96, 400, 900])
        _fonts[size] = f
    return _fonts[size]


def fit_number(d, text, max_w, start=360):
    s = start
    while s > 120 and d.textlength(text, font=num_font(s)) > max_w:
        s -= 12
    return s


def phone_card(frame_img, w=400):
    h = int(w * 2.22)
    body = Image.new("RGBA", (w + 24, h + 24), (0, 0, 0, 0))
    bd = ImageDraw.Draw(body)
    bd.rounded_rectangle([0, 0, w + 23, h + 23], 52, fill=(23, 33, 28, 255))
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, w - 1, h - 1], 42, fill=255)
    body.paste(frame_img.resize((w, h), Image.BILINEAR), (12, 12), m)
    return body


def decoder(clip, ss, w=400):
    h = int(w * 2.22)
    p = subprocess.Popen(["ffmpeg", "-v", "error", "-ss", str(ss), "-stream_loop", "-1", "-i", os.path.join(CLIPS, clip + ".mp4"), "-vf", "fps=%d,scale=%d:%d" % (FPS, w, h),
                          "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
    return p, w, h


def ticker_strip(lang, color):
    f = font(lang, "sans", 40)
    one = COPY[lang]["ticker"]
    d = ImageDraw.Draw(Image.new("RGB", (10, 10)))
    per = int(d.textlength(one, font=f)) + 1
    unit = Image.new("RGBA", (per, 84), (0, 0, 0, 0))
    ImageDraw.Draw(unit).text((0, 16), one, font=f, fill=color)
    strip = Image.new("RGBA", (per * 3 + W, 84), (0, 0, 0, 0))
    for k in range(4 + W // per):
        strip.alpha_composite(unit, (k * per, 0)) if k * per < strip.width else None
    return strip, per


def centered(d, text, y, fnt, fill, cx=W // 2, dx=0, dy=0):
    w = d.textlength(text, font=fnt)
    d.text((cx - w / 2 + dx, y + dy), text, font=fnt, fill=fill)
    return w


def render(lang):
    os.makedirs(OUT, exist_ok=True); os.makedirs(TMP, exist_ok=True)
    c = COPY[lang]
    nst = len(STATS)
    slams = [HOOK + SLOT * i for i in range(nst)]
    end_start = HOOK + SLOT * nst
    total = end_start + END
    aud = os.path.join(TMP, "brag.wav")
    if lang == "en" or not os.path.exists(aud):
        sound.make_brag(aud, total, slams, end_start)
    out = os.path.join(OUT, "umbra-brag-%s.mp4" % lang)
    stills = [float(x) for x in os.environ.get("BRAG_STILLS", "").split(",") if x]
    enc = subprocess.Popen(["cat"], stdin=subprocess.PIPE, stdout=subprocess.DEVNULL) if stills else subprocess.Popen(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "%dx%d" % (W, H), "-r", str(FPS), "-i", "-", "-i", aud,
                            "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p", "-profile:v", "high", "-level", "4.2",
                            "-c:a", "aac", "-b:a", "160k", "-shortest", "-movflags", "+faststart", out], stdin=subprocess.PIPE)
    strip_l, per_l = ticker_strip(lang, (244, 249, 240))
    rng = np.random.default_rng(3)
    shakes = rng.uniform(-1, 1, (int(total * FPS) + 1, 2))
    decs = {}
    curdec = None
    frame_cache = None
    nframes = int(total * FPS)
    colors = [NIGHT] + [SAGE if i % 2 == 0 else NIGHT for i in range(nst)] + [SAGE]
    starts = [0.0] + slams + [end_start]
    for i in range(nframes):
        t = i / FPS
        seg = max(k for k, s in enumerate(starts) if t >= s)
        st = t - starts[seg]
        bgc = colors[seg]
        prev = colors[seg - 1] if seg > 0 else bgc
        dark = bgc == NIGHT
        fg = (244, 249, 240) if dark else INKC
        sub = (160, 190, 168) if dark else (98, 120, 102)
        img = Image.new("RGB", (W, H), prev)
        d = ImageDraw.Draw(img)
        if seg > 0 and st < 0.32:
            r = int(smoother(st / 0.3) * 2000)
            d.ellipse([W // 2 - r, 620 - r, W // 2 + r, 620 + r], fill=bgc)
        else:
            d.rectangle([0, 0, W, H], fill=bgc)
        amp = 22 * math.exp(-st * 9) if seg >= 0 else 0
        dx, dy = shakes[i] * amp
        if seg == 0:                                           # hook
            k, head = c["hook"]
            hf = font(lang, "head", 230)
            centered(d, k, 420 + dy, font(lang, "sans", 36), (143, 176, 151), dx=dx)
            u = smoother((st - 0.1) / 0.35)
            y = 560 + (1 - u) * 80
            for j, ln in enumerate(head.split("\n")):
                centered(d, ln, y + j * 250, hf, fg, dx=dx, dy=dy)
            # highlight swipe
            bw = int(smoother((st - 0.7) / 0.4) * 700)
            d.rectangle([190, 1130, 190 + bw, 1150], fill=ACC)
        elif seg <= nst:
            s = STATS[seg - 1]
            label, subtext = s[lang if lang == "en" else "zh"]
            # count-up
            u = ease_out_expo(st / 1.1)
            val = int(round(s["n"] * u))
            numtxt = "{:,}".format(val)
            full = numtxt + s["suffix"]
            maxw = 900
            size = fit_number(d, "{:,}".format(s["n"]) + s["suffix"], maxw)
            pop = 1 + 0.30 * math.exp(-st * 11)
            nf = num_font(size * pop)
            w = d.textlength(full, font=nf)
            bb = d.textbbox((0, 0), full, font=nf)
            ny = 500 - (bb[1] + bb[3]) / 2
            d.text((W / 2 - w / 2 + dx, ny + dy), full, font=nf, fill=ACC if not dark else (143, 190, 156))
            # label + sub
            la = smoother((st - 0.30) / 0.35)
            lf = font(lang, "head", 58 if lang == "en" else 62)
            ly = 730 + (1 - la) * 40
            if la > 0:
                col = tuple(int(bg + (f - bg) * la) for bg, f in zip(bgc, fg))
                centered(d, label, ly + dy, lf, col, dx=dx)
                sc = tuple(int(bg + (f - bg) * la) for bg, f in zip(bgc, sub))
                centered(d, subtext, ly + 90 + dy, font(lang, "sans", 34), sc, dx=dx)
            if "extra" in s and st > 1.2:
                ev, el, ez = s["extra"]
                ea = smoother((st - 1.2) / 0.3)
                ef = num_font(110)
                txt = "%s %s" % (ev, ez if lang != "en" else el)
                tw = d.textlength(txt, font=ef) if lang == "en" else d.textlength(txt, font=font(lang, "head", 100))
                d.rounded_rectangle([W / 2 - tw / 2 - 40 + dx, 880 + dy, W / 2 + tw / 2 + 40 + dx, 1010 + dy], 65, fill=ACC2 if ea > 0.5 else bgc)
                if ea > 0.5:
                    d.text((W / 2 - tw / 2 + dx, 887 + dy), txt, font=ef if lang == "en" else font(lang, "head", 100), fill=(244, 249, 240))
            # ticker tape
            ty = 790 if "extra" not in s else 1050
            off = int((t * 330) % per_l)
            img_rgba = img.convert("RGBA")
            band = Image.new("RGBA", (W, 84), ACC2 + (255,))
            band.alpha_composite(strip_l.crop((off, 0, off + W, 84)))
            if "extra" not in s:
                img_rgba.alpha_composite(band, (0, 905))
            img = img_rgba.convert("RGB")
            d = ImageDraw.Draw(img)
            # phone card with real footage
            if curdec != seg:
                if curdec in decs:
                    decs[curdec][0].kill()
                decs[seg] = decoder(s["clip"], s["ss"])
                curdec = seg
            p, pw, ph = decs[seg]
            buf = p.stdout.read(pw * ph * 3)
            if len(buf) == pw * ph * 3:
                frame_cache = Image.frombytes("RGB", (pw, ph), buf)
            if frame_cache is not None:
                card = phone_card(frame_cache, pw)
                ang = (5 if seg % 2 else -5) * (1 - 0.3 * smoother(st / 0.6))
                card = card.rotate(ang, expand=True, resample=Image.BICUBIC)
                rise = (1 - smoother((st - 0.15) / 0.5)) * 700
                cx = W // 2 + (150 if seg % 2 else -150)
                top = 1090 + rise + math.sin(t * 2.2) * 6
                shadow = Image.new("RGBA", (card.width + 120, card.height + 120), (0, 0, 0, 0))
                shadow.paste((0, 0, 0, 90), (60, 80, 60 + card.width, 80 + card.height), card.split()[3])
                shadow = shadow.filter(ImageFilter.GaussianBlur(26))
                im2 = img.convert("RGBA")
                im2.alpha_composite(shadow, (int(cx - card.width / 2 - 60), int(top - 40)))
                im2.alpha_composite(card, (int(cx - card.width / 2), int(top)))
                img = im2.convert("RGB")
            # flash
            if st < 0.12:
                fl = int(110 * (1 - st / 0.12))
                img = Image.blend(img, Image.new("RGB", (W, H), (255, 255, 255)), fl / 255)
        else:                                                  # end card
            if curdec in decs:
                decs[curdec][0].kill(); curdec = None
            name, tag, line = c["end"]
            cx, cy, r = W // 2, 640, 150
            u = smoother(st / 0.5)
            d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=ACC)
            d.ellipse([cx - r + 52, cy - r - 36, cx + r + 44, cy + r - 44], fill=bgc)
            centered(d, name, 860 + (1 - u) * 60, font(lang, "head", 160), INKC, dy=dy)
            centered(d, tag, 1090, font(lang, "head", 64), (98, 120, 102))
            centered(d, line, 1200, font(lang, "sans", 40), ACC2)
            uf = font(lang, "sans", 46)
            tw = d.textlength(URL, font=uf)
            x0 = (W - tw) / 2 - 56
            d.rounded_rectangle([x0, 1330, x0 + tw + 112, 1440], 55, fill=ACC2)
            d.text((x0 + 56, 1356), URL, font=uf, fill=(247, 251, 242))
        enc.stdin.write(img.tobytes())
        if stills and any(abs(t - x) < 1 / FPS / 2 for x in stills):
            img.save(os.path.join(TMP, 'brag_%s_%05.2f.png' % (lang, t)))
        if stills and t > max(stills):
            break
        if i % 150 == 0:
            print("frame %d/%d" % (i, nframes), flush=True)
    enc.stdin.close(); enc.wait()
    for p, _, _ in decs.values():
        p.kill()
    print("wrote", out)


if __name__ == "__main__":
    for lang in (sys.argv[1:] or ["en", "zh-tw"]):
        render(lang)
