# ADR 0005 — Laya as a loopback HTTP sidecar with a deterministic fallback

Status: accepted · Spec: §4, §17

## Context

Laya (Apache-2.0) is a non-autoregressive decision model (322M multilingual / 421M English and
typed-decisions checkpoints) with three primitives — `choice`, `score`, `noul` (probability of
yes) — and a `laya-serve` HTTP wire protocol (`POST /v1/systemone`, `/v1/systemone/batch`).
There is no Android SDK. Published single-question latency is 33 ms on a T4 GPU and ~0.2–0.5 s on
Apple/x86 CPUs, so phone CPU latency will be hundreds of milliseconds, not tens.

## Decision

* `core/decision` defines `DecisionEngine` mirroring the wire protocol (state + typed questions →
  answers with probabilities and confidence) and a `DecisionRouter` that applies confidence
  thresholds and decides local-answer vs escalate-to-OMP (spec §4).
* Backends: `LayaHttpEngine` (loopback sidecar inside the guest), `HeuristicDecisionEngine`
  (rules over UI structure/text/logs; always available; used in tests and when the sidecar is
  down). In-app ONNX Runtime is a stretch goal, not part of v1.
* Questions are batched per state so one forward pass answers several decisions.
* Weights are never committed; they are downloaded on-device on user action.

## Consequences

* Capability detection reports Laya as: sidecar running / heuristic only / unavailable, with the
  measured latency from the conformance suite.
* Loopback cleartext requires a network-security-config exception (ADR 0002).

## Amendment (2026-10-25): Laya inside the app

Laya no longer runs as a sidecar in the Linux environment. The typed-decisions checkpoint is converted to LiteRT
(`runtime-build/laya/convert.py`) and `LayaNativeEngine` (core/decision) answers in the app process: on the NPU (a file
compiled ahead of time for the SoC, or on Qualcomm the fp16 file compiled on the phone), else the GPU, else the CPU,
each refused unless it reproduces reference logits. The pip install, `laya-serve`, the loopback port and its bearer key
are gone; `LayaHttpEngine` remains for a `laya-serve` the user runs elsewhere. Weights are still never committed: the
converted files live on R2 and the APK carries their sizes and SHA-256. Details: `docs/design/laya-gpu-2026-10.md`.

## Amendment (2026-10-26): Laya in its own process

On a phone, loading Laya on the NPU or GPU ended the whole app: vendor driver code aborts the process it runs in, and
the 810 MB model makes that process the low-memory killer's first choice. LiteRT now runs only in `:laya`
(`data/laya/LayaService`, a private bound service; `LayaProcess` is the app's side). The app downloads and verifies the
files, asks the process what LiteRT offers, then opens one candidate per fresh process and checks it against the
reference logits in **every** sequence bucket (256 and 1024 tokens; before, the 1024 bucket was first compiled in the
middle of a task). A process that dies or hangs during a load refuses that accelerator for this app version; one that
dies while loaded sends decisions back to the built-in rules at once, and Android's record of how it ended
(`ApplicationExitInfo`) decides what follows: a crash refuses that accelerator and Laya starts on the next one, a
low-memory kill waits for the hourly retry. Settings shows NPU, GPU and CPU at all times with each one's state and why.
