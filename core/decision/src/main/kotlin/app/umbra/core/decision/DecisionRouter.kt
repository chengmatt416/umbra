package app.umbra.core.decision

import app.umbra.core.model.Clock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

data class RouterConfig(
    val timeoutMs: Long = 1_500,
    /** A Laya answer below this is not trusted: the situation is handed to OMP. */
    val minConfidence: Double = 0.60,
    /** The heuristic fallback has to be more certain than Laya before it is trusted. */
    val minHeuristicConfidence: Double = 0.75,
    val maxStateChars: Int = 2_500,
    /** After this many consecutive failures the primary engine is skipped for [primaryCooldownMs]. */
    val primaryFailuresBeforeCooldown: Int = 3,
    val primaryCooldownMs: Long = 30_000,
)

/**
 * A routed decision. [value] is the engine's best answer; it is only to be acted on when
 * [escalate] is false. When true, the caller hands the situation to OMP and may use [value] as a hint.
 */
data class Routed<out T>(
    val value: T?,
    val confidence: Double,
    val engine: String,
    val usedFallback: Boolean,
    val escalate: Boolean,
    val reason: String,
) {
    val trusted: Boolean get() = value != null && !escalate
}

data class DecisionRecord(
    val kind: String,
    val summary: String,
    val answer: String,
    val confidence: Double,
    val engine: String,
    val escalated: Boolean,
    val latencyMs: Long,
)

/** Where decisions are recorded (the session timeline). */
fun interface DecisionSink {
    suspend fun record(record: DecisionRecord)

    companion object {
        val None = DecisionSink { }
    }
}

data class Candidate(val id: String, val description: String)

enum class ToolRoute { ANDROID_UI, SHELL, LINUX_GUI, PRIVILEGED }

enum class RecoveryAction { RETRY, ALTERNATIVE_TOOL, VISUAL_FALLBACK, SHELL_FALLBACK, PRIVILEGED_FALLBACK, REPLAN, ASK_USER }

data class RouterStats(
    val primaryAnswers: Int = 0,
    val fallbackAnswers: Int = 0,
    val escalations: Int = 0,
    val primaryFailures: Int = 0,
    val noAnswer: Int = 0,
)

/**
 * The fast reactive layer (spec §4): typed decisions that complement OMP instead of replacing it.
 * Each method builds one batched request, tries the primary engine (Laya) within a short timeout,
 * falls back to heuristics when it is unavailable, and sets [Routed.escalate] when nothing is
 * confident enough, which is the signal to ask OMP.
 */
