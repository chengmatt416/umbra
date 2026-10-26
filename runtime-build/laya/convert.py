"""Convert Laya's typed-decisions checkpoint for the app's native engine (LiteRT: NPU, then GPU, then CPU).

Steps (each writes into --out, default build/laya-native):

  export    PyTorch -> LiteRT fp32, one signature per sequence bucket (static shapes: NPUs and GPUs need them)
  quantize  fp16 weights (GPU, CPU and the NPU compiler's input); int8 only for comparison
  verify    run the LiteRT files on this machine's CPU against the PyTorch model on golden requests
  golden    tokenizer + sequence + decoding fixtures for the Kotlin port (core/decision tests)
  aot       ahead-of-time NPU compilation per SoC (Linux x86_64 only: the vendor compiler plugins ship only there;
            .github/workflows/laya-npu.yml runs it, one SoC per job, and publishes the files as a GitHub release)
  manifest  app assets: laya-native.json (every file with bytes, SHA-256, backend and SoC), config, tokenizer
  upload    every manifest file hosted on R2 to laya/<revision>/ (NPU files with their own URL stay where they are)
  remote    download every published file and check size, SHA-256 and range support

The graph (see `exportable`) per bucket length L, K = MAX_OPTIONS:
  input_ids int32[1,L], key_mask f32[1,L], markers f32[1,K,L] (one-hot), qtype f32[1,3] (one-hot) -> logits f32[1,K]
Pre- and post-processing (tokenizer, sequence layout, temperatures, confidence) stay in Kotlin, mirroring
laya.onnx_agent so either runtime answers the same request the same way.
"""
import argparse
import hashlib
import json
import os
import sys
import time

CHECKPOINT = "convaiinnovations/laya-typed-decisions"
REVISION = "1a793eb568e6718f15941d08f85432581df534e3"  # laya.revisions.PINNED_REVISIONS
BUCKETS = (256, 1024)  # sequence lengths; a request uses the smallest that fits (max_len is 1024)
MAX_OPTIONS = 32       # laya.structured.MAX_OPTIONS; more options fall back to another engine
NPU_TARGETS = {
    # Every phone and tablet SoC LiteRT 2.3.0's compilers name (ai_edge_litert/aot/vendors/*/target.py; Qualcomm's
    # automotive SA8255/SA8295 are left out). Laya's graph is fp16: a SoC whose NPU cannot take it (Hexagon before v73
    # has no fp16, say) fails its compile, aot-report.json says why, and that SoC uses its GPU instead.
    # (SM8350, Hexagon v68, refuses the fp16 Gather and GELU: it uses its GPU.)
    "qualcomm": ["SM8450", "SM8475", "SM8550", "SM8650", "SM8750", "SM8845", "SM8850"],
    # Google Tensor is listed for completeness: LiteRT 2.3.0's Tensor compiler download (tensor-ml-sdk 6.1.0) answers
    # 404, so CI cannot build it and Pixels use their GPU.
    # (MT6886 and MT6895 are in LiteRT's list, but NeuroPilot's compiler answers "Not a valid platform" for them.)
    # (MT6853 and MT6893, older APUs, refuse a dozen of its ops: they use their GPU.)
    "mediatek": ["MT6877", "MT6878", "MT6879", "MT6897", "MT6983", "MT6985",
                 "MT6989", "MT6991", "MT6993", "MT8171", "MT8188", "MT8189"],
    "google_tensor": ["Tensor_G3", "Tensor_G4", "Tensor_G5", "Tensor_G6"],
}


def load_agent(checkpoint_dir):
    import laya
    agent = laya.Agent(checkpoint_dir, device="cpu")
    agent.model.float().eval()
    return agent


MASKED = -1e4  # finite: representable in fp16 and still exp() == 0 next to real scores


