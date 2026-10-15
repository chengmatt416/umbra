#!/usr/bin/env python3
"""Cuts the thirteen feature clips for the tour (real screen recordings, marketing/raw/*): 604x1384 (the phone's screen), 30 fps, ~4.2 s each."""
import os, subprocess
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(ROOT, "raw")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "composition", "assets", "video")
# name: (source, start, seconds of source, speed)
CUTS = {
    "f01": ("scene_a_0.mp4", 3.2, 8.6, 2.0),     # ask: the request typed on Home
    "f02": ("scene_a_0.mp4", 22.3, 14.2, 3.4),   # operate: Settings, scroll, About
    "f03": ("scene_d_0.mp4", 10.0, 25.0, 6.0),   # background display
    "f04": ("scene_d_0.mp4", 35.0, 14.0, 3.3),   # linux: crash log, fix, rebuild
    "f05": ("scene_b_0.mp4", 1.0, 19.5, 4.6),    # capsule + snapshot selection
    "f06": ("scene_e_0.mp4", 2.0, 16.0, 3.8),    # plan: DAG, agent tree, wait graph
    "f07": ("scene_e_0.mp4", 18.0, 17.0, 4.0),   # capacity: resources, admission
    "f08": ("scene_e_0.mp4", 52.0, 22.0, 5.2),   # resume: terminal, contract, pause/resume
    "f09": ("scene_e_0.mp4", 77.5, 11.5, 2.7),   # nodes
    "f10": ("scene_c_0.mp4", 1.0, 5.3, 1.25),    # approval: the card, Deny
    "f11": ("scene_e_0.mp4", 89.5, 16.0, 3.8),   # vault + commit
    "f12": ("scene_e_0.mp4", 113.0, 19.0, 4.5),  # evidence + emergency
    "f13": ("scene_e_0.mp4", 155.0, 10.0, 2.3),  # access and data (ends on Access and data)
}
os.makedirs(OUT, exist_ok=True)
for name, (src, ss, dur, sp) in CUTS.items():
    out = os.path.join(OUT, name + ".mp4")
    vf = "setpts=PTS/%s,fps=30,scale=-2:1384:flags=lanczos,crop=604:1384" % sp
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", str(ss), "-t", str(dur), "-i", os.path.join(RAW, src), "-vf", vf, "-an", "-c:v", "libx264", "-crf", "20", "-preset", "slow", "-pix_fmt", "yuv420p", "-movflags", "+faststart", out], check=True)
    d = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", out], capture_output=True, text=True).stdout)
    print(name, round(d, 2), "s")
