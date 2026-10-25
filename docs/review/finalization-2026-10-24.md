# Finalization pass — 2026-10-24

Picks up [the Codex handoff](../HANDOFF-2026-10-24.md) and the owner's note list. Goal: finish the
open implementation, prepare a production build and redo the UI/UX and motion. Nothing is pushed or
published from this pass without the owner's explicit go-ahead.

## Owner note → status

| # | Note | Status at start | This pass |
|---|---|---|---|
| 1 | Local vision decision model | Deferred by owner | — |
| 2 | Prompt-injection prevention (Laya) | `LayaActionSecurityCheck` | Keep; re-verify in suites |
| 3 | Live multimodal model | Deferred by owner | — |
| 4 | Simple live mode (local STT/TTS) | Deferred by owner | — |
| 5 | Claude Code / Codex / omp from Coding | Screen-takeover route + investigation | Keep; native adapters not in scope |
| 6 | Approve once / prefix forever | Shipped 0.4.5 + fixture | Re-verify |
| 7–8 | Capsule driven by broadcasts only | Implemented + fixture | Re-verify |
| 9 | **Screen control procedure (URGENT)** | `android_run_plan` with model-enumerated actions only | Laya picks actions from the live screen; backup vision inside the loop |
| 10 | Proactive spec | A1–D8 partially implemented | Close implementable gaps (see below) |
| 11 | Clear composer after send | Fixed + fixture | Re-verify |
| 12 | Remote-node pairing crash | QR teardown serialized + fixture | Re-verify |
| 13 | Coding session needs pause/resume | Fixed + fixture | Re-verify |
| 14 | Result summary Markdown decode | Fixed | Re-verify |
| 15 | Security audit 2026-10-23 | Several items fixed in 0.4.5 commits | Status table + fix remaining P0/P1 that are code |
| 16 | Telegram images/files/reactions | Photos/files/voice in; bot reacts | User reactions in, albums as one message, files out |

## Order of work

1. Build health: `:app` compile, all JVM suites (handoff steps 1–2).
2. Screen control procedure (note 9).
3. Telegram media + reactions + file delivery (note 16, roadmap B6).
4. Handoff clean-ups: pending-delegate session test, stale constant, `SessionDeps` authority wiring.
5. Security audit status and remaining code fixes (note 15).
6. Proactive gaps that are code, not just fixtures.
7. UI/UX and motion pass on the emulator.
8. Full regression, lint, Android fixtures, release build, commits split by topic.

## Progress log

- 2026-10-24: `:app:compileDebugKotlin` passes on the handed-over tree.
- JVM suites on the handed-over tree: safety 287, omp 77, control 45, session 58, orchestrator 88 — all pass.
  `tools` `OnDeviceLiveTest` fails only because the attached emulator holds a release-signed app.
- **Note 9 (screen control):** `android_run_plan` steps may now `allow` on-screen operations (tap, long_press,
  type, scroll, back, enter). Each loop turn derives candidates from the live accessibility tree (≤40, active
  window first); Laya picks one, every action still goes through the normal approval pipeline. Laya types only
  the plan's literal `inputs` and never into password fields. Prompts updated. `ScreenPlanTest` 8/8.
- **Note 16 (Telegram):** user reactions (`message_reaction`) reach the agent as feedback with a `[quiet]`
  reply rule (toggle in the bot page); albums are delivered as one message (1.5 s window, ≤8 images); video,
  animation and video notes are saved like files; stickers pass as emoji; a turn's published deliverables are
  sent back as documents after the same SHA-256 check as the in-app card (`DeliverableFiles`). Upload tests
  caught and fixed a filename bug.
- Handoff clean-ups: `checkToolAuthority` no longer has a silent default; stale `SYSTEM_PROMPT_FILE`
  removed; new `aTurnThatEndsWithUnfinishedDelegatesIsNotVerifiedAndNamesThem`. Orchestrator 89/89.
- **Audit E:** guest credentials no longer touch a file. `SecretStdin` sends them over the command's stdin
  (base64, end marker) for `execWithSecrets` in both runtimes and for omp spawns. Real-bash test 3/3.
- **Audit G21/G03 (partial):** Laya gets a per-install bearer key (`LAYA_API_KEY`, Keystore-encrypted,
  delivered through the secret channel); health needs 2xx + `status: ok` + the authorized detail.
