package app.umbra.core.orchestrator

import app.umbra.core.session.SessionState
import app.umbra.runtime.omp.OmpTodoItem
import app.umbra.runtime.omp.OmpTodoPhase
import app.umbra.runtime.omp.bash
import app.umbra.runtime.omp.branch
import app.umbra.runtime.omp.branchMessages
import app.umbra.runtime.omp.fork
import app.umbra.runtime.omp.models
import app.umbra.runtime.omp.sessionState
import app.umbra.runtime.omp.sessionStats
import app.umbra.runtime.omp.setSessionName
import app.umbra.runtime.omp.setTodos
import app.umbra.runtime.omp.thinkingLevels
import app.umbra.runtime.omp.tree
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * What the omp GUI (ADR 0013) shows and does, against the real omp binary: the conversation view built from omp's
 * events and transcript, and the commands behind the GUI's controls (rename, todos, stats, slash commands, `!`
 * bash, branch, fork) with the view following each of them. Only the model is scripted.
 */
class OmpGuiTest {
    @TempDir
    lateinit var dir: Path

    private val ompBinary: File? = listOf(
        System.getenv("OMP_BIN"),
        *System.getenv("PATH").orEmpty().split(File.pathSeparator).map { "$it/omp" }.toTypedArray(),
        "${System.getProperty("user.home")}/.bun/bin/omp",
    ).filterNotNull().map(::File).firstOrNull { it.canExecute() }

    private fun withRig(model: ScriptedModel, block: suspend OrchestratorRig.() -> Unit) {
        assumeTrue(ompBinary != null, "omp is not installed; skipping the omp GUI test")
        model.use { OrchestratorRig(dir, ompBinary!!, it.url).use { rig -> runBlocking { rig.block() } } }
    }

    private suspend fun OrchestratorRig.view(id: String, timeoutMs: Long = 30_000, until: (ConversationState) -> Boolean): ConversationState =
        withTimeout(timeoutMs) { supervisor.conversation(id).first(until) }

    private fun ConversationState.userTexts() = transcript.filterIsInstance<ChatItem.User>().map { it.text }

    @Test
    fun aTurnWithAToolShowsUpAsMessagesAndAToolRunAndIsSavedWhenItSettles() {
        val model = ScriptedModel { request ->
            if (request.lastToolResult == null) ScriptedModel.toolCall("bash", """{"command":"echo gui-check"}""") else ScriptedModel.text("Finished.")
        }
        withRig(model) {
            val session = supervisor.launch("run the check")
            awaitState(session.id, SessionState.COMPLETED)
            val c = view(session.id) { s -> s.transcript.filterIsInstance<ChatItem.Assistant>().any { it.text == "Finished." } && s.tools.isNotEmpty() }

            assertEquals(listOf("run the check"), c.userTexts())
            val call = c.transcript.filterIsInstance<ChatItem.Assistant>().flatMap { it.blocks }.filterIsInstance<Block.ToolCall>().single()
            assertEquals("bash", call.name)
            val run = c.tools.getValue(call.id)
            assertTrue("gui-check" in run.result.orEmpty(), "the tool's output is in the view: $run")
            assertTrue(!run.running)
            assertTrue(c.live)
            assertEquals("fake/fake-model", c.modelKey)
            assertTrue(c.commands.any { it.name == "session" }, "omp's slash commands are offered: ${c.commands.map { it.name }}")
            assertTrue(!c.working)

            val saved = withTimeout(10_000) { while (savedConversations[session.id].isNullOrEmpty()) kotlinx.coroutines.delay(50); savedConversations.getValue(session.id) }
            assertTrue(saved.size >= 4, "user, assistant (tool call), tool result, assistant: ${saved.size}")
        }
    }

