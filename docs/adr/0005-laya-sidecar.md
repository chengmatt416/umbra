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
