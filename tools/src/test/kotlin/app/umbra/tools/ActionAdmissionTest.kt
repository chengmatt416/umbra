package app.umbra.tools

import app.umbra.core.model.action.ActionRequest
import app.umbra.core.model.action.ActionStatus
import app.umbra.core.model.action.Operation
import app.umbra.core.model.action.Target
import app.umbra.core.model.Point
import app.umbra.core.model.observation.UiSnapshot
import app.umbra.core.safety.*
import app.umbra.platform.api.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ActionAdmissionTest {
    private class Apps(var available: () -> Unit = {}) : AppBackend {
        var launches = 0
        var display: Int? = null
        override val name = "fixture-apps"
        override suspend fun isAvailable(): Boolean { available(); return true }
        override suspend fun launch(launch: AppLaunch): LaunchOutcome { launches++; display = launch.displayId; return LaunchOutcome(true) }
        override suspend fun stop(packageName: String, force: Boolean) = Unit
        override suspend fun foreground(displayId: Int?) = emptyList<app.umbra.core.model.observation.ForegroundInfo>()
        override suspend fun bringToFront(packageName: String, displayId: Int?) = false
    }

    @Test
    fun authorityIsCheckedAgainAfterBackendAvailabilityWaits() = runTest {
        var epoch = 0L
        val apps = Apps { epoch++ }
        val gate = SafetyGate(RiskClassifier(), { ApprovalPolicy() }, ApprovalBroker())
        val pipeline = ActionPipeline(StaticBackends(apps = apps), gate, inputEpoch = { _, _, _ -> epoch })
        val request = ActionRequest("availability", Operation.LaunchApp("fixture.app", waitForForeground = false), Target(display = 7))
        assertEquals(ActionStatus.NEEDS_USER, pipeline.execute(request).result.status)
        assertEquals(0, apps.launches)
        apps.available = {}
        assertEquals(ActionStatus.OK, pipeline.execute(request.copy(id = "fresh")).result.status)
        assertEquals(1, apps.launches)
    }

    @Test
    fun displayLaunchUsesTheActualDisplayAndHonorsTakeback() = runTest {
        val apps = Apps()
        val displays = object : DisplayBackend {
            override val name = "fixture-displays"
            override suspend fun isAvailable() = true
            override suspend fun list() = emptyList<app.umbra.core.model.observation.DisplayInfo>()
            override suspend fun create(spec: VirtualDisplaySpec) = error("unused")
            override suspend fun destroy(displayId: Int) = Unit
            override suspend fun ownedDisplays() = list()
        }
        val gate = SafetyGate(RiskClassifier(), { ApprovalPolicy() }, ApprovalBroker())
        val pipeline = ActionPipeline(StaticBackends(apps = apps, displays = displays), gate,
            inputGate = { _, display, touches -> if (touches && display == 0) "Taken back by the owner" else null })
        val op = Operation.Display(app.umbra.core.model.action.DisplayAction.LAUNCH, displayId = 0, packageName = "fixture.app")
        assertEquals(ActionStatus.NEEDS_USER, pipeline.execute(ActionRequest("display-zero", op)).result.status)
        assertEquals(0, apps.launches)
        assertEquals(ActionStatus.OK, pipeline.execute(ActionRequest("background", op.copy(displayId = 7))).result.status)
        assertEquals(1, apps.launches)
        assertEquals(7, apps.display)
    }

    @Test
    fun aLockdownThatWasAlreadyLiftedStillInvalidatesAPendingReview() = runTest {
        var calls = 0
        var epoch = 0L
        val review = CompletableDeferred<RiskAssessment>()
        val shell = object : ShellBackend {
            override val name = "fixture"
            override val identity = ShellIdentity.APP
            override suspend fun isAvailable() = true
            override suspend fun exec(request: ShellRequest): ShellResult { calls++; return ShellResult(0, "fixture") }
            override suspend fun execBytes(request: ShellRequest) = BinaryShellResult(0, byteArrayOf())
            override fun stream(request: ShellRequest) = emptyFlow<ShellEvent>()
        }
        val gate = SafetyGate(RiskClassifier(), { ApprovalPolicy() }, ApprovalBroker(), securityCheck = ActionSecurityCheck { _, _ -> review.await() })
        val pipeline = ActionPipeline(StaticBackends(shells = listOf(shell)), gate, inputEpoch = { _, _, _ -> epoch })
        val action = async { pipeline.execute(ActionRequest("pending", Operation.Shell("echo fixture"))) }
        runCurrent()
        epoch += 2 // Lock, then lift before the review finishes.
        review.complete(RiskAssessment.NONE)
        assertEquals(ActionStatus.NEEDS_USER, action.await().result.status)
        assertEquals(0, calls)
        assertEquals(ActionStatus.OK, pipeline.execute(ActionRequest("fresh", Operation.Shell("echo fixture"))).result.status)
        assertEquals(1, calls, "Fresh work after control is returned remains usable")
    }

    @Test
    fun takingControlBetweenStrategiesPreventsTheFallbackTap() = runTest {
        var epoch = 0L
        var calls = 0
        val ui = object : UiBackend {
            override val name = "fixture"
            override val kind = app.umbra.core.model.capability.UiBackendKind.ACCESSIBILITY
            override val features = setOf(UiFeature.GESTURES)
            override suspend fun isAvailable() = true
            override suspend fun snapshot(options: SnapshotOptions) = UiSnapshot()
            override suspend fun performAction(node: app.umbra.core.model.observation.UiNode, action: app.umbra.core.model.observation.NodeAction, args: NodeActionArgs) = false
            override suspend fun longPress(displayId: Int?, point: Point, durationMs: Long) = error("unused")
            override suspend fun swipe(displayId: Int?, from: Point, to: Point, durationMs: Long) = error("unused")
            override suspend fun drag(displayId: Int?, from: Point, to: Point, holdMs: Long, durationMs: Long) = error("unused")
            override suspend fun key(displayId: Int?, key: String) = error("unused")
            override suspend fun typeText(displayId: Int?, text: String) = error("unused")
            override suspend fun global(action: app.umbra.core.model.action.GlobalAction) = false
            override suspend fun tap(displayId: Int?, point: Point) {
                calls++
                epoch++
                throw BackendException(app.umbra.core.model.action.ErrorCode.BACKEND_UNAVAILABLE, "fixture failure", retryable = true)
            }
        }
        val gate = SafetyGate(RiskClassifier(), { ApprovalPolicy() }, ApprovalBroker())
        val pipeline = ActionPipeline(StaticBackends(androidUi = listOf(ui)), gate, inputEpoch = { _, _, _ -> epoch })
        val request = ActionRequest("tap", Operation.Tap, target = Target(point = Point(20, 30)))
        assertEquals(ActionStatus.NEEDS_USER, pipeline.execute(request).result.status)
        assertEquals(1, calls, "No retry or alternate strategy may run with stale authority")
    }

    @Test
    fun lockdownDuringIndependentReviewStopsANonUiActionBeforeDispatch() = runTest {
        var calls = 0
        var locked = false
        val review = CompletableDeferred<RiskAssessment>()
        val shell = object : ShellBackend {
            override val name = "dummy"
            override val identity = ShellIdentity.APP
            override suspend fun isAvailable() = true
            override suspend fun exec(request: ShellRequest): ShellResult { calls++; return ShellResult(0, "dummy") }
            override suspend fun execBytes(request: ShellRequest) = BinaryShellResult(0, byteArrayOf())
            override fun stream(request: ShellRequest) = emptyFlow<ShellEvent>()
        }
        val gate = SafetyGate(RiskClassifier(), { ApprovalPolicy() }, ApprovalBroker(), securityCheck = ActionSecurityCheck { _, _ -> review.await() })
        val pipeline = ActionPipeline(StaticBackends(shells = listOf(shell)), gate, inputGate = { _, _, _ -> if (locked) "Locked by owner" else null })
        val action = async { pipeline.execute(ActionRequest("dummy", Operation.Shell("echo fixture"))) }
        runCurrent()
        locked = true
        review.complete(RiskAssessment.NONE)
        assertEquals(ActionStatus.NEEDS_USER, action.await().result.status)
        assertEquals(0, calls, "No executor may receive work after lockdown during review")
    }
}
