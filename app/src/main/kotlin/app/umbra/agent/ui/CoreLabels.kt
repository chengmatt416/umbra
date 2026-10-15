package app.umbra.agent.ui

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import app.umbra.agent.R
import app.umbra.agent.services.ServiceMonitor
import app.umbra.agent.services.SharedService
import app.umbra.core.control.Breaker
import app.umbra.core.control.CredentialScope
import app.umbra.core.control.EvidenceLevel
import app.umbra.core.control.HealthLevel
import app.umbra.core.control.IncidentState
import app.umbra.core.control.NodeTrust
import app.umbra.core.control.TrustBoundary
import app.umbra.core.perf.PressureLevel
import app.umbra.core.safety.RiskAssessment
import app.umbra.core.safety.RiskCategory
import app.umbra.core.safety.RiskLevel

/**
 * Display names for labels that come from the core modules as English text (enum titles, the shared service
 * catalog). The core keeps its English wording for logs and journals; the screens map each value here, so what the
 * user reads follows the app language. Every `when` is exhaustive, so a new enum entry fails the build until it has
 * a string.
 */

// ---- Risk ----

@StringRes
fun riskCategoryRes(c: RiskCategory): Int = when (c) {
    RiskCategory.DESTRUCTIVE_SYSTEM -> R.string.rc_destructive_system
    RiskCategory.IRREVERSIBLE_ROOT -> R.string.rc_irreversible_root
    RiskCategory.FACTORY_RESET -> R.string.rc_factory_reset
    RiskCategory.IMPORTANT_DATA_DELETION -> R.string.rc_important_data_deletion
    RiskCategory.CREDENTIAL_SUBMISSION -> R.string.rc_credential_submission
    RiskCategory.FINANCIAL_TRANSACTION -> R.string.rc_financial_transaction
    RiskCategory.PURCHASE -> R.string.rc_purchase
    RiskCategory.SENDS_MESSAGE -> R.string.rc_sends_message
    RiskCategory.PUBLISHES_CONTENT -> R.string.rc_publishes_content
    RiskCategory.ACCOUNT_SECURITY_CHANGE -> R.string.rc_account_security_change
    RiskCategory.INSTALLS_UNTRUSTED_APP -> R.string.rc_installs_untrusted_app
    RiskCategory.DEVICE_SECURITY_COMPROMISE -> R.string.rc_device_security_compromise
    RiskCategory.SENSITIVE_DATA_EXPOSURE -> R.string.rc_sensitive_data_exposure
    RiskCategory.SYSTEM_CONFIGURATION -> R.string.rc_system_configuration
    RiskCategory.PRIVILEGE_ESCALATION -> R.string.rc_privilege_escalation
    RiskCategory.NETWORK_CHANGE -> R.string.rc_network_change
    RiskCategory.PLACES_CALL -> R.string.rc_places_call
    RiskCategory.DYNAMIC_COMMAND -> R.string.rc_dynamic_command
    RiskCategory.SELF_PROTECTION -> R.string.rc_self_protection
}

@StringRes
private fun riskLevelWordRes(level: RiskLevel): Int = when (level) {
    RiskLevel.NONE -> R.string.risk_d_lvl_none
    RiskLevel.LOW -> R.string.risk_d_lvl_low
    RiskLevel.MEDIUM -> R.string.risk_d_lvl_medium
    RiskLevel.HIGH -> R.string.risk_d_lvl_high
    RiskLevel.CRITICAL -> R.string.risk_d_lvl_critical
}

/**
 * [RiskAssessment.describe] (a member, so this has another name) in the app language: category and level are translated, the classifier's `reason`
 * stays as it came (English).
 */
fun RiskAssessment.localizedDescription(r: Resources): String =
    if (findings.isEmpty()) r.getString(R.string.risk_d_none)
    else findings
        .map { r.getString(R.string.risk_d_finding, r.getString(riskCategoryRes(it.category)), r.getString(riskLevelWordRes(it.level)), it.reason) }
        .reduce { acc, s -> r.getString(R.string.risk_d_join, acc, s) }

@Composable
fun RiskAssessment.localizedDescription(): String = localizedDescription(LocalResources.current)

// ---- Resource pressure ----

@StringRes
fun pressureLevelRes(l: PressureLevel): Int = when (l) {
    PressureLevel.NORMAL -> R.string.pressure_normal
    PressureLevel.ELEVATED -> R.string.pressure_elevated
    PressureLevel.HIGH -> R.string.pressure_high
    PressureLevel.CRITICAL -> R.string.pressure_critical
}

// ---- Shared services ----

@StringRes
fun healthLevelRes(l: HealthLevel): Int = when (l) {
    HealthLevel.L1 -> R.string.health_l1
    HealthLevel.L2 -> R.string.health_l2
    HealthLevel.L3 -> R.string.health_l3
    HealthLevel.L4 -> R.string.health_l4
}