def exportable(model, length, layout="default"):
    """laya.common.DecisionModel.forward (sequential layout, inference) for one fixed sequence length, written so that
    LiteRT's CPU, GPU and NPU backends can all run it. Same weights, same maths; what changes is the shape of the graph:

    * Masks are additive with a finite MASKED instead of -inf. Inputs are padded to a fixed length, and a padded
      position more than ModernBERT's sliding window (64) away from the last real token has no key it may see: softmax
      over nothing is NaN, and 0 x NaN then reaches the real tokens. Real tokens see exactly the keys HF's masks allow
      (`|q - k| <= sliding_window`, key not padding), so their outputs do not change.
    * Attention is written with 4-D tensors and plain matmuls (HF's packed qkv view is 5-D, which mobile GPUs and NPUs
      do not take); the sliding-window mask and the RoPE tables are constants per length.
    * The key mask, the option markers (one-hot over positions) and the question type (one-hot) come in as floats, so
      the graph needs no boolean ops, no gather and no small embedding lookup -- none of which the GPU runs.
    * Only the option logits come out: Umbra does not read Laya's act head.
    * LayerNorm divides each token by its largest |x| first (LayerNorm does not change under scaling; epsilon is scaled
      with it). ModernBERT's residual stream holds outliers near 28,600: they fit in fp16, but the squares a decomposed
      LayerNorm takes (TFLite has no LayerNorm op) do not, so a GPU or NPU computing in fp16 returned inf.

    Inputs: input_ids int32[1,L], key_mask f32[1,L] (0 or MASKED), markers f32[1,K,L], qtype f32[1,3] -> logits f32[1,K]

    layout "npu4d": the same maths, with the option pick (markers x hidden states) shaped like the attention's batch
    matmuls ([16,K,L] x [16,L,64]). The converter turns the plain pick into BATCH_MATMUL [1,K,L] x [L,H], mixed ranks,
    which MediaTek's Neuron compiler refuses ("input rank is invalid") and so compiled nothing. This file is only the
    NPU compilers' input; the GPU and CPU file stays as it is. Its attention is written in 3-D too (see attend).
    """
    import torch
    import torch.nn.functional as F

    enc = model.encoder
    cfg = enc.config
    heads = cfg.num_attention_heads
    head_dim = cfg.hidden_size // heads
    positions = torch.arange(length)
    near = (positions[None, :] - positions[:, None]).abs() <= cfg.sliding_window
    rope = {}
    for layer_type in set(cfg.layer_types):
        inv = getattr(enc.rotary_emb, "%s_inv_freq" % layer_type).float()
        freqs = positions[:, None].float() * inv[None, :]
        emb = torch.cat((freqs, freqs), dim=-1)
        scale = getattr(enc.rotary_emb, "%s_attention_scaling" % layer_type)
        rope[layer_type] = ((emb.cos() * scale)[None, None], (emb.sin() * scale)[None, None])

    def norm(ln, x):
        if not isinstance(ln, torch.nn.LayerNorm):
            return ln(x)  # ModernBERT's first layer has no attention norm
        scale = x.abs().amax(dim=-1, keepdim=True).clamp(min=1.0)
        xs = x / scale
        centered = xs - xs.mean(dim=-1, keepdim=True)
        y = centered * torch.rsqrt((centered * centered).mean(dim=-1, keepdim=True) + ln.eps / scale / scale)
        if ln.weight is not None:
            y = y * ln.weight
        return y + ln.bias if ln.bias is not None else y

    def rotate_half(x):
        half = x.shape[-1] // 2
        return torch.cat((-x[..., half:], x[..., :half]), dim=-1)

    def split_heads(x, n, d):
        return x.reshape(1, length, n, d).transpose(1, 2)

    def attend(q, k, v, mask, d):
        if layout == "npu4d":
            # In 3-D ([heads, L, d], mask [1, L, L]): the converter folds the batch of 1 out of the scores but not out
            # of the mask, and MediaTek's MDLA refuses ADD <16 x L x L> + <1 x 1 x L x L>.
            q, k, v, mask = q[0], k[0], v[0], mask[0]
            scores = torch.matmul(q, k.transpose(-1, -2)) * (d ** -0.5) + mask
            return torch.matmul(torch.softmax(scores, dim=-1), v).transpose(0, 1).reshape(1, length, -1)
        scores = torch.matmul(q, k.transpose(-1, -2)) * (d ** -0.5) + mask
        return torch.matmul(torch.softmax(scores, dim=-1), v).transpose(1, 2).reshape(1, length, -1)

    class Exported(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.model = model
            self.register_buffer("near", torch.where(near, 0.0, MASKED)[None, None].float(), persistent=False)
            for name, (cos, sin) in rope.items():
                self.register_buffer("cos_" + name, cos, persistent=False)
                self.register_buffer("sin_" + name, sin, persistent=False)

        def forward(self, input_ids, key_mask, markers, qtype):
            m = self.model
            if layout == "npu4d":
                # The lookup first: MediaTek runs it on the CPU, and an NPU op before it (the masks) would be a
                # partition of its own.
                looked_up = enc.embeddings.tok_embeddings(input_ids.long())
            full = key_mask[:, None, None, :]
            masks = {"full_attention": full, "sliding_attention": full + self.near}
            # nn.Embedding lowers to EMBEDDING_LOOKUP, which Qualcomm's compiler takes and the quantizer stores as fp16
            # (index_select lowers to GATHER_ND plus bounds checks, and its table stays fp32: 100 MB more).
            if layout != "npu4d":
                looked_up = enc.embeddings.tok_embeddings(input_ids.long())
            h = norm(enc.embeddings.norm, looked_up)
            width = heads * head_dim
            for layer in enc.layers:
                a = layer.attn
                qkv = a.Wqkv(norm(layer.attn_norm, h))
                q = split_heads(qkv[..., :width], heads, head_dim)
                k = split_heads(qkv[..., width:2 * width], heads, head_dim)
                v = split_heads(qkv[..., 2 * width:], heads, head_dim)
                cos = getattr(self, "cos_" + layer.attention_type)
                sin = getattr(self, "sin_" + layer.attention_type)
                q = q * cos + rotate_half(q) * sin
                k = k * cos + rotate_half(k) * sin
                h = h + a.Wo(attend(q, k, v, masks[layer.attention_type], head_dim))
                h = h + layer.mlp(norm(layer.mlp_norm, h))
            h = norm(enc.final_norm, h)
            h = h + torch.matmul(qtype, m.type_emb.weight)[:, None, :]
            if m.head is not None:
                for layer in m.head.layers:  # nn.TransformerEncoderLayer, norm_first, eval
                    sa = layer.self_attn
                    n = sa.num_heads
                    d = sa.head_dim
                    qkv = F.linear(norm(layer.norm1, h), sa.in_proj_weight, sa.in_proj_bias)
                    e = n * d
                    att = attend(split_heads(qkv[..., :e], n, d), split_heads(qkv[..., e:2 * e], n, d),
                                 split_heads(qkv[..., 2 * e:], n, d), full, d)
                    h = h + sa.out_proj(att)
                    h = h + layer.linear2(layer.activation(layer.linear1(norm(layer.norm2, h))))
            if layout == "npu4d":
                k = markers.shape[1]
                parts = h.reshape(1, length, heads, head_dim).transpose(1, 2)               # [1, 16, L, 64]
                picks = torch.cat([markers[:, None]] * heads, dim=1)                         # [1, 16, K, L]
                picked = torch.matmul(picks, parts).transpose(1, 2).reshape(1, k, width)     # [1, K, H]
            else:
                picked = torch.matmul(markers, h)
            ln, *rest = m.scorer
            picked = norm(ln, picked)
            for module in rest:
                picked = module(picked)
            return picked.squeeze(-1)

    return Exported().eval()


def graph_inputs(ids, markers, qtype, pad_id, length=None):
    """The padded graph inputs for one question row, as numpy arrays (the Kotlin engine builds the same)."""
    import numpy as np
    length = length or bucket_for(len(ids))
    x = np.full((1, length), pad_id, dtype=np.int32)
    x[0, :len(ids)] = ids
    key = np.full((1, length), MASKED, dtype=np.float32)
    key[0, :len(ids)] = 0.0
    onehot = np.zeros((1, MAX_OPTIONS, length), dtype=np.float32)
    for slot, position in enumerate(markers):
        onehot[0, slot, position] = 1.0
    qt = np.zeros((1, 3), dtype=np.float32)
    qt[0, qtype] = 1.0
    return length, (x, key, onehot, qt)


def sample(length):
    import torch
    _, arrays = graph_inputs([1] * 16, [4, 8], 0, 0, length)
    return tuple(torch.from_numpy(a) for a in arrays)


def cmd_export(args):
    import torch
    import litert_torch
    agent = load_agent(args.checkpoint)
    converter = None
    for length in (int(b) for b in args.buckets.split(",")):
        name = "l%d" % length
        module = exportable(agent.model, length, args.layout)
        converter = (litert_torch.signature if converter is None else converter.signature)(name, module, sample(length))
    started = time.time()
    with torch.no_grad():
        edge = converter.convert()
    path = os.path.join(args.out, "laya-typed-decisions-fp32.tflite")
    edge.export(path)
    print("exported %s (%d bytes) in %.0f s" % (path, os.path.getsize(path), time.time() - started))


FP16_RECIPE = [{
    "regex": ".*", "operation": "*", "algorithm_key": "float_casting",
    "op_config": {"weight_tensor_config": {"num_bits": 16, "dtype": "FLOAT"}, "compute_precision": "FLOAT",
                  "explicit_dequantize": True},
}]


# npu4d: the embedding table stays fp32. MediaTek runs the lookup on the CPU, and a DEQUANTIZE of an fp16 table would
# be an NPU partition with no input of its own, which LiteRT's MediaTek plugin cannot compile ("Failed to identify
# model I/Os"). 100 MB more, in that file only.
FP16_NPU4D_RECIPE = FP16_RECIPE + [{"regex": ".*", "operation": "EMBEDDING_LOOKUP", "algorithm_key": "no_quantize"}]


def cmd_quantize(args):
    from ai_edge_quantizer import quantizer, recipe
    source = os.path.join(args.out, "laya-typed-decisions-fp32.tflite")
    # int8 (dynamic range) is kept for comparison only: it quantizes activations per tensor, and ModernBERT's outliers
    # (~28,600) leave too little precision for everything else (decisions flipped in verify). The CPU uses fp16 too.
    fp16 = FP16_NPU4D_RECIPE if args.layout == "npu4d" else FP16_RECIPE
    for name, rec in (("fp16", fp16), ("int8", recipe.dynamic_wi8_afp32())):
        if name not in args.variants.split(","):
            continue
        started = time.time()
        q = quantizer.Quantizer(source)
        q.load_quantization_recipe(rec)
        path = os.path.join(args.out, "laya-typed-decisions-%s.tflite" % name)
        if os.path.exists(path):
            os.remove(path)
        q.quantize().export_model(path)
        print("%s: %s (%d bytes) in %.0f s" % (name, path, os.path.getsize(path), time.time() - started))


def golden_requests():
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "golden-requests.json")) as f:
        data = json.load(f)
    for r in data["requests"]:
        if r["state"] == "LONG":
            # Longer than max_len, so the Kotlin port has to truncate exactly where Laya does.
            r["state"] = "\n".join("[%04d] gradle: Task :app:compileReleaseKotlin step %d of 400 took %d ms (%s)"
                                   % (i, i, (i * 37) % 900, "ok" if i % 9 else "warning: deprecated API")
                                   for i in range(120)) + "\nBUILD FAILED in 2m 14s"
    return data


