# Umbra · one take — "a dive through scale"

A film made with the [onetake](https://github.com/feitangyuan/onetake) skill (concept 2 of its concept round, look **porcelain** = the app's own colours),
vertical 1080×1920, 29 s, English and 繁體中文 from one composition (`comp.html?lang=en|zh`).

**Concept.** One camera, no cut. Every level is inside the one before: the camera starts *inside the phone's camera hole* and pulls out through the Capsule that wraps it
to the whole phone ("Ask."); pushes into the sentence typed on it, dives into its **last letter** and finds the Linux machine inside ("Build."); dives into the
**built apk** and finds the app running ("Run." = the Welcome screen); whips back out 800× → 1× to the same phone, now holding the one question only the
person answers; taps **Deny** ("Decide."); pulls back to the name on the ground. Carry = scale (800×): nothing is replaced, every level is *inside* the one before.

**Real material.** The phone's screen is the recorded app (one uninterrupted take, `marketing/capture/scene_f_onetake.py`, Android 16 emulator, touches shown by Android).
The terminal and the app are drawn in vector at their own units from the strings of the real on-device run (the `crashy-login` fixture: `built build/crashy-login.apk`,
`Wall time: 4.25 seconds`, `Crashy Login` → `Welcome, demo`). The language model in the recording is the project's scripted stand-in.

## Beat sheet

| t | beat | camera | carries into the next |
| --- | --- | --- | --- |
| 0.0–1.1 | inside the camera hole, dead still | z 60 on the hole | the hole |
| 1.1–3.2 | out through the lens rings → the Capsule → the whole phone | 60× → 0.86× | the phone |
| 3.2–6.6 | "Ask." · the sentence types on the real footage | 0.88× → 2.3×, follows the text | the last letter |
| 6.6–9.4 | the dive into the last letter of "app" | 2.3× → 78× (log, cubicInOut) | the window inside it |
| 9.4–14.2 | "Build." · a held breath, then the real build (one rest of 1 s) | still | the apk |
| 14.2–17.1 | the dive into the built apk | 78× → 800× | the app |
| 17.1–19.5 | the app runs: demo / ••••••••, LOG IN, "Welcome, demo" · rest | still | the phone |
| 19.5–21.4 | the whip out | 800× → 1.08× in 1.9 s | the phone |
| 21.4–24.9 | the approval on the real phone; the push onto **Deny**; the tap | 1.1× → 2.6× | the empty inbox |
| 24.9–26.3 | "Nothing is waiting for you." · silence | 1.9× | the pull-back |
| 26.3–29.0 | "Decide." above, **Umbra** on the ground below | 0.64× | end |

## Numbers (draft = what ships)

- render.py: 1080×1920 · 30 fps · 180° shutter sized by `__motion` (gap 24 px, 4–12 captures) · 5 workers · 870 frames, 4,605 captures (156 held frames skipped the shutter, 54 kept a hard cut) in 1,052 s per language.
- verify_promo.py `--comp`: **PASS** — rest: still 0.353, longest quiet 5.25 s · burst: the two dives, the whip and the zoom onto Deny · not-flat 0.83 · audio: −3.4 dBFS, 0 clipped, quiet 0.172 · continuity 1.00 over **0 boundaries** (one world: carry judged by eye) · cadence/framing skipped (no shots, no `__track` boxes).
- Reference: no clip was supplied, so `analyze_ref.py` was run on onetake's own launch film for the feel (still 0.092, low 0.311, longest quiet 1.92 s, 18 moves, median t80 0.73); this film is stiller on purpose.

## Sound (`dump_events.py` → `score.py`)

Every time comes from the comp's own `__events()`; the camera's air is three noise bands crossfaded by depth and as loud as d log zoom / dt; keys are CC0 presses (Kenney) layered
with the onetake palette (one room, T60 1.2 s); a soft pad composed here. Five held silences (the hole, the arrival in the terminal, after "Welcome", after Deny, the end). −16 LUFS, −3.5 dBTP.

## Re-render

```bash
cd marketing/onetake-film          # needs marketing/vendor/onetake and marketing/.venv (see ../README.md); footage frames: ffmpeg from marketing/raw/scene_f_0.mp4
cp ../vendor/onetake/lib/motion.js . && ../.venv/bin/python ../vendor/onetake/scripts/look.py apply look.json comp.html --text-from strings.json --cjk NotoSansTC.ttf
../.venv/bin/python dump_events.py en && ../.venv/bin/python score.py
../.venv/bin/python ../vendor/onetake/scripts/render.py "comp.html?lang=en" --out draft-en.mp4 --sfx mix.wav --workers 5 --width 1080 --height 1920
```
Licence note: onetake is PolyForm Noncommercial 1.0.0 (free for noncommercial use); this film promotes a free, open-source project. Fonts: Fraunces, Inter, Geist Mono (SIL OFL, shipped with the skill) and Noto Sans TC (OFL, downloaded).