@StringRes
fun breakerRes(b: Breaker): Int = when (b) {
    Breaker.CLOSED -> R.string.breaker_closed
    Breaker.OPEN -> R.string.breaker_open
    Breaker.HALF_OPEN -> R.string.breaker_half_open
}

private const val TMUX_PREFIX = "tmux."

private class ServiceStrings(@StringRes val name: Int, @StringRes val detail: Int, @StringRes val singleton: Int)

private val serviceStrings = mapOf(
    ServiceMonitor.LINUX to ServiceStrings(R.string.svcn_linux, R.string.svcd_linux, R.string.svcs_linux),
    ServiceMonitor.HELPER to ServiceStrings(R.string.svcn_helper, R.string.svcd_helper, R.string.svcs_helper),
    ServiceMonitor.ROOT_HELPER to ServiceStrings(R.string.svcn_root_helper, R.string.svcd_root_helper, R.string.svcs_root_helper),
    ServiceMonitor.X_DESKTOP to ServiceStrings(R.string.svcn_x_desktop, R.string.svcd_x_desktop, R.string.svcs_x_desktop),
    ServiceMonitor.LAYA to ServiceStrings(R.string.svcn_laya, R.string.svcd_laya, R.string.svcs_laya),
)

private val tmuxStrings = ServiceStrings(R.string.svcn_tmux, R.string.svcd_tmux, R.string.svcs_tmux)

private fun SharedService.strings(): Pair<ServiceStrings, String?>? = when {
    id.startsWith(TMUX_PREFIX) -> tmuxStrings to id.removePrefix(TMUX_PREFIX)
    else -> serviceStrings[id]?.let { it to null }
}

/** The catalog entry's name, in the app language (an id the table does not know keeps the catalog's own text). */
fun SharedService.displayName(r: Resources): String =
    strings()?.let { (s, group) -> if (group != null) r.getString(s.name, group) else r.getString(s.name) } ?: name

fun SharedService.displayDetail(r: Resources): String =
    strings()?.let { (s, group) -> if (group != null) r.getString(s.detail, group) else r.getString(s.detail) } ?: detail

/** Why this service may not be replicated into a pool. */
fun SharedService.displaySingleton(r: Resources): String =
    strings()?.let { (s, _) -> r.getString(s.singleton) } ?: singleton

// ---- Security & Evidence ----

@StringRes
fun evidenceLevelRes(l: EvidenceLevel): Int = when (l) {
    EvidenceLevel.L1 -> R.string.evl_l1
    EvidenceLevel.L2 -> R.string.evl_l2
    EvidenceLevel.L3 -> R.string.evl_l3
    EvidenceLevel.L4 -> R.string.evl_l4
}

@StringRes
fun evidenceMeaningRes(l: EvidenceLevel): Int = when (l) {
    EvidenceLevel.L1 -> R.string.evl_l1_meaning
    EvidenceLevel.L2 -> R.string.evl_l2_meaning
    EvidenceLevel.L3 -> R.string.evl_l3_meaning
    EvidenceLevel.L4 -> R.string.evl_l4_meaning
}

@StringRes
fun trustBoundaryRes(b: TrustBoundary): Int = when (b) {
    TrustBoundary.CREDENTIAL -> R.string.tb_credential
    TrustBoundary.GIT -> R.string.tb_git
    TrustBoundary.NODE -> R.string.tb_node
    TrustBoundary.ROOT -> R.string.tb_root
    TrustBoundary.OTHER -> R.string.tb_other
}

@StringRes
fun incidentStateRes(s: IncidentState): Int = when (s) {
    IncidentState.DETECTED -> R.string.inc_detected
    IncidentState.TRIAGED -> R.string.inc_triaged
    IncidentState.CONTAINED -> R.string.inc_contained
    IncidentState.REMEDIATING -> R.string.inc_remediating
    IncidentState.TRUST_RESTORATION -> R.string.inc_trust_restoration
    IncidentState.RESOLVED -> R.string.inc_resolved
}

// ---- Credentials and nodes ----

@StringRes
fun credentialScopeRes(s: CredentialScope): Int = when (s) {
    CredentialScope.ANY_SESSION -> R.string.cscope_any_session
    CredentialScope.ITS_PROVIDER_ONLY -> R.string.cscope_its_provider_only
    CredentialScope.NOT_FOR_AGENTS -> R.string.cscope_not_for_agents
}

@StringRes
fun nodeTrustRes(t: NodeTrust): Int = when (t) {
    NodeTrust.OWNED -> R.string.trust_owned
    NodeTrust.LIMITED -> R.string.trust_limited
}