    @Test
    fun theGuisCommandsChangeTheSessionAndTheViewFollows() {
        val model = ScriptedModel { ScriptedModel.text("hello there") }
        withRig(model) {
            val id = supervisor.launch("hello").id
            awaitState(id, SessionState.COMPLETED)

            assertTrue(supervisor.omp(id) { it.models() }.any { it.key == "fake/fake-model" })
            assertTrue(supervisor.omp(id) { it.thinkingLevels() }.isNotEmpty())

            supervisor.omp(id) { it.setSessionName("Renamed by the GUI") }
            assertEquals("Renamed by the GUI", supervisor.omp(id) { it.sessionState() }.sessionName)

            val phases = listOf(OmpTodoPhase("Fix", listOf(OmpTodoItem("find the crash", "completed"), OmpTodoItem("patch it", "in_progress"))))
            supervisor.omp(id) { it.setTodos(phases) }
            val todos = supervisor.omp(id) { it.sessionState() }.todos
            assertEquals(listOf("find the crash", "patch it"), todos.single().tasks.map { it.content })
            assertEquals("in_progress", todos.single().tasks[1].status)

            val stats = supervisor.omp(id) { it.sessionStats() }
            assertTrue(stats.userMessages >= 1 && stats.assistantMessages >= 1, "$stats")

            // A builtin slash command runs headless; what it prints arrives as command output.
            supervisor.omp(id) { it.prompt("/session info") }
            val printed = view(id) { s -> s.items.any { it is ChatItem.CommandOutput } }
            assertTrue(printed.items.filterIsInstance<ChatItem.CommandOutput>().any { it.text.isNotBlank() })

            // `!command`: runs in the session's folder and becomes part of the conversation.
            val bang = supervisor.omp(id) { it.bash("echo from-the-bang") }
            assertTrue("from-the-bang" in bang.output, "$bang")
            supervisor.reloadConversation(id)
            assertTrue(view(id) { s -> s.transcript.any { it is ChatItem.Bash } }.transcript.filterIsInstance<ChatItem.Bash>().any { "from-the-bang" in it.output })
            assertEquals(SessionState.COMPLETED, session(id).state, "GUI commands do not restart a finished task")
        }
    }

    @Test
    fun branchingAndForkingRewriteTheConversationAndARestartContinuesWhereTheUserWas() {
        val model = ScriptedModel { request -> ScriptedModel.text("answer to ${request.lastUserText}") }
        withRig(model) {
            val id = supervisor.launch("first question").id
            awaitState(id, SessionState.COMPLETED)
            supervisor.send(id, "second question")
            awaitEvent(id) { it is app.umbra.core.session.SessionEvent.AgentMessage && it.text == "answer to second question" }
            awaitState(id, SessionState.COMPLETED)
            view(id) { it.userTexts() == listOf("first question", "second question") }

            // Edit and resend: go back to the second question.
            val points = supervisor.omp(id) { it.branchMessages() }
            assertEquals(listOf("first question", "second question"), points.map { it.second })
            assertEquals("second question", supervisor.omp(id) { it.branch(points[1].first) })
            supervisor.reloadConversation(id)
            view(id) { it.userTexts() == listOf("first question") }

            supervisor.send(id, "a different second question")
            awaitEvent(id) { it is app.umbra.core.session.SessionEvent.AgentMessage && it.text == "answer to a different second question" }
            awaitState(id, SessionState.COMPLETED)
            view(id) { it.userTexts() == listOf("first question", "a different second question") }
            val tree = supervisor.omp(id) { it.tree() }
            assertNotNull(tree.leafId)
            assertTrue(tree.roots.isNotEmpty())

            // Fork: the conversation continues in a new file, which the session remembers.
            val before = session(id).ompSessionFile
            assertTrue(supervisor.omp(id) { it.fork() })
            supervisor.reloadConversation(id)
            val forked = withTimeout(10_000) { var f = session(id).ompSessionFile; while (f == before) { kotlinx.coroutines.delay(50); f = session(id).ompSessionFile }; f }
            assertNotEquals(before, forked)

            // The process goes away (idle release); the next message continues in the forked conversation.
            supervisor.liveSession(id)!!.releaseProcess()
            supervisor.send(id, "after the restart")
            awaitEvent(id) { it is app.umbra.core.session.SessionEvent.AgentMessage && it.text == "answer to after the restart" }
            val messages = model.requests.last().body["messages"].toString()
            assertTrue("a different second question" in messages, "the restarted process continued the forked conversation")
            assertEquals(forked, session(id).ompSessionFile)
        }
    }
}

class LockdownTest {
    @TempDir
    lateinit var dir: Path

    private val ompBinary: File? = listOf(System.getenv("OMP_BIN"), "${System.getProperty("user.home")}/.bun/bin/omp").filterNotNull().map(::File).firstOrNull { it.canExecute() }

    @Test
    fun aLockdownRefusesNewInputAndOmpsToolCallsAreDenied() {
        assumeTrue(ompBinary != null, "omp is not installed")
        var locked: String? = null
        var lockOnNextTurn = false
        val model = ScriptedModel { request ->
            if (request.lastToolResult == null) {
                // The lockdown starts while the agent is mid-turn, just before it asks to run a command.
                if (lockOnNextTurn) locked = "test"
                ScriptedModel.toolCall("bash", """{"command":"touch lockdown-marker"}""")
            } else {
                ScriptedModel.text("done: ${request.lastToolResult}")
            }
        }
        model.use {
            OrchestratorRig(dir, ompBinary!!, it.url, fence = { locked }).use { rig ->
                runBlocking {
                    val id = rig.supervisor.launch("first").id
                    rig.awaitState(id, SessionState.COMPLETED)
                    assertTrue(rig.workspaceRoot.toFile().walk().any { f -> f.name == "lockdown-marker" }, "before the lockdown the command runs")
                    rig.workspaceRoot.toFile().walk().filter { f -> f.name == "lockdown-marker" }.forEach { f -> f.delete() }

                    lockOnNextTurn = true
                    rig.supervisor.send(id, "do it again")
                    rig.awaitState(id, SessionState.COMPLETED)
                    assertTrue(rig.workspaceRoot.toFile().walk().none { f -> f.name == "lockdown-marker" }, "during the lockdown omp's command was denied, not run")
                    assertTrue(model.requests.last().lastToolResult.orEmpty().isNotBlank(), "the agent was told the tool did not run")

                    kotlin.test.assertFailsWith<AgentSupervisor.LockdownException> { rig.supervisor.send(id, "again") }
                    kotlin.test.assertFailsWith<AgentSupervisor.LockdownException> { rig.supervisor.launch("new task") }
                }
            }
        }
    }
}

