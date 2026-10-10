package app.umbra.core.orchestrator

import app.umbra.core.safety.ApprovalDecision
import app.umbra.core.safety.ApprovalMode
import app.umbra.core.safety.ApprovalSource
import app.umbra.core.session.SessionCommand
import app.umbra.core.session.SessionEvent
import app.umbra.core.session.SessionState
import app.umbra.runtime.omp.AskAnswer
import app.umbra.runtime.omp.UiResponse
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The production orchestrator driving the real omp binary (see [OrchestratorRig]). Skipped when `omp`
 * is not installed. Each test checks one behaviour a user depends on: that a task completes and is
 * recorded, that the safety system decides what omp may run, that pause/stop/resume and process
 * failures are handled, and that the agent can ask the user questions.
 */
class AgentSessionTest {
    @TempDir
    lateinit var dir: Path

    private val ompBinary: File? = listOf(
        System.getenv("OMP_BIN"),
        *System.getenv("PATH").orEmpty().split(File.pathSeparator).map { "$it/omp" }.toTypedArray(),
        "${System.getProperty("user.home")}/.bun/bin/omp",
    ).filterNotNull().map(::File).firstOrNull { it.canExecute() }

    private fun withRig(
        model: ScriptedModel,
        mode: ApprovalMode = ApprovalMode.BALANCED,
        governor: app.umbra.core.perf.ResourceGovernor? = null,
        block: suspend OrchestratorRig.() -> Unit,
    ) {
        assumeTrue(ompBinary != null, "omp is not installed; skipping the orchestrator test")
        model.use {
            OrchestratorRig(dir, ompBinary!!, it.url, mode, governor = governor).use { rig ->
                runBlocking {
                    try {
                        rig.block()
                    } catch (t: Throwable) {
                        System.err.println("--- timeline ---")
                        rig.store.list().forEach { s -> rig.events(s.id).forEach { e -> System.err.println("${e.seq} ${e.event.toString().take(300)}") } }
                        throw t
                    }
                }
            }
        }
    }

    // ---- lifecycle ----

    @Test
    fun aTaskRunsToCompletionAndIsRecordedInTheTimeline() {
        val model = ScriptedModel { ScriptedModel.text("All done.") }
        withRig(model) {
            val session = supervisor.launch("Say hello")
            val done = awaitState(session.id, SessionState.COMPLETED)
            val events = events(session.id).map { it.event }

            assertTrue(events.any { it is SessionEvent.UserMessage && it.text == "Say hello" }, "the request is the first user message")
            assertTrue(events.any { it is SessionEvent.AgentMessage && it.text == "All done." }, "the agent's reply is stored")
            assertEquals(listOf(SessionState.RUNNING, SessionState.COMPLETED), events.filterIsInstance<SessionEvent.StateChanged>().map { it.to })
            assertNotNull(done.ompSessionFile, "the omp conversation file is remembered so the session can be resumed")
            assertEquals(session.title, "Say hello")
            assertTrue(workspaceRoot.resolve("projects").toFile().exists(), "the session got its own workspace")
        }
    }

    @Test
    fun theAgentIsToldWhereItIsAndGetsTheAndroidToolsButNotTheDesktopOnes() {
        val model = ScriptedModel { ScriptedModel.text("ok") }
        withRig(model) {
            val session = supervisor.launch("hi")
            awaitState(session.id, SessionState.COMPLETED)
            val request = model.requests.first()
            assertTrue("Umbra: you operate an Android phone" in request.systemText, "the Umbra briefing is part of the system prompt")
            assertTrue("Shell: available" in request.systemText, "the capabilities of the device are included")
            for (tool in listOf("android_observe", "android_tap", "android_shell", "verify", "decide")) assertTrue(tool in request.toolNames, "$tool is offered: ${request.toolNames}")
            assertFalse("gui_observe" in request.toolNames, "no desktop exists, so no desktop tools")
            assertFalse("computer" in request.toolNames, "omp's own desktop tool is off")
            assertTrue("bash" in request.toolNames, "the Linux shell stays")
        }
    }

