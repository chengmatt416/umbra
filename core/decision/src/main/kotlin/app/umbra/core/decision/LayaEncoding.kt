package app.umbra.core.decision

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlinx.serialization.Serializable

/** The checkpoint values the native engine needs besides its weights (`laya-config.json`, written by convert.py). */
@Serializable
data class LayaConfig(
    val max_len: Int,
    val head_max_len: Int,
    val temperature: List<Double>,
    val temperature_by_options: Map<String, Double> = emptyMap(),
    val cls: Int,
    val sep: Int,
    val mask: Int,
    val pad: Int,
    val buckets: List<Int>,
    val max_options: Int,
)

/** One question row as the graph reads it: the sequence, where each option's `[MASK]` sits, and the question type. */
class LayaRow(val ids: List<Int>, val markers: List<Int>, val qtype: Int)

/**
 * Laya's sequence layout and answer decoding, ported from `laya.common` / `laya.onnx_agent` (0.4.15) so the native
 * engine answers a request exactly as the `laya-serve` sidecar answers the same request from [LayaHttpEngine]:
 * the state goes in as `{"body": state}` and each question is rendered into
 * `[CLS] <type> question: instructions [SEP] [MASK] option … [SEP] state [SEP]`.
 */
class LayaEncoding(private val tok: LayaTokenizer, val config: LayaConfig) {
    private val temperature = config.temperature.map(::clamp)
    private val byOptions = config.temperature_by_options.mapValues { clamp(it.value) }

    /** The rows for every question of [request], tokenizing the shared state once. */
    fun encode(request: DecisionRequest, maxStateChars: Int): Map<String, LayaRow> {
        val stateIds = tok.encode(pythonJson(mapOf("body" to request.state.take(maxStateChars))).replace(MASK_TEXT, " "))
        return request.questions.mapValues { (id, q) -> row(id, q, stateIds) }
    }

    private fun row(id: String, q: Question, stateIds: List<Int>): LayaRow {
        val options = render(q)
        if (options.size > config.max_options) throw DecisionEngineException("question '$id' has ${options.size} options; the native engine reads at most ${config.max_options}")
        val headIds = tok.encode("${type(q)} question: ${q.instructions.replace(MASK_TEXT, " ")}")
        var optIds = options.map { listOf(config.mask) + tok.encode(" " + it.replace(MASK_TEXT, " ")).take(48) }
        var budget = config.head_max_len - optIds.sumOf { it.size }
        if (budget < 16) {
            val per = max(4, (config.head_max_len - 16).floorDiv(max(1, optIds.size)))
            optIds = optIds.map { it.take(per) }
            budget = config.head_max_len - optIds.sumOf { it.size }
        }
        val ids = ArrayList<Int>(config.max_len)
        ids += config.cls
        ids += headIds.take(max(8, budget))
        ids += config.sep
        val markers = ArrayList<Int>()
        for (o in optIds) { markers += ids.size; ids += o }
        ids += config.sep
        val room = max(0, config.max_len - ids.size - 1)
        ids += stateIds.take(room)
        ids += config.sep
        val kept = markers.filter { it < config.max_len }
        if (kept.size != options.size) {
            throw DecisionEngineException("question '$id': only ${kept.size} of its ${options.size} option markers fit in ${config.max_len} tokens")
        }
        return LayaRow(ids.take(config.max_len), kept, qtype(q))
    }

    /** The smallest sequence length the converted graph was built for that holds [length] tokens. */
    fun bucket(length: Int): Int = config.buckets.sorted().firstOrNull { it >= length }
        ?: throw DecisionEngineException("a sequence of $length tokens is longer than ${config.buckets.max()}")