- **Audit A:** shell screen input (`input`, `cmd input`, `sendevent`, `monkey`, `uiautomator`) is now HIGH
  `DYNAMIC_COMMAND` and never offered or matched as a permanent prefix (old saved grants stop applying).
  Behaviour change: `input tap/text` from a shell now asks. Safety 287/287.
- **Audit G17:** the privileged helpers take their token from `UMBRA_HELPER_TOKEN` (owner-only
  `/proc/<pid>/environ`) instead of `--token` in the world-readable command line; `--token` still accepted.

## Security review status (2026-10-23 audit)

Checked against the working tree in this pass. "Not re-checked" means no claim either way.

| ID | Status | Evidence / remaining |
|---|---|---|
| A | Fixed (deterministic part) | Shell screen-input classification + no prefix; Laya independent check (`LayaActionSecurityCheck`). Scripts/other programs that synthesize input remain a residual risk |
| B | Fixed | `installRootFile`/`writeFile` walk trusted root-owned parents, `mktemp` + rename; `RootFileSafetyLiveTest` |
| C | Fixed | Lockdown/epoch recheck before dispatch (`DispatchFence`, commit bd6924a) |
| D | Fixed | Literal prefix + cwd binding, compound/root/critical refused (0.4.5) |
| E | **Fixed this pass** | `SecretStdin` — no credential file in the guest |
| F | Outside this repo | Enforcement belongs in the owner's omp fork |
| G01 | Open (documented) | Neither mode gives the agent its own Android UID |
| G02 | Open | Helper token still grants the full helper surface |
| G03 | Partial | Bearer key stops other local processes from using Laya; same-UID guest code can still impersonate it (G01). Impact bounded: Laya only adds caution or picks among already-permitted actions |
| G05/G06/G14 | Fixed (Go node) | Process-group kill, ≤24 h per renewal, owner-bound jobs |
| G11 | Owner decision | Laya auto-updates by design; source/version verification of a mirror not implemented |
| G16 | Fixed | `BoundedLineReader` in helper, client, shell, omp transport |
| G17 | **Fixed this pass** | Token via environment |
| G21 | **Fixed this pass** | Health requires 2xx + Laya payload |
| G04, G07–G10, G12, G13, G15, G18–G20, G22, G23 | Not re-checked | — |

## Design pass: Ink (owner request 2026-10-24: "redesign the whole UI, white and black, fantastic motion")

- New default theme **Ink** (`ThemeStyle.MONO`): light = #F7F7F7 page, white bordered cards, black ink and
  capsule buttons; dark = true black, #131313 cards, white ink and buttons. Red/amber/green only for meaning.
  Every existing colour is remapped through `Retint.mono` (neutral greys, stretched contrast, glaze greens →
  ink); avatars/illustrations use `pcGrey`. Porcelain, Liquid and Apple remain in Appearance; prefs v2
  migrates a Porcelain look to Ink once (and the v0-only Auto migrations no longer misfire for v1 prefs).
- Ink components (`ui/porcelain/Ink.kt`): spring press (`inkPress`), capsule `InkButton`, `InkIconButton`,
  `InkSegmented` (ink pill springs between measured options), flat bordered surfaces, `inkEnter`, `inkItem`.
- Motion: `Motion` spring vocabulary; Ink page transitions on a shared X axis (spring slide + cross-fade);
  list rows animate insert/remove/reorder (`animateItem`) on Home, Agents, Inbox, Sessions; chat timelines fade
  rows in without placement springs; fading scroll edges on every `PageColumn` page.
- UX fixes found on the emulator: Coding empty-state headline overlapped the two-row composer → centred
  empty state with starter prompts that only fill the composer; FAB "new" circles → labelled pills; settings
  rows with a value squeezed their subtitle → value on the title line; task result summary duplicated the
  final answer → shown only when the conversation does not already end with it; Ink code blocks visible.

## Capsule motion and coexistence (owner request: "won't conflict with Material Capsule")

- Expand/collapse morph: the card grows out of the pill at the camera (graphics-layer scale/alpha only —
  per-frame overlay window resizing stalled the window manager for ~0.5 s and left the card half open; found
  on the emulator and fixed). The window widens only after the card's first layout, and on collapse it stays
  wide until the fold finishes (≤420 ms), so the pill never sits stranded in a wide window.
