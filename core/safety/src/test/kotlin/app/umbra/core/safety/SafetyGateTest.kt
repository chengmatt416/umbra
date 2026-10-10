package app.umbra.core.safety

import app.umbra.core.model.Clock
import app.umbra.core.model.action.ActionStatus
import app.umbra.core.model.action.ErrorCode
import app.umbra.core.model.action.Operation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class SafetyGateTest {
    private class MemoryLedger : ApprovalLedger {
        val entries = mutableListOf<ApprovalLedgerEntry>()
        override suspend fun record(entry: ApprovalLedgerEntry) { entries += entry }
        val kinds get() = entries.map { it.kind }
    }

    private class Fixture(scope: TestScope, mode: ApprovalMode = ApprovalMode.BALANCED, timeoutMs: Long = 60_000) {
        val clock = Clock { scope.currentTime }
        val ledger = MemoryLedger()
        val broker = ApprovalBroker(clock, ledger)
        var policy = ApprovalPolicy(mode, clock = clock)
        val gate = SafetyGate(RiskClassifier(), { policy }, broker, clock, timeoutMs)
    }

    private val ctx = ActionContext(workspaceRoots = listOf("/workspace/demo"))
    private val risky = request(Operation.Shell("rm -rf /sdcard/DCIM"), id = "t1")
    private val benign = request(Operation.Shell("ls -la"), id = "t2")

    @Test
    fun benignActionsProceedWithoutAskingAnyone() = runTest {
        val f = Fixture(this)
        val outcome = f.gate.check(benign, ctx)
        assertIs<SafetyGate.Outcome.Proceed>(outcome)
        assertTrue(f.broker.pending.value.isEmpty())
        assertTrue(f.ledger.entries.isEmpty())
    }

    @Test
    fun riskyActionWaitsForApprovalThenProceeds() = runTest {
        val f = Fixture(this)
        val pending = async { f.gate.check(risky, ctx) }
        runCurrent()
        val shown = f.broker.pending.value.single()
        assertEquals("s1", shown.sessionId)
        assertTrue("rm -rf /sdcard/DCIM" in shown.description, shown.description)
        assertTrue(shown.assessment.level >= RiskLevel.HIGH)
        assertFalse(pending.isCompleted)

        assertTrue(f.broker.resolve(shown.id, ApprovalDecision.Approved()))
        val outcome = pending.await()
        assertIs<SafetyGate.Outcome.Proceed>(outcome)
        assertEquals("approved by the user", outcome.how)
        assertEquals(listOf("requested", "approved"), f.ledger.kinds)
        assertTrue(f.broker.pending.value.isEmpty())
    }

    @Test
    fun deniedActionBecomesAStructuredResultTheAgentCanReadAndNotRetry() = runTest {
        val f = Fixture(this)
        val pending = async { f.gate.check(risky, ctx) }
        runCurrent()
        f.broker.resolve(f.broker.pending.value.single().id, ApprovalDecision.Denied("not my photos"))
        val outcome = pending.await()
        assertIs<SafetyGate.Outcome.Blocked>(outcome)
        assertEquals(ActionStatus.DENIED, outcome.result.status)
        assertEquals(ErrorCode.DENIED_BY_USER, outcome.result.error?.code)
        assertTrue("Do not retry" in outcome.result.summary && "not my photos" in outcome.result.summary, outcome.result.summary)
        assertEquals(listOf("requested", "denied"), f.ledger.kinds)
    }

    @Test
    fun noAnswerFailsClosedAsNeedsUser() = runTest {
        val f = Fixture(this, timeoutMs = 30_000)
        val pending = async { f.gate.check(risky, ctx) }
        runCurrent()
        advanceTimeBy(30_001)
        val outcome = pending.await()
        assertIs<SafetyGate.Outcome.Blocked>(outcome)
        assertEquals(ActionStatus.NEEDS_USER, outcome.result.status)
        assertEquals(listOf("requested", "timed_out"), f.ledger.kinds)
        assertTrue(f.broker.pending.value.isEmpty())
    }

    @Test
    fun selfProtectionIsDeniedWithoutBotheringTheUser() = runTest {
        val f = Fixture(this, mode = ApprovalMode.AUTONOMOUS)
        val outcome = f.gate.check(request(Operation.Shell("pm uninstall app.umbra.agent")), ctx)
        assertIs<SafetyGate.Outcome.Blocked>(outcome)
        assertEquals(ActionStatus.DENIED, outcome.result.status)
        assertEquals(ErrorCode.DENIED_BY_POLICY, outcome.result.error?.code)
        assertTrue(f.broker.pending.value.isEmpty())
        assertEquals(listOf("auto_denied"), f.ledger.kinds)
    }

    @Test
    fun rememberedApprovalAutoAllowsTheSameKindOfActionThisSession() = runTest {
        val f = Fixture(this)
        val first = async { f.gate.check(risky, ctx.copy(foregroundPackage = "com.example.files")) }
        runCurrent()
        f.broker.resolve(f.broker.pending.value.single().id, ApprovalDecision.Approved(RememberScope.THIS_CATEGORY_THIS_SESSION))
        assertIs<SafetyGate.Outcome.Proceed>(first.await())
        assertEquals(1, f.gate.rulesFor("s1").size)

        val second = f.gate.check(request(Operation.Shell("rm -rf /sdcard/Download"), id = "t3"), ctx)
        assertIs<SafetyGate.Outcome.Proceed>(second)
        assertTrue("remembered approval" in second.how, second.how)
        assertEquals(listOf("requested", "approved", "auto_allowed"), f.ledger.kinds)

        // Other sessions are unaffected.
        val other = async { f.gate.check(request(Operation.Shell("rm -rf /sdcard/Download"), id = "t4", session = "s2"), ctx) }
        runCurrent()
        assertEquals(1, f.broker.pending.value.size)
        f.broker.resolve(f.broker.pending.value.single().id, ApprovalDecision.Denied())
        assertIs<SafetyGate.Outcome.Blocked>(other.await())
    }

    @Test
    fun criticalActionsAskEveryTimeEvenAfterRemember() = runTest {
        val f = Fixture(this)
        val reset = request(Operation.Shell("am broadcast -a android.intent.action.MASTER_CLEAR"), id = "c1")
        val first = async { f.gate.check(reset, ctx) }
        runCurrent()
        f.broker.resolve(f.broker.pending.value.single().id, ApprovalDecision.Approved(RememberScope.THIS_CATEGORY_THIS_SESSION))
        first.await()
        assertEquals(0, f.gate.rulesFor("s1").size, "CRITICAL findings are never remembered")

        val second = async { f.gate.check(reset.copy(id = "c2"), ctx) }
        runCurrent()
        assertEquals(1, f.broker.pending.value.size, "must ask again")
        f.broker.cancelAll()
        assertIs<SafetyGate.Outcome.Blocked>(second.await())
    }

    @Test
    fun stoppingASessionCancelsItsPendingApprovals() = runTest {
        val f = Fixture(this)
        val s1 = async { f.gate.check(risky, ctx) }
        val s2 = async { f.gate.check(request(Operation.Shell("rm -rf /sdcard/Pictures"), id = "t9", session = "s2"), ctx) }
        runCurrent()
        assertEquals(2, f.broker.pending.value.size)

        f.broker.cancelSession("s1")
        val outcome = s1.await()
        assertIs<SafetyGate.Outcome.Blocked>(outcome)
        assertEquals(ActionStatus.CANCELLED, outcome.result.status)
        assertEquals(listOf("s2"), f.broker.pending.value.map { it.sessionId })
        f.broker.cancelAll()
        s2.await()
    }

    @Test
    fun cancellingTheCallerStillLeavesALedgerTrace() = runTest {
        val f = Fixture(this)
        val job = async { f.gate.check(risky, ctx) }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue("cancelled" in f.ledger.kinds, f.ledger.kinds.toString())
        assertTrue(f.broker.pending.value.isEmpty())
    }

    @Test
    fun resolvingAnUnknownRequestIsHarmless() = runTest {
        val f = Fixture(this)
        assertFalse(f.broker.resolve("nope", ApprovalDecision.Approved()))
    }

    @Test
    fun policyModeChangesTakeEffectImmediately() = runTest {
        val f = Fixture(this, mode = ApprovalMode.AUTONOMOUS)
        val settingChange = request(Operation.Shell("settings put global some_unknown_flag 1"), id = "m1")
        assertIs<SafetyGate.Outcome.Proceed>(f.gate.check(settingChange, ctx))

        f.policy = f.policy.withMode(ApprovalMode.CAUTIOUS)
        val pending = async { f.gate.check(settingChange.copy(id = "m2"), ctx) }
        runCurrent()
        assertNotNull(f.broker.pending.value.singleOrNull())
        f.broker.cancelAll()
        pending.await()
    }

    @Test
    fun approvalPromptsNeverContainSecrets() = runTest {
        val f = Fixture(this)
        val typed = request(Operation.TypeText("correct-horse-battery-staple", secret = true), id = "p1")
        val node = passwordField()
        val pending = async { f.gate.check(typed, ctx.copy(targetNode = node, foregroundPackage = "com.example.app")) }
        runCurrent()
        val shown = f.broker.pending.value.single()
        assertFalse("correct-horse" in shown.description, shown.description)
        assertFalse(shown.details.values.any { "correct-horse" in it })
        f.broker.cancelAll()
        pending.await()
    }
}