    @Test
    fun aFollowUpContinuesTheSameConversationAfterCompletion() {
        val model = ScriptedModel { request -> ScriptedModel.text(if (request.lastUserText?.contains("again") == true) "second answer" else "first answer") }
        withRig(model) {
            val session = supervisor.launch("first request")
            awaitState(session.id, SessionState.COMPLETED)
            supervisor.send(session.id, "do it again")
            awaitEvent(session.id) { it is SessionEvent.AgentMessage && it.text == "second answer" }
            awaitState(session.id, SessionState.COMPLETED)
            val messages = model.requests.last().body["messages"].toString()
            assertTrue("first request" in messages && "do it again" in messages, "the second turn sees the first: the conversation continued")
            val transitions = events(session.id).map { it.event }.filterIsInstance<SessionEvent.StateChanged>().map { it.to }
            assertEquals(listOf(SessionState.RUNNING, SessionState.COMPLETED, SessionState.RUNNING, SessionState.COMPLETED), transitions)
        }
    }

    @Test
    fun aModelErrorFailsTheSessionWithTheReason() {
        val model = ScriptedModel { ScriptedModel.error(401, "invalid api key") }
        withRig(model) {
            val session = supervisor.launch("hello")
            val failed = awaitState(session.id, SessionState.FAILED)
            assertTrue(failed.lastError.orEmpty().contains("invalid api key", ignoreCase = true), "the user sees why: ${failed.lastError}")
            assertEquals(1, failed.failureCount)
        }
    }

    // ---- tools ----

    @Test
    fun aHarmlessShellCommandRunsWithoutAskingAndIsRecorded() {
        val model = ScriptedModel { r -> if (r.lastToolResult == null) ScriptedModel.toolCall("bash", """{"command":"pwd && echo hello-from-bash"}""") else ScriptedModel.text("saw ${r.lastToolResult!!.take(200)}") }
        withRig(model) {
            val asked = CopyOnWriteArrayList<Int>()
            scope.launch { approvals.pending.collect { asked += it.size } }
            val session = supervisor.launch("run a command")
            awaitState(session.id, SessionState.COMPLETED)
            assertTrue(asked.all { it == 0 }, "nothing needed the user's attention")
            val events = events(session.id).map { it.event }
            val call = events.filterIsInstance<SessionEvent.ToolCall>().single { it.tool == "bash" }
            assertEquals("omp", call.source)
            val result = events.filterIsInstance<SessionEvent.ToolResult>().single { it.callId == call.callId }
            assertEquals("ok", result.status)
            assertTrue("hello-from-bash" in result.summary, result.summary)
            assertTrue(workspaceRoot.toAbsolutePath().normalize().toString() in result.summary, "the command ran in the session's workspace: ${result.summary}")
        }
    }

    @Test
    fun aRiskyCommandAsksTheUserAndDenialStopsIt() {
        val marker = dir.resolve("marker-denied")
        val model = ScriptedModel { r ->
            if (r.lastToolResult == null) ScriptedModel.toolCall("bash", """{"command":"touch $marker; pm uninstall com.whatsapp"}""")
            else ScriptedModel.text("could not: ${r.lastToolResult!!.take(100)}")
        }
        withRig(model) {
            val session = supervisor.launch("remove an app")
            val request = withTimeout(60_000) { approvals.pending.first { it.isNotEmpty() }.first() }
            assertEquals(ApprovalSource.OMP, request.source)
            assertTrue("pm uninstall" in request.description, request.description)
            assertTrue(request.assessment.hitsSpecCategory, "deleting app data is a category that always asks")
            awaitState(session.id, SessionState.WAITING_APPROVAL)
            assertTrue(approvals.resolve(request.id, ApprovalDecision.Denied("no")))
            awaitState(session.id, SessionState.COMPLETED)
            assertFalse(marker.toFile().exists(), "a denied command must not run at all")
            val result = events(session.id).map { it.event }.filterIsInstance<SessionEvent.ToolResult>().single { it.tool == "bash" }
            assertEquals("failed", result.status)
            assertTrue(events(session.id).any { it.event is SessionEvent.StateChanged && (it.event as SessionEvent.StateChanged).to == SessionState.WAITING_APPROVAL })
        }
    }

