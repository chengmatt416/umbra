# Marketing material (Umbra)

Everything here is generated from the **real app** running on the Android 16 emulator; the language model in the recordings is the project's scripted stand-in
(`scripts/scripted_model.py`), as in the on-device tests. Nothing is staged: the screens, taps, approvals, Capsule and Linux builds are real.

| what | where | how it is made |
| --- | --- | --- |
| screen recordings | `capture/*.py` → `raw/` (ignored) | `scripts/emulator.sh start`, `scripts/dev-device.sh setup`, then e.g. `python3 marketing/capture/scene_f_onetake.py` |
| website clips | `clips.py`, `clips.json` → `../website/public/clips` | ffmpeg cuts. Sources: say/operate ← `scene_a_0`, background/linux ← `scene_d_0`, capsule ← `scene_b_0`, approve ← `scene_c_0`, plan/resources/resume ← `scene_e_0`, nodes/vault/evidence/access ← `scene_e2_0`. Scene E: run `capture/scene_e_session.py` in the background, open a terminal in that session, start `node-runner/build/install/umbra-node/bin/umbra-node serve --pair --port 47100 > NODE_LOG`, then `capture/scene_e_tour.py SESSION_ID NODE_LOG` (it pairs the Mac as a node on camera) |
| social card | `../website/public/og.png` (1200×630) | the same layout with the two phone screens replaced: frames of `raw/scene_d_0.mp4` (7.5 s) and `raw/scene_b_0.mp4` (11.0 s), scaled to 225×500 at (718, 58) and (968, 118), corner radius 26 |
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
