# Marketing material (Umbra)

Everything here is generated from the **real app** running on the Android 16 emulator; the language model in the recordings is the project's scripted stand-in
(`scripts/scripted_model.py`), as in the on-device tests. Nothing is staged: the screens, taps, approvals, Capsule and Linux builds are real.

| what | where | how it is made |
| --- | --- | --- |
| screen recordings | `capture/*.py` → `raw/` (ignored) | `scripts/emulator.sh start`, `scripts/dev-device.sh setup`, then e.g. `python3 marketing/capture/scene_f_onetake.py` |
| website clips | `clips.py`, `clips.json` → `../website/public/clips` | ffmpeg cuts. Sources: say/operate ← `scene_a_0`, background/linux ← `scene_d_0` (needs the android add-on), capsule ← `scene_b_0`, approve ← `scene_c_0`, models ← `scene_g_0`, power/resume/dev ← `scene_e_0`, nodes/safety/access ← `scene_e2_0`. Scene E: run `capture/scene_e_session.py` in the background, then `capture/scene_e_power.py SESSION_ID`. Scene E2: start `node-runner/build/install/umbra-node/bin/umbra-node serve --pair --port 47100 > NODE_LOG`, then `capture/scene_e2_trust.py NODE_LOG` (it pairs the Mac as a node on camera). The scenes need the debug build (control channel); sign it with the release key to install over a release build without losing data |
| agents + proactive clips (0.6) | `capture/scene_h_agents.py AGENT_ID` → `raw/scene_h_en_0.mp4` | Home with the brief and suggestions, the Proactivity page (brief, suggestion review, goals, watchers, routines, levels), a message to the agent. The agent is created in the app (New agent); its proactive records come from the debug command `demo.proactive.seed` and nothing seeded is enabled. The clock is set to 08:24 for the morning brief. Since 0.6 the Capsule scene sets the Capsule's **Top centre** placement (the emulator's camera is off-centre) and takes the default agent's id; scene E2 shows the already paired node when run without a NODE_LOG (it never places a job on that computer) |
| social card | `../website/public/og.png` (1200×630) | `og/og.html` rendered by `.venv/bin/python og/render.py` (Playwright) with frames of `raw/scene_h_en_0.mp4` (5 s) and `raw/scene_d_0.mp4` (112 s) |
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