def bucket_for(n):
    for length in BUCKETS:
        if n <= length:
            return length
    raise ValueError("sequence of %d tokens is longer than the largest bucket" % n)


def decoder(agent):
    """laya.onnx_agent.ONNXAgent._decode_answers bound to this checkpoint's temperatures."""
    from laya.onnx_agent import ONNXAgent
    d = ONNXAgent.__new__(ONNXAgent)
    d.temperature, d.temperature_by_options = agent.temperature, agent.temperature_by_options
    d.lang_temperatures, d.binning_map = {}, None
    return d


def rows(agent, request):
    """Every question row of one request exactly as Laya builds it: (qid, internal, ids, markers, qtype)."""
    from laya.common import QTYPES, build_sequence, encode_text, serialize_state
    from laya.onnx_agent import ONNXAgent
    state = {"body": request["state"]}
    tok = agent.tok
    state_ids = encode_text(tok, serialize_state(state).replace(tok.mask_token, " "), add_special_tokens=False)["input_ids"]
    out = []
    for qid, qdef in request["questions"].items():
        q = ONNXAgent._to_internal(qdef)
        ids, markers = build_sequence(tok, state, q, agent.cfg["max_len"], agent.cfg["head_max_len"], state_ids=state_ids)
        out.append((qid, q, ids, markers, QTYPES[q["t"]]))
    return state, state_ids, out


