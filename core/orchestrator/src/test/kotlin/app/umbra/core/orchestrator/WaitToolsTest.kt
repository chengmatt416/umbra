package app.umbra.core.orchestrator

import app.umbra.core.safety.ApprovalBroker
import app.umbra.core.safety.ApprovalPolicy
import app.umbra.core.safety.RiskClassifier
import app.umbra.core.safety.SafetyGate
import app.umbra.core.session.InMemorySessionStore
import app.umbra.core.session.ProactiveTurnMode
import app.umbra.core.session.Session
import app.umbra.core.session.SessionKind
import app.umbra.core.session.SessionService
import app.umbra.core.session.SessionState
import app.umbra.core.workspace.WorkspaceLayout
import app.umbra.core.workspace.WorkspaceManager
import app.umbra.runtime.omp.ContentBlock
import app.umbra.runtime.omp.HostToolCall
import app.umbra.runtime.omp.HostToolPayload
import app.umbra.runtime.omp.HostToolProgress
import app.umbra.tools.HostSideTool
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir

class WaitToolsTest {
    @TempDir
    lateinit var dir: Path

    private data class Fixture(val store: InMemorySessionStore, val supervisor: AgentSupervisor, val bus: SessionBus, val tool: HostSideTool)

    private suspend fun TestScope.fixture(
        exec: suspend (String, Long) -> Int? = { _, _ -> error("Unexpected Linux probe") },
        finalText: suspend (String) -> String = { "" },
    ): Fixture {
        val store = InMemorySessionStore()
        store.create(session("caller"))
        val approvals = ApprovalBroker()
        val supervisor = AgentSupervisor(SessionDeps(
            store = store,
            service = SessionService(store),
            workspaces = WorkspaceManager(WorkspaceLayout(dir.resolve("workspaces"), "/workspace")),
            environment = LocalEnvironment("unused-omp", dir.resolve("home"), dir.resolve("workspaces")),
            providers = ProviderResolver { error("Unexpected model access") },
            toolHosts = { error("Unexpected agent launch") },
            gate = SafetyGate(RiskClassifier(), { ApprovalPolicy() }, approvals),
            approvals = approvals,
            questions = QuestionBroker(),
            prompts = { PromptContext(deviceModel = "test") },
            checkToolAuthority = {},
        ), backgroundScope)
        val bus = SessionBus(supervisor, store, backgroundScope)
        return Fixture(store, supervisor, bus, WaitTools(store, finalText, bus, exec).forSession("caller").single())
    }

    private fun session(id: String, state: SessionState = SessionState.RUNNING) =
        Session(id = id, title = id, request = id, createdAtMs = 0, state = state, handle = id)

    private suspend fun HostSideTool.call(json: String, progress: HostToolProgress = HostToolProgress { }): HostToolPayload =
        run(HostToolCall("call", "call", name, Json.parseToJsonElement(json).jsonObject), progress)