    @Test
    fun anApprovedRiskyCommandRuns() {
        val marker = dir.resolve("marker-approved")
        val model = ScriptedModel { r ->
            if (r.lastToolResult == null) ScriptedModel.toolCall("bash", """{"command":"touch $marker; pm uninstall com.whatsapp"}""") else ScriptedModel.text("done")
        }
        withRig(model) {
            val session = supervisor.launch("remove an app")
            val request = withTimeout(60_000) { approvals.pending.first { it.isNotEmpty() }.first() }
            approvals.resolve(request.id, ApprovalDecision.Approved())
            awaitState(session.id, SessionState.COMPLETED)
            assertTrue(marker.toFile().exists(), "an approved command runs")
        }
    }

    @Test
    fun theAgentCanNeverStopItsOwnAppEvenWhenAsking() {
        val model = ScriptedModel { r -> if (r.lastToolResult == null) ScriptedModel.toolCall("bash", """{"command":"am force-stop app.umbra.agent"}""") else ScriptedModel.text("ok") }
        withRig(model, ApprovalMode.AUTONOMOUS) {
            val session = supervisor.launch("stop yourself")
            awaitState(session.id, SessionState.COMPLETED)
            assertTrue(approvals.pending.value.isEmpty(), "self-protection is not a question for the user")
            val result = events(session.id).map { it.event }.filterIsInstance<SessionEvent.ToolResult>().single { it.tool == "bash" }
            assertEquals("failed", result.status, "denied outright")
        }
    }

    @Test
    fun aHostToolCallIsRecordedWithItsStructuredResult() {
        val model = ScriptedModel { r ->
            if (r.lastToolResult == null) ScriptedModel.toolCall("android_shell", """{"command":"echo from-host-tool"}""") else ScriptedModel.text("host said: ${r.lastToolResult!!.take(120)}")
        }
        withRig(model) {
            val session = supervisor.launch("use the phone shell")
            awaitState(session.id, SessionState.COMPLETED)
            val events = events(session.id).map { it.event }
            val call = events.filterIsInstance<SessionEvent.ToolCall>().single { it.tool == "android_shell" }
            assertEquals("host", call.source)
            val result = events.filterIsInstance<SessionEvent.ToolResult>().single { it.callId == call.callId }
            assertEquals("ok", result.status, result.summary)
            assertTrue("from-host-tool" in result.summary, result.summary)
            assertNotNull(result.backend, "the backend that ran it is recorded")
            assertEquals(1, events.count { it is SessionEvent.ToolCall && it.tool == "android_shell" }, "host tool calls are not recorded twice")
        }
    }

    @Test
    fun secretsTypedByTheAgentNeverReachTheTimeline() {
        val model = ScriptedModel { r ->
            if (r.lastToolResult == null) ScriptedModel.toolCall("bash", """{"command":"echo hi # password=hunter2secret and token=abcdef123456"}""") else ScriptedModel.text("ok")
        }
        withRig(model) {
            val session = supervisor.launch("x")
            awaitState(session.id, SessionState.COMPLETED)
            val stored = events(session.id).joinToString("\n") { it.event.toString() }
            assertFalse("hunter2secret" in stored, "a password in a command is scrubbed before it is stored")
            assertFalse("abcdef123456" in stored)
        }
    }

    // ---- questions ----