def cmd_golden(args):
    import gzip
    import shutil
    import numpy as np
    import torch
    agent = load_agent(args.checkpoint)
    tok = agent.tok
    data = golden_requests()
    dec = decoder(agent)
    fixtures = []
    for request in data["requests"]:
        state, state_ids, qrows = rows(agent, request)
        reference = agent.system_one(state, request["questions"])["answers"]
        out_rows = []
        for qid, q, ids, markers, qtype in qrows:
            # The reference is Laya's own DecisionModel on the unpadded row: what the converted graph must reproduce.
            with torch.no_grad():
                logits, act = agent.model(torch.tensor([ids]), torch.ones((1, len(ids)), dtype=torch.long),
                                          torch.tensor([markers]), torch.ones((1, len(markers)), dtype=torch.bool),
                                          torch.tensor([qtype]))
            logits, act = logits.float().numpy(), torch.softmax(act.float(), -1).numpy()
            items = [{"markers": markers}]
            answer = dec._decode_answers(logits, act, items, [qid], {qid: q}, 0)[qid]
            out_rows.append({"qid": qid, "ids": list(map(int, ids)), "markers": list(map(int, markers)), "qtype": qtype,
                             "logits": [float(v) for v in logits[0, :len(markers)]],
                             "act_logits": [float(v) for v in act[0]], "answer": answer,
                             "reference": reference[qid]})
        fixtures.append({"id": request["id"], "state": request["state"], "questions": request["questions"],
                         "state_ids": list(map(int, state_ids)), "rows": out_rows})
    tokenizer_cases = [{"text": t, "ids": tok(t, add_special_tokens=False)["input_ids"]} for t in data["tokenizer"]]
    cfg = {"max_len": agent.cfg["max_len"], "head_max_len": agent.cfg["head_max_len"],
           "temperature": agent.temperature, "temperature_by_options": agent.temperature_by_options,
           "cls": tok.cls_token_id, "sep": tok.sep_token_id, "mask": tok.mask_token_id, "pad": tok.pad_token_id,
           "buckets": list(BUCKETS), "max_options": MAX_OPTIONS}
    dest = os.path.join("core", "decision", "src", "test", "resources", "laya")
    os.makedirs(dest, exist_ok=True)
    with open(os.path.join(dest, "golden.json"), "w") as f:
        json.dump({"checkpoint": CHECKPOINT, "revision": REVISION, "config": cfg, "requests": fixtures,
                   "tokenizer": tokenizer_cases}, f, ensure_ascii=False, indent=1)
    with open(os.path.join(args.checkpoint, "tokenizer", "tokenizer.json"), "rb") as src, \
            gzip.open(os.path.join(dest, "tokenizer.json.gz"), "wb", compresslevel=9) as gz:
        shutil.copyfileobj(src, gz)
    with open(os.path.join(args.out, "laya-config.json"), "w") as f:
        json.dump(cfg, f, indent=1)
    agree = sum(r["answer"].get("choice", r["answer"].get("score", r["answer"].get("noul")))
                == r["reference"].get("choice", r["reference"].get("score", r["reference"].get("noul")))
                for fx in fixtures for r in fx["rows"])
    total = sum(len(fx["rows"]) for fx in fixtures)
    print("golden: %d requests, %d rows (%d match laya's batched answer exactly), %d tokenizer cases -> %s"
          % (len(fixtures), total, agree, len(tokenizer_cases), dest))