    private fun HostToolPayload.text() = content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }

    @Test fun timeoutIsRequiredInTheSchemaAndAtRuntime() = runTest {
        val tool = fixture().tool
        assertTrue(JsonPrimitive("timeout_s") in (tool.definition.parameters["required"] as JsonArray))
        listOf("", ",\"timeout_s\":4", ",\"timeout_s\":3601", ",\"timeout_s\":\"10\"").forEach { timeout ->
            val result = tool.call("""{"until":"condition","command":"test -f out.apk"$timeout}""")
            assertTrue(result.isError)
            assertTrue("wait_until needs timeout_s: how long" in result.text())
        }
    }

    @Test fun conditionIsRetriedUntilItSucceeds() = runTest {
        var calls = 0
        val tool = fixture(exec = { command, timeout ->
            assertEquals("test -f build/out.apk", command)
            assertTrue(timeout in 1..5_000)
            if (++calls == 1) 1 else 0
        }).tool
        val result = tool.call("""{"until":"condition","command":"test -f build/out.apk","interval_s":2,"timeout_s":10}""")
        assertFalse(result.isError)
        assertTrue("condition met after" in result.text())
        assertEquals(2, calls)
        assertEquals(2_000L, testScheduler.currentTime)
    }

    @Test fun processExitWaitsWhileThePidExists() = runTest {
        var calls = 0
        val tool = fixture(exec = { command, _ ->
            assertEquals("test -d /proc/4123", command)
            if (++calls == 1) 0 else 1
        }).tool
        val result = tool.call("""{"until":"process_exit","pid":4123,"timeout_s":10}""")
        assertEquals("process 4123 exited", result.text())
        assertEquals(2, calls)
    }

    @Test fun processPatternIsQuotedAndExcludesProbeAncestors() = runTest {
        val tool = fixture(exec = { command, _ ->
            assertEquals("exec pgrep -A -f -- 'build'\\''s $(touch nope)'", command)
            1
        }).tool
        val result = tool.call("""{"until":"process_exit","pattern":"build's ${'$'}(touch nope)","timeout_s":5}""")
        assertFalse(result.isError)
        assertTrue("exited" in result.text())
    }

    @Test fun timeoutSaysWhatIsPendingAndOffersAnotherWait() = runTest {
        val result = fixture(exec = { _, _ -> 1 }).tool.call("""{"until":"condition","command":"test -f out.apk","timeout_s":5}""")
        assertFalse(result.isError)
        assertTrue("Timed out after 5 s" in result.text())
        assertTrue("condition is still pending: test -f out.apk" in result.text())
        assertTrue("wait again or move on" in result.text())
    }

    @Test fun completedSessionReturnsItsFinalTextCappedAtTheReportLimit() = runTest {
        val answer = "The final answer. " + "x".repeat(SessionBus.REPORT_LIMIT)
        val f = fixture(finalText = { assertEquals("worker", it); answer })
        f.store.create(session("worker", SessionState.COMPLETED).copy(spawnedBy = "caller"))
        val result = f.tool.call("""{"until":"session_done","target":"#worker","timeout_s":5}""")
        assertEquals("#worker finished (completed): " + answer.take(SessionBus.REPORT_LIMIT), result.text())
    }

    @Test fun sessionWaitingForApprovalReturnsWithoutWaitingForTheTimeout() = runTest {
        val f = fixture()
        f.store.create(session("worker", SessionState.WAITING_APPROVAL))
        val result = f.tool.call("""{"until":"session_done","target":"worker","timeout_s":60}""")
        assertTrue("a person must act" in result.text())
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun sessionTimeoutIncludesItsPendingState() = runTest {
        val f = fixture()
        f.store.create(session("worker"))
        val result = f.tool.call("""{"until":"session_done","target":"worker","timeout_s":5}""")
        assertTrue("#worker is still running" in result.text())
        assertTrue("Timed out" in result.text())
    }

    @Test fun privateSessionIsRefusedBeforeReadingItsAnswer() = runTest {
        val f = fixture(finalText = { error("Private answer must not be read") })
        f.store.update("caller") { it.copy(agentId = "agent-a") }
        f.store.create(session("private", SessionState.COMPLETED).copy(agentId = "agent-b", kind = SessionKind.AGENT))
        val result = f.tool.call("""{"until":"session_done","target":"@private","timeout_s":5}""")
        assertTrue(result.isError)
        assertTrue("private history was not shared" in result.text())
    }

    @Test fun discoveryAllowsOnlySessionWaits() = runTest {
        val f = fixture(finalText = { "done" })
        f.store.update("caller") { it.copy(turnMode = ProactiveTurnMode.READ_ONLY_DISCOVERY) }
        f.store.create(session("worker", SessionState.COMPLETED))
        assertTrue(ScopedTools.allows("wait_until", emptySet()))
        assertFalse(f.tool.call("""{"until":"session_done","target":"worker","timeout_s":5}""").isError)
        assertTrue(f.tool.call("""{"until":"process_exit","pid":4123,"timeout_s":5}""").isError)
        assertTrue(f.tool.call("""{"until":"condition","command":"test -f out.apk","timeout_s":5}""").isError)
    }

    @Test fun linuxNotReadyIsAnError() = runTest {
        val result = fixture(exec = { _, _ -> null }).tool.call("""{"until":"condition","command":"true","timeout_s":5}""")
        assertTrue(result.isError)
        assertTrue("Linux is not ready" in result.text())
    }

    @Test fun invalidProbesAreRejectedBeforeExec() = runTest {
        val tool = fixture().tool
        listOf(
            """{"until":"condition","command":"true","interval_s":1,"timeout_s":5}""",
            """{"until":"condition","command":"true","interval_s":61,"timeout_s":5}""",
            """{"until":"condition","command":"${"x".repeat(2001)}","timeout_s":5}""",
            """{"until":"process_exit","pid":0,"timeout_s":5}""",
            """{"until":"process_exit","pid":1,"pattern":"gradle","timeout_s":5}""",
        ).forEach { assertTrue(tool.call(it).isError) }
    }

    @Test fun aFailedProcessProbeIsNotReportedAsAnExitedProcess() = runTest {
        val result = fixture(exec = { _, _ -> 2 }).tool.call("""{"until":"process_exit","pattern":"[","timeout_s":5}""")
        assertTrue(result.isError)
        assertTrue("Process probe failed" in result.text())
    }

    @Test fun cancellationStopsAnInFlightProbe() = runTest {
        val entered = CompletableDeferred<Unit>()
        var stopped = false
        val tool = fixture(exec = { _, _ ->
            entered.complete(Unit)
            try { awaitCancellation() } finally { stopped = true }
        }).tool
        val wait = async { tool.call("""{"until":"condition","command":"test -f out.apk","timeout_s":60}""") }
        entered.await()
        wait.cancelAndJoin()
        assertTrue(stopped)
    }

    @Test fun progressContinuesBetweenWidelySpacedProbes() = runTest {
        val updates = mutableListOf<String>()
        val tool = fixture(exec = { _, _ -> 1 }).tool
        val wait = async { tool.call("""{"until":"condition","command":"test -f out.apk","interval_s":60,"timeout_s":120}""", HostToolProgress { updates += it.text() }) }
        runCurrent()
        advanceTimeBy(10_001)
        assertEquals(3, updates.size)
        assertTrue(updates.all { "out.apk" in it && "elapsed" in it })
        wait.cancelAndJoin()
    }

    @Test fun subagentWaitAndSynchronousStartRequireAnExplicitTimeout() = runTest {
        val f = fixture()
        val tools = SessionTools(f.bus, f.supervisor, f.store).forSession("caller").associateBy { it.name }
        assertTrue(JsonPrimitive("timeout_s") in (tools.getValue("subagent_wait").definition.parameters["required"] as JsonArray))
        listOf("{}", """{"timeout_s":4}""", """{"timeout_s":1801}""", """{"timeout_s":"10"}""").forEach {
            assertTrue(tools.getValue("subagent_wait").call(it).isError)
        }
        val result = tools.getValue("subagent_start").call("""{"task":"work","name":"worker","wait":true}""")
        assertTrue(result.isError)
        assertTrue("needs timeout_s" in result.text())
        assertEquals(1, f.store.list().size)
    }
}