    @Test
    fun theAgentCanAskTheUserAQuestionAndGetsTheAnswer() {
        val model = ScriptedModel { r ->
            if (r.lastToolResult == null) ScriptedModel.toolCall("ask", """{"questions":[{"id":"q1","question":"Which color?","options":[{"label":"Red"},{"label":"Blue"}]}]}""")
            else ScriptedModel.text("you chose: ${r.lastToolResult!!.take(200)}")
        }
        withRig(model) {
            val session = supervisor.launch("pick a color for me")
            val pending = withTimeout(60_000) { questions.pending.first { it.isNotEmpty() }.first() }
            assertEquals(session.id, pending.sessionId)
            awaitState(session.id, SessionState.WAITING_APPROVAL)
            val ask = pending.request as app.umbra.runtime.omp.UiRequest.Ask
            questions.answer(ask.id, UiResponse.Answers(listOf(AskAnswer("q1", listOf("Blue")))))
            awaitState(session.id, SessionState.COMPLETED)
            assertTrue("Blue" in model.requests.last().lastToolResult.orEmpty(), "the model received the user's answer: ${model.requests.last().lastToolResult}")
        }
    }

    // ---- control ----

    @Test
    fun pausingStopsTheTurnAndResumingPicksTheTaskBackUp() {
        val calls = AtomicInteger()
        val model = ScriptedModel { if (calls.incrementAndGet() == 1) ScriptedModel.text("too slow", delayMs = 30_000) else ScriptedModel.text("picked up") }
        withRig(model) {
            val session = supervisor.launch("long task")
            awaitState(session.id, SessionState.RUNNING)
            // Wait until the model request is in flight, then pause.
            withTimeout(30_000) { while (model.requests.isEmpty()) delay(50) }
            supervisor.pause(session.id)
            awaitState(session.id, SessionState.PAUSED)
            delay(500)
            assertEquals(SessionState.PAUSED, session(session.id).state, "an aborted turn must not move a paused session to completed")
            supervisor.resume(session.id)
            awaitEvent(session.id) { it is SessionEvent.AgentMessage && it.text == "picked up" }
            awaitState(session.id, SessionState.COMPLETED)
            assertTrue(model.requests.last().lastUserText.orEmpty().contains("resumed"), "the agent was told the task resumed")
        }
    }

    @Test
    fun stoppingEndsTheSessionAndLeavesNoProcessBehind() {
        val model = ScriptedModel { ScriptedModel.text("never", delayMs = 30_000) }
        withRig(model) {
            val session = supervisor.launch("endless")
            withTimeout(30_000) { while (model.requests.isEmpty()) delay(50) }
            val pid = supervisor.liveSession(session.id)?.localProcessId
            assertNotNull(pid)
            supervisor.stop(session.id)
            assertEquals(SessionState.STOPPED, session(session.id).state)
            withTimeout(10_000) { while (ProcessHandle.of(pid!!).map { it.isAlive }.orElse(false)) delay(100) }
            assertEquals(null, supervisor.liveSession(session.id), "a stopped session holds no live agent")
        }
    }

    @Test
    fun stopAllEndsEveryRunningAndPausedTaskAndLeavesFinishedOnesAlone() {
        val calls = AtomicInteger()
        val model = ScriptedModel { if (calls.incrementAndGet() == 1) ScriptedModel.text("finished quickly") else ScriptedModel.text("never", delayMs = 30_000) }
        withRig(model) {
            val finished = supervisor.launch("quick one")
            awaitState(finished.id, SessionState.COMPLETED)
            val running = supervisor.launch("slow one")
            val paused = supervisor.launch("another slow one")
            withTimeout(30_000) { while (model.requests.size < 3) delay(50) }
            supervisor.pause(paused.id)
            awaitState(paused.id, SessionState.PAUSED)

            supervisor.stopAll()

            assertEquals(SessionState.STOPPED, session(running.id).state)
            assertEquals(SessionState.STOPPED, session(paused.id).state)
            assertEquals(SessionState.COMPLETED, session(finished.id).state, "a task that already finished stays finished")
            assertEquals(null, supervisor.liveSession(running.id), "no agent is left running")
        }
    }

