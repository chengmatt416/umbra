package app.umbra.core.decision

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Deterministic fallback for [DecisionEngine]: lexical evidence with idf weighting for choices and
 * scales, and negation- and polarity-aware overlap for yes/no statements. It is never as good as
 * Laya, so it is honest about weak evidence: confidence stays near chance when nothing matches,
 * which makes the router escalate instead of guessing.
 */
class HeuristicDecisionEngine : DecisionEngine {
    override val name: String = "heuristic"

    override suspend fun isAvailable(): Boolean = true

    override suspend fun decide(request: DecisionRequest): DecisionResponse {
        val started = System.nanoTime()
        val stateTokens = Text.tokens(request.state)
        val answers = request.questions.mapValues { (_, question) ->
            when (question) {
                is Question.Choice -> choice(request.state, stateTokens, question)
                is Question.Score -> score(request.state, stateTokens, question)
                is Question.Noul -> noul(stateTokens, question)
            }
        }
        return DecisionResponse(answers, name, (System.nanoTime() - started) / 1_000_000)
    }

    private class Scored(val probabilities: List<Double>, val evidence: Double)

    private fun distribution(state: String, stateTokens: List<String>, intent: String?, options: List<Option>): Scored {
        val optionTokens = options.map { Text.tokens(it.id + " " + it.description).toSet() }
        val stateSet = stateTokens.filterNot(Text::isNegation).toSet()
        val intentSet = intent?.let { Text.tokens(it).filterNot(Text::isNegation).toSet() }.orEmpty()
        val n = options.size
        val df = HashMap<String, Int>()
        for (tokens in optionTokens) for (t in tokens) df.merge(t, 1, Int::plus)

        val raw = DoubleArray(n)
        for (i in 0 until n) {
            for (t in optionTokens[i]) {
                val idf = ln(1.0 + n.toDouble() / df.getValue(t))
                if (t in stateSet) raw[i] += idf
                if (t in intentSet) raw[i] += INTENT_WEIGHT * idf
            }
        }
        // An option id named in the text is strong evidence, but only when it singles one option out:
        // a screen that lists every button says nothing about which one to press.
        addUniqueMention(raw, options, state.lowercase())
        intent?.let { addUniqueMention(raw, options, it.lowercase()) }

        val evidence = raw.sum()
        if (evidence <= 0.0) return Scored(List(n) { 1.0 / n }, 0.0)
        val scaled = raw.map { it * TEMPERATURE }
        val maxScaled = scaled.max()
        val exps = scaled.map { exp(it - maxScaled) }
        val total = exps.sum()
        return Scored(exps.map { it / total }, evidence)
    }

    private fun addUniqueMention(raw: DoubleArray, options: List<Option>, text: String) {
        // Short ids ("ok") must count too, otherwise a longer id looks uniquely mentioned by comparison.
        val mentioned = options.indices.filter { i ->
            val id = options[i].id.lowercase()
            id.length >= 2 && Regex("(^|[^\\p{L}\\p{N}])${Regex.escape(id)}($|[^\\p{L}\\p{N}])").containsMatchIn(text)
        }
        if (mentioned.size == 1) raw[mentioned.single()] += 2.0
    }

    private fun confidenceOf(p: List<Double>, evidence: Double): Double {
        val n = p.size
        val sorted = p.sortedDescending()
        var c = sorted[0]
        if (n > 1 && sorted[0] - sorted[1] < 0.1) c *= 0.7
        // Weak evidence can never be trusted, however the softmax happens to fall.
        if (evidence < MIN_EVIDENCE) c = minOf(c, 1.0 / n + 0.1 * evidence)
        return c.coerceIn(0.0, 0.99)
    }

    private fun choice(state: String, stateTokens: List<String>, q: Question.Choice): Answer.ChoiceAnswer {
        val d = distribution(state, stateTokens, q.intent, q.options)
        val best = d.probabilities.indices.maxBy { d.probabilities[it] }
        return Answer.ChoiceAnswer(
            choice = q.options[best].id,
            probabilities = q.options.indices.associate { q.options[it].id to d.probabilities[it] },
            confidence = confidenceOf(d.probabilities, d.evidence),
        )
    }

    private fun score(state: String, stateTokens: List<String>, q: Question.Score): Answer.ScoreAnswer {
        val d = distribution(state, stateTokens, null, q.levels)
        val expected = d.probabilities.indices.sumOf { it * d.probabilities[it] }
        return Answer.ScoreAnswer(expected, d.probabilities, confidenceOf(d.probabilities, d.evidence))
    }

    /**
     * Evidence for a statement: each content token of the statement counts for it when the state
     * mentions it, and against it when the state negates it ("no crash") or mentions the opposite
     * polarity ("saved" against "failed"). Polarity words weigh double: they carry the meaning.
     */
    private fun noul(stateTokens: List<String>, q: Question.Noul): Answer.NoulAnswer {
        val statement = Text.tokens(q.instructions)
        val negated = statement.any(Text::isNegation)
        val content = statement.filterNot(Text::isNegation).distinct()
        if (content.isEmpty()) return Answer.NoulAnswer(0.5, 0.0)

        val stateSet = stateTokens.toSet()
        var total = 0.0
        var score = 0.0
        for (t in content) {
            val w = if (t in POLARITY) 2.0 else 1.0
            total += w
            val at = stateTokens.indices.filter { stateTokens[it] == t }
            val unnegated = at.any { i -> (maxOf(0, i - 2) until i).none { Text.isNegation(stateTokens[it]) } }
            when {
                unnegated -> score += w
                at.isNotEmpty() -> score -= w
                CONFLICTS[t]?.any { it in stateSet } == true -> score -= w
            }
        }
        val evidence = score / total
        var p = 0.5 + 0.48 * evidence
        if (negated) p = 1.0 - p
        var confidence = abs(p - 0.5) * 2
        if (content.size < 2) confidence *= 0.6
        return Answer.NoulAnswer(p.coerceIn(0.02, 0.98), confidence.coerceIn(0.0, 0.95))
    }

    private companion object {
        const val TEMPERATURE = 1.5
        const val MIN_EVIDENCE = 1.0
        const val INTENT_WEIGHT = 1.5
        val POLARITY = setOf("success", "error", "crash")
        val CONFLICTS = mapOf(
            "success" to setOf("error", "crash"),
            "error" to setOf("success"),
            "crash" to setOf("success"),
        )
    }
}