    /**
     * The answer from one row's option logits, as `ONNXAgent._decode_answers` computes it (float32 softmax over the
     * temperature-scaled logits, rounded to four places), in the shape [LayaHttpEngine] reads from the sidecar: the
     * reported `confidence` is Laya's normalized-entropy confidence for choice and score and max(p, 1 - p) for noul.
     */
    fun decode(q: Question, logits: FloatArray, k: Int): Answer {
        val qt = qtype(q)
        val t = (byOptions[bucketName(qt, k)] ?: temperature[qt]).toFloat()
        val z = FloatArray(k) { logits[it] / t }
        val top = z.max()
        val e = FloatArray(k) { exp(z[it] - top) }
        val sum = e.sum()
        val p = FloatArray(k) { e[it] / sum }
        val entropyConfidence = round4(entropyConfidence(p, k))
        return when (q) {
            is Question.Choice -> Answer.ChoiceAnswer(
                q.options[p.indices.maxBy { p[it] }].id,
                q.options.indices.associate { q.options[it].id to round4(p[it].toDouble()) },
                entropyConfidence,
            )
            is Question.Score -> Answer.ScoreAnswer(
                round4(p.indices.sumOf { it * p[it].toDouble() }),
                p.map { round4(it.toDouble()) },
                entropyConfidence,
            )
            is Question.Noul -> {
                val yes = p[1].toDouble()
                Answer.NoulAnswer(round4(yes), round4(max(yes, 1.0 - yes)))
            }
        }
    }

    private fun entropyConfidence(p: FloatArray, k: Int): Double {
        if (k < 2) return 1.0
        var ent = 0f
        for (i in 0 until k) ent += p[i] * ln(p[i].coerceIn(1e-12f, 1f))
        return (1.0 - (-ent.toDouble()) / ln(k.toDouble())).coerceIn(0.0, 1.0)
    }

    companion object {
        const val MASK_TEXT = "[MASK]"
        const val TEMP_MIN = 0.5
        const val TEMP_MAX = 5.0

        /** The noul criteria [LayaHttpEngine] sends, so both engines show the model the same option text. */
        private val NOUL = listOf("false: the statement is false", "true: the statement is true")

        fun type(q: Question) = when (q) { is Question.Choice -> "choice"; is Question.Score -> "score"; is Question.Noul -> "noul" }

        fun qtype(q: Question) = when (q) { is Question.Choice -> 0; is Question.Score -> 1; is Question.Noul -> 2 }

        /** `laya.common.render_options`: choice `id: description`, score `level i: description`, noul false then true. */
        fun render(q: Question): List<String> = when (q) {
            is Question.Choice -> q.options.map { if (it.description.isEmpty()) it.id else "${it.id}: ${it.description}" }
            is Question.Score -> q.levels.mapIndexed { i, level -> "level $i: ${level.description.ifEmpty { level.id }}" }
            is Question.Noul -> NOUL
        }

        fun bucketName(qtype: Int, k: Int): String =
            listOf("choice", "score", "noul")[qtype] + ":" + when { k <= 2 -> "2"; k <= 5 -> "3-5"; k <= 10 -> "6-10"; else -> "11+" }

        fun clamp(t: Double): Double = if (t.isNaN() || t.isInfinite()) 1.0 else t.coerceIn(TEMP_MIN, TEMP_MAX)

        /** Python's `round(x, 4)`: half-even on the exact binary value. */
        fun round4(x: Double): Double = BigDecimal(x).setScale(4, RoundingMode.HALF_EVEN).toDouble()

        /** `json.dumps(value, ensure_ascii=False)` for a map of strings, Laya's `serialize_state` of a dict state. */
        fun pythonJson(value: Map<String, String>): String = buildString {
            append('{')
            value.entries.forEachIndexed { i, (k, v) ->
                if (i > 0) append(", ")
                pythonString(k, this)
                append(": ")
                pythonString(v, this)
            }
            append('}')
        }

        private fun pythonString(s: String, out: StringBuilder) {
            out.append('"')
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> out.append("\\\"")
                    c == '\\' -> out.append("\\\\")
                    c == '\n' -> out.append("\\n")
                    c == '\r' -> out.append("\\r")
                    c == '\t' -> out.append("\\t")
                    c == '\b' -> out.append("\\b")
                    c == '\u000c' -> out.append("\\f")
                    c < ' ' -> out.append("\\u%04x".format(c.code))
                    // A lone surrogate cannot reach the tokenizer as UTF-8 (the sidecar refuses such a request).
                    c.isHighSurrogate() && (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) -> out.append('�')
                    c.isLowSurrogate() && (i == 0 || !s[i - 1].isHighSurrogate()) -> out.append('�')
                    else -> out.append(c)
                }
                i++
            }
            out.append('"')
        }
    }
}