class DecisionRouter(
    private val primary: DecisionEngine?,
    private val fallback: DecisionEngine = HeuristicDecisionEngine(),
    private val config: RouterConfig = RouterConfig(),
    private val sink: DecisionSink = DecisionSink.None,
    private val clock: Clock = Clock.System,
) {
    private val consecutivePrimaryFailures = AtomicInteger()
    private val primaryDownUntilMs = AtomicLong(0)
    private val counters = Counters()

    val stats: RouterStats get() = counters.snapshot()

    private class Counters {
        val primary = AtomicInteger(); val fallback = AtomicInteger(); val escalations = AtomicInteger()
        val failures = AtomicInteger(); val none = AtomicInteger()
        fun snapshot() = RouterStats(primary.get(), fallback.get(), escalations.get(), failures.get(), none.get())
    }

    // ---- the spec §4 decisions ----

    /** "Selecting the most appropriate next action." */
    suspend fun chooseNextAction(goal: String, situation: String, candidates: List<Candidate>): Routed<Candidate> =
        chooseFrom("next_action", "Which action moves toward the goal: $goal?", situation, candidates, intent = goal)

    /** "Choosing between candidate UI targets." */
    suspend fun chooseTarget(intent: String, screen: String, candidates: List<Candidate>): Routed<Candidate> =
        chooseFrom("target", "Which element should be used to: $intent?", screen, candidates, intent = intent)

    /** "Deciding whether an action succeeded." */
    suspend fun didActionSucceed(expectation: String, before: String, after: String): Routed<Boolean> =
        yesNo(
            kind = "action_succeeded",
            statement = "The action had its intended effect: $expectation",
            state = "BEFORE: ${before.take(config.maxStateChars / 2)}\nAFTER: ${after.take(config.maxStateChars / 2)}",
        )

    /** "Selecting retry or recovery behaviour." */
    suspend fun chooseRecovery(failure: String, available: Set<RecoveryAction> = RecoveryAction.entries.toSet()): Routed<RecoveryAction> {
        val options = RecoveryAction.entries.filter { it in available }.map { Option(it.name.lowercase(), RECOVERY_TEXT.getValue(it)) }
        require(options.size >= 2) { "need at least two recovery options" }
        val routed = chooseFrom("recovery", "What should be done after this failure?", failure, options.map { Candidate(it.id, it.description) })
        return routed.map { c -> RecoveryAction.valueOf(c.id.uppercase()) }
    }

    /** "Choosing between Android, shell, Linux GUI, or privileged actions", "routing work between tools". */
    suspend fun routeTool(task: String, available: Set<ToolRoute> = ToolRoute.entries.toSet()): Routed<ToolRoute> {
        val options = ToolRoute.entries.filter { it in available }.map { Candidate(it.name.lowercase(), ROUTE_TEXT.getValue(it)) }
        require(options.size >= 2) { "need at least two tool routes" }
        return chooseFrom("tool_route", "Which kind of tool fits this task?", task, options).map { c -> ToolRoute.valueOf(c.id.uppercase()) }
    }

    /** "Deciding whether a task should be escalated to OMP." True means: hand it to OMP. */
    suspend fun shouldEscalate(situation: String): Routed<Boolean> =
        yesNo("escalate_to_omp", "This needs multi-step planning, code understanding or deep reasoning rather than a quick reactive step", situation)

    /** Risk refinement for the safety layer: could this be irreversible, costly, or speak for the user? */
    suspend fun isRisky(description: String): Routed<Boolean> =
        yesNo("risky", "Doing this could cause irreversible harm, spend money, or send something on the user's behalf", description)

    /** Free-form choice among candidates, for the agent's own `decide` tool. */
    suspend fun choose(question: String, state: String, candidates: List<Candidate>): Routed<Candidate> =
        chooseFrom("custom_choice", question, state, candidates, intent = question)

    /** Free-form yes/no judgement, for the agent's own `decide` tool. */
    suspend fun judge(statement: String, state: String): Routed<Boolean> = yesNo("custom_judgement", statement, state)

    /** Several typed questions about one state in a single request (one forward pass). */
    suspend fun ask(request: DecisionRequest): Routed<Map<String, Answer>> =
        route("batch", request) { answers -> Extracted(answers, answers.values.minOfOrNull { it.confidence } ?: 0.0, "batch") }

    // ---- building blocks ----

    private suspend fun chooseFrom(
        kind: String,
        question: String,
        state: String,
        candidates: List<Candidate>,
        intent: String? = null,
    ): Routed<Candidate> {
        require(candidates.size >= 2) { "need at least two candidates (got ${candidates.size})" }
        val request = DecisionRequest(
            state = state.take(config.maxStateChars),
            questions = mapOf("pick" to Question.Choice(candidates.map { Option(it.id, it.description) }, question, intent)),
        )
        return route(kind, request) { answers ->
            val a = answers["pick"] as? Answer.ChoiceAnswer ?: throw DecisionEngineException("no choice answer")
            val picked = candidates.firstOrNull { it.id == a.choice } ?: throw DecisionEngineException("answer '${a.choice}' is not a candidate")
            Extracted(picked, a.confidence, picked.id)
        }
    }

    private suspend fun yesNo(kind: String, statement: String, state: String): Routed<Boolean> {
        val request = DecisionRequest(state.take(config.maxStateChars), mapOf("yes" to Question.Noul(statement)))
        return route(kind, request) { answers ->
            val a = answers["yes"] as? Answer.NoulAnswer ?: throw DecisionEngineException("no yes/no answer")
            Extracted(a.probability >= 0.5, a.confidence, if (a.probability >= 0.5) "yes" else "no")
        }
    }

    private class Extracted<T>(val value: T, val confidence: Double, val label: String)

    private suspend fun <T> route(kind: String, request: DecisionRequest, extract: (Map<String, Answer>) -> Extracted<T>): Routed<T> {
        val notes = mutableListOf<String>()
        val engine = primary
        if (engine != null) {
            if (clock.nowMs() < primaryDownUntilMs.get()) {
                notes += "${engine.name} is cooling down after repeated failures"
            } else {
                try {
                    val response = withTimeoutOrNull(config.timeoutMs) { engine.decide(request) }
                    if (response == null) {
                        notePrimaryFailure()
                        notes += "${engine.name} timed out after ${config.timeoutMs}ms"
                    } else {
                        val x = extract(response.answers)
                        consecutivePrimaryFailures.set(0)
                        counters.primary.incrementAndGet()
                        val escalate = x.confidence < config.minConfidence
                        val reason = if (escalate) "confidence ${fmt(x.confidence)} is below ${fmt(config.minConfidence)}" else "confident"
                        return finish(kind, request, Routed(x.value, x.confidence, response.engine, false, escalate, reason), x.label, response.latencyMs)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    notePrimaryFailure()
                    notes += "${engine.name} failed: ${e.message}"
                }
            }
        }

        return try {
            val response = fallback.decide(request)
            val x = extract(response.answers)
            counters.fallback.incrementAndGet()
            val escalate = x.confidence < config.minHeuristicConfidence
            val why = (notes + if (escalate) "heuristic confidence ${fmt(x.confidence)} is below ${fmt(config.minHeuristicConfidence)}" else "heuristic is confident").joinToString("; ")
            finish(kind, request, Routed(x.value, x.confidence, response.engine, true, escalate, why), x.label, response.latencyMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            counters.none.incrementAndGet()
            counters.escalations.incrementAndGet()
            finish(kind, request, Routed(null, 0.0, fallback.name, true, true, (notes + "no engine could answer: ${e.message}").joinToString("; ")), "none", 0)
        }
    }

    private suspend fun <T> finish(kind: String, request: DecisionRequest, routed: Routed<T>, label: String, latencyMs: Long): Routed<T> {
        if (routed.escalate && routed.value != null) counters.escalations.incrementAndGet()
        sink.record(
            DecisionRecord(
                kind = kind,
                summary = request.questions.values.first().instructions.take(160),
                answer = label,
                confidence = routed.confidence,
                engine = routed.engine,
                escalated = routed.escalate,
                latencyMs = latencyMs,
            ),
        )
        return routed
    }

    private fun notePrimaryFailure() {
        counters.failures.incrementAndGet()
        if (consecutivePrimaryFailures.incrementAndGet() >= config.primaryFailuresBeforeCooldown) {
            primaryDownUntilMs.set(clock.nowMs() + config.primaryCooldownMs)
            consecutivePrimaryFailures.set(0)
        }
    }

    private fun fmt(d: Double) = "%.2f".format(d)

    private companion object {
        val RECOVERY_TEXT = mapOf(
            RecoveryAction.RETRY to "try the same action again after a short wait because the failure looks transient",
            RecoveryAction.ALTERNATIVE_TOOL to "use a different tool for the same goal, for example shell instead of tapping the screen",
            RecoveryAction.VISUAL_FALLBACK to "use screenshot based visual targeting because the accessibility tree is missing or unreliable",
            RecoveryAction.SHELL_FALLBACK to "do it with a shell command because the interface cannot be operated",
            RecoveryAction.PRIVILEGED_FALLBACK to "use root or shell privileges because a permission or system restriction blocks the normal way",
            RecoveryAction.REPLAN to "the plan is wrong or stuck so escalate to the planner to re-plan",
            RecoveryAction.ASK_USER to "something needs the user such as a login, captcha, payment or permission",
        )
        val ROUTE_TEXT = mapOf(
            ToolRoute.ANDROID_UI to "interact with Android apps on screen: tap, type, scroll, open an app, read what is shown",
            ToolRoute.SHELL to "command line work: files, git, build, scripts, packages, compilers, text processing",
            ToolRoute.LINUX_GUI to "operate a graphical Linux desktop application such as a browser or IDE window",
            ToolRoute.PRIVILEGED to "system level changes needing root or shell privileges: settings, permissions, services, displays",
        )
    }
}

internal fun <T, R> Routed<T>.map(transform: (T) -> R): Routed<R> =
    Routed(value?.let(transform), confidence, engine, usedFallback, escalate, reason)
