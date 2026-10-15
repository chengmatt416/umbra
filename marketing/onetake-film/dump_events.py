#!/usr/bin/env python3
"""Writes events.json (window.__events()) and zoom.json (d log zoom / dt at 240 Hz) from the composition, for score.py."""
import asyncio, json, os, sys
from playwright.async_api import async_playwright

HERE = os.path.dirname(os.path.abspath(__file__))
LANG = sys.argv[1] if len(sys.argv) > 1 else "en"


async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch()
        pg = await b.new_page(viewport={"width": 1080, "height": 1920})
        await pg.goto("file://%s/comp.html?lang=%s" % (HERE, LANG))
        await pg.evaluate("window.__ready")
        ev = await pg.evaluate("window.__events()")
        n = int(ev["T"] * 240) + 1
        zv = await pg.evaluate("n => Array.from({length: n}, (_, i) => window.__zoomVel(i / 240))", n)
        zz = await pg.evaluate("n => Array.from({length: n}, (_, i) => Math.log(Math.exp(0) * 1))", 1)
        json.dump(ev, open(os.path.join(HERE, "events.json"), "w"))
        json.dump({"hz": 240, "v": zv}, open(os.path.join(HERE, "zoom.json"), "w"))
        print("events", len(ev["events"]), "zoom samples", len(zv), "peak |dlogz/dt|", round(max(abs(x) for x in zv), 2))
        await b.close()

asyncio.run(main())
