package app.umbra.core.orchestrator

import app.umbra.core.model.action.ActionRequest
import app.umbra.core.model.action.FileAction
import app.umbra.core.model.action.Operation
import app.umbra.core.safety.ActionContext
import app.umbra.core.safety.SafetyGate
import app.umbra.runtime.omp.UiRequest
import app.umbra.runtime.omp.UiRequestHandler
import app.umbra.runtime.omp.UiResponse
import java.util.concurrent.atomic.AtomicLong

/**
 * An omp tool-approval prompt, as omp words it (`formatApprovalPrompt` in omp's `tools/approval.ts`):
 *
 * ```
 * Allow tool: bash
 * Reason: Prompt required by bash pattern: sudo *      (only when a rule asked)
 * Command: git push origin main                        (tool-specific detail lines)
 * ```
 */
data class OmpApprovalPrompt(val tool: String, val reason: String?, val details: List<String>, val raw: String) {
    /** The command of a `bash` call; multi-line commands keep their lines. */
    val command: String?
        get() {
            val index = details.indexOfFirst { it.startsWith("Command: ") }
            if (index < 0) return null
            return (listOf(details[index].removePrefix("Command: ")) + details.drop(index + 1)).joinToString("\n")
        }

    /** The value of a `Label: value` detail line. */
    fun detail(label: String): String? = details.firstOrNull { it.startsWith("$label: ") }?.removePrefix("$label: ")

    companion object {
        const val PREFIX = "Allow tool: "

        fun parse(title: String): OmpApprovalPrompt? {
            if (!title.startsWith(PREFIX)) return null
            val lines = title.lines()
            val tool = lines.first().removePrefix(PREFIX).trim()
            if (tool.isEmpty()) return null
            var reason: String? = null
            val details = ArrayList<String>()
            for (line in lines.drop(1)) {
                if (reason == null && details.isEmpty() && line.startsWith("Reason: ")) reason = line.removePrefix("Reason: ") else details += line
            }
            return OmpApprovalPrompt(tool, reason, details, title)
        }
    }
}

/** Answers a question omp (or one of its tools) puts to the user in the middle of a task. */
fun interface UserQuestions {
    suspend fun ask(sessionId: String, request: UiRequest): UiResponse
}

/**
 * omp asks the host before it runs tools its approval mode does not allow by itself. The host does not
 * show omp's yes/no to the user directly: it translates the call into the same risk assessment every
 * other action gets, so one policy decides (spec §28). Harmless commands go through silently, the
 * agent's own safety system is protected, and the rest is put to the user with a plain description.
 *
 * Every other dialog (`ask`, `input`, `confirm`, ...) is a question from the agent to the user and goes
 * to [questions].
 */
class OmpApprovalBridge(
    private val sessionId: String,
    private val gate: SafetyGate,
    private val context: suspend () -> ActionContext,
    private val questions: UserQuestions,
    /** Non-null during an emergency input lockdown: every tool omp asks about is denied (§8.2). */
    private val fence: () -> String? = { null },
    /** Connector-first routing (§9.1): a route returned here refuses the UI-driving call. */
    private val route: suspend (OmpApprovalPrompt) -> ConnectorRouting.Route? = { null },
    /** Tells the model why a call was routed away (omp itself only reports a denial). */
    private val onRouted: suspend (ConnectorRouting.Route) -> Unit = {},
) : UiRequestHandler {
    private val counter = AtomicLong()

    override suspend fun handle(request: UiRequest): UiResponse {
        val prompt = (request as? UiRequest.Select)?.takeIf { APPROVE in it.options && DENY in it.options }?.let { OmpApprovalPrompt.parse(it.title) }
            ?: return questions.ask(sessionId, request)
        if (fence() != null) return UiResponse.Value(DENY)
        route(prompt)?.let { routed ->
            onRouted(routed)
            return UiResponse.Value(DENY)
        }
        val action = ActionRequest(
            id = "omp-${prompt.tool}-${counter.incrementAndGet()}",
            operation = operationFor(prompt),
            sessionId = sessionId,
            rationale = prompt.reason,
        )
        val ctx = context().copy(actionDescription = "Coding tool ${prompt.tool}: ${prompt.details.joinToString("\n")}")
        return when (gate.check(action, ctx, app.umbra.core.safety.ApprovalSource.OMP)) {
            is SafetyGate.Outcome.Proceed -> UiResponse.Value(if (fence() == null) APPROVE else DENY)
            is SafetyGate.Outcome.Blocked -> UiResponse.Value(DENY)
        }
    }

    companion object {
        const val APPROVE = "Approve"
        const val DENY = "Deny"

        /**
         * How a tool call is judged: shell commands by the shell analyser, file tools by the path rules.
         * Code-execution and subagent tools run inside the sandboxed guest as the app user, so their risk
         * is bounded by it; they are labelled, not analysed.
         */
        internal fun operationFor(prompt: OmpApprovalPrompt): Operation = when (prompt.tool) {
            "bash" -> Operation.Shell(prompt.command ?: prompt.details.joinToString("\n").ifBlank { prompt.tool })
            "ssh" -> Operation.Shell("ssh ${prompt.details.joinToString(" ")}".trim())
            "write", "edit", "ast_edit", "notebook" -> Operation.File(FileAction.WRITE, pathOf(prompt))
            "read", "grep", "glob", "find", "ast_grep" -> Operation.File(FileAction.READ, pathOf(prompt))
            else -> Operation.Shell(prompt.tool)
        }

        private fun pathOf(prompt: OmpApprovalPrompt): String =
            (prompt.detail("Path") ?: prompt.detail("File") ?: prompt.details.firstOrNull().orEmpty()).substringBefore(" (").trim()
    }
}