def run_litert(model, signature, inputs):
    """One forward pass through a LiteRT CompiledModel signature; returns the option logits [1, K]."""
    import numpy as np
    index = model.get_signature_index(signature)
    ins = model.create_input_buffers(index)
    outs = model.create_output_buffers(index)
    for buf, arr in zip(ins, inputs):
        buf.write(arr)
    model.run_by_index(index, ins, outs)
    return outs[0].read(MAX_OPTIONS, np.float32).reshape(1, MAX_OPTIONS)


def cmd_verify(args):
    """Each converted file on this machine's CPU (and GPU, where LiteRT has one here) against the PyTorch logits."""
    import numpy as np
    from ai_edge_litert.compiled_model import CompiledModel
    from ai_edge_litert.hardware_accelerator import HardwareAccelerator as HW
    from ai_edge_litert.options import CpuOptions, GpuOptions, Options
    with open(os.path.join("core", "decision", "src", "test", "resources", "laya", "golden.json")) as f:
        golden = json.load(f)
    pad = golden["config"]["pad"]
    report = {}
    for variant in args.variants.split(","):
        path = os.path.join(args.out, "laya-typed-decisions-%s.tflite" % variant)
        for accel_name in args.accelerators.split(","):
            # gpu32 is how the app runs the GPU (fp32 precision); gpu is the GPU's default fp16, for comparison.
            accel = {"cpu": HW.CPU, "gpu": HW.GPU | HW.CPU, "gpu32": HW.GPU | HW.CPU}[accel_name]
            try:
                started = time.time()
                model = CompiledModel.from_file(path, options=Options(hardware_accelerators=accel,
                                                                      cpu_options=CpuOptions(num_threads=os.cpu_count() or 4),
                                                                      gpu_options=GpuOptions(enforce_f32=accel_name == "gpu32")))
                load_s = time.time() - started
            except Exception as e:  # noqa: BLE001 -- report and go on to the next combination
                report["%s/%s" % (variant, accel_name)] = {"error": str(e)[:300]}
                continue
            worst, flips, times = 0.0, 0, []
            # A file exported with fewer buckets (--buckets) answers a short row padded to the next length it has.
            have = sorted(int(n[1:]) for n in model.get_signature_list())
            for fx in golden["requests"]:
                for r in fx["rows"]:
                    length, inputs = graph_inputs(r["ids"], r["markers"], r["qtype"], pad,
                                                  next(n for n in have if n >= bucket_for(len(r["ids"]))))
                    started = time.time()
                    logits = run_litert(model, "l%d" % length, inputs)
                    times.append((length, time.time() - started))
                    k = len(r["markers"])
                    ref = np.array(r["logits"])
                    got = logits[0, :k]
                    # NaN must count as the worst difference, not slip past max()
                    worst = max(worst, float(np.nan_to_num(np.abs(got - ref), nan=np.inf).max()))
                    flips += int(np.argmax(got) != np.argmax(ref))
            report["%s/%s" % (variant, accel_name)] = {
                "load_s": round(load_s, 1), "max_abs_logit_diff": round(worst, 4), "argmax_flips": flips,
                "ms_l256": round(1000 * np.median([t for l, t in times if l == 256] or [0]), 1),
                "ms_l1024": round(1000 * np.median([t for l, t in times if l == 1024] or [0]), 1)}
            print(variant, accel_name, report["%s/%s" % (variant, accel_name)], flush=True)
            del model
    with open(os.path.join(args.out, "verify-report.json"), "w") as f:
        json.dump(report, f, indent=1)


