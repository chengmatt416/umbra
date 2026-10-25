package app.umbra.agent.agents.proactive

import app.umbra.agent.agents.AgentProfile
import app.umbra.core.model.UmbraJson
import app.umbra.core.safety.OutboundDlpGuard

/** Exports an allowlisted definition, never the runtime profile or its private state. */
object AgentTemplates {
    fun export(profile: AgentProfile, soul: String, skills: List<SkillTemplate>, routines: List<AgentRoutine>): String {
        fun clean(text: String): String {
            var value = OutboundDlpGuard().redact(text)
            profile.autoReply.forEach { privateRef -> value = value.replace(privateRef, "[PRIVATE_RECIPIENT]") }
            value = value.replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[EMAIL_PARAMETER]")
            value = value.replace(Regex("(?:/data/(?:user/\\d+|data)/|/home/agent/|/workspace/)[^\\s\"'`]+"), "[WORKSPACE_PARAMETER]")
            return value
        }
        val portable = skills.filter { it.skill.synthesizedScript == null && it.skill.executionTarget != ExecutionTarget.SANDBOX_TRANSFORM }.take(20)
        val safeSkills = portable.mapIndexed { index, template ->
            SkillTemplate(template.skill.copy(id = "template_skill_$index", ownerAgentId = null, name = clean(template.skill.name), description = clean(template.skill.description),
                status = UmbraSkill.Status.DRAFT, testedVersion = null, approvedVersion = null, testSessionId = null,
                successCount = 0, failureCount = 0, updatedAtMs = 0, pendingPatch = null, synthesizedScript = null, scriptLanguage = null, codeReviewSha256 = null, testCases = emptyList(),
                approvalBoundary = clean(template.skill.approvalBoundary), verificationChecks = template.skill.verificationChecks.map(::clean),
                idempotencyRule = template.skill.idempotencyRule?.let(::clean),
                parameters = template.skill.parameters.map { it.copy(description = clean(it.description), defaultValue = null) }), clean(template.instructions))
        }
        val mapping = portable.mapIndexed { i, item -> item.skill.id to safeSkills[i].skill.id }.toMap()
        val safeRoutines = routines.take(50).mapIndexed { index, r -> r.copy(id = "template_routine_$index", ownerAgentId = "template",
            title = clean(r.title), instruction = clean(r.instruction), enabled = false, authorizedAtMs = null,
            createdAtMs = 0, lastRunAtMs = null, nextRunAtMs = null, recentRuns = emptyList(), skillArgs = emptyMap(),
            skillId = r.skillId?.let(mapping::get), trigger = when (val t = r.trigger) {
                is RoutineTrigger.EventMatch -> t.copy(senderOrChannelFilter = null, textContains = null, semanticCondition = t.semanticCondition?.let(::clean))
                is RoutineTrigger.WatcherMatch -> RoutineTrigger.Manual
                else -> t
            }) }
        return UmbraJson.encodeToString(AgentTemplateBundle.serializer(), AgentTemplateBundle(name = clean(profile.name), avatar = profile.avatar,
            template = profile.template, soul = clean(soul), requiredConnectors = profile.channels, skills = safeSkills, routines = safeRoutines))
    }
    fun parse(text: String): AgentTemplateBundle {
        require(text.length <= 1_000_000) { "Agent templates are limited to 1 MB" }
        val bundle = UmbraJson.decodeFromString(AgentTemplateBundle.serializer(), text)
        require(bundle.version == 1 && bundle.name.isNotBlank() && bundle.name.length <= 100 && bundle.soul.length <= 50_000)
        require(bundle.skills.size <= 20 && bundle.routines.size <= 50)
        require(bundle.skills.map { it.skill.id }.distinct().size == bundle.skills.size)
        require(bundle.skills.all { it.skill.synthesizedScript == null && it.skill.executionTarget != ExecutionTarget.SANDBOX_TRANSFORM && it.instructions.isNotBlank() && it.instructions.length <= 100_000 }) { "Executable synthesized tools are not portable templates; re-author and review them locally" }
        bundle.skills.forEach { SkillStore.validateDefinition(it.skill, it.instructions) }
        bundle.routines.forEach { routine ->
            require(routine.skillId == null || bundle.skills.any { it.skill.id == routine.skillId }) { "The template references a missing skill" }
            RoutineDefinitions.validate(routine.copy(enabled = false, authorizedAtMs = null, allowedConnectors = emptyList()))
        }
        // Imported scopes and execution state are always discarded by the importer.
        return bundle
    }
}