- Pill: pop-in at the camera, one attention pulse when something needs the user, label cross-slides.
- Neighbours (`CapsuleNeighbours`): another capsule app is recognised by its enabled accessibility service
  label/package (Material Capsule, dynamicSpot, Dynamic Bar, notch apps — no stale package list) and, with
  Umbra's accessibility service, by its window over the camera (`UmbraAccessibilityService.topOverlays`,
  bounds and package only). New setting **Capsule position**: Auto (camera, or top corner beside a
  neighbour), Around the camera, Top corner. While a neighbour's island is expanded, Umbra's pill fades out
  and is not touchable. Verified on the emulator: Auto at the camera, Top corner placement, open/close
  morph (slow-motion frames). Not verified: a real Material Capsule install (not available here).
- **Roadmap B2:** `calendar_update` / `calendar_delete` for one existing event, on the creation path's guardrails
  (grant + WRITE_EVENTS + Android permission + writable calendar + DLP + approval with before/after + authority/
  lockdown recheck + unchanged-event recheck + once per turn). Recurring events and events with other guests are
  refused so an agent never sends updates or cancellations to other people. Not exercised against a real calendar.
- Chat bubbles in Ink: the user's messages are ink (black, or white in the dark), replies are paper.

## 0.6.0 pass (owner request: "ensure every listed feature is implemented, enhance UI/UX and motion, re-record the website video, make it production ready")

- **Spec gaps closed in code:** B8 synthesized Python/Bun/Bash tools (`GuestScripts.kt`, `script_run`), B3 connector-first
  routing (`ConnectorRouting.kt`, omp approval bridge + host-tool handler), B7 teaching on a paired computer and on the
  Linux desktop (`Demonstrations.startRemote/startLinuxDesktop`, viewer hooks). Owner-deferred items stay deferred
  (local vision decision model, live multimodal mode, local STT/TTS live mode); external Claude Code/Codex session
  attachment remains the documented screen-takeover route.
- **UI/UX:** designed brief and suggestion cards (`ProactiveCards.kt`) on Home and the Proactivity page; Home deck with
  today's brief and top suggestions, goal line per agent; named autonomy levels with animated description; counted,
  self-scrolling section pills with a sliding section transition; goal progress and milestone checklist; plain-language
  watcher conditions, routine schedules (`CronText`) and brief headlines (`BriefHeadline`); readable message-draft
  review; optimistic chat bubbles; Capsule **Top centre** placement and agent-reply peek.
- **Found on the emulator and fixed:** raw `NumericCompare(...)`/`CronSchedule(...)` text, JSON in the message review,
  "0 upcoming events · 0 decisions · 0 conflicts" headline, clipped stat pills, own message appearing ~10 s late,
  off-centre Capsule on an off-centre camera, the Capsule answer arriving silently.
- **Website:** Ink restyle, two new chapters (agents, proactive) and an "Also in 0.6" bento, FAQ/trust/hero updated in
  both languages, all 15 clips re-recorded from the Ink UI (scenes A–E2 + new H), new social card (`marketing/og`).
  Deployed only to the preview branch `site-0-6-preview`; production and the APK feed are unchanged.
- Emulator housekeeping: removed ~1.4 GB of stale pip download folders from the guest's `/tmp` (the install needed the
  space) and seven placeholder test sessions from earlier fixture/recording runs.

## 0.6.1 pass (2026-10-25, owner requests)

Briefing page per agent (overview button and chat), proactive settings moved into settings + Settings → Proactive
agents + a setup step, unrolling brief cards, a plain message sheet for suggestions, spawn guard (`SpawnGuard`) and wake
budget (`WakeBudget`) against runaway sessions and wake loops, usage counts on by default for new installs (prefs 3
keeps earlier installs' choice), and a CUDA-free Laya install (CPU-only PyTorch; failure time persisted). Laya on the
phone's GPU is a design awaiting approval: `docs/design/laya-gpu-2026-10.md`. Draft notes: `docs/RELEASE_0.6.1.md`.
Emulator housekeeping: removed `triton` (820 MB GPU compiler) from the emulator's own Laya install to make room.