SITE = "https://umbra-agent.pages.dev/download/"
CPU_FILE = "laya-typed-decisions-fp16.tflite"  # the GPU's file: falling back to the CPU downloads nothing more
ASSETS = os.path.join("app", "src", "main", "assets", "laya")
# Largest absolute logit difference a backend may show on the reference row before the app refuses it (see verify).
TOLERANCE = {"npu": 0.35, "gpu": 0.35, "cpu": 0.6}


def cmd_aot(args):
    """Compile the fp16 file ahead of time for each NPU in NPU_TARGETS (Linux x86_64: the vendor plugins exist only
    there). Each SoC gets npu/<vendor>-<soc>.tflite; failures are reported, and that SoC uses the GPU file instead."""
    import glob
    import platform
    import shutil
    import tempfile
    if not (sys.platform.startswith("linux") and platform.machine() in ("x86_64", "AMD64")):
        raise SystemExit("aot needs Linux x86_64 (LiteRT ships its NPU compiler plugins only for it)")
    from ai_edge_litert.aot import aot_compile
    from ai_edge_litert.aot.core import apply_plugin
    # LiteRT's Python layer passes no compiler flags of its own; these go to the apply_plugin tool as --key=value.
    # Qualcomm: the two signatures (256 and 1024 tokens) are two graphs with the same weights, which without sharing
    # are stored twice (1.73 GB for an 0.85 GB model).
    plugin_flags = dict(f.split("=", 1) for f in args.plugin_flags.split(",") if f)
    if plugin_flags:
        call = apply_plugin.ApplyPlugin.__call__
        apply_plugin.ApplyPlugin.__call__ = lambda self, *a, **kw: call(self, *a, **{**kw, **plugin_flags})
    from ai_edge_litert.aot.vendors.google_tensor import target as tensor
    from ai_edge_litert.aot.vendors.mediatek import target as mediatek
    from ai_edge_litert.aot.vendors.qualcomm import target as qualcomm
    targets = {"qualcomm": lambda soc: qualcomm.Target(qualcomm.SocModel(soc)),
               "mediatek": lambda soc: mediatek.Target(mediatek.SocModel(soc)),
               "google_tensor": lambda soc: tensor.Target(tensor.SocModel(soc))}
    source = os.path.join(args.out, "laya-typed-decisions-fp16.tflite")
    out = os.path.join(args.out, "npu")
    os.makedirs(out, exist_ok=True)
    wanted = set(args.socs.split(",")) if args.socs else None
    report = {}
    for vendor, socs in NPU_TARGETS.items():
        for soc in socs:
            if wanted and soc not in wanted and vendor not in wanted:
                continue
            key = "%s-%s" % (vendor, soc)
            started = time.time()
            with tempfile.TemporaryDirectory() as tmp:
                try:
                    result = aot_compile.aot_compile(source, output_dir=tmp, target=targets[vendor](soc), keep_going=True)
                    if result.failed_backends or not result.models_with_backend:
                        raise RuntimeError("; ".join(str(e)[:400] for _, e in result.failed_backends) or "no model")
                    result.export(tmp, model_name="compiled")
                    produced = sorted(glob.glob(os.path.join(tmp, "compiled*.tflite")))
                    if len(produced) != 1:
                        raise RuntimeError("expected one compiled model, got %s" % produced)
                    target = os.path.join(out, key + ".tflite")
                    shutil.move(produced[0], target)
                    report[key] = {"seconds": round(time.time() - started), "bytes": os.path.getsize(target),
                                   "report": result.compilation_report()[:2000]}
                    # What the manifest needs, for a file that is published from this machine and never copied back,
                    # with the sequence lengths it holds when not all of BUCKETS (the app pads up to them).
                    meta = {"bytes": os.path.getsize(target), "sha256": sha256(target)}
                    held = signature_lengths(source)
                    if held != list(BUCKETS):
                        meta["buckets"] = held
                    with open(os.path.join(out, key + ".json"), "w") as f:
                        json.dump(meta, f)
                except Exception as e:  # noqa: BLE001 -- one SoC failing must not stop the others
                    report[key] = {"error": str(e)[:1000]}
                    # The plugin writes why to a temporary file ("See /tmp/....error for details"), gone with tmp.
                    import re
                    for detail in re.findall(r"See (\S+\.error)", str(e)):
                        if os.path.exists(detail):
                            with open(detail, errors="replace") as f:
                                report[key]["detail"] = f.read()[-6000:]
            print(key, {k: v for k, v in report[key].items() if k != "report"}, flush=True)
    with open(os.path.join(out, "aot-report.json"), "w") as f:
        json.dump(report, f, indent=1)


