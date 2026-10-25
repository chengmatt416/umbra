package app.umbra.agent.agents.proactive

import app.umbra.core.model.Rect
import app.umbra.core.safety.OutboundDlpGuard
import kotlinx.serialization.Serializable

@Serializable
data class DemonstrationStep(val elapsedMs: Long, val packageName: String, val windowTitle: String,
    val resourceId: String?, val className: String?, val bounds: Rect, val action: String,
    val label: String, val description: String, val secretInput: Boolean, val keyframe: String? = null)

@Serializable
data class Demonstration(val id: String, val agentId: String, val startedAtMs: Long, val finishedAtMs: Long? = null,
    val steps: List<DemonstrationStep> = emptyList(), val status: String = "recording", val incomplete: Boolean = false,
    /** `display:0` (this phone) or `node:<id>` (a paired computer's screen, recorded from the user's takeover input). */
    val target: String = "display:0") {
    val remoteNode get() = target.removePrefix("node:").takeIf { target.startsWith("node:") }
    /** Recorded from the user's pointer and keys on a framebuffer (a computer or the Linux desktop), not an element tree. */
    val pointerOnly get() = remoteNode != null || target == LINUX_DESKTOP
    fun draft(now: Long): Pair<UmbraSkill, String> {
        require(status == "completed" && finishedAtMs != null && steps.isNotEmpty() && !incomplete) { "Finish an uninterrupted demonstration before extracting a skill" }
        if (pointerOnly) return pointerDraft(now)
        val parameters = mutableListOf<SkillParam>()
        val instructions = buildString {
            append("# Demonstrated workflow\nRead the current UI before every step; selectors and coordinates are evidence, not permission. Stop if the target or expected page differs. Never enter credentials through model tools or skill parameters.\n")
            steps.forEachIndexed { i, step ->
                append("\n${i + 1}. ${step.action} in ${step.packageName}; window ${step.windowTitle}; selector ${step.resourceId ?: step.className.orEmpty()}; label ${step.label}; bounds ${step.bounds}.")
                if (step.secretInput) append(" Hand control to the user for this secret input. Never request or store its value.")
                else if (step.action == "TYPE") {
                    val name = "input_${parameters.size + 1}"
                    parameters += SkillParam(name, description = "Non-secret input for ${step.packageName} ${step.resourceId.orEmpty()}")
                    append(" Use parameter {{").append(name).append("}} only for non-secret data; login or verification input requires user takeover.")
                }
                step.keyframe?.let { append(" Reference frame: $it.") }
            }
            append("\n\nVerify the final state independently. Ask for confirmation before sending, purchasing, deleting or changing account/security settings. Demonstrations do not grant approval. Source: $id.")
        }
        require(instructions.length <= 100_000)
        OutboundDlpGuard().requireSafe(instructions)
        return UmbraSkill("skill" + java.util.UUID.randomUUID().toString().replace("-", ""), "Demonstrated workflow", "Review and parameterize the recorded steps before testing.", agentId,
            ExecutionTarget.ANDROID_VISUAL, parameters, listOf("Observe and verify the final state against the user's task and demonstrated result"),
            "User handles credentials; review external communications, financial actions, deletion and account changes individually.",
            idempotencyRule = "Observe the current state and check for prior effects before every mutation", createdFrom = "demonstration:$id", updatedAtMs = now) to instructions
    }

    /** A computer demonstration has no element tree: pointer positions are relative evidence, typed text is never kept. */
    private fun pointerDraft(now: Long): Pair<UmbraSkill, String> {
        val nodeId = remoteNode
        val parameters = mutableListOf<SkillParam>()
        val instructions = buildString {
            if (nodeId != null) append("# Demonstrated computer workflow\nRun on the paired computer $nodeId with remote_screen. Take a fresh screenshot (operation=screenshot) before every step and find the described target; ")
            else append("# Demonstrated Linux desktop workflow\nRun on the Linux desktop with the gui_* tools (gui_screenshot, gui_click, gui_type, gui_key, gui_drag). Take a fresh gui_screenshot before every step and find the described target; ")
            append("positions are fractions of the recorded screen, evidence of where things were, not permission. Stop if the screen differs. Never enter passwords or codes: hand those to the user.\n")
            steps.forEachIndexed { i, step ->
                val w = step.description.substringBefore('x').toIntOrNull()?.coerceAtLeast(1) ?: 1
                val h = step.description.substringAfter('x').substringBefore(' ').toIntOrNull()?.coerceAtLeast(1) ?: 1
                fun at(x: Int, y: Int) = String.format(java.util.Locale.ROOT, "(%.2f, %.2f)", x.toFloat() / w, y.toFloat() / h)
                append("\n${i + 1}. ")
                when (step.action) {
                    "CLICK" -> append("Click at ${at(step.bounds.left, step.bounds.top)} of the screen.")
                    "DRAG" -> append("Drag from ${at(step.bounds.left, step.bounds.top)} to ${at(step.bounds.right, step.bounds.bottom)}.")
                    "KEY" -> append("Press ${step.label}.")
                    "TYPE" -> {
                        val name = "input_${parameters.size + 1}"
                        parameters += SkillParam(name, description = "Text typed on the computer at step ${i + 1} (never a password or code)")
                        append("Type {{").append(name).append("}} into the focused field; if it is a password, code or payment field, hand control to the user instead.")
                    }
                    else -> append(step.action)
                }
                append(" (${step.elapsedMs / 1000}s into the demonstration on ${step.windowTitle})")
            }
            append("\n\nVerify the final state independently with a screenshot. Ask for confirmation before sending, purchasing, deleting or changing account/security settings. Demonstrations do not grant approval. Source: $id.")
        }
        require(instructions.length <= 100_000)
        OutboundDlpGuard().requireSafe(instructions)
        return UmbraSkill("skill" + java.util.UUID.randomUUID().toString().replace("-", ""), if (nodeId != null) "Demonstrated computer workflow" else "Demonstrated Linux desktop workflow",
            "Review and parameterize the recorded steps before testing.", agentId,
            if (nodeId != null) ExecutionTarget.REMOTE_NODE else ExecutionTarget.LINUX_CLI, parameters, listOf("Take a screenshot and verify the final state against the user's task and demonstrated result"),
            "User handles credentials; review external communications, financial actions, deletion and account changes individually.",
            idempotencyRule = "Screenshot the current state and check for prior effects before every mutation", createdFrom = "demonstration:$id", updatedAtMs = now) to instructions
    }
    companion object { const val LINUX_DESKTOP = "gui:0" }
}
