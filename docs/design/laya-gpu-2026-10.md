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

### CPU memory: XNNPACK weight cache (2026-10-26)

On the CPU, XNNPACK unpacks the fp16 weights to fp32. Measured on this Mac with LiteRT 2.3.0 (`phys_footprint`, 256 and
1024 tokens run, logits identical to the reference in every case):

| CPU load | peak footprint | after both buckets | load |
| --- | --- | --- | --- |
| no weight cache | 3.62 GB | 3.37 GB | 6.8 s |
| weight cache, first start (writes the cache) | 0.67 GB | 0.55 GB | 3.5 s |
| weight cache, later starts | 0.65 GB | 0.53 GB | 2.4 s |

The cache is a 1,478,713,960-byte file of repacked weights that the runtime maps: memory the phone can page out instead
of anonymous memory the low-memory killer counts. `CpuOptions.xnnPackWeightCachePath` must name a **file** (LiteRT's
Python docs say a directory; a directory is refused with "could not open file ... Is a directory" and the load goes on
without a cache). The app keeps it at `cacheDir/laya/<revision>-cpu.xnn`, trusts it only at the size a passed check
recorded (a half-written cache is rebuilt), and turns it off until Retry if a load with it fails. With the cache the CPU
needs a 3 GB phone and 1.6 GB of free storage; without it, still 6 GB.

### One model, one fresh process

"CPU: IllegalStateException: a model is already open in this process" came from binding a `:laya` process that still
held an earlier model. The app took that process for dead when a binder call failed with `DeadObjectException`, and
skipped unbinding and killing it. A call into a process the phone has **frozen** (HyperOS and others freeze background
apps; the binder answers a frozen process with `FAILED_TRANSACTION`, which Java reports as `DeadObjectException`) fails
the same way while the process lives on, model and all; the next `bindService` then returned that same process. Now:
a "dead" process that still lives is killed (`LayaExit.paused`, which refuses no processor and ends that load with a
retry later); `stop()` always unbinds and waits up to 10 s for the process to go; `start()` first ends every `:laya`
process no live `LayaProcess` holds, and the service reports whether it holds a model, so a process that does is ended
and replaced (three tries). Should a model still be open, `OPEN` closes it instead of failing.

