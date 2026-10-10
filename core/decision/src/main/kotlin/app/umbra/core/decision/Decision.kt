package app.umbra.core.decision

/** One selectable label with the text the model reads to understand it. */
data class Option(val id: String, val description: String)

/**
 * The three typed primitives of Laya (spec §4): pick one of N, place on an ordered scale, or give
 * the probability that a statement is true. All are answered in a single forward pass.
 */
sealed interface Question {
    val instructions: String

    /**
     * Pick exactly one option. [intent] is what the pick is for ("press OK"); engines that reason
     * lexically (the heuristic fallback) use it as strong evidence, Laya reads it in [instructions].
     */
    data class Choice(
        val options: List<Option>,
        override val instructions: String,
        val intent: String? = null,
    ) : Question {
        init {
            require(options.size >= 2) { "a choice needs at least two options" }
            require(options.map { it.id }.toSet().size == options.size) { "option ids must be unique" }
        }
    }

    /** Place on an ordered scale; [levels] run from lowest to highest. */
    data class Score(val levels: List<Option>, override val instructions: String) : Question {
        init {
            require(levels.size >= 2) { "a score needs at least two levels" }
        }
    }

    /** Probability that the statement in [instructions] is true given the state. */
    data class Noul(override val instructions: String) : Question
}

data class DecisionRequest(
    /** Plain-text description of the situation. Callers keep it short; engines truncate it. */
    val state: String,
    val questions: Map<String, Question>,
) {
    init {
        require(questions.isNotEmpty()) { "at least one question" }
    }
}

sealed interface Answer {
    /** How sure the engine is, 0..1. The router compares this to its threshold. */
    val confidence: Double

    data class ChoiceAnswer(
        val choice: String,
        val probabilities: Map<String, Double>,
        override val confidence: Double,
    ) : Answer

    data class ScoreAnswer(
        /** Expected level index, 0 = lowest. */
        val score: Double,
        val distribution: List<Double>,
        override val confidence: Double,
    ) : Answer

    data class NoulAnswer(
        /** Probability the statement is true. */
        val probability: Double,
        override val confidence: Double,
    ) : Answer
}

data class DecisionResponse(
    val answers: Map<String, Answer>,
    val engine: String,
    val latencyMs: Long,
)

class DecisionEngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface DecisionEngine {
    val name: String

    /** Cheap liveness check; never throws. */
    suspend fun isAvailable(): Boolean

    /** @throws DecisionEngineException when the engine cannot answer. */
    suspend fun decide(request: DecisionRequest): DecisionResponse
}
