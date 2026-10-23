package app.umbra.core.safety

import app.umbra.core.model.Umbra
import app.umbra.core.model.observation.UiNode
import kotlinx.serialization.Serializable

@Serializable
enum class RiskLevel { NONE, LOW, MEDIUM, HIGH, CRITICAL }

/**
 * Risk categories. Those with [inSpec] are the categories spec §28 says must always need explicit
 * approval at HIGH or above, in every approval mode.
 */
@Serializable
enum class RiskCategory(val inSpec: Boolean, val title: String) {
    DESTRUCTIVE_SYSTEM(true, "Destructive system operation"),
    IRREVERSIBLE_ROOT(true, "Irreversible root operation"),
    FACTORY_RESET(true, "Factory reset"),
    IMPORTANT_DATA_DELETION(true, "Deletion of important user data"),
    CREDENTIAL_SUBMISSION(true, "Credential or password submission"),
    FINANCIAL_TRANSACTION(true, "Financial transaction"),
    PURCHASE(true, "Purchase"),
    SENDS_MESSAGE(true, "Sends a message or email on the user's behalf"),
    PUBLISHES_CONTENT(true, "Publishes content publicly"),
    ACCOUNT_SECURITY_CHANGE(true, "Changes account security settings"),
    INSTALLS_UNTRUSTED_APP(true, "Installs an untrusted or unknown app"),
    DEVICE_SECURITY_COMPROMISE(true, "Could significantly compromise device security"),

    SENSITIVE_DATA_EXPOSURE(false, "Exposes sensitive data"),
    SYSTEM_CONFIGURATION(false, "Changes system configuration"),
    PRIVILEGE_ESCALATION(false, "Uses elevated privileges"),
    NETWORK_CHANGE(false, "Changes network configuration"),
    PLACES_CALL(false, "Places a phone call"),
    DYNAMIC_COMMAND(false, "Runs a command that cannot be statically analysed"),
    PROMPT_INJECTION(true, "Possible instructions injected through untrusted content"),

    /** Targets the agent's own app, approvals, or ledger. Always denied, never asked. */
    SELF_PROTECTION(false, "Targets the agent's own safety system"),
}

@Serializable
data class RiskFinding(
    val category: RiskCategory,
    val level: RiskLevel,
    val reason: String,
)

@Serializable
data class RiskAssessment(val findings: List<RiskFinding> = emptyList()) {
    val level: RiskLevel get() = findings.maxOfOrNull { it.level } ?: RiskLevel.NONE
    val categories: Set<RiskCategory> get() = findings.map { it.category }.toSet()
    val isSelfProtectionViolation: Boolean get() = findings.any { it.category == RiskCategory.SELF_PROTECTION }

    /** True when a spec §28 category is present at HIGH or above. */
    val hitsSpecCategory: Boolean
        get() = findings.any { it.category.inSpec && it.level >= RiskLevel.HIGH }

    operator fun plus(other: RiskAssessment) = RiskAssessment(findings + other.findings)

    fun describe(): String =
        if (findings.isEmpty()) "no risk" else findings.joinToString("; ") { "${it.category.title} (${it.level.name.lowercase()}): ${it.reason}" }

    companion object {
        val NONE = RiskAssessment()
        fun of(vararg findings: RiskFinding) = RiskAssessment(findings.toList())
    }
}

/** What the classifier knows about the situation beyond the request itself. */
data class ActionContext(
    val foregroundPackage: String? = null,
    /** Resolved node for tap/type style operations, when known. */
    val targetNode: UiNode? = null,
    /** Visible texts on screen, used to read the intent of a button by its surroundings. */
    val screenTexts: List<String> = emptyList(),
    /** True when a password field is visible; tapping "Log in" then submits credentials. */
    val passwordFieldVisible: Boolean = false,
    /** Working directory commands run in; null means "the workspace". */
    val cwd: String? = null,
    val workspaceRoots: List<String> = emptyList(),
    /** Where the app itself stages APKs it built; installing from here is routine dev work. */
    val stagingRoots: List<String> = DEFAULT_STAGING_ROOTS,
    val tempRoots: List<String> = DEFAULT_TEMP_ROOTS,
    /** Packages the agent built and installed in this session; managing them is routine dev work. */
    val trustedPackages: Set<String> = emptySet(),
    /** Packages the agent must never stop, disable, clear or uninstall. */
    val protectedPackages: Set<String> = setOf(Umbra.APPLICATION_ID),
    /** Extra paths (approval ledger, policy files) the agent must never write. */
    val protectedPaths: List<String> = emptyList(),
    /** Trusted adapter's description of an operation that has no phone UI target (for example a remote tool). */
    val actionDescription: String? = null,
    /** A bounded sample of recent tool output, treated as data rather than user authority. */
    val untrustedContent: String? = null,
) {
    companion object {
        val DEFAULT_STAGING_ROOTS = listOf("/data/local/tmp/umbra")
        val DEFAULT_TEMP_ROOTS = listOf("/tmp", "/data/local/tmp", "/var/tmp", "/dev/shm")
    }
}