def signature_lengths(path):
    """The sequence lengths a converted file has signatures for ("l256", "l1024")."""
    from ai_edge_litert import schema_py_generated as schema
    with open(path, "rb") as f:
        model = schema.Model.GetRootAsModel(f.read(), 0)
    return sorted(int(model.SignatureDefs(i).SignatureKey().decode()[1:]) for i in range(model.SignatureDefsLength()))


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def cmd_manifest(args):
    """app/src/main/assets/laya/: laya-native.json (every file with size and SHA-256, and a reference row the app runs
    before trusting a backend), laya-config.json and the tokenizer. The APK carries these; R2 carries the files."""
    import glob
    import shutil
    revision = REVISION[:12]
    files = []
    for name, backend in (("laya-typed-decisions-fp16.tflite", "gpu"), (CPU_FILE, "cpu")):
        path = os.path.join(args.out, name)
        files.append({"name": name, "backend": backend, "bytes": os.path.getsize(path), "sha256": sha256(path)})
    # NPU files: the .tflite itself, or the .json the aot step writes next to it (bytes and SHA-256) when the file was
    # compiled and published elsewhere (--npu-url: where they are, as <vendor>-<soc>.tflite).
    npu = os.path.join(args.out, "npu")
    sidecars = [p for p in glob.glob(os.path.join(npu, "*-*.json")) if not p.endswith(".report.json")]
    keys = sorted({os.path.basename(p).rsplit(".", 1)[0] for p in glob.glob(os.path.join(npu, "*.tflite")) + sidecars})
    for key in keys:
        vendor, soc = key.split("-", 1)
        path = os.path.join(npu, key + ".tflite")
        if os.path.exists(path):
            size, digest = os.path.getsize(path), sha256(path)
        else:
            with open(os.path.join(npu, key + ".json")) as f:
                meta = json.load(f)
            size, digest = meta["bytes"], meta["sha256"]
        entry = {"name": "npu/%s.tflite" % key, "backend": "npu", "vendor": vendor, "soc": soc, "bytes": size, "sha256": digest}
        if os.path.exists(os.path.join(npu, key + ".json")):
            with open(os.path.join(npu, key + ".json")) as f:
                held = json.load(f).get("buckets")
            if held:
                entry["buckets"] = held
        if args.npu_url:
            entry["url"] = args.npu_url.rstrip("/") + "/%s.tflite" % key
        files.append(entry)
    with open(os.path.join("core", "decision", "src", "test", "resources", "laya", "golden.json")) as f:
        golden = json.load(f)
    # A short row with a clear margin between its options, so the check tells "computes correctly" from "noise".
    row = next(r for fx in golden["requests"] if fx["id"] == "did-it-work" for r in fx["rows"])
    manifest = {"checkpoint": CHECKPOINT, "revision": REVISION, "base": "%slaya/%s/" % (SITE, revision), "files": files,
                "check": {"length": bucket_for(len(row["ids"])), "ids": row["ids"], "markers": row["markers"],
                          "qtype": row["qtype"], "logits": row["logits"], "tolerance": TOLERANCE}}
    os.makedirs(ASSETS, exist_ok=True)
    with open(os.path.join(ASSETS, "laya-native.json"), "w") as f:
        json.dump(manifest, f, indent=1)
    shutil.copy(os.path.join(args.out, "laya-config.json"), os.path.join(ASSETS, "laya-config.json"))
    # Plain: aapt unpacks a .gz asset and drops the extension, and the APK compresses assets anyway.
    shutil.copy(os.path.join(args.checkpoint, "tokenizer", "tokenizer.json"), os.path.join(ASSETS, "tokenizer.json"))
    print("manifest: %d files -> %s" % (len(files), ASSETS))
    for f in files:
        print("  %-44s %-4s %12d %s" % (f["name"], f["backend"], f["bytes"], f["sha256"][:16]))


