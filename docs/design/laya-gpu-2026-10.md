# Laya on the phone's NPU, GPU and CPU

Owner requests 2026-10-25: "Design the laya to run on the GPU for the program and prevent download cuda", then
"design both GPU and NPU. Use NPU first and use GPU when NPU is not available. Use CPU as the final backup. Put all
converted files on R2". Status: implemented for the next release (after 0.6.1); not yet verified on a physical phone.

## What changed

Until 0.6.1 Laya ran as `laya-serve` (Python, PyTorch) inside the Linux environment: a 2 GB pip install that pulled
CUDA wheels a phone cannot use, then CPU only. Now the typed-decisions checkpoint is converted to LiteRT and runs inside
the app process, behind the existing `DecisionEngine` interface, so the router, thresholds and callers are unchanged.

| Order | Backend | File | How it is chosen |
| --- | --- | --- | --- |
| 1 | NPU | the SoC's file compiled ahead of time (`npu/<vendor>-<soc>.tflite`) when R2 has one; else, on Qualcomm, the fp16 file compiled on the phone (cached, so once) | LiteRT reports an NPU, and the SoC (`Build.SOC_MANUFACTURER/SOC_MODEL`) has a file or is Qualcomm |
| 2 | GPU | `laya-typed-decisions-fp16.tflite` (849 MB) | LiteRT reports a GPU |
| 3 | CPU | the same fp16 file, so falling back downloads nothing more | always |

Each candidate must open **and** reproduce the PyTorch logits of a reference question (tolerance 0.35, same answer)
before it answers anything; a candidate that fails is remembered for that app version and skipped on later starts.
Settings → Laya shows which processor answers and why better ones were skipped.

## Pieces

- `runtime-build/laya/convert.py`: export (one signature per bucket, 256 and 1024 tokens; static shapes for GPU/NPU),
  fp16 quantize, golden fixtures, verify, AOT compile (Linux x86_64), manifest, upload to R2, remote check.
- `core/decision`: `LayaTokenizer` (ModernBERT byte-level BPE from `tokenizer.json`), `LayaEncoding` (Laya 0.4.15's
  `build_sequence`, `render_options` and `_decode_answers`), `LayaNativeEngine`. JVM tests pin all three to fixtures
  written by Laya's own Python code (token ids, sequences, markers, answers).
- `app/.../data/laya`: `LayaPlan` (the order above), `LayaNative` (verified resumable downloads, LiteRT graph on its own
  thread, the reference check), `LayaSidecar` (same API as before: state, engine, download/start/stop/remove).
- The APK carries `assets/laya/laya-native.json` with every file's size and SHA-256, the checkpoint config and the
  tokenizer. Nothing fetched from the network can change what the engine loads.
- R2: `laya/<revision>/<file>` behind the site's `/download/` route (Range, immutable caching).
- Qualcomm's QNN runtime (140 MB: HTP v73–v81 stubs and skeletons, `libQnnHtpPrepare.so` for compiling on the phone)
  ships **inside the APK**: QTI's licence allows distributing it only as part of an application, so it is staged at
  build time (`runtime-build/laya/fetch-qnn.sh`) and never committed or hosted on its own. MediaTek and Google Tensor
  NPUs use the phone's own vendor libraries.

## What the conversion had to fix (all checked against PyTorch on golden requests)

1. `topk` lowered to `STABLEHLO_SORT`, which no LiteRT backend runs: the act head is dropped (Umbra never reads it).
2. Padded positions beyond ModernBERT's 64-token sliding window see no key; softmax over nothing is NaN in LiteRT and
   `0 × NaN` reached real tokens: masks are additive with a finite −10⁴.
3. HF's attention reshapes are 5-D (224 ops the GPU refused, splitting the graph into CPU islands) and the option
   gather is `GATHER_ND`: attention is written in 4-D, markers and question type come in one-hot.
4. ModernBERT's residual stream holds outliers near 28,600; the decomposed LayerNorm squares them (inf in fp16):
   LayerNorm divides each token by its largest |x| first (scale-invariant; epsilon scaled with it).
5. int8 dynamic-range weights quantize activations per tensor; the same outliers ruined them (decisions flipped), so
   the CPU uses fp16 too.

## Measured (Apple M1, LiteRT 2.3.0, 12 golden questions)

| Run | Largest logit difference | Answers changed | 256 tokens | 1024 tokens |
| --- | --- | --- | --- | --- |
| CPU, fp16 file | 0.000 | 0 | 1.9 s | 8.9 s |
| GPU (Metal) fp16 | 0.193 | 1 (two options 0.01 apart) | 0.13 s | 0.8 s |
| GPU (Metal) fp32 | 0.000 | 0 | 0.17 s | 8.7 s |

The app runs the GPU in fp16 (the NPU computes in fp16 too). Phone numbers will differ; the emulator check covers the
CPU path and the fallback chain.

## Not done / not verified

- **NPU files compiled ahead of time are not on R2 yet.** LiteRT's NPU compiler plugins exist only for Linux x86_64
  and compiling a 421M-parameter model needs far more memory than this 8 GB Mac has; `convert.py aot` is ready for such
  a machine (Qualcomm SM8550–SM8850, MediaTek MT6985–MT6993, Google Tensor G3–G5). Until then: Qualcomm compiles on the
  phone (first load takes minutes, then cached); MediaTek and Google Tensor phones use the GPU.
- No physical phone: NPU and GPU behaviour on Adreno/Mali/Hexagon is unverified; the reference check is the guard.