Two findings from the emulator probe (`laya.probe.open/call/stop`, a 304-byte ADD model, `am freeze --sticky`):
- `alive(pid)` tested `/proc/<pid>`. Every app process is made non-dumpable (UmbraApp), and Android's `/proc` hides a
  non-dumpable process from its siblings, so the app always saw `:laya` as gone: `stop()` never killed it (only the
  service's own 300 ms unbind timer did), which left the window in which the next bind reached the old process and
  its model. It now asks with `kill(pid, 0)`.
- Android 16 ends a frozen process that is called (`ApplicationExitInfo.REASON_FREEZER`, "Sync transaction while
  frozen"); it is reported as paused, not as a crash.

Verified on the QA AVD (Android 16): freeze → call → "the phone paused it in the background (paused=true)", process
gone, next open in a fresh process; a second open while a lost process still held a model → "still held a model" on
try 1, a fresh process on try 2; `stop()` leaves no `:laya` process. App unit tests 245/245, lint 0 errors.

## NPU files compiled ahead of time (2026-10-27)

`.github/workflows/laya-npu.yml` (Linux x86_64 runners, run by hand) compiles Laya with LiteRT 2.3.0 for every phone
SoC LiteRT's compilers name and publishes each file as an asset of the pre-release
[`laya-npu-1a793eb568e6`](https://github.com/chengmatt416/umbra/releases/tag/laya-npu-1a793eb568e6); the manifest gives
each file its `url` (`convert.py manifest --npu-url ...`). 19 of the 30 compiled:

| Vendor | Built | Not built (those phones use the GPU) |
| --- | --- | --- |
| Qualcomm (QAIRT 2.50, fp16 file, `qualcomm_enable_weight_sharing`) | SM8450, SM8475, SM8550, SM8650, SM8750, SM8845, SM8850: 0.88 GB each | SM8350 (v68 HTP: no fp16 Gather/GELU); automotive SA8255/SA8295 not tried |
| MediaTek (NeuroPilot 8.2.30, `npu4d` source, 1024 tokens only) | MT6877, MT6878, MT6879, MT6897, MT6983, MT6985, MT6989, MT6991, MT6993, MT8171, MT8188, MT8189: 0.96 GB (1.19 GB on MT6877/MT6879) | MT6853, MT6893 (older APUs refuse a dozen ops); MT6886, MT6895 ("Not a valid platform") |
| Google Tensor | none | G3–G6: LiteRT 2.3.0's compiler download (tensor-ml-sdk 6.1.0) answers 404 |

Qualcomm SoCs without a file still compile on the phone (Hexagon v69 and v73–v81 libraries ship in the APK).

What it took, from the compilers' own errors:
- Qualcomm's plugin links LLVM's libc++ (`libc++.so.1`), which the runner lacks. Without weight sharing, the two
  signatures stored the weights twice (1.73 GB); LiteRT's Python layer passes no plugin flags, so `convert.py aot
  --plugin-flags` hands them to `apply_plugin`.
- MediaTek refused the converter's mixed-rank batch matmuls: the option pick ([1,K,L] x [L,H]) and the attention mask
  ADD (<16 x L x L> + <1 x 1 x L x L>), and a DEQUANTIZE of the fp16 embedding table between the two CPU ops became a
  partition with no inputs ("Failed to identify model I/Os"). The `npu4d` export layout (same maths, 3-D attention,
  per-head option pick, lookup first, fp32 embedding table) compiles as one NPU partition plus the lookup on the CPU;
  verified against PyTorch on the golden requests (largest logit difference 0.0, no decision flipped). With no weight
  sharing, it holds only the 1024-token graph; the app pads shorter rows (`LayaFile.buckets`, `PaddedGraph`).

## Not done / not verified

- No physical phone: compiled NPU files are checked by size and SHA-256 only; whether each one runs on its SoC (and
  passes the reference check) is untested until a phone with that chip loads it.
- No physical phone: NPU and GPU behaviour on Adreno/Mali/Hexagon is unverified; the reference check is the guard.

## Every chip of the last five years (2026-10-28)

Owner request: NPU and GPU support for the Snapdragon, MediaTek and Exynos chips of the last five years. What this
round did, and what each family now runs:

| Family | NPU | GPU |
| --- | --- | --- |
| Snapdragon 8 Gen 1 → 8 Elite Gen 5 (SM8450–SM8850), 7 Gen 1 (SM7450, new) | file compiled ahead of time | Adreno, as fallback |
| Snapdragon 8s Gen 3/4, 7 Gen 3/4, 7+ Gen 2/3, 7s Gen 2/3, 6 Gen 1/3/4, 4 Gen 2 | none (see below) | Adreno |
| Snapdragon 888, 780G, 778G (Hexagon v68) | none (see below) | Adreno |
| Dimensity 900 → 9500, 8300, 7300, Kompanio (19 SoCs) | file compiled ahead of time | Mali / Immortalis, as fallback |
| Dimensity 720/800U/1100/1200 (old APUs: no fp16), 1080/8100 ("not a valid platform") | none | Mali |
| Exynos 2100 → 2600, 1280 → 1580 | none (see below) | Mali / Xclipse |

- **Mid-range Snapdragons** (CI run 40663777315): LiteRT 2.3.0's Python list names only flagships, so `convert.py aot`
  now widens the SoC enum to the compiler plugin's own table. SM7450 compiled (2487/2487 ops, one partition per
  signature). The others fail on one hardware limit: `q::*InputSlice not sufficiently tiled to fit in TCM. Requires
  67108864 bytes` — their HTP's tightly-coupled memory cannot tile the 1024-token attention (16 × 1024 × 1024 scores).
  SM7675 failed in the job itself. A 256-token-only NPU file would compile, but the app would then need a second
  engine for longer rows (twice the memory), so these phones use the GPU.
- **Hexagon v68**: the new `embeds` layout (the app looks the token rows up in `laya-embeddings-fp16.bin`, GELU in
  tanh form) removed the fp16 GATHER, but v68 still refuses GELU (one remains in the scorer head, or the converter
  fuses the tanh form back into GELU) and an fp16 DIV (the LayerNorm scaling): not built. The table is published and
  the app can read `embeds` files (`LayaFile.embeddings`), for when a build passes.
- **Exynos**: LiteRT's Samsung NPU plugin (in its development tree) covers only Exynos 2500/2600, needs Samsung's
  non-public AI LiteCore SDK to compile and has no published runtime, so no app can ship it yet. Exynos phones run Laya
  on their GPU.
- **GPU on every chip**: the GPU now has three paths, each refused on its own (`gpu:<file>`, `#opengl`, `#fp32`):
  LiteRT's own choice (OpenCL where offered) in fp16; then OpenGL ES compute, for drivers whose OpenCL fails or
  computes wrongly (some Mali and Xclipse); then OpenCL at fp32 on phones with 8 GB or more, for GPUs whose fp16 is off.
  The GPU keeps the programs it compiled (`cacheDir/laya-gpu`, LiteRT's program cache keyed by the file's SHA-256), so
  a later start no longer compiles every kernel again; a failed GPU load clears it. Laya's GPU queue runs at low
  priority, below the app's own drawing.
- **Power**: on Snapdragon NPUs Laya asks Hexagon for its balanced performance mode instead of the burst vote that
  held the DSP at full clock while the model stayed open.

Not verified on any physical phone (none here); the reference check still guards every path before it answers.
