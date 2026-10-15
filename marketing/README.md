# Marketing material (Umbra)

Everything here is generated from the **real app** running on the Android 16 emulator; the language model in the recordings is the project's scripted stand-in
(`scripts/scripted_model.py`), as in the on-device tests. Nothing is staged: the screens, taps, approvals, Capsule and Linux builds are real.

| what | where | how it is made |
| --- | --- | --- |
| screen recordings | `capture/*.py` → `raw/` (ignored) | `scripts/emulator.sh start`, `scripts/dev-device.sh setup`, then e.g. `python3 marketing/capture/scene_f_onetake.py` |
| website clips | `clips.py`, `clips.json` → `../website/public/clips` | ffmpeg cuts |
| Instagram Reels | `reels/build_reel.py` → `reels/out/umbra-reel-{en,zh-tw}.mp4` | PIL + ffmpeg, synthesised sound (`reels/sound.py`) |
| **/brag** feature tour (all 13 features, 55 s) | `brag-output-tour/` (`build_tour.py` → Hyperframes `composition/`, `make_clips.py`, plan, share copy) | same skill, run 2 |
| **/brag** launch video (22.5 s teaser) | `brag-output/` (`brag.mp4`, `brag-zh-tw.mp4`, plan, brief, share copy, Hyperframes `composition/`) | the [latent-spaces/brag](https://github.com/latent-spaces/brag) skill (MIT) + [HeyGen Hyperframes](https://github.com/heygen-com/hyperframes) |
| **onetake** film | `onetake-film/` (`comp.html`, `score.py`, `dump_events.py`, `README.md`) → `onetake-film/umbra-onetake-{en,zh-tw}.mp4` | the [feitangyuan/onetake](https://github.com/feitangyuan/onetake) skill (PolyForm Noncommercial 1.0.0: free for noncommercial use) |

The two skills are **not** committed (their licences differ from this repo's): fetch them into `marketing/vendor/` when you want to re-render:

```bash
git clone --depth 1 https://github.com/feitangyuan/onetake marketing/vendor/onetake
python3 -m venv marketing/.venv && marketing/.venv/bin/pip install playwright numpy scipy pillow matplotlib fonttools brotli opencv-python-headless && marketing/.venv/bin/playwright install chromium
export HYPERFRAMES_NO_TELEMETRY=1      # for the brag composition: cd brag-output/composition && npx hyperframes check && npx hyperframes render --variables '{"lang":"zh"}'
```
