# Brag Plan: Umbra

*(/brag, full workflow. Rubric answers are folded into the sections below. Output: vertical, English and 繁體中文 from the same composition.)*

## What is this app?
Umbra is an autonomous agent that lives on your Android phone: it operates apps for you, builds and tests code in a real Debian Linux on the same device, checks its own work, and asks before anything risky. (README: "An autonomous agent that lives on your Android phone.")

## The angle
Everyone is asking what happens when you hand an AI your phone. Umbra's answer is a dry one: **it asks first, and the one thing it can never do is answer its own question.** The README says it plainly: "The agent can never operate its own app or answer its own approvals." The video is a single, believable request, *Check my Android version, then remove the SIM toolkit app*, followed from typing to a denied approval. The product is impressive because it is restrained, so the film is restrained too.

## Hook (first 2-3 seconds)
"You gave an AI your phone." (serif, full width, slams in and holds.) Then the composer under it types the request in the app's own words, with key ticks.

## Key moments (the middle)
- The typed request leaves the composer and becomes the **Capsule**, the camera-anchored pill that follows every task (real feature, real name).
- Real footage of the agent tapping through Settings on an Android 16 emulator: scroll, About, version.
- The **approval**: the capsule opens into the approval panel with the real command, `pm uninstall --user 0 com.android.stk`, the real risk lines, and the real reassurance "This answers this one action. It does not grant lasting control." A cursor taps **Deny**.
- "Denied. Nothing was removed."

## Outro / punchline
Flip to the dark Porcelain night colour: **"It can't approve its own requests."** and under it, the README's rule. Then the lockup: Umbra · free · open source · Android 16 · umbra-agent.pages.dev.

## User flow worth showing
Entry → key action → result, exactly as the app does it: type a request on Home → the agent works (Capsule over Settings) → a risky step waits for approval → the person denies it → nothing changes. (Recorded flow: `marketing/capture/scene_f_onetake.py`.)

## Tone
- Preset: `polished`
- Creative direction: quiet premium product film with one deadpan beat
- Interpretation: slow crossfades, one idea per scene, long holds on the approval card; the only joke is the rule itself, delivered straight.

## Format: vertical — 1080x1920
## Duration: 22.5 s (five scenes on a 96 BPM bar grid: 5 + 5 + 5 + 4.25 + 3.25)

## Visual identity (from the project: `app/.../ui/porcelain/Porcelain.kt`)
- Background: `#E9EDE5` (base), night flip `#26372F`
- Accent: `#66866D` (sage), deep accent `#476D54`; amber `#A17A45` only on the real "Approve once" button
- Text: `#32443A`
- Display font: Noto Serif (the app's own headline face; Noto Serif TC for 繁體中文)
- Body font: Roboto for recreated Android UI (it is the real UI face), Montserrat for labels
- Strongest visual element: the sage-ceramic card UI, the Capsule, the approval card with its "High risk" pill

## Share copy (draft)
Umbra is an AI agent that lives on your Android phone. It taps, builds and tests, and the one thing it can never do is approve its own requests. Free, open source.

## Audio direction
- Role: warm bed with sparse professional accents
- Music: an original bed composed for this video (synthesised, no third-party licence): A-minor, 96 BPM, pad + sparse plucks, soft pulse from the reveal. The skill's bundled ende.app tracks were not used because their licence is not documented.
- Music treatment: in over the first bar, hold under the typing, lift slightly into the approval, drop to the pad for the punchline, long fade after the logo bell.
- Music cue guidance: bar grid 2.5 s (96 BPM); scene cuts land on bars 0 / 2 / 4 / 6; strong cues: the send tick at 4.7 s → capsule, the Deny click at 13.2 s, the night flip at 15.0 s.
- Audio-reactive treatment: subtle; the background glow breathes with the bed's low band. No waveform or equaliser visuals.
- SFX posture: sparse, motion-matched, Kenney CC0 (from the skill) at low HF-risk: key presses for typing, `click_003` for taps, `impactSoft_medium` for reveals, `bong_001` for the denied accent, `impactBell_heavy_000` for the logo payoff.
- Audio-coupled moments: typed request (key presses), send (click + soft swoosh), Deny tap (click), "Denied" (bong), night flip (soft impact), logo (bell).
- Restraint rule: nothing sharp or bright repeated; no sound under the punchline except the pad.

## Storyboard

### Scene 1 — Hook — 5.0 s
Ivory canvas, sage glow. "You gave an AI / your phone." slams in (0.3 s) and holds. The Home composer (recreated from the real UI: "Ask Umbra to do anything…", Independent chip, send) rises under it and types "Check my Android version, then remove the SIM toolkit app".
Sequential/interaction: yes, the request types out character by character; the send button lights at 4.6 s.
Audio intent: calm, human; key ticks.
Audio-coupled idea: key presses on every character group.
Music: bed fades in.
Transition mood: carry → Scene 2 (the composer folds into the Capsule pill).

### Scene 2 — It operates the phone — 5.0 s
The pill sits at the top; an Android phone rises into frame playing real recorded footage of Umbra tapping through Settings (scroll, About emulated device, Android version 16). Label: "It taps through Settings for you." A thin ring marks the Capsule: "The Capsule follows every task."
Sequential/interaction: real footage shows the taps.
Audio intent: gentle motion; one soft swoosh as the phone rises.
Transition mood: the capsule opens → Scene 3.

### Scene 3 — The approval — 5.0 s
The Capsule opens into the approval panel (real copy): "needs your approval · Run: pm uninstall --user 0 com.android.stk · Deletion of important user data… · Destructive system operation… · This answers this one action. It does not grant lasting control." A cursor moves to Deny and taps; the panel closes to "Denied. Nothing was removed." with a check.
Sequential/interaction: yes: cursor path, press state, panel collapse.
Audio intent: the held breath, then a click and one warm bong.
Transition mood: iris → Scene 4 (from the check mark).

### Scene 4 — Punchline — 4.25 s
Night colour. "It can't approve / its own requests." then, smaller: "No operating its own app. No answering its own approvals."
Sequential/interaction: none; the "Approve once" button sits dim under the line and does not move.
Audio intent: pad only.
Transition mood: slow crossfade → Scene 5.

### Scene 5 — Lockup — 3.25 s
Crescent mark, "Umbra", "Free · open source · Android 16", pill "umbra-agent.pages.dev". Bell, long fade.
Audio intent: payoff bell over the pad, then silence.

**Music mood for this video:** polished, warm, restrained
**Audio summary:** a quiet bed that holds its breath at the approval, drops to a pad for the joke, and rings once at the end.