class PlanRevisionTest {
    @TempDir
    lateinit var dir: Path

    private val ompBinary: File? = listOf(System.getenv("OMP_BIN"), "${System.getProperty("user.home")}/.bun/bin/omp").filterNotNull().map(::File).firstOrNull { it.canExecute() }

    @Test
    fun theAgentsTodoListBecomesAPlanRevisionAndItsProgressDoesNot() {
        assumeTrue(ompBinary != null, "omp is not installed")
        val steps = listOf(
            ScriptedModel.toolCall("todo", """{"op":"init","list":[{"phase":"Fix","items":["find the crash","patch it"]},{"phase":"Verify","items":["run the tests"]}]}""", id = "c1"),
            ScriptedModel.toolCall("todo", """{"op":"start","task":"find the crash"}""", id = "c2"),
            ScriptedModel.text("Planned and started."),
        )
        val model = ScriptedModel { request -> steps[minOf(request.body["messages"].toString().split("\"role\":\"tool\"").size - 1, steps.size - 1)] }
        model.use {
            OrchestratorRig(dir, ompBinary!!, it.url).use { rig ->
                runBlocking {
                    val id = rig.supervisor.launch("plan the fix").id
                    rig.awaitState(id, SessionState.COMPLETED)
                    val history = withTimeout(20_000) { while (rig.plans.history(id).isEmpty()) kotlinx.coroutines.delay(100); rig.plans.history(id) }
                    assertEquals(1, history.size, "init made one revision; starting a task is progress: $history")
                    assertEquals(listOf("Fix", "Verify"), history.single().phases.map { it.name })
                    assertEquals("agent", history.single().source)
                    val progress = rig.plans.progressOf(id)!!
                    assertEquals("in_progress", progress.phases[0].tasks.first { it.content == "find the crash" }.status)
                }
            }
        }
    }
}

class AdmissionTest {
    @TempDir
    lateinit var dir: Path

    private val ompBinary: File? = listOf(System.getenv("OMP_BIN"), "${System.getProperty("user.home")}/.bun/bin/omp").filterNotNull().map(::File).firstOrNull { it.canExecute() }

    @Test
    fun aProjectAtItsMaximumWaitsWhileAnotherProjectStillStarts() {
        assumeTrue(ompBinary != null, "omp is not installed")
        val policies = app.umbra.core.control.ResourcePolicies(app.umbra.core.control.ControlStore(dir.resolve("policy")))
        policies.saveDraft(app.umbra.core.control.ResourcePolicy(slots = 4, budgets = mapOf("p1" to app.umbra.core.control.Budget(1, 1))))
        policies.publish(policies.simulate(policies.draft()!!.policy, emptyMap(), 4, setOf("p1")))
        val model = ScriptedModel { request -> ScriptedModel.text("done", delayMs = if (request.lastUserText?.contains("slow") == true) 6_000 else 0) }
        model.use {
            OrchestratorRig(dir, ompBinary!!, it.url, admission = { p, r -> policies.admit(p, r) }).use { rig ->
                runBlocking {
                    val first = rig.supervisor.launch("slow work", projectId = "p1")
                    rig.awaitState(first.id, SessionState.RUNNING)
                    val before = rig.store.list().size
                    val refused = kotlin.test.assertFailsWith<CapacityException> { rig.supervisor.launch("more work", projectId = "p1") }
                    assertTrue("maximum 1" in refused.message.orEmpty(), refused.message)
                    assertEquals(before, rig.store.list().size, "a refused start leaves no failed session behind")
                    val other = rig.supervisor.launch("independent work")
                    rig.awaitState(other.id, SessionState.COMPLETED)
                    rig.awaitState(first.id, SessionState.COMPLETED)
                    val again = rig.supervisor.launch("more work after", projectId = "p1")
                    rig.awaitState(again.id, SessionState.COMPLETED)
                }
            }
        }
    }
}
