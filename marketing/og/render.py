#!/usr/bin/env python3
"""Renders website/public/og.png (1200x630) from og.html with two frames of the real recordings:
the agents clip (Home with the brief and suggestions) and the Linux clip. Run with marketing/.venv/bin/python."""
import os
import subprocess
from playwright.sync_api import sync_playwright

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
RAW = os.path.join(ROOT, "marketing", "raw")
for name, src, t in (("frame-a.png", "scene_h_en_0.mp4", 5.0), ("frame-b.png", "scene_d_0.mp4", 112.0)):
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", str(t), "-i", os.path.join(RAW, src), "-frames:v", "1", "-vf", "scale=480:-1", os.path.join(HERE, name)], check=True)
with sync_playwright() as p:
    browser = p.chromium.launch()
    page = browser.new_page(viewport={"width": 1200, "height": 630}, device_scale_factor=1)
    page.goto("file://" + os.path.join(HERE, "og.html"))
    page.wait_for_timeout(1500)
    page.screenshot(path=os.path.join(ROOT, "website", "public", "og.png"))
    browser.close()
print("wrote website/public/og.png")
