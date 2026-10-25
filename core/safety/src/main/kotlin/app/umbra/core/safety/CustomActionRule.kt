package app.umbra.core.safety

import app.umbra.core.model.action.ActionRequest
import kotlinx.serialization.Serializable

@Serializable data class CustomActionRule(val id: String, val agentId: String? = null, val scope: RuleScope,
    val disposition: Disposition, val description: String, val enabled: Boolean = true) {
    @Serializable enum class Disposition { AUTO_EXECUTE, PRE_APPROVED_SCOPE, ALWAYS_ASK, HANDOFF_TO_USER }
    fun matches(request: ActionRequest, assessment: RiskAssessment, context: ActionContext): Boolean {
        if (!enabled) return false
        if (scope.categories.isNotEmpty() && assessment.categories.none { it in scope.categories }) return false
        if (scope.appPackage != null && scope.appPackage != context.foregroundPackage) return false
        if (scope.connectorOrTool != null && scope.connectorOrTool != context.toolName && scope.connectorOrTool != request.operation.name) return false
        if (scope.recipientOrDomain != null) {
            val recipient = context.recipientOrDomain ?: return false
            val expected = scope.recipientOrDomain
            if (expected.startsWith("*.")) {
                val suffix = expected.drop(1).lowercase()
                if (!recipient.lowercase().endsWith(suffix) || recipient.length <= suffix.length) return false
            } else if (!expected.equals(recipient, true)) return false
        }
        if (scope.maxAmountUsd != null) {
            val amount = context.amountUsd ?: return false
            if (!amount.isFinite() || amount < 0 || amount > scope.maxAmountUsd) return false
        }
        return true
    }
}
@Serializable data class RuleScope(val categories: Set<RiskCategory> = emptySet(), val appPackage: String? = null,
    val connectorOrTool: String? = null, val recipientOrDomain: String? = null, val maxAmountUsd: Double? = null)
