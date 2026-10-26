package app.umbra.agent.data.laya

import android.content.Context
import android.os.Build
import android.util.Log
import app.umbra.core.decision.LayaConfig
import app.umbra.core.decision.LayaEncoding
import app.umbra.core.decision.LayaGraph
import app.umbra.core.decision.LayaInputs
import app.umbra.core.decision.LayaNativeEngine
import app.umbra.core.decision.LayaTokenizer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.abs
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Laya inside the app (ADR 0005, amended 2026-10): the typed-decisions checkpoint converted to LiteRT, run on the
 * phone's NPU when there is a file compiled for its SoC, else on its GPU, else on its CPU ([LayaPlan]). Files come
 * from R2, are resumed when interrupted and are checked against the sizes and SHA-256 the APK carries; Qualcomm's
 * NPU runtime libraries ship inside the APK itself (their licence allows only that).
 *
 * The model itself loads and runs in its own process ([LayaService]): a driver that aborts, or the low-memory killer
 * taking the biggest process, ends only that one, never the app.
 */
class LayaNative(private val context: Context, private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("laya_native", Context.MODE_PRIVATE)

    val manifest: LayaManifest by lazy { json.decodeFromString(LayaManifest.serializer(), asset("laya/laya-native.json")) }

    private val encoding: LayaEncoding by lazy {
        LayaEncoding(LayaTokenizer.fromJson(asset("laya/tokenizer.json")), json.decodeFromString(LayaConfig.serializer(), asset("laya/laya-config.json")))
    }

    private val dir: File get() = File(context.filesDir, "laya/${manifest.revision.take(12)}")

    /** A download in progress: which file and how far. */
    data class Progress(val file: String, val have: Long, val total: Long)

    private val mutableProgress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = mutableProgress.asStateFlow()

    /** What answered the last load, and why the better accelerators did not. [died] completes if its process dies. */
    data class Loaded(val engine: LayaNativeEngine, val backend: LayaBackend, val file: String, val checkMs: Long, val skipped: List<String>,
        val died: Deferred<LayaExit>, val process: LayaProcess? = null)

    /**
     * What Settings shows for one processor, kept across starts: whether LiteRT reports it on this phone, whether Laya
     * has a file for it, and how its last try went.
     */
    data class Processor(val backend: LayaBackend, val status: Status, val detail: String?) {
        enum class Status {
            /** Laya answers on it now. */
            IN_USE,
            /** It passed its check before (or would be tried first) and is not refused. */
            READY,
            /** Its last try failed; [detail] says why. Not tried again until the next app version or a reset. */
            REFUSED,
            /** Skipped: not downloaded, not enough memory... ([detail]). */
            SKIPPED,
            /** Not on this phone, or no file of Laya runs on it here. */
            UNAVAILABLE,
            /** Not looked at yet (Laya has not started on this phone). */
            UNKNOWN,
        }
    }

    class NeedsDownload(val bytes: Long) : IOException("Laya's files are not downloaded")

    /** True when some file this phone could run is already here and verified. */
    fun downloaded(): Boolean = manifest.files.any { verified(it) }

    /** Bytes still to download for the best file this phone can use. Sizes only (Settings asks while it draws). */
    fun bytesToDownload(): Long = plan(available = setOf(LayaBackend.NPU, LayaBackend.GPU)).firstOrNull()?.file?.let { f ->
        if (File(dir, f.name).length() == f.bytes) 0L else f.bytes - (File(dir, f.name + ".part").takeIf { it.isFile }?.length() ?: 0L)
    } ?: 0L

    /**
     * Loads the best backend that both opens and reproduces the reference logits, downloading its file when [download]
     * allows it. A backend that fails is remembered for this app version and skipped next time; the CPU always stays.
     */
    suspend fun load(download: Boolean): Loaded = withContext(Dispatchers.IO) {
        // A backend whose load ended the app last time (before Laya had its own process, or the app went down with it)
        // is refused before it can do that again on every start.
        prefs.getString(KEY_LOADING, null)?.let { crashed ->
            prefs.edit().remove(KEY_LOADING).commit()
            if (!crashed.startsWith("cpu:")) refuse(crashed, "the app stopped while it loaded")
            else throw IOException("Laya stopped the app while loading on the CPU last time (not enough memory?); tap Start to try again")
        }
        val memory = android.app.ActivityManager.MemoryInfo().also { context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }
        if (memory.totalMem < MIN_TOTAL_MEMORY) {
            throw IOException("Laya needs a phone with at least ${MIN_TOTAL_MEMORY / (1L shl 30)} GB of memory; this one has ${memory.totalMem / (1L shl 20)} MB")
        }
        // What LiteRT can use here, asked in Laya's own process: loading its native libraries is the first thing that
        // can take a process down on an odd phone.
        val available = accelerators()
        val candidates = plan(available)
        val skipped = ArrayList<String>()
        LayaBackend.entries.filter { b -> candidates.none { it.backend == b } }.forEach { b -> note(b, Outcome.UNAVAILABLE, unavailableWhy(b, available)) }
        for (candidate in candidates) {
            // On the CPU the runtime unpacks the fp16 weights (about 2.5 GB while loading, measured on a 4 GB emulator
            // where the low-memory killer ended the app): too much for a phone with less memory.
            if (candidate.backend == LayaBackend.CPU && memory.totalMem < MIN_CPU_MEMORY) {
                val why = "needs ${MIN_CPU_MEMORY / (1L shl 30)} GB of memory"
                skipped += "cpu: $why"
                note(LayaBackend.CPU, Outcome.SKIPPED, why)
                continue
            }
            val file = File(dir, candidate.file.name)
            if (!verified(candidate.file)) {
                if (!download) {
                    // A better file that is not here yet waits for the next download; one that is here may answer now.
                    if (candidates.any { it !== candidate && verified(it.file) }) {
                        skipped += "${candidate.backend.key}: not downloaded"
                        note(candidate.backend, Outcome.SKIPPED, "not downloaded")
                        continue
                    }
                    throw NeedsDownload(candidate.file.bytes)
                }
                fetch(candidate.file)
            }
            // Written before the load and cleared after it, synchronously: if the app dies in between, the next load
            // refuses this backend (above).
            prefs.edit().putString(KEY_LOADING, LayaPlan.key(candidate)).commit()
            var process: LayaProcess? = null
            try {
                process = LayaProcess.start(context)
                process.open(file, candidate.backend, candidate.onDevice)
                val graph = process.graph(candidate.backend)
                val checkMs = check(graph, candidate.backend)
                prefs.edit().remove(KEY_LOADING).putString(KEY_LAST, LayaPlan.key(candidate)).commit()
                note(candidate.backend, Outcome.PASSED, "${checkMs} ms per check")
                return@withContext Loaded(LayaNativeEngine(encoding, graph), candidate.backend, candidate.file.name, checkMs, skipped, process.died, process)
            } catch (e: kotlinx.coroutines.CancellationException) {
                process?.stop()
                prefs.edit().remove(KEY_LOADING).commit()
                throw e
            } catch (e: Exception) {
                process?.stop()
                prefs.edit().remove(KEY_LOADING).commit()
                Log.w(TAG, "laya ${candidate.backend.key} refused", e)
                val compileDied = candidate.onDevice && e is LayaProcessDied
                val why = if (compileDied) "compiling for this chip's NPU ended Laya's process (${(e as LayaProcessDied).reason.text})"
                    else e.message?.lineSequence()?.firstOrNull()?.take(160) ?: e.javaClass.simpleName
                skipped += "${candidate.backend.key}: $why"
                // The same compile of the same file dies the same way after an app update too: it waits for Retry.
                if (compileDied) prefs.edit().putStringSet(KEY_COMPILE_DIED, prefs.getStringSet(KEY_COMPILE_DIED, emptySet()).orEmpty() + "${LayaPlan.key(candidate)}@${candidate.file.sha256}").apply()
                // Android ending the process for memory is not the accelerator's fault, but it would happen again on
                // the next start just the same: every failure refuses the accelerator; the CPU always stays.
                if (candidate.backend != LayaBackend.CPU) refuse(LayaPlan.key(candidate), why)
                else note(LayaBackend.CPU, Outcome.FAILED, why)
            }
        }
        throw IOException("Laya could not run on this phone (${skipped.joinToString("; ")})")
    }

    /**
     * Laya's process died while it answered (see [Loaded.died]). A crash refuses that accelerator, so the next start uses
     * the next one; Android ending it for memory does not.
     */
    fun died(loaded: Loaded, exit: LayaExit) {
        val key = "${loaded.backend.key}:${loaded.file}"
        when {
            exit.crashed && loaded.backend != LayaBackend.CPU -> refuse(key, "stopped while answering: ${exit.text}")
            else -> note(loaded.backend, Outcome.FAILED, "stopped while answering: ${exit.text}")
        }
    }

    /** Debug builds: crashes the loaded model's process (to check that the app survives it). */
    fun crashForTest(loaded: Loaded) { loaded.process?.crash() }

    /** NPU, GPU and CPU as Settings shows them; [inUse] is where Laya answers now, if it does. Reads only saved state. */
    fun processors(inUse: String?): List<Processor> {
        val available = prefs.getStringSet(KEY_AVAILABLE, null)?.mapNotNull { k -> LayaBackend.entries.firstOrNull { it.key == k } }?.toSet()
        val refused = refused()
        val plan = runCatching { plan(available ?: LayaBackend.entries.toSet(), honourRefusals = false) }.getOrDefault(emptyList())
        return LayaBackend.entries.map { b ->
            val saved = prefs.getString("$KEY_STATUS${b.key}", null)?.split('|', limit = 2)
            val outcome = saved?.getOrNull(0)?.let { o -> Outcome.entries.firstOrNull { it.name == o } }
            val detail = saved?.getOrNull(1)
            when {
                inUse == b.key -> Processor(b, Processor.Status.IN_USE, detail)
                refused.any { it.startsWith("${b.key}:") } -> Processor(b, Processor.Status.REFUSED, detail ?: "failed its check")
                available != null && b !in available -> Processor(b, Processor.Status.UNAVAILABLE, unavailableWhy(b, available))
                plan.none { it.backend == b } -> Processor(b, Processor.Status.UNAVAILABLE, unavailableWhy(b, available ?: LayaBackend.entries.toSet()))
                outcome == Outcome.UNAVAILABLE -> Processor(b, Processor.Status.UNAVAILABLE, detail)
                outcome == Outcome.SKIPPED -> Processor(b, Processor.Status.SKIPPED, detail)
                outcome == Outcome.FAILED -> Processor(b, Processor.Status.REFUSED, detail)
                outcome == Outcome.PASSED -> Processor(b, Processor.Status.READY, detail)
                else -> Processor(b, Processor.Status.UNKNOWN, null)
            }
        }
    }

    /** The phone's chip, as Android names it ("Qualcomm SM8750"), for Settings. */
    fun chip(): String? = listOfNotNull(Build.SOC_MANUFACTURER.takeIf { it.isNotBlank() && it != Build.UNKNOWN }, Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN })
        .joinToString(" ").ifBlank { null }

    /** Forgets refused accelerators and their status, so the next start tries them all again (the files stay). */
    fun resetProcessors() {
        prefs.edit().remove(refusedKey()).remove(KEY_COMPILE_DIED).apply {
            LayaBackend.entries.forEach { remove("$KEY_STATUS${it.key}") }
        }.apply()
    }

    private enum class Outcome { PASSED, FAILED, SKIPPED, UNAVAILABLE }

    private fun note(backend: LayaBackend, outcome: Outcome, detail: String?) {
        prefs.edit().putString("$KEY_STATUS${backend.key}", outcome.name + "|" + detail.orEmpty().take(200)).apply()
    }

    private fun unavailableWhy(backend: LayaBackend, available: Set<LayaBackend>): String = when {
        backend !in available -> "not offered by this phone"
        backend == LayaBackend.NPU -> "no Laya build for this chip's NPU yet"
        else -> "no Laya file for it"
    }

    /** Asks a short-lived `:laya` process what LiteRT can use here, and remembers it for Settings. */
    private fun accelerators(): Set<LayaBackend> {
        val process = try { LayaProcess.start(context) } catch (e: Exception) { throw IOException("Laya's process could not start: ${e.message}", e) }
        try {
            val names = try { process.accelerators() } catch (e: LayaProcessDied) {
                throw IOException("LiteRT could not start on this phone (${e.reason.text})", e)
            }
            prefs.edit().putStringSet(KEY_AVAILABLE, names).apply()
            return names.mapNotNull { k -> LayaBackend.entries.firstOrNull { it.key == k } }.toSet()
        } finally {
            process.stop()
        }
    }

    /** Deletes the downloaded files and forgets refused accelerators. */
    fun clear() {
        File(context.filesDir, "laya").deleteRecursively()
        prefs.edit().clear().apply()
    }

    private fun plan(available: Set<LayaBackend>, honourRefusals: Boolean = true) =
        LayaPlan.candidates(manifest, Build.SOC_MANUFACTURER, Build.SOC_MODEL, available, if (honourRefusals) refused() else emptySet())

    private fun refused(): Set<String> = prefs.getStringSet(refusedKey(), emptySet()).orEmpty() +
        prefs.getStringSet(KEY_COMPILE_DIED, emptySet()).orEmpty().mapNotNull { entry ->
            val key = entry.substringBefore('@')
            key.takeIf { manifest.files.any { f -> key.endsWith(":${f.name}") && f.sha256 == entry.substringAfter('@') } }
        }

    private fun refuse(key: String, why: String) {
        prefs.edit().putStringSet(refusedKey(), refused() + key).apply()
        LayaBackend.entries.firstOrNull { key.startsWith("${it.key}:") }?.let { note(it, Outcome.FAILED, why) }
    }

    private fun refusedKey() = "refused-" + context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    /**
     * Runs the manifest's reference row and compares the logits; returns how long the pass took. The row is run in
     * every sequence bucket (padding is masked, so the logits must not move): a bucket the check skipped would first be
     * compiled and run in the middle of a task, where a driver that cannot take it would end Laya's process.
     */
    private fun check(graph: LayaGraph, backend: LayaBackend): Long {
        val c = manifest.check
        val k = c.markers.size
        val tolerance = c.tolerance[backend.key] ?: 0.5f
        var ms = 0L
        for (length in (encoding.config.buckets + c.length).distinct().filter { it >= c.ids.size }.sorted()) {
            val inputs = LayaInputs.of(c.ids, c.markers, c.qtype, length, encoding.config.max_options, encoding.config.pad)
            val started = System.nanoTime()
            val logits = graph.run(inputs.length, inputs.ids, inputs.keyMask, inputs.markers, inputs.qtype)
            if (length == c.length) ms = (System.nanoTime() - started) / 1_000_000
            val worst = (0 until k).maxOf { if (logits[it].isNaN()) Float.POSITIVE_INFINITY else abs(logits[it] - c.logits[it]) }
            val argmax = (0 until k).maxBy { logits[it] }
            val expected = (0 until k).maxBy { c.logits[it] }
            if (worst > tolerance || argmax != expected) {
                throw IOException("${backend.key} computed logits off by $worst at $length tokens (allowed $tolerance)")
            }
        }
        return ms
    }

    private fun verified(f: LayaFile): Boolean {
        val file = File(dir, f.name)
        if (!file.isFile || file.length() != f.bytes) return false
        val marker = "${file.length()}:${file.lastModified()}:${f.sha256}"
        if (prefs.getString("ok-${f.name}", null) == marker) return true
        val ok = sha256(file) == f.sha256
        if (ok) prefs.edit().putString("ok-${f.name}", marker).apply()
        return ok
    }

    private fun fetch(f: LayaFile) {
        val target = File(dir, f.name)
        target.parentFile?.mkdirs()
        // Files of other conversions are of no use any more.
        File(context.filesDir, "laya").listFiles().orEmpty().filter { it.name != dir.name }.forEach { it.deleteRecursively() }
        val part = File(dir, f.name + ".part")
        var have = if (part.isFile) part.length() else 0L
        if (have > f.bytes) { part.delete(); have = 0 }
        val needed = f.bytes - have + SPACE_MARGIN
        if (dir.usableSpace < needed) throw IOException("Laya needs ${needed / (1 shl 20)} MB free")
        mutableProgress.value = Progress(f.name, have, f.bytes)
        try {
            if (have < f.bytes) {
                val request = Request.Builder().url(manifest.base + f.name).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
                http.newCall(request).execute().use { r ->
                    when {
                        r.code == 206 && have > 0 -> Unit
                        r.code == 200 -> have = 0
                        else -> throw IOException("${f.name}: HTTP ${r.code}")
                    }
                    FileOutputStream(part, have > 0).use { out ->
                        val input = r.body.byteStream()
                        val buffer = ByteArray(256 * 1024)
                        var shown = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            have += n
                            if (have > f.bytes) throw IOException("${f.name}: more than ${f.bytes} bytes")
                            val t = System.nanoTime()
                            if (t - shown > 500_000_000) { mutableProgress.value = Progress(f.name, have, f.bytes); shown = t }
                        }
                    }
                }
            }
            if (part.length() != f.bytes) throw IOException("${f.name}: ${part.length()} of ${f.bytes} bytes")
            val digest = sha256(part)
            if (digest != f.sha256) {
                part.delete()
                throw IOException("${f.name}: SHA-256 $digest ≠ ${f.sha256}")
            }
            if (!part.renameTo(target)) throw IOException("rename ${part.name}")
            // Just checked: later looks at the file need not read all of it again.
            prefs.edit().putString("ok-${f.name}", "${target.length()}:${target.lastModified()}:${f.sha256}").apply()
        } finally {
            mutableProgress.value = null
        }
    }

    private fun asset(path: String) = context.assets.open(path).use { it.readBytes().decodeToString() }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "LayaNative"
        const val KEY_LAST = "last"
        const val KEY_LOADING = "loading"
        const val KEY_AVAILABLE = "accelerators"
        const val KEY_STATUS = "status-"
        /** On-device NPU compiles that ended Laya's process, per file: not retried after an update, only on Retry. */
        const val KEY_COMPILE_DIED = "compile-died"
        /** The fp16 model takes about 1–2 GB while it runs; below this the low-memory killer ends the app instead. */
        const val MIN_TOTAL_MEMORY = 3L shl 30
        const val MIN_CPU_MEMORY = 6L shl 30
        const val SPACE_MARGIN = 200L shl 20
    }
}
