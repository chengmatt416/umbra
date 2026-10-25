package app.umbra.agent.agents.proactive

import app.umbra.agent.AppContainer
import app.umbra.agent.agents.AgentProfile
import java.util.UUID

/** Imported templates create stopped agents with no connector access or runtime approvals. */
class TemplateExchange(private val container: AppContainer) {
    fun export(agentId: String): String {
        val p = requireNotNull(container.agents.store.get(agentId))
        val output = AgentTemplates.export(p, container.agents.store.soul(agentId), container.skills.list().filter { it.ownerAgentId == agentId || it.ownerAgentId == null }
            .map { SkillTemplate(it, container.skills.instructions(it.id)) }, container.agents.proactive.store.routines(agentId))
        app.umbra.core.safety.OutboundDlpGuard(4) { container.protectedValues() }.requireSafe(output)
        return output
    }
    suspend fun import(text: String): AgentProfile {
        val bundle = AgentTemplates.parse(text)
        require(bundle.skills.all { it.skill.verificationChecks.isNotEmpty() && it.skill.approvalBoundary.isNotBlank() && it.skill.parameters.size <= 30 })
        val p = container.agents.create(bundle.name, bundle.avatar, bundle.template, bundle.soul, channels = emptyList(), running = false)
        val mapping = mutableMapOf<String, String>()
        bundle.skills.forEach { t ->
            val id = "skill" + UUID.randomUUID().toString().replace("-", "")
            container.skills.propose(t.skill.copy(id = id, ownerAgentId = p.id, status = UmbraSkill.Status.DRAFT, testSessionId = null, pendingPatch = null), t.instructions)
            mapping[t.skill.id] = id
        }
        bundle.routines.forEach { r ->
            container.agents.proactive.store.saveRoutine(r.copy(id = "routine" + UUID.randomUUID().toString().replace("-", ""), ownerAgentId = p.id,
                skillId = r.skillId?.let(mapping::get), enabled = false, authorizedAtMs = null, nextRunAtMs = null, recentRuns = emptyList(),
                lastRunAtMs = null, createdAtMs = System.currentTimeMillis(), allowedConnectors = emptyList(), skillArgs = emptyMap(), trigger = when (val t = r.trigger) {
                    is RoutineTrigger.EventMatch -> t.copy(senderOrChannelFilter = null, textContains = null)
                    is RoutineTrigger.WatcherMatch -> RoutineTrigger.Manual
                    else -> t
                }))
        }
        container.agents.proactive.store.writeNote(p.id, "import-review", "Imported template; the agent is stopped and all connectors are disconnected. Required sources: ${bundle.requiredConnectors.joinToString()}. Review the role, test every skill and explicitly authorize each routine before use.", false)
        return p
    }
}
