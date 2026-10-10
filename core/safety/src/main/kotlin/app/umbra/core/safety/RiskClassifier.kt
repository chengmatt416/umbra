package app.umbra.core.safety

import app.umbra.core.model.action.ActionRequest
import app.umbra.core.model.action.ClipboardAction
import app.umbra.core.model.action.Environment
import app.umbra.core.model.action.FileAction
import app.umbra.core.model.action.GlobalAction
import app.umbra.core.model.action.NotificationAction
import app.umbra.core.model.action.Operation
import app.umbra.core.model.action.PackageAction
import app.umbra.core.model.action.Privilege
import app.umbra.core.model.action.ProcessAction
import app.umbra.core.safety.RiskCategory.PRIVILEGE_ESCALATION
import app.umbra.core.safety.RiskCategory.SELF_PROTECTION
import app.umbra.core.safety.RiskCategory.SENDS_MESSAGE
import app.umbra.core.safety.RiskCategory.SENSITIVE_DATA_EXPOSURE
import app.umbra.core.safety.RiskCategory.SYSTEM_CONFIGURATION
import app.umbra.core.safety.RiskLevel.CRITICAL
import app.umbra.core.safety.RiskLevel.HIGH
import app.umbra.core.safety.RiskLevel.LOW
import app.umbra.core.safety.RiskLevel.MEDIUM

/**
 * Maps any [ActionRequest] to a [RiskAssessment] (spec §28). Structured operations are rewritten
 * into the equivalent shell command so a single rule set in [ShellRiskAnalyzer] covers both
 * `pm uninstall x` typed as a shell command and `Packages(UNINSTALL, x)` issued as an operation.
 */
