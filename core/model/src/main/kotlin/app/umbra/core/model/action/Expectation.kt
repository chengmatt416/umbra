package app.umbra.core.model.action

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A predicate over observable state, used both as "expected state change" of an action and as the
 * `wait_for` condition (spec §16, §17). Verification may use accessibility, screenshots,
 * application/activity state, shell output, processes, files, logs and user-defined conditions.
 */
@Serializable
sealed interface Expectation {
    @Serializable @SerialName("none")
    data object None : Expectation

    /** Anything on screen changed (accessibility tree or pixels). */
    @Serializable @SerialName("ui_changed")
    data object UiChanged : Expectation

    @Serializable @SerialName("screen_changed")
    data class ScreenChanged(val minDiffRatio: Float = 0.01f) : Expectation

    @Serializable @SerialName("text_visible")
    data class TextVisible(
        val text: String,
        val exact: Boolean = false,
        val ignoreCase: Boolean = true,
    ) : Expectation

    @Serializable @SerialName("text_gone")
    data class TextGone(
        val text: String,
        val exact: Boolean = false,
        val ignoreCase: Boolean = true,
    ) : Expectation

    @Serializable @SerialName("element_visible")
    data class ElementVisible(val query: ElementQuery) : Expectation

    @Serializable @SerialName("element_gone")
    data class ElementGone(val query: ElementQuery) : Expectation

    @Serializable @SerialName("element_state")
    data class ElementState(
        val query: ElementQuery,
        val checked: Boolean? = null,
        val enabled: Boolean? = null,
        val text: String? = null,
    ) : Expectation

    @Serializable @SerialName("package_foreground")
    data class PackageForeground(val packageName: String) : Expectation

    /** [component] is `pkg/.Activity`, `pkg/full.Activity`, or just `pkg`; with [prefix] a prefix match. */
    @Serializable @SerialName("activity")
    data class ActivityIs(val component: String, val prefix: Boolean = true) : Expectation

    @Serializable @SerialName("process_running")
    data class ProcessRunning(val name: String) : Expectation

    @Serializable @SerialName("process_gone")
    data class ProcessGone(val name: String) : Expectation

    @Serializable @SerialName("file_exists")
    data class FileExists(val path: String, val privileged: Boolean = false) : Expectation

    @Serializable @SerialName("file_changed")
    data class FileChanged(val path: String, val sinceMs: Long? = null, val privileged: Boolean = false) : Expectation

    @Serializable @SerialName("log_matches")
    data class LogMatches(
        val regex: String,
        val tag: String? = null,
        val packageName: String? = null,
        val withinMs: Long = 5_000,
    ) : Expectation

    /** No crash/ANR event for [packageName] observed since the action started. */
    @Serializable @SerialName("no_crash")
    data class NoCrash(val packageName: String) : Expectation

    @Serializable @SerialName("shell_output")
    data class ShellOutput(
        val command: String,
        val regex: String? = null,
        val exitCode: Int? = 0,
        val asRoot: Boolean = false,
    ) : Expectation

    @Serializable @SerialName("all_of")
    data class AllOf(val items: List<Expectation>) : Expectation

    @Serializable @SerialName("any_of")
    data class AnyOf(val items: List<Expectation>) : Expectation

    @Serializable @SerialName("not")
    data class Not(val item: Expectation) : Expectation
}

/** How an action is verified after execution. */
@Serializable
data class Verification(
    val expect: Expectation = Expectation.None,
    val timeoutMs: Long = 2_500,
    val pollMs: Long = 150,
    /** Wait this long after the action before the first check, for animations/transitions. */
    val settleMs: Long = 0,
    /** If true, a failed verification makes the whole action FAILED; otherwise it is only reported. */
    val required: Boolean = true,
)

@Serializable
data class CheckResult(
    val expectation: String,
    val passed: Boolean,
    val evidence: String,
)

@Serializable
data class VerificationResult(
    val passed: Boolean,
    val summary: String,
    val checks: List<CheckResult> = emptyList(),
    val durationMs: Long = 0,
)
