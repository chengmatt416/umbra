package app.umbra.tools

import app.umbra.core.decision.Candidate
import app.umbra.core.decision.DecisionRouter
import app.umbra.core.model.action.ActionRequest
import app.umbra.core.model.action.ObserveLevel
import app.umbra.core.model.action.Operation
import app.umbra.core.model.action.Target
import app.umbra.runtime.omp.ContentBlock
import app.umbra.runtime.omp.HostToolCall
import app.umbra.runtime.omp.HostToolDefinition
import app.umbra.runtime.omp.HostToolHandler
import app.umbra.runtime.omp.HostToolPayload
import app.umbra.runtime.omp.HostToolProgress
import app.umbra.runtime.omp.ToolLoadMode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A tool the host answers itself, outside the action pipeline: talking to other sessions, subagents, an agent's
 * memory and timers. [run] gets the raw call and returns what the model reads.
 */
class HostSideTool(val definition: HostToolDefinition, val run: suspend (call: HostToolCall, progress: HostToolProgress) -> HostToolPayload) {
    val name: String get() = definition.name
}

/**
 * The tools of one agent session, ready to hand to omp (`set_host_tools`): definitions for the model
 * and a handler that decodes each call, runs it through the [ActionPipeline] and renders the answer.
 * [extra] are tools the app answers itself ([HostSideTool]).
 */
class UmbraToolHost(
    private val pipeline: ActionPipeline,
    val sessionId: String?,
    private val decisions: DecisionRouter? = null,
    val state: SessionToolState = SessionToolState(sessionId),
    private val loadMode: (toolName: String) -> ToolLoadMode = { ToolLoadMode.ESSENTIAL },
    /** Tools to leave out, e.g. `gui_*` while no Linux desktop is configured. */
    private val exclude: (toolName: String) -> Boolean = { false },
    private val extra: List<HostSideTool> = emptyList(),
) {
    private val specs = HostToolRegistry.all.filterNot { exclude(it.name) }
    private val byName = specs.associateBy { it.name }
    private val extraByName = extra.associateBy { it.name }

    val toolNames: List<String> = specs.map { it.name } + extra.map { it.name }

    fun definitions(): List<HostToolDefinition> =
        specs.map { HostToolDefinition(it.name, it.description, it.schema, loadMode = loadMode(it.name)) } +
            extra.map { it.definition.copy(loadMode = loadMode(it.name)) }

    val handler: HostToolHandler = HostToolHandler { call, progress -> handle(call, progress) }

    suspend fun handle(call: HostToolCall, progress: HostToolProgress): HostToolPayload {
        extraByName[call.toolName]?.let { tool ->
            return try {
                tool.run(call, progress)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                HostToolPayload.error("${call.toolName} failed: ${e.message ?: e::class.simpleName}")
            }
        }
        val spec = byName[call.toolName] ?: return HostToolPayload.error("Unknown tool '${call.toolName}'.")
        val decoded = try {
            spec.decode(ToolArgs(call.arguments), DecodeContext(state, sessionId, call.toolCallId))
        } catch (e: ArgumentException) {
            return HostToolPayload.error("Invalid arguments for ${spec.name}: ${e.message}")
        }
        return when (decoded) {
            is DecodedCall.Decide -> decide(decoded)
            is DecodedCall.Action -> act(decoded)
        }
    }

    private suspend fun act(call: DecodedCall.Action): HostToolPayload {
        val exec = pipeline.execute(call.request, state)
        val payload = ResultRenderer.payload(exec)
        if (!call.observeAfter || !exec.result.isOk) return payload

        val observe = ActionRequest(
            id = call.request.id + ".after",
            operation = Operation.Observe(ObserveLevel.TREE),
            target = Target(call.request.target.environment, call.request.target.display),
            sessionId = sessionId,
            timeoutMs = 10_000,
        )
        val after = pipeline.execute(observe, state)
        val check = checkWithLaya(call.request.rationale, exec.result.summary, after.result.summary)
        return payload.copy(content = payload.content + ContentBlock.Text("\nScreen now:\n" + after.result.summary + check))
    }

    /**
     * Laya's quick look at whether the action did what it was for (its `reason`), so the model can move on without
     * reading the screen again itself. Only Laya's own answer is passed on: the built-in rules cannot judge a screen.
     */
    private suspend fun checkWithLaya(reason: String?, result: String, screen: String): String {
        val router = decisions ?: return ""
        if (reason.isNullOrBlank()) return ""
        val routed = runCatching {
            router.judge("The action achieved what it was for: $reason", "What was done: $result\nThe screen now:\n${screen.take(2_000)}")
        }.getOrNull() ?: return ""
        if (routed.usedFallback || routed.value == null) return ""
        val confidence = "%.2f".format(routed.confidence)
        return when {
            !routed.trusted -> "\nLaya could not tell whether it worked (confidence $confidence); check the screen above."
            routed.value == true -> "\nLaya's check: it worked (confidence $confidence)."
            else -> "\nLaya's check: it did not do what it was for (confidence $confidence); look at the screen above."
        }
    }

    private suspend fun decide(call: DecodedCall.Decide): HostToolPayload {
        val router = decisions ?: return HostToolPayload.error("The decision engine is not available; decide yourself.")
        return if (call.kind == "choice") {
            val routed = router.choose(call.question, call.context, call.options.map { (id, description) -> Candidate(id, description) })
            val picked = routed.value
            val text = when {
                picked == null -> "No decision available (${routed.reason}). Decide yourself."
                routed.trusted -> "Pick: \"${picked.id}\"${if (picked.description != picked.id) " (${picked.description})" else ""}. Confidence ${"%.2f".format(routed.confidence)} (${routed.engine})."
                else -> "Low confidence (${"%.2f".format(routed.confidence)}, ${routed.engine}): best guess \"${picked.id}\", but ${routed.reason}. Decide yourself."
            }
            HostToolPayload.text(
                text,
                buildJsonObject {
                    put("kind", "choice"); picked?.let { put("answer", it.id) }
                    put("confidence", routed.confidence); put("engine", routed.engine); put("trusted", routed.trusted)
                },
            )
        } else {
            val routed = router.judge(call.question, call.context)
            val yes = routed.value
            val text = when {
                yes == null -> "No decision available (${routed.reason}). Decide yourself."
                routed.trusted -> "${if (yes) "Yes" else "No"}. Confidence ${"%.2f".format(routed.confidence)} (${routed.engine})."
                else -> "Low confidence (${"%.2f".format(routed.confidence)}, ${routed.engine}): leaning ${if (yes) "yes" else "no"}, but ${routed.reason}. Decide yourself."
            }
            HostToolPayload.text(
                text,
                buildJsonObject {
                    put("kind", "yes_no"); yes?.let { put("answer", it) }
                    put("confidence", routed.confidence); put("engine", routed.engine); put("trusted", routed.trusted)
                },
            )
        }
    }
}