class RiskClassifier(
    private val shell: ShellRiskAnalyzer = ShellRiskAnalyzer(),
    private val ui: UiRiskAnalyzer = UiRiskAnalyzer(),
) {
    fun assess(request: ActionRequest, ctx: ActionContext = ActionContext()): RiskAssessment {
        val findings = LinkedHashSet<RiskFinding>()
        val rooted = request.requires == Privilege.ROOT
        findings += selfProtection(request.operation, ctx)

        when (val op = request.operation) {
            is Operation.Shell -> findings += shell.analyze(op.command, op.asRoot || rooted, ctx)
            is Operation.LaunchCommand -> findings += shell.analyze(joinCommand(op.command, op.args), rooted, ctx)
            is Operation.Packages -> findings += packages(op, rooted, ctx)
            is Operation.File -> findings += file(op, rooted, ctx)
            is Operation.Process -> findings += process(op, rooted, ctx)
            is Operation.StopApp -> if (op.packageName in ctx.protectedPackages) {
                findings += RiskFinding(SELF_PROTECTION, CRITICAL, "would stop the agent app itself")
            }
            is Operation.LaunchApp -> findings += launch(op, ctx)
            is Operation.Tap, is Operation.DoubleTap, is Operation.RightClick, is Operation.LongPress -> findings += ui.analyzeTap(ctx)
            is Operation.TypeText -> findings += ui.analyzeType(ctx, op.secret, op.submit)
            is Operation.PressKey -> if (isEnter(op.key) && ctx.passwordFieldVisible) {
                findings += RiskFinding(RiskCategory.CREDENTIAL_SUBMISSION, HIGH, "pressing Enter submits a form with a password field")
            }
            is Operation.Global -> if (op.action == GlobalAction.POWER_DIALOG) {
                findings += RiskFinding(SYSTEM_CONFIGURATION, MEDIUM, "opens the power menu")
            }
            is Operation.Notifications -> when (op.action) {
                NotificationAction.REPLY -> findings += RiskFinding(SENDS_MESSAGE, HIGH, "replies to a notification, sending a message")
                NotificationAction.PRESS_ACTION -> findings += RiskFinding(SYSTEM_CONFIGURATION, MEDIUM, "presses a notification action")
                else -> Unit
            }
            is Operation.Clipboard -> if (op.action == ClipboardAction.GET || op.action == ClipboardAction.PASTE) {
                findings += RiskFinding(SENSITIVE_DATA_EXPOSURE, LOW, "reads the clipboard")
            }
            is Operation.Observe, is Operation.Screenshot -> findings += ui.analyzeObservation(ctx)
            is Operation.SelectText, is Operation.Swipe, is Operation.Scroll, is Operation.Drag,
            is Operation.SwitchApp, is Operation.WaitFor, is Operation.Find, is Operation.Window, is Operation.SysInfo,
            is Operation.Logs, is Operation.Display, is Operation.Verify,
            -> Unit
        }

        if (rooted && findings.isEmpty() && request.operation !is Operation.Observe) {
            findings += RiskFinding(PRIVILEGE_ESCALATION, LOW, "uses root")
        }
        return RiskAssessment(findings.toList())
    }

    /**
     * The agent must never operate its own app or answer its own approval prompts (spec §28): whatever
     * the user is asked, the user answers. Touching the screen while the agent's app is in front, or
     * while the notification shade shows its approval notification, is refused outright, as is acting on
     * a notification the agent's app posted.
     */
    private fun selfProtection(op: Operation, ctx: ActionContext): List<RiskFinding> {
        val touches = op is Operation.Tap || op is Operation.DoubleTap || op is Operation.RightClick || op is Operation.LongPress ||
            op is Operation.Swipe || op is Operation.Scroll || op is Operation.Drag || op is Operation.TypeText ||
            op is Operation.SelectText || op is Operation.PressKey
        val pkg = ctx.foregroundPackage
        if (touches) {
            if (pkg != null && pkg in ctx.protectedPackages) {
                return listOf(RiskFinding(SELF_PROTECTION, CRITICAL, "the screen shows the agent's own app, which only the user may operate"))
            }
            if (pkg == "com.android.systemui" && ctx.screenTexts.any { it.contains("Approval needed") || it.contains("Umbra needs you") }) {
                return listOf(RiskFinding(SELF_PROTECTION, CRITICAL, "the notification shade shows an approval request, which only the user may answer"))
            }
        }
        if (op is Operation.Notifications && op.action != NotificationAction.LIST && op.key != null && ctx.protectedPackages.any { op.key!!.contains("|$it|") }) {
            return listOf(RiskFinding(SELF_PROTECTION, CRITICAL, "this notification belongs to the agent's own app"))
        }
        return emptyList()
    }

    private fun packages(op: Operation.Packages, root: Boolean, ctx: ActionContext): List<RiskFinding> {
        val pkg = op.packageName
        val command = when (op.action) {
            PackageAction.LIST, PackageAction.INFO -> return emptyList()
            PackageAction.INSTALL -> "pm install ${quote(op.apkPath ?: "")}"
            PackageAction.UNINSTALL -> "pm uninstall ${quote(pkg ?: "")}"
            PackageAction.CLEAR_DATA -> "pm clear ${quote(pkg ?: "")}"
            PackageAction.ENABLE -> return emptyList()
            PackageAction.DISABLE -> "pm disable-user ${quote(pkg ?: "")}"
            PackageAction.GRANT -> "pm grant ${quote(pkg ?: "")} ${quote(op.permission ?: "")}"
            PackageAction.REVOKE -> "pm revoke ${quote(pkg ?: "")} ${quote(op.permission ?: "")}"
        }
        return shell.analyze(command, root, ctx)
    }

    private fun file(op: Operation.File, root: Boolean, ctx: ActionContext): List<RiskFinding> {
        val path = quote(op.path)
        val command = when (op.action) {
            FileAction.READ -> "cat $path"
            FileAction.WRITE, FileAction.APPEND -> "tee $path"
            FileAction.DELETE -> "rm -r $path"
            FileAction.MOVE -> "mv $path ${quote(op.destination ?: "")}"
            FileAction.MKDIR -> "mkdir $path"
            FileAction.LIST, FileAction.STAT -> return emptyList()
        }
        return shell.analyze(command, root, ctx)
    }

    private fun process(op: Operation.Process, root: Boolean, ctx: ActionContext): List<RiskFinding> {
        if (op.action != ProcessAction.KILL) return emptyList()
        val target = op.pid?.toString() ?: op.name_ ?: return emptyList()
        return shell.analyze("kill -${op.signal ?: 15} ${quote(target)}", root, ctx)
    }

    private fun launch(op: Operation.LaunchApp, ctx: ActionContext): List<RiskFinding> {
        val parts = buildList {
            add("am start")
            op.intentAction?.let { add("-a ${quote(it)}") }
            op.uri?.let { add("-d ${quote(it)}") }
        }
        val intentFindings = if (op.intentAction != null || op.uri != null) shell.analyze(parts.joinToString(" "), false, ctx) else emptyList()
        val sensitive = if (ui.isSensitiveApp(op.packageName)) {
            listOf(RiskFinding(SENSITIVE_DATA_EXPOSURE, LOW, "opens a banking, wallet or password-manager app"))
        } else {
            emptyList()
        }
        return intentFindings + sensitive
    }

    private fun isEnter(key: String): Boolean {
        val k = key.uppercase()
        return k == "KEYCODE_ENTER" || k == "ENTER" || k == "RETURN" || k == "66"
    }

    private fun joinCommand(command: String, args: List<String>): String =
        (listOf(command) + args).joinToString(" ") { quote(it) }

    private fun quote(s: String): String =
        if (s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in "._-/:@+=,%" }) s else "'" + s.replace("'", "'\\''") + "'"
}
