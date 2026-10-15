# Hyperframes Composition Brief: Umbra

## Objective
Create a short launch-style brag video for Umbra, in English and 繁體中文 from one composition (`--variables '{"lang":"zh"}'`).

## Output
- Composition directory: `composition/`
- Rendered video: `brag.mp4` (English), `brag-zh-tw.mp4` (繁體中文)
- Format: vertical — 1080x1920
- Duration: 22.5 s

## Source Material
- Project root: the umbra repository
- Primary files read: README.md, website/src/content.mjs (copy), app/src/main/kotlin/app/umbra/agent/ui/porcelain/Porcelain.kt (colours), docs/traceability.md, the app's screens (Home, Inbox approval card, capsule)
- Product name: Umbra
- Tagline / strongest claim: "An autonomous agent that lives on your Android phone." / "The agent can never operate its own app or answer its own approvals."
- Key UI or visual moment to recreate: the Home composer; the Capsule; the approval panel with its real command and risk lines
- Copy that must appear verbatim:
  - Ask Umbra to do anything…
  - Check my Android version, then remove the SIM toolkit app
  - Run: pm uninstall --user 0 com.android.stk
  - Deletion of important user data (high): uninstalls com.android.stk and its data
  - Destructive system operation (high): uninstalls system package com.android.stk
  - This answers this one action. It does not grant lasting control.

## Creative Direction
- Tone preset: polished
- Creative direction: quiet premium product film with one deadpan beat
- Angle: you handed an AI your phone; it asked first, and the one thing it can never do is answer its own question.
- Hook: "You gave an AI your phone." + the request typing into the real composer.
- Outro / punchline: "It can't approve its own requests." then the lockup.
- Avoid: generic SaaS language; abstract filler; redesigning the product's look.

## Visual Identity
- Background: #E9EDE5 (night flip #26372F) · Text: #32443A · Accent: #66866D / #476D54 · Amber #A17A45 only on the real "Approve once" button
- Display font: Noto Serif / Noto Serif TC · Body: Roboto (recreated Android UI), Montserrat (labels), JetBrains Mono (the command)

## Storyboard
See `brag-plan.md`. Scenes: 1 Hook (0-5.0) · 2 It operates the phone, real footage (5.0-10.0) · 3 The approval (10.0-15.0) · 4 Punchline (15.0-19.25) · 5 Lockup (19.25-22.5).

## Audio
- Audio role: warm bed with sparse professional accents
- Music: `assets/music/umbra-bed.wav`, composed for this video (synthesised, original). The bundled ende.app tracks were not used: their licence is not documented.
- Music cue guidance: 96 BPM, 2.5 s bars; scene cuts on bars; strong cues at 4.7 s (send → capsule), 13.2 s (Deny), 15.0 s (night flip).
- Audio-reactive treatment: subtle; the background glows breathe with the bed's bass and treble bands (`assets/audio-lite.js`, extracted with `extract-audio-data.py`). No waveform visuals.
- SFX: Kenney CC0 from the skill (key presses → one mixed `typing.wav`, `click_003`, `click_002`, `card-slide-1`, `impactSoft_medium_001/002`, `bong_001`, `impactBell_heavy_000`), chosen after the animation existed.
- Real footage: `assets/video/operate.mp4`, cut from a screen recording of the real app on an Android 16 emulator (the model is scripted in the recording; everything the agent does is real).

## Checks
`npx hyperframes check` passes (0 errors); remaining warnings are the advisory "split into sub-compositions" notes for a single-file, five-scene composition.
