package app.umbra.agent.data

import android.util.Log
import app.umbra.agent.AppContainer
import app.umbra.core.decision.Answer
import app.umbra.core.decision.DecisionRequest
import app.umbra.core.decision.Option
import app.umbra.core.decision.Question
import app.umbra.core.session.Effort
import app.umbra.core.session.ModelRef
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Auto" as a decision: for each new task, Laya reads the task and picks the model of a connected account that suits
 * it (a light, fast one for a quick question; the strongest for long, multi-step or coding work), and how hard that
 * model thinks. Both are asked in one request. The picks are the task's from then on, so they do not change halfway.
 * When Laya is not there (not downloaded, not running, or slow to answer), the task gets no model of its own and the
 * directory's recommendation answers, as before, and its effort is omp's own per-turn `auto` judge.
 */
class AutoModel(private val container: AppContainer) {

    /** What a new task starts with: its model (null: the default answers) and its effort (null: omp's default). */
    data class TaskChoice(val model: ModelRef?, val effort: Effort?)

    suspend fun choose(task: String, pictures: Boolean): TaskChoice {
        val prefs = container.settings.value
        val directory = container.modelDirectory.directory.value
        val choices = if (prefs.autoModel) options(directory, prefs, pictures) else emptyList()
        val laya = container.laya.state.value == LayaSidecar.State.RUNNING
        val questions = buildMap {
            if (laya && choices.size >= 2) put(QUESTION, question(choices))
            if (laya && prefs.autoEffort) put(EFFORT_QUESTION, effortQuestion())
        }
        val answers = if (questions.isEmpty()) emptyMap() else withTimeoutOrNull(DECIDE_MS) {
            runCatching { container.laya.engine.decide(DecisionRequest(state = state(task, pictures), questions = questions)) }.getOrNull()
        }?.answers.orEmpty()

        val modelAnswer = answers[QUESTION] as? Answer.ChoiceAnswer
        val model = when {
            !prefs.autoModel -> null
            choices.size < 2 -> choices.firstOrNull()?.ref
            else -> modelAnswer?.let { a -> choices.firstOrNull { it.id == a.choice } }?.ref
        }
        val effortAnswer = answers[EFFORT_QUESTION] as? Answer.ChoiceAnswer
        // The model the task will run on decides which levels exist: Laya's pick, else what the default resolves to.
        val runsOn = model ?: (if (prefs.autoModel) container.modelDirectory.auto(prefs) else prefs.defaultModel)
        val effort = effort(prefs, effortAnswer?.choice, container.modelDirectory.model(runsOn)?.efforts.orEmpty())
        Log.i(TAG, "laya picked $model (confidence ${modelAnswer?.confidence}) from ${choices.size}, effort $effort (${effortAnswer?.choice})")
        return TaskChoice(model, effort)
    }

    /** One model Laya can choose: its option id, the model, and how it reads to Laya. */
    data class Choice(val id: String, val ref: ModelRef, val description: String)

    companion object {
        private const val TAG = "AutoModel"
        private const val QUESTION = "model"
        private const val EFFORT_QUESTION = "effort"
        /** Laya's three classes, as omp's own on-device judge maps them: trivial, moderate, hard → low, high, xhigh. */
        internal val EFFORT_BY_CLASS = mapOf("trivial" to "low", "moderate" to "high", "hard" to "xhigh")
        private const val DECIDE_MS = 4_000L
        /** Laya picks among this many at most: each account's recommended model first, then the rest in list order. */
        internal const val MAX_CHOICES = 12

        /** The models of the accounts and keys that are connected (a task with pictures: only those that read them). */
        internal fun options(directory: Directory, prefs: AppPrefs, pictures: Boolean): List<Choice> {
            val listed = ModelDirectory.connected(directory, prefs).flatMap { p ->
                p.models.filter { !pictures || it.images }.sortedByDescending { it.recommended }.map { m -> p to m }
            }
            // each account's recommendation, then the others, round-robin so no account fills the list alone
            val byProvider = listed.groupBy { it.first.id }.values.map { it.toMutableList() }
            val ordered = ArrayList<Pair<DirectoryProvider, DirectoryModel>>()
            while (byProvider.any { it.isNotEmpty() }) byProvider.forEach { if (it.isNotEmpty()) ordered += it.removeAt(0) }
            val fromDirectory = ordered.map { (p, m) ->
                val traits = listOfNotNull(
                    if (m.reasoning) "reasons step by step" else null,
                    if (m.images) "reads pictures" else null,
                    m.contextWindow?.let { "${it / 1000}k context" },
                    if (m.recommended) "${p.label}'s everyday model" else null,
                )
                ModelRef(p.id, m.id) to "${m.name} (${p.label})" + if (traits.isEmpty()) "" else ": " + traits.joinToString(", ")
            }
            val custom = prefs.providers.filter { it.custom && (!pictures || it.images) }.flatMap { p ->
                p.models.map { id -> ModelRef(p.id, id) to "$id (${p.label}, a compatible API)" }
            }
            return (fromDirectory + custom).distinctBy { it.first }.take(MAX_CHOICES)
                .mapIndexed { i, (ref, text) -> Choice("m$i", ref, text) }
        }

        /**
         * The effort a new task starts with. Auto: Laya's class mapped to a level the model has, or, without an
         * answer from Laya, omp's per-turn judge (`auto`). Not Auto: the level the user set, or none (omp's default).
         */
        internal fun effort(prefs: AppPrefs, layaClass: String?, supported: List<String>): Effort? {
            if (!prefs.autoEffort) return prefs.thinkingLevel?.let { Effort(it) }
            val level = layaClass?.let { EFFORT_BY_CLASS[it] } ?: return Effort(Effort.OMP_AUTO, auto = true)
            return Effort(Effort.clamp(level, supported), auto = true)
        }

        internal fun effortQuestion() = Question.Choice(
            options = listOf(
                Option("trivial", "Trivial: a quick answer, one setting, a short lookup"),
                Option("moderate", "Moderate: ordinary work with a few steps"),
                Option("hard", "Hard: long work with many steps, writing or fixing code, careful reasoning"),
            ),
            instructions = "How hard is this task for the model? This sets how long it thinks before it acts.",
            intent = "choose how hard the model thinks",
        )

        internal fun state(task: String, pictures: Boolean): String =
            "A person asked their phone's agent to do this task:\n" + task.take(1500) +
                if (pictures) "\n(A picture from the screen is attached.)" else ""

        internal fun question(choices: List<Choice>) = Question.Choice(
            options = choices.map { Option(it.id, it.description) },
            instructions = "Pick the model that should do this task. A quick question, a single setting or a short " +
                "lookup suits a light, fast model; a long task with many steps, writing or fixing code, or careful " +
                "reasoning suits the strongest model. Prefer an account's everyday model when the task is ordinary.",
            intent = "choose the model for a task",
        )
    }
}
