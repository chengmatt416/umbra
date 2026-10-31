package app.umbra.agent.data.laya

import app.umbra.core.decision.LayaGraph
import app.umbra.core.decision.LayaInputs
import kotlinx.serialization.Serializable

/**
 * What this build of the app may download for the native Laya engine (`assets/laya/laya-native.json`, written by
 * `runtime-build/laya/convert.py manifest`). The APK carries every file's size and SHA-256, so nothing fetched from
 * the network can change what the engine loads; a new conversion ships with a new app version.
 */
@Serializable
data class LayaManifest(
    val checkpoint: String,
    val revision: String,
    /** Where the files live, relative names appended (Cloudflare R2 behind the site's `/download/`). */
    val base: String,
    val files: List<LayaFile>,
    val check: LayaCheck,
)

@Serializable
data class LayaFile(
    val name: String,
    /** "npu", "gpu" or "cpu"; "aux" for a file no processor runs by itself (the token table of [embeddings]). */
    val backend: String,
    val bytes: Long,
    val sha256: String,
    /** For NPU files: "qualcomm", "mediatek" or "google_tensor", and the SoC it was compiled for. */
    val vendor: String? = null,
    val soc: String? = null,
    /** Where the file is published when not next to the others under [LayaManifest.base] (the NPU files: a GitHub release). */
    val url: String? = null,
    /**
     * The sequence lengths it has graphs for, when not all of the app's buckets: MediaTek's NPU files hold only 1024
     * tokens (two graphs would store the weights twice), and a shorter row is padded up ([PaddedGraph]).
     */
    val buckets: List<Int>? = null,
    /**
     * For a file of the "embeds" layout (Hexagon v68, whose NPU has no fp16 lookup and no GELU): the token table, by
     * name, it reads its first input from. The app looks the rows up itself and hands the graph embeddings, not ids.
     */
    val embeddings: String? = null,
    /** For the token table: [vocabulary, hidden] (fp16, little-endian). */
    val shape: List<Int>? = null,
) {
    fun source(manifest: LayaManifest): String = url ?: (manifest.base + name)
}

/**
 * A known question row and the logits the PyTorch checkpoint gives for it. A backend must reproduce them within its
 * tolerance before it answers anything: an accelerator that loads but computes wrongly is refused like one that fails.
 */
@Serializable
data class LayaCheck(
    val length: Int,
    val ids: List<Int>,
    val markers: List<Int>,
    val qtype: Int,
    val logits: List<Float>,
    /** Largest allowed absolute logit difference per backend. */
    val tolerance: Map<String, Float>,
)

enum class LayaBackend(val key: String) { NPU("npu"), GPU("gpu"), CPU("cpu") }

/**
 * How the GPU runs Laya. [AUTO] is LiteRT's choice (OpenCL where the phone offers it) in fp16; [OPENGL] is OpenGL ES
 * compute, for a phone whose OpenCL driver fails or computes wrongly (some Mali and Xclipse drivers); [FP32] is OpenCL
 * at full precision, for a GPU whose fp16 is off, tried only where memory allows twice the weights.
 */
enum class GpuPath(val suffix: String) { AUTO(""), OPENGL("#opengl"), FP32("#fp32") }

/**
 * [onDevice]: an NPU candidate with no file compiled for this SoC, so LiteRT compiles the GPU's file on the phone.
 * [gpu]: for a GPU candidate, how it runs.
 */
data class LayaCandidate(val backend: LayaBackend, val file: LayaFile, val onDevice: Boolean = false, val gpu: GpuPath = GpuPath.AUTO)

object LayaPlan {
    /**
     * Vendors whose NPU LiteRT can compile the whole graph for on the phone. Qualcomm's compiler has a builder for every
     * op in it; MediaTek's lacks gather, abs, reduce-max and divide, and Google Tensor takes only files compiled ahead
     * of time, so those two use the NPU only with such a file.
     */
    private val ON_DEVICE = setOf("qualcomm")

