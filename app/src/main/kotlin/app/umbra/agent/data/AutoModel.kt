package app.umbra.agent.data

import android.util.Log
import app.umbra.agent.AppContainer
import app.umbra.core.decision.Answer
import app.umbra.core.decision.DecisionRequest
import app.umbra.core.decision.Option
import app.umbra.core.decision.Question
import app.umbra.core.session.ModelRef
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Auto" as a decision: for each new task, Laya reads the task and picks the model of a connected account that suits
 * it (a light, fast one for a quick question; the strongest for long, multi-step or coding work). The pick is the
 * task's model from then on, so it does not change halfway. When Laya is not there (not downloaded, not running, or
 * slow to answer), the task gets no model of its own and the directory's recommendation answers, as before.
 */
class AutoModel(private val container: AppContainer) {

    /** The model for a new task, or null to leave it to the default (Auto's recommendation, or the chosen model). */
    suspend fun choose(task: String, pictures: Boolean): ModelRef? {
        val prefs = container.settings.value
        if (!prefs.autoModel) return null
        val choices = options(container.modelDirectory.directory.value, prefs, pictures)
        if (choices.size < 2) return choices.firstOrNull()?.ref
        if (container.laya.state.value != LayaSidecar.State.RUNNING) return null
        val request = DecisionRequest(state = state(task, pictures), questions = mapOf(QUESTION to question(choices)))
        val answer = withTimeoutOrNull(DECIDE_MS) { runCatching { container.laya.engine.decide(request) }.getOrNull() }
            ?.answers?.get(QUESTION) as? Answer.ChoiceAnswer
        val picked = answer?.let { a -> choices.firstOrNull { it.id == a.choice } }
        Log.i(TAG, "laya picked ${picked?.ref} (confidence ${answer?.confidence}) from ${choices.size}")
        return picked?.ref
    }

    /** One model Laya can choose: its option id, the model, and how it reads to Laya. */
    data class Choice(val id: String, val ref: ModelRef, val description: String)

    companion object {
        private const val TAG = "AutoModel"
        private const val QUESTION = "model"
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
