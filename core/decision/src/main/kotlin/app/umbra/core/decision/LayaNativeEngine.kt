package app.umbra.core.decision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One loaded copy of Laya's converted graph on one accelerator (the app implements it with LiteRT), as
 * `runtime-build/laya/convert.py` exports it for a sequence bucket of [length] tokens and K option slots:
 * token ids, an additive key mask (0 for a token, [LayaInputs.MASKED] for padding), the option markers one-hot over
 * positions (K × length, row-major) and the question type one-hot (3). The result holds one logit per option slot.
 */
interface LayaGraph : AutoCloseable {
    /** Where it runs: "npu", "gpu" or "cpu". */
    val backend: String

    fun run(length: Int, inputIds: IntArray, keyMask: FloatArray, markers: FloatArray, qtype: FloatArray): FloatArray
}

/** The graph inputs for one row, padded to its bucket. */
class LayaInputs(val length: Int, val ids: IntArray, val keyMask: FloatArray, val markers: FloatArray, val qtype: FloatArray) {
    companion object {
        /** convert.py's MASKED: finite so a padded position that sees no key stays a number (fp16 holds it). */
        const val MASKED = -1e4f

        fun of(ids: List<Int>, markers: List<Int>, qtype: Int, length: Int, maxOptions: Int, pad: Int): LayaInputs {
            require(ids.size <= length && markers.size <= maxOptions)
            val onehot = FloatArray(maxOptions * length)
            markers.forEachIndexed { slot, position -> onehot[slot * length + position] = 1f }
            return LayaInputs(
                length,
                IntArray(length) { if (it < ids.size) ids[it] else pad },
                FloatArray(length) { if (it < ids.size) 0f else MASKED },
                onehot,
                FloatArray(3) { if (it == qtype) 1f else 0f },
            )
        }
    }
}

/**
 * Laya answered inside the app (ADR 0005, amended): the same typed questions as [LayaHttpEngine], tokenized and laid
 * out by [LayaEncoding], one graph pass per question on whichever accelerator [graph] was loaded on.
 */
class LayaNativeEngine(
    private val encoding: LayaEncoding,
    private val graph: LayaGraph,
    private val maxStateChars: Int = 3_000,
) : DecisionEngine {
    override val name: String = "laya"

    private val lock = Mutex()

    @Volatile private var closed = false

    override suspend fun isAvailable(): Boolean = !closed

    override suspend fun decide(request: DecisionRequest): DecisionResponse = withContext(Dispatchers.Default) {
        val started = System.nanoTime()
        val rows = encoding.encode(request, maxStateChars)
        val answers = LinkedHashMap<String, Answer>()
        lock.withLock {
            if (closed) throw DecisionEngineException("the native Laya engine was unloaded")
            for ((id, row) in rows) {
                val logits = try { pass(row) } catch (e: DecisionEngineException) { throw e }
                catch (e: Exception) { throw DecisionEngineException("laya (${graph.backend}) failed: ${e.message}", e) }
                answers[id] = encoding.decode(request.questions.getValue(id), logits, row.markers.size)
            }
        }
        DecisionResponse(answers, "$name-${graph.backend}", (System.nanoTime() - started) / 1_000_000)
    }

    /** One forward pass for [row], padded to its bucket. */
    fun pass(row: LayaRow): FloatArray {
        val config = encoding.config
        val inputs = LayaInputs.of(row.ids, row.markers, row.qtype, encoding.bucket(row.ids.size), config.max_options, config.pad)
        val logits = graph.run(inputs.length, inputs.ids, inputs.keyMask, inputs.markers, inputs.qtype)
        if (logits.size < row.markers.size || logits.take(row.markers.size).any { it.isNaN() || it.isInfinite() }) {
            throw DecisionEngineException("laya (${graph.backend}) returned invalid logits")
        }
        return logits
    }

    /** Releases the graph; later calls fail so the router falls back. */
    suspend fun close() = lock.withLock {
        closed = true
        graph.close()
    }
}
