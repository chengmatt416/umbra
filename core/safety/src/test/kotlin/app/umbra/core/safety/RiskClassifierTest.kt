package app.umbra.core.safety

import app.umbra.core.model.action.ClipboardAction
import app.umbra.core.model.action.FileAction
import app.umbra.core.model.action.GlobalAction
import app.umbra.core.model.action.NotificationAction
import app.umbra.core.model.action.Operation
import app.umbra.core.model.action.PackageAction
import app.umbra.core.model.action.Privilege
import app.umbra.core.model.action.ProcessAction
import app.umbra.core.safety.RiskCategory.CREDENTIAL_SUBMISSION
import app.umbra.core.safety.RiskCategory.DESTRUCTIVE_SYSTEM
import app.umbra.core.safety.RiskCategory.IMPORTANT_DATA_DELETION
import app.umbra.core.safety.RiskCategory.INSTALLS_UNTRUSTED_APP
import app.umbra.core.safety.RiskCategory.PRIVILEGE_ESCALATION
import app.umbra.core.safety.RiskCategory.SELF_PROTECTION
import app.umbra.core.safety.RiskCategory.SENDS_MESSAGE
import app.umbra.core.safety.RiskCategory.SENSITIVE_DATA_EXPOSURE
import app.umbra.core.safety.RiskLevel.CRITICAL
import app.umbra.core.safety.RiskLevel.HIGH
import app.umbra.core.safety.RiskLevel.LOW
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RiskClassifierTest {
    private val classifier = RiskClassifier()
    private val ctx = ActionContext(
        workspaceRoots = listOf("/workspace/demo"),
        trustedPackages = setOf("com.example.crashy"),
    )

    private fun assess(op: Operation, context: ActionContext = ctx, requires: Privilege = Privilege.NONE) =
        classifier.assess(request(op, requires = requires), context)

    @Test
    fun shellOperationsUseTheShellRules() {
        val a = assess(Operation.Shell("rm -rf /sdcard/DCIM"))
        assertTrue(a.findings.any { it.category == IMPORTANT_DATA_DELETION && it.level == HIGH }, a.describe())
        assertEquals(RiskLevel.NONE, assess(Operation.Shell("ls -la")).level)
    }

    @Test
    fun shellInputAndWorkingDirectoryCannotHideConsequentialActions() {
        assertTrue(assess(Operation.Shell("sh", stdin = "rm -rf /sdcard/DCIM\n")).categories.contains(IMPORTANT_DATA_DELETION))
        assertTrue(assess(Operation.Shell("env sh -s", stdin = "pm clear app.umbra.agent\n")).isSelfProtectionViolation)
        assertTrue(assess(Operation.Shell("rm -rf DCIM", cwd = "/sdcard")).categories.contains(IMPORTANT_DATA_DELETION))
        assertTrue(assess(Operation.LaunchCommand("rm", listOf("-rf", "DCIM"), cwd = "/sdcard")).categories.contains(IMPORTANT_DATA_DELETION))
        assertEquals(RiskLevel.NONE, assess(Operation.Shell("cat", stdin = "rm -rf /sdcard/DCIM\n")).level, "Data supplied to cat is not a script")
    }

    @Test
    fun asRootFlagAndRequiredPrivilegeBothMakeTheCommandRoot() {
        val a = assess(Operation.Shell("rm -rf /system/app", asRoot = true))
        assertTrue(a.findings.any { it.category == RiskCategory.IRREVERSIBLE_ROOT }, a.describe())
        val b = assess(Operation.Shell("rm -rf /system/app"), requires = Privilege.ROOT)
        assertTrue(b.findings.any { it.category == RiskCategory.IRREVERSIBLE_ROOT }, b.describe())
    }

    @Test
    fun packageOperationsMatchTheEquivalentShellCommands() {
        assertTrue(assess(Operation.Packages(PackageAction.UNINSTALL, "com.whatsapp")).level >= HIGH)
        assertEquals(RiskLevel.NONE, assess(Operation.Packages(PackageAction.UNINSTALL, "com.example.crashy")).level)
        assertTrue(assess(Operation.Packages(PackageAction.CLEAR_DATA, "com.whatsapp")).level >= HIGH)
        assertEquals(RiskLevel.NONE, assess(Operation.Packages(PackageAction.LIST)).level)
        assertEquals(RiskLevel.NONE, assess(Operation.Packages(PackageAction.INFO, "com.whatsapp")).level)
    }

    @Test
    fun installingFromTheWorkspaceIsRoutineButFromDownloadsIsNot() {
        val ok = assess(Operation.Packages(PackageAction.INSTALL, apkPath = "/workspace/demo/app/build/outputs/apk/debug/app-debug.apk"))
        assertEquals(RiskLevel.NONE, ok.level, ok.describe())
        val risky = assess(Operation.Packages(PackageAction.INSTALL, apkPath = "/sdcard/Download/free-robux.apk"))
        assertTrue(risky.findings.any { it.category == INSTALLS_UNTRUSTED_APP && it.level == HIGH }, risky.describe())
    }

    @Test
    fun pathsWithSpacesAreQuotedBeforeAnalysis() {
        val a = assess(Operation.File(FileAction.DELETE, "/sdcard/My Photos/2024 trip"))
        assertTrue(a.findings.any { it.category == IMPORTANT_DATA_DELETION && "My Photos" in it.reason }, a.describe())
    }

    @Test
    fun fileOperationsMapToCommands() {
        assertTrue(assess(Operation.File(FileAction.DELETE, "/system/app/Foo")).level == CRITICAL)
        assertTrue(assess(Operation.File(FileAction.WRITE, "/system/etc/hosts", content = "x")).level == CRITICAL)
        assertTrue(assess(Operation.File(FileAction.MOVE, "/sdcard/DCIM/a.jpg", destination = "/dev/null")).level >= LOW)
        assertEquals(RiskLevel.NONE, assess(Operation.File(FileAction.LIST, "/sdcard")).level)
        assertEquals(RiskLevel.NONE, assess(Operation.File(FileAction.WRITE, "/workspace/demo/out.txt", content = "x")).level)
        assertTrue(assess(Operation.File(FileAction.READ, "/data/misc/keystore/user_0/x")).findings.any { it.category == SENSITIVE_DATA_EXPOSURE })
    }

    @Test
    fun killingCriticalProcessesIsCritical() {
        assertEquals(CRITICAL, assess(Operation.Process(ProcessAction.KILL, name_ = "system_server")).level)
        assertEquals(RiskLevel.NONE, assess(Operation.Process(ProcessAction.LIST)).level)
    }

    @Test
    fun stoppingTheAgentItselfIsASelfProtectionViolation() {
        val a = assess(Operation.StopApp("app.umbra.agent"))
        assertTrue(a.isSelfProtectionViolation, a.describe())
        assertEquals(RiskLevel.NONE, assess(Operation.StopApp("com.example.crashy")).level)
    }

    @Test
    fun uiOperationsUseContext() {
        val tapCtx = ctx.copy(foregroundPackage = "com.example.shop", targetNode = button("Buy now"))
        assertTrue(assess(Operation.Tap, tapCtx).level >= HIGH)
        assertEquals(RiskLevel.NONE, assess(Operation.Tap, ctx.copy(targetNode = button("Next"))).level)

        val typeCtx = ctx.copy(targetNode = passwordField())
        assertTrue(assess(Operation.TypeText("hunter2"), typeCtx).findings.any { it.category == CREDENTIAL_SUBMISSION })
    }

    @Test
    fun enterOnAFormWithAPasswordFieldSubmitsCredentials() {
        val a = assess(Operation.PressKey("KEYCODE_ENTER"), ctx.copy(passwordFieldVisible = true))
        assertTrue(a.findings.any { it.category == CREDENTIAL_SUBMISSION })
        assertEquals(RiskLevel.NONE, assess(Operation.PressKey("KEYCODE_ENTER")).level)
    }

    @Test
    fun repliesFromNotificationsSendMessages() {
        assertTrue(assess(Operation.Notifications(NotificationAction.REPLY, key = "k", text = "ok")).findings.any { it.category == SENDS_MESSAGE })
        assertEquals(RiskLevel.NONE, assess(Operation.Notifications(NotificationAction.LIST)).level)
    }

    @Test
    fun clipboardReadsAreLowRiskExposure() {
        assertEquals(LOW, assess(Operation.Clipboard(ClipboardAction.GET)).level)
        assertEquals(RiskLevel.NONE, assess(Operation.Clipboard(ClipboardAction.SET, "x")).level)
    }

    @Test
    fun launchingWithACallIntentIsFlagged() {
        val a = assess(Operation.LaunchApp(packageName = "com.android.phone", intentAction = "android.intent.action.CALL", uri = "tel:911"))
        assertTrue(a.findings.any { it.category == RiskCategory.PLACES_CALL }, a.describe())
        assertEquals(RiskLevel.NONE, assess(Operation.LaunchApp(packageName = "com.example.crashy")).level)
    }

    @Test
    fun observingASensitiveAppIsNotedButBenignAppsAreClean() {
        val a = assess(Operation.Observe(), ctx.copy(foregroundPackage = "com.bitwarden.app"))
        assertTrue(a.findings.any { it.category == SENSITIVE_DATA_EXPOSURE })
        assertEquals(RiskLevel.NONE, assess(Operation.Observe(), ctx.copy(foregroundPackage = "com.example.notes")).level)
    }

    @Test
    fun powerMenuIsMedium() {
        assertEquals(RiskLevel.MEDIUM, assess(Operation.Global(GlobalAction.POWER_DIALOG)).level)
        assertEquals(RiskLevel.NONE, assess(Operation.Global(GlobalAction.BACK)).level)
    }

    @Test
    fun usingRootWithoutOtherFindingsLeavesALowNote() {
        val a = assess(Operation.Display(app.umbra.core.model.action.DisplayAction.LIST), requires = Privilege.ROOT)
        assertTrue(a.findings.any { it.category == PRIVILEGE_ESCALATION && it.level == LOW }, a.describe())
    }

    @Test
    fun launchCommandsAreAnalysedAsShellCommands() {
        val a = assess(Operation.LaunchCommand("rm", listOf("-rf", "/")))
        assertTrue(a.findings.any { it.category == DESTRUCTIVE_SYSTEM && it.level == CRITICAL }, a.describe())
        val b = assess(Operation.LaunchCommand("git", listOf("commit", "-m", "rm -rf /")))
        assertEquals(RiskLevel.NONE, b.level, b.describe())
    }

    @Test
    fun selfProtectionFindingsAreRecognised() {
        val a = assess(Operation.Packages(PackageAction.UNINSTALL, "app.umbra.agent"))
        assertTrue(a.findings.any { it.category == SELF_PROTECTION && it.level == CRITICAL })
    }

    // ---- the agent never operates its own app or answers its own approvals ----

    @Test
    fun touchingTheAgentsOwnAppIsRefusedOutright() {
        val inOwnApp = ctx.copy(foregroundPackage = "app.umbra.agent")
        for (op in listOf(Operation.Tap, Operation.DoubleTap, Operation.LongPress(), Operation.Scroll(app.umbra.core.model.action.Direction.DOWN), Operation.TypeText("x"), Operation.PressKey("ENTER"))) {
            val a = assess(op, inOwnApp)
            assertTrue(a.isSelfProtectionViolation, "${op.name} in the agent's own app must be refused: ${a.describe()}")
        }
        assertEquals(false, assess(Operation.Tap, ctx.copy(foregroundPackage = "com.android.settings")).isSelfProtectionViolation)
        assertEquals(false, assess(Operation.Observe(app.umbra.core.model.action.ObserveLevel.TREE), inOwnApp).isSelfProtectionViolation, "looking is allowed")
    }

    @Test
    fun theAgentCannotAnswerItsApprovalNotificationFromTheShade() {
        val shade = ctx.copy(foregroundPackage = "com.android.systemui", screenTexts = listOf("Umbra", "Approval needed", "Run: rm -rf /sdcard/DCIM", "Approve", "Deny"))
        assertTrue(assess(Operation.Tap, shade).isSelfProtectionViolation)
        val otherShade = ctx.copy(foregroundPackage = "com.android.systemui", screenTexts = listOf("Messages", "Hi there"))
        assertEquals(false, assess(Operation.Tap, otherShade).isSelfProtectionViolation)
    }

    @Test
    fun notificationsOfTheAgentsOwnAppCannotBeActedOn() {
        val own = Operation.Notifications(NotificationAction.PRESS_ACTION, key = "0|app.umbra.agent|1000|null|10223", actionIndex = 0)
        assertTrue(assess(own).isSelfProtectionViolation)
        assertTrue(assess(Operation.Notifications(NotificationAction.DISMISS, key = "0|app.umbra.agent|1|null|10223")).isSelfProtectionViolation)
        assertEquals(false, assess(Operation.Notifications(NotificationAction.LIST)).isSelfProtectionViolation)
        assertEquals(false, assess(Operation.Notifications(NotificationAction.PRESS_ACTION, key = "0|com.whatsapp|1|null|10100", actionIndex = 0)).isSelfProtectionViolation)
    }
}