def cmd_upload(args):
    """Every file of the manifest to R2 (laya/<revision>/<name>) through website/tools/upload-release.mjs."""
    import subprocess
    with open(os.path.join(ASSETS, "laya-native.json")) as f:
        manifest = json.load(f)
    revision = manifest["revision"][:12]
    for entry in {e["name"]: e for e in manifest["files"] if "url" not in e}.values():  # one file can serve two backends
        local = os.path.abspath(os.path.join(args.out, entry["name"]))
        assert os.path.getsize(local) == entry["bytes"] and sha256(local) == entry["sha256"], entry["name"]
        subprocess.run(["node", "tools/upload-release.mjs", local, "laya/%s/%s" % (revision, entry["name"]), "--asset"],
                       cwd="website", check=True)


def cmd_remote(args):
    """Downloads every published file once and checks its size, SHA-256 and resumable range against the manifest."""
    import urllib.request
    with open(os.path.join(ASSETS, "laya-native.json")) as f:
        manifest = json.load(f)
    for entry in {e["name"]: e for e in manifest["files"]}.values():
        url = entry.get("url") or manifest["base"] + entry["name"]
        req = urllib.request.Request(url, headers={"Range": "bytes=0-15", "User-Agent": "umbra-laya-check"})
        with urllib.request.urlopen(req, timeout=60) as r:
            assert r.status == 206 and r.headers["Content-Range"] == "bytes 0-15/%d" % entry["bytes"], (url, r.status)
        h = hashlib.sha256()
        n = 0
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "umbra-laya-check"}), timeout=900) as r:
            for chunk in iter(lambda: r.read(1 << 20), b""):
                h.update(chunk)
                n += len(chunk)
        assert n == entry["bytes"] and h.hexdigest() == entry["sha256"], (url, n, h.hexdigest())
        print("PASS", entry["name"], n, "bytes")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("step", choices=["export", "quantize", "verify", "golden", "aot", "manifest", "upload", "remote"])
    p.add_argument("--checkpoint", default="build/laya-convert/typed-decisions")
    p.add_argument("--out", default="build/laya-native")
    p.add_argument("--variants", default="fp32,fp16,int8")
    p.add_argument("--accelerators", default="cpu,gpu,gpu32")
    p.add_argument("--socs", default="", help="aot: comma-separated SoCs or vendors (default: all of NPU_TARGETS)")
    p.add_argument("--plugin-flags", default="", help="aot: key=value,... passed to LiteRT's apply_plugin tool")
    p.add_argument("--buckets", default=",".join(map(str, BUCKETS)), help="export: sequence lengths to give signatures")
    p.add_argument("--layout", default="default", choices=["default", "npu4d"], help="export: graph layout (see exportable)")
    p.add_argument("--npu-url", default="", help="manifest: where the NPU files are published (default: R2 next to the others)")
    args = p.parse_args()
    os.makedirs(args.out, exist_ok=True)
    globals()["cmd_" + args.step](args)


if __name__ == "__main__":
    sys.exit(main())