    @Test
    fun deleteAllRemovesEveryTaskWithItsTimeline() {
        val model = ScriptedModel { ScriptedModel.text("done") }
        withRig(model) {
            val a = supervisor.launch("first")
            val b = supervisor.launch("second")
            awaitState(a.id, SessionState.COMPLETED)
            awaitState(b.id, SessionState.COMPLETED)
            assertEquals(2, store.list().size)

            supervisor.deleteAll()

            assertEquals(emptyList(), store.list())
            assertEquals(emptyList(), events(a.id), "the timeline goes with the task")
            assertEquals(null, supervisor.liveSession(a.id))
        }
    }

    @Test
    fun aKilledAgentProcessIsRestartedAndTheTaskContinues() {
        val calls = AtomicInteger()
        val model = ScriptedModel { if (calls.incrementAndGet() == 1) ScriptedModel.text("slow", delayMs = 30_000) else ScriptedModel.text("recovered") }
        withRig(model) {
            val session = supervisor.launch("survive a crash")
            withTimeout(30_000) { while (model.requests.isEmpty()) delay(50) }
            val pid = supervisor.liveSession(session.id)!!.localProcessId!!
            ProcessHandle.of(pid).get().destroyForcibly()

            awaitEvent(session.id) { it is SessionEvent.Failure && it.subsystem == "omp" }
            awaitEvent(session.id) { it is SessionEvent.Recovery && it.success }
            awaitEvent(session.id) { it is SessionEvent.AgentMessage && it.text == "recovered" }
            awaitState(session.id, SessionState.COMPLETED)
            assertTrue(model.requests.last().lastUserText.orEmpty().contains("restarted"), "the agent was told its process restarted: ${model.requests.last().lastUserText}")
        }
    }

    @Test
    fun sessionsLeftRunningByAPreviousRunArePausedNotLost() {
        val model = ScriptedModel { ScriptedModel.text("unused") }
        withRig(model) {
            val s = supervisor.create("interrupted work")
            val service = app.umbra.core.session.SessionService(store)
            service.apply(s.id, SessionCommand.START)
            assertEquals(SessionState.RUNNING, session(s.id).state)
            supervisor.recover()
            assertEquals(SessionState.PAUSED, session(s.id).state)
            val note = events(s.id).map { it.event }.filterIsInstance<SessionEvent.StateChanged>().last()
            assertTrue(note.reason.orEmpty().contains("restarted"), "the user is told why: ${note.reason}")
        }
    }

    @Test
    fun aFinishedTasksIdleAgentIsReleasedWhenThePhoneIsShortOnMemoryAndANewTaskCanThenStart() {
        val model = ScriptedModel { ScriptedModel.text("done") }
        // Memory looks short while any omp process of this test run exists, which is what a finished task leaves behind.
        val sampler = app.umbra.core.perf.ResourceSampler {
            val omp = ProcessHandle.current().descendants().anyMatch { h -> h.info().commandLine().map { "omp" in it }.orElse(false) }
            app.umbra.core.perf.ResourceSample(timestampMs = System.currentTimeMillis(), memAvailMb = if (omp) 100 else 8_000, memTotalMb = 8_000)
        }
        val governor = app.umbra.core.perf.ResourceGovernor(sampler, intervalMs = 150)
        val ompRunning = { ProcessHandle.current().descendants().anyMatch { h -> h.info().commandLine().map { "omp" in it }.orElse(false) } }
        withRig(model, governor = governor) {
            governor.start(scope)
            // The first task needs a slot before the pressure it will cause exists.
            val first = supervisor.launch("first task")
            awaitState(first.id, SessionState.COMPLETED)
            // Its agent process now sits idle, and the phone says it is short of memory: the process goes, the task stays.
            withTimeout(60_000) { while (ompRunning()) delay(200) }
            assertEquals(SessionState.COMPLETED, session(first.id).state, "the finished task is still there")
            // Once the pressure has eased (a few good samples), a new task is accepted.
            val second = withTimeout(60_000) {
                var started: app.umbra.core.session.Session? = null
                while (started == null) {
                    started = try { supervisor.launch("second task") } catch (_: CapacityException) { delay(300); null }
                }
                started
            }
            awaitState(second.id, SessionState.COMPLETED)
        }
    }
}
