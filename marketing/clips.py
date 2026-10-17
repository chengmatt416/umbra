#!/usr/bin/env python3
"""Cuts the raw emulator recordings into the website clips: marketing/clips.py [name ...]
Each clip: source file (marketing/raw), start, end (seconds in the source), speed, poster time (seconds in the output).
Output: website/public/clips/NAME.mp4 (H.264, 576x1280, no audio, 30 fps) and NAME.jpg (poster)."""
import json
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(ROOT, "marketing", "raw")
OUT = os.path.join(ROOT, "website", "public", "clips")
SPEC = json.load(open(os.path.join(ROOT, "marketing", "clips.json")))


def cut(name, c, reel=False):
    src = os.path.join(RAW, c["src"])
    parts = []
    # Optional list of segments [[start, end, speed], ...] concatenated; or one start/end/speed.
    segs = c.get("segments") or [[c["start"], c["end"], c.get("speed", 1)]]
    filt = []
    for i, (a, b, sp) in enumerate(segs):
        # screenrecord writes a frame only when the screen changes: fps=30 first turns that into a constant rate, so a
        # segment that starts or ends during a still keeps the still instead of jumping to the next change.
        filt.append("[0:v]fps=30,trim=%s:%s,setpts=(PTS-STARTPTS)/%s,fps=30,scale=%s:flags=lanczos,setsar=1[v%d]" % (a, b, sp, "664:1476" if reel else "576:1280", i))
        parts.append("[v%d]" % i)
    filt.append("%sconcat=n=%d:v=1:a=0[o]" % ("".join(parts), len(segs)))
    out = os.path.join(ROOT, "marketing", "reels", "work") if reel else OUT
    os.makedirs(out, exist_ok=True)
    mp4 = os.path.join(out, name + ".mp4")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", src, "-filter_complex", ";".join(filt), "-map", "[o]", "-an",
                    "-c:v", "libx264", "-preset", "slow", "-crf", str(16 if reel else c.get("crf", 27)), "-pix_fmt", "yuv420p", "-profile:v", "high",
                    "-movflags", "+faststart", mp4], check=True)
    if not reel:
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", str(c.get("poster", 1.0)), "-i", mp4, "-frames:v", "1", "-q:v", "3", os.path.join(out, name + ".jpg")], check=True)
    dur = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", mp4], capture_output=True, text=True).stdout)
    print("%-12s %5.1fs %6.0f KB" % (name, dur, os.path.getsize(mp4) / 1024))


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if a != "--reel"]
    for n in (args or SPEC):
        cut(n, SPEC[n], reel="--reel" in sys.argv)
