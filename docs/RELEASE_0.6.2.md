# Umbra 0.6.2 (live 2026-10-26)

Version code **16** (0.6.1 is 15). Built from the `release-0.6.1` branch (aacc3bf) plus the uncommitted working tree.

- APK: `umbra-0.6.2.apk` in R2 (`umbra-releases`), served at https://umbra-agent.pages.dev/download/umbra-0.6.2.apk,
  with `umbra-0.6.2.apk.sha256`; `926761189` bytes, SHA-256 `fe18125037ed316fc3317d9109a105d07d3d3b1c86e5c84d38cdb3ecf660c0bd`
- Signing certificate SHA-256 `b38060dee29910fb73d15cb75fe4b557708d85d37cb2c3bdbe6a5ecc3a2231c3` (unchanged); targetSdk 36;
  release lint 0 errors; carries the Linux image 0.4.0, Laya's assets and the QNN runtime (11 libraries, 140 MB)
- **Live:** https://umbra-agent.pages.dev (deployment https://fd3319b4.umbra-agent.pages.dev), update feed 0.6.2 /
  versionCode 16, `/download/latest` → 0.6.2. Deployed from a clean worktree at HEAD plus this release's site changes
  only (no Terms page, no shared-tree edits). `tools/verify-release.mjs` all PASS.
- Laya model file on R2 since earlier today: `laya/1a793eb568e6/laya-typed-decisions-fp16.tflite` (848871808 bytes,
  SHA-256 7d612c52…; `convert.py remote` PASS).

## What changes for the user

- **Laya runs inside the app, on the NPU, GPU or CPU.** Downloading Laya now fetches one 810 MB model file (from the
  project site, checked against a SHA-256 the app carries) instead of a 2 GB Linux install. On a Snapdragon phone it
  compiles for the NPU once; otherwise it uses the GPU, and the CPU as the last resort. Settings → Laya says which one
  answers and why a better one was not used. It no longer needs the Linux environment, and no CUDA is ever downloaded.
- **Built-in viewers and editors** for what agents make and what is in workspaces: text, Markdown, JSON, CSV/TSV,
  Excel (.xlsx), Word (.docx; .doc read-only), PowerPoint (.pptx), HTML, images, SVG and video. Select something — text,
  a range of cells, a slide, an area of a picture, a moment of a video — and send the agent an instruction about it.
  "Open with…" still hands the file to another app.
- **Agent results reach the agent.** A coding session or an adopted suggestion started by an agent now reports into
  that agent's conversation (before, the report was filed away and the agent never saw it), and a subagent whose
  answer came after its parent stopped waiting is no longer lost.
- **`wait_until`:** agents can wait for a subagent, a coding session, a Linux process or a condition — always with a
  timeout they choose (subagent waits now require one too).
- **Briefs** list all of the agent's tasks since the last brief — done, failed, running, waiting — instead of a
  "done while you were away" list.
- **Suggestions** whose prepared step has no instruction text can be adopted (the agent gets the card and its data).
- **Chat:** opening the keyboard no longer pans the screen or hides the message you just sent; tables show line breaks
  instead of `<br>`.
- **Website:** total downloads and this month's active users, from the existing anonymous usage counts (APK downloads
  are now counted as anonymous totals too).
- **Targets Android 16 (targetSdk 36).**

## Verification

- JVM: orchestrator + decision 170 tests, app 225 tests (run by a Haiku test subagent), all pass; lint 0 errors.
- Real omp binary: subagent and coding-session reports reach their parent even where messages are screened; a report
  arriving after a timed-out subagent_wait is delivered (SessionBusTest, 6/6).
- Emulator (QA AVD, debug build targetSdk 36): Laya downloaded from R2 through the site with progress; the GPU path was
  refused cleanly (the emulator's GL could not compile the model; recorded, skipped next time); the CPU load was ended by
  the low-memory killer on 2–4 GB emulators and the crash marker turned that into a clear failure, not a restart loop
  (the CPU fallback now needs 6 GB). Direct exec from app data is refused at targetSdk 36 as expected. The big model was
  not tested further on the 8 GB development Mac, at the owner's request.

## Not verified yet

- Laya on a physical phone's NPU or GPU (the emulator ran the GPU path through OpenGL; its CPU path is checked against
  PyTorch on the development Mac). NPU files compiled ahead of time are not on R2 yet (they need a Linux x86_64
  machine; see `docs/design/laya-gpu-2026-10.md`).
- targetSdk 36 on a physical phone.
