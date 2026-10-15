package app.umbra.core.safety

import app.umbra.core.safety.ApprovalSurface.CAPSULE
import app.umbra.core.safety.ApprovalSurface.FULL
import app.umbra.core.safety.ApprovalSurface.NOTIFICATION
import app.umbra.core.safety.ApproveGesture.CONFIRM
import app.umbra.core.safety.ApproveGesture.ONE_TAP
import app.umbra.core.safety.ApproveGesture.OPEN_INBOX
import app.umbra.core.safety.RememberScope.THIS_APP_THIS_SESSION
import app.umbra.core.safety.RememberScope.THIS_CATEGORY_THIS_SESSION
import app.umbra.core.safety.RememberScope.THIS_COMMAND_THIS_SESSION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApprovalSurfacesTest {
    private fun approval(level: RiskLevel, vararg remember: RememberScope) = ApprovalRequest(
        id = "r1", sessionId = "s1", source = ApprovalSource.HOST_TOOL, title = "t", description = "d",
        assessment = RiskAssessment(listOf(RiskFinding(RiskCategory.SYSTEM_CONFIGURATION, level, "because"))),
        createdAtMs = 0, timeoutMs = 1, rememberOptions = remember.toList(),
    )

    @Test
    fun theShadeNeverApprovesHighRisk() {
        assertEquals(ONE_TAP, approveGesture(RiskLevel.LOW, NOTIFICATION))
        assertEquals(ONE_TAP, approveGesture(RiskLevel.MEDIUM, NOTIFICATION))
        assertEquals(OPEN_INBOX, approveGesture(RiskLevel.HIGH, NOTIFICATION))
        assertEquals(OPEN_INBOX, approveGesture(RiskLevel.CRITICAL, NOTIFICATION))
    }

    @Test
    fun theCapsuleStopsAtHigh() {
        assertEquals(ONE_TAP, approveGesture(RiskLevel.HIGH, CAPSULE))
        assertEquals(OPEN_INBOX, approveGesture(RiskLevel.CRITICAL, CAPSULE))
    }

    @Test
    fun criticalInTheInboxNeedsASecondStep() {
        assertEquals(ONE_TAP, approveGesture(RiskLevel.HIGH, FULL))
        assertEquals(CONFIRM, approveGesture(RiskLevel.CRITICAL, FULL))
    }

    @Test
    fun noSurfaceMakesCriticalEasierThanHigh() {
        for (surface in ApprovalSurface.entries) {
            val high = approveGesture(RiskLevel.HIGH, surface)
            val critical = approveGesture(RiskLevel.CRITICAL, surface)
            assert(critical != ONE_TAP || high == ONE_TAP) { "$surface makes CRITICAL easier than HIGH" }
            assert(critical != ONE_TAP) { "$surface approves CRITICAL in one tap" }
        }
    }

    @Test
    fun theOfferedScopeIsTheNarrowest() {
        assertEquals(THIS_COMMAND_THIS_SESSION, offeredRememberScope(approval(RiskLevel.MEDIUM, THIS_CATEGORY_THIS_SESSION, THIS_COMMAND_THIS_SESSION), FULL))
        assertEquals(THIS_APP_THIS_SESSION, offeredRememberScope(approval(RiskLevel.HIGH, THIS_CATEGORY_THIS_SESSION, THIS_APP_THIS_SESSION), FULL))
        assertEquals(THIS_CATEGORY_THIS_SESSION, offeredRememberScope(approval(RiskLevel.HIGH, THIS_CATEGORY_THIS_SESSION), FULL))
    }

    @Test
    fun rememberingNeedsTheWholeRequestAndIsNeverOfferedForCritical() {
        assertNull(offeredRememberScope(approval(RiskLevel.MEDIUM, THIS_COMMAND_THIS_SESSION), CAPSULE))
        assertNull(offeredRememberScope(approval(RiskLevel.MEDIUM, THIS_COMMAND_THIS_SESSION), NOTIFICATION))
        assertNull(offeredRememberScope(approval(RiskLevel.CRITICAL, THIS_COMMAND_THIS_SESSION), FULL))
        assertNull(offeredRememberScope(approval(RiskLevel.MEDIUM), FULL))
    }
}