    /**
     * The files to try on this phone, best first: the NPU (its SoC's file compiled ahead of time, or else, on Qualcomm,
     * the GPU's file compiled on the phone), then the GPU (LiteRT's own path, then OpenGL ES, then fp32 when [roomy]),
     * then the CPU, which always remains. [available] is what LiteRT reports it can use here; [refused] holds the
     * [key]s of candidates that already failed on this phone with this app version, so a broken accelerator is not
     * retried on every start.
     */
    fun candidates(manifest: LayaManifest, socManufacturer: String?, socModel: String?, available: Set<LayaBackend>, refused: Set<String>,
        roomy: Boolean = false): List<LayaCandidate> {
        val vendor = vendor(socManufacturer, socModel)
        val soc = socModel?.let(::normalize)
        val compiled = manifest.files.firstOrNull { it.backend == "npu" && it.vendor == vendor && soc != null && it.soc?.let { f -> sameSoc(normalize(f), soc) } == true }
        val gpuFile = manifest.files.firstOrNull { it.backend == "gpu" }
        val npu = when {
            LayaBackend.NPU !in available -> null
            compiled != null -> LayaCandidate(LayaBackend.NPU, compiled)
            vendor in ON_DEVICE && gpuFile != null -> LayaCandidate(LayaBackend.NPU, gpuFile, onDevice = true)
            else -> null
        }
        val gpus = gpuFile?.takeIf { LayaBackend.GPU in available }?.let { f ->
            listOfNotNull(LayaCandidate(LayaBackend.GPU, f), LayaCandidate(LayaBackend.GPU, f, gpu = GpuPath.OPENGL),
                LayaCandidate(LayaBackend.GPU, f, gpu = GpuPath.FP32).takeIf { roomy })
        }.orEmpty()
        val cpu = manifest.files.firstOrNull { it.backend == "cpu" }
        return (listOfNotNull(npu) + gpus + listOfNotNull(cpu?.let { LayaCandidate(LayaBackend.CPU, it) }))
            .filter { key(it) !in refused || it.backend == LayaBackend.CPU }
    }

    /**
     * How a refused candidate is remembered: one file can serve two backends (the GPU's file also runs on the CPU), and
     * the GPU's other paths add theirs ("gpu:<file>#opengl"), so each is refused on its own.
     */
    fun key(candidate: LayaCandidate) = "${candidate.backend.key}:${candidate.file.name}${candidate.gpu.suffix}"

    /** LiteRT's NPU vendor for a `Build.SOC_MANUFACTURER` / `Build.SOC_MODEL` pair, or null. */
    fun vendor(socManufacturer: String?, socModel: String?): String? {
        val maker = socManufacturer.orEmpty().lowercase()
        val model = normalize(socModel.orEmpty())
        return when {
            maker in setOf("qti", "qualcomm") || model.startsWith("SM") -> "qualcomm"
            maker.startsWith("mediatek") || model.startsWith("MT") -> "mediatek"
            maker == "google" || model.startsWith("TENSOR") -> "google_tensor"
            else -> null
        }
    }

    /** "Tensor G3", "Tensor_G3" and "TENSORG3" name one SoC. */
    fun normalize(soc: String): String = soc.uppercase().filter { it.isLetterOrDigit() }

    /**
     * A file compiled for [file] runs on [phone] when both name the same chip; phones may add a variant's letters
     * ("MT6989Z" for MT6989, "MT6893Z/CZA"), never more digits (MT69891 would be another chip).
     */
    fun sameSoc(file: String, phone: String): Boolean =
        phone == file || (file.isNotEmpty() && phone.startsWith(file) && phone.substring(file.length).all { it.isLetter() })
}

/**
 * A graph with fewer sequence lengths than the app's buckets: a row is padded to the next length it has. Padding is
 * masked, so the logits do not move (the check at load runs every bucket through this, against the same reference).
 */
class PaddedGraph(private val inner: LayaGraph, lengths: List<Int>, private val pad: Int) : LayaGraph by inner {
    private val lengths = lengths.sorted()

    override fun run(length: Int, inputIds: IntArray, keyMask: FloatArray, markers: FloatArray, qtype: FloatArray): FloatArray {
        val to = lengths.firstOrNull { it >= length } ?: length
        if (to == length) return inner.run(length, inputIds, keyMask, markers, qtype)
        val options = markers.size / length
        // markers are one-hot over positions, option by option: each option's row moves to its place in the longer layout.
        val padded = FloatArray(options * to)
        for (k in 0 until options) System.arraycopy(markers, k * length, padded, k * to, length)
        return inner.run(to, IntArray(to) { if (it < length) inputIds[it] else pad },
            FloatArray(to) { if (it < length) keyMask[it] else LayaInputs.MASKED }, padded, qtype)
    }
}
