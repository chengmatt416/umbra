package app.umbra.agent.data.laya

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
    /** "npu", "gpu" or "cpu". */
    val backend: String,
    val bytes: Long,
    val sha256: String,
    /** For NPU files: "qualcomm", "mediatek" or "google_tensor", and the SoC it was compiled for. */
    val vendor: String? = null,
    val soc: String? = null,
)

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

/** [onDevice]: an NPU candidate with no file compiled for this SoC, so LiteRT compiles the GPU's file on the phone. */
data class LayaCandidate(val backend: LayaBackend, val file: LayaFile, val onDevice: Boolean = false)

object LayaPlan {
    /**
     * Vendors whose NPU LiteRT can compile the whole graph for on the phone. Qualcomm's compiler has a builder for every
     * op in it; MediaTek's lacks gather, abs, reduce-max and divide, and Google Tensor takes only files compiled ahead
     * of time, so those two use the NPU only with such a file.
     */
    private val ON_DEVICE = setOf("qualcomm")

    /**
     * The files to try on this phone, best first: the NPU (its SoC's file compiled ahead of time, or else, on Qualcomm,
     * the GPU's file compiled on the phone), then the GPU, then the CPU, which always remains. [available] is what LiteRT reports it can use here; [refused] holds the [key]s of
     * candidates that already failed on this phone with this app version, so a broken accelerator is not retried on
     * every start.
     */
    fun candidates(manifest: LayaManifest, socManufacturer: String?, socModel: String?, available: Set<LayaBackend>, refused: Set<String>): List<LayaCandidate> {
        val vendor = vendor(socManufacturer, socModel)
        val soc = socModel?.let(::normalize)
        val compiled = manifest.files.firstOrNull { it.backend == "npu" && it.vendor == vendor && soc != null && it.soc?.let(::normalize) == soc }
        val gpuFile = manifest.files.firstOrNull { it.backend == "gpu" }
        val npu = when {
            LayaBackend.NPU !in available -> null
            compiled != null -> LayaCandidate(LayaBackend.NPU, compiled)
            vendor in ON_DEVICE && gpuFile != null -> LayaCandidate(LayaBackend.NPU, gpuFile, onDevice = true)
            else -> null
        }
        val gpu = gpuFile?.takeIf { LayaBackend.GPU in available }
        val cpu = manifest.files.firstOrNull { it.backend == "cpu" }
        return listOfNotNull(
            npu,
            gpu?.let { LayaCandidate(LayaBackend.GPU, it) },
            cpu?.let { LayaCandidate(LayaBackend.CPU, it) },
        ).filter { key(it) !in refused || it.backend == LayaBackend.CPU }
    }

    /** How a refused candidate is remembered: one file can serve two backends (the GPU's file also runs on the CPU). */
    fun key(candidate: LayaCandidate) = "${candidate.backend.key}:${candidate.file.name}"

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
}
