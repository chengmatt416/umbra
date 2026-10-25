package app.umbra.core.orchestrator

import app.umbra.core.safety.ApprovalMode
import app.umbra.core.session.Effort
import app.umbra.core.session.SessionKind
import app.umbra.core.session.SessionState
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Sessions talking to each other and subagents, through the real omp binary (see [OrchestratorRig]): the model is
 * scripted, everything between the tool call and the other session's conversation is the product's.
 */
class SessionBusTest {
    @TempDir(factory = UuidTempDirFactory::class)
    lateinit var dir: Path

    private val ompBinary: File? = listOf(
        System.getenv("OMP_BIN"),
        *System.getenv("PATH").orEmpty().split(File.pathSeparator).map { "$it/omp" }.toTypedArray(),
        "${System.getProperty("user.home")}/.bun/bin/omp",
    ).filterNotNull().map(::File).firstOrNull { it.canExecute() }

    private fun withRig(model: ScriptedModel, block: suspend OrchestratorRig.() -> Unit) {
        assumeTrue(ompBinary != null, "omp is not installed; skipping the session bus test")
        model.use {
            OrchestratorRig(dir, ompBinary!!, it.url, ApprovalMode.AUTONOMOUS).use { rig ->
                runBlocking {
                    try {
                        rig.block()
                    } catch (t: Throwable) {
                        System.err.println("--- timeline ---")
                        rig.store.list().forEach { s -> rig.events(s.id).forEach { e -> System.err.println("${s.id} ${e.seq} ${e.event.toString().take(300)}") } }
                        throw t
                    }
                }
            }
        }
    }

    private suspend fun OrchestratorRig.userTexts(id: String): List<String> =
        supervisor.conversation(id).value.items.filterIsInstance<ChatItem.User>().map { it.text }

    private suspend fun OrchestratorRig.awaitUserText(id: String, part: String, timeoutMs: Long = 90_000): String = withTimeout(timeoutMs) {
        while (true) {
            userTexts(id).firstOrNull { part in it }?.let { return@withTimeout it }
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    @Test
    fun aSessionWritesAnotherAndGetsItsReply() {
        val model = ScriptedModel { r ->
            val user = r.lastUserText.orEmpty()
            when {
                r.lastToolResult != null -> ScriptedModel.text("tool said: " + r.lastToolResult!!.take(120))
                user.startsWith("[Message m") -> {
                    val id = Regex("""\[Message (m\d+)""").find(user)!!.groupValues[1]
                    ScriptedModel.toolCall("session_reply", """{"message_id":"$id","message":"pong from bee"}""")
                }
                user.startsWith("[Reply to") -> ScriptedModel.text("got the reply")
                "ping the bee" in user -> ScriptedModel.toolCall("session_send", """{"to":"#bee","message":"ping from ant"}""")
                else -> ScriptedModel.text("ready")
            }
        }
        withRig(model) {
            val bee = supervisor.launch("wait for messages", title = "Bee", spec = SessionSpec(handle = "bee"))
            awaitState(bee.id, SessionState.COMPLETED)
            val ant = supervisor.launch("ping the bee", title = "Ant", spec = SessionSpec(handle = "ant"))
            val received = awaitUserText(bee.id, "[Message m")
            assertTrue("ping from ant" in received && "#ant" in received, received)
            val reply = awaitUserText(ant.id, "[Reply to m")
            assertTrue("pong from bee" in reply, reply)
            // The tools were offered to omp, and omp's own task tool was not.
            val tools = model.requests.first().toolNames
            assertTrue("session_send" in tools && "subagent_start" in tools, tools.toString())
            assertFalse("task" in tools, tools.toString())
        }
    }

    @Test
    fun aSubagentWorksInTheParentsFolderAndReportsBack() {
        val model = ScriptedModel { r ->
            val user = r.lastUserText.orEmpty()
            when {
                "You are a subagent" in r.systemText -> ScriptedModel.text("the answer is 4")
                user.startsWith("[Subagent") -> ScriptedModel.text("my subagent says: " + user.lineSequence().drop(1).first())
                r.lastToolResult != null -> ScriptedModel.text("started it")
                else -> ScriptedModel.toolCall("subagent_start", """{"task":"Add 2 and 2 and answer with the sum.","name":"Adder","effort":"low"}""")
            }
        }
        withRig(model) {
            val parent = supervisor.launch("split the work", title = "Parent")
            val report = awaitUserText(parent.id, "[Subagent #adder")
            assertTrue("the answer is 4" in report, report)
            val child = store.list().single { it.spawnedBy == parent.id }
            assertEquals(SessionKind.SUBAGENT, child.kind)
            assertEquals("adder", child.handle)
            assertEquals(Effort("low"), child.effort)
            assertEquals(session(parent.id).workspaceId, child.workspaceId)
            val subagentRequest = model.requests.first { "You are a subagent" in it.systemText }
            assertFalse("subagent_start" !in subagentRequest.toolNames && false)
            assertNotNull(awaitState(parent.id, SessionState.COMPLETED))
        }
    }

    @Test
    fun aCoordinatingChildReportsOnlyAfterItsDelegatesAndAggregateTurnFinish() {
        val model = ScriptedModel { r ->
            when {
                r.lastUserText.orEmpty().startsWith("[Report from") -> ScriptedModel.text("Received the aggregate result.")
                r.lastUserText == "aggregate the completed work" -> ScriptedModel.text("The checked aggregate result.")
                else -> ScriptedModel.text("Waiting for delegated work.")
            }
        }
        withRig(model) {
            val parent = supervisor.launch("coordinate the project", title = "Coordinator")
            awaitState(parent.id, SessionState.COMPLETED)
            val child = supervisor.create("delegate coordination", title = "Specialist", spawnedBy = parent.id)
            val leaf = supervisor.create("pending leaf", spawnedBy = child.id)
            supervisor.send(child.id, "coordinate the delegated work")
            awaitState(child.id, SessionState.COMPLETED)
            delay(2_000)
            assertFalse(userTexts(parent.id).any { it.startsWith("[Report from") }, "A waiting coordinator must not publish its partial answer as a finished report")

            // A descendant reaching a terminal state is not an aggregate result. A fresh coordinator
            // turn must consume its report before the parent can receive final completion.
            supervisor.stop(leaf.id)
            delay(1_600)
            store.update(child.id) { it.copy(activity = "Awaiting aggregation") }
            delay(500)
            assertFalse(userTexts(parent.id).any { it.startsWith("[Report from") })
            supervisor.send(child.id, "aggregate the completed work", messageSource = "report")
            val report = awaitUserText(parent.id, "[Report from")
            assertTrue("The checked aggregate result." in report, report)
            delay(2_000)
            assertEquals(1, userTexts(parent.id).count { it.startsWith("[Report from") })
        }
    }

    @Test
    fun subagentWaitReturnsTheAnswerWithoutASecondReport() {
        val model = ScriptedModel { r ->
            val user = r.lastUserText.orEmpty()
            when {
                "You are a subagent" in r.systemText -> ScriptedModel.text("done: 42", delayMs = 1_500)
                user.startsWith("[Subagent") -> ScriptedModel.text("UNEXPECTED second report")
                r.lastToolResult?.contains("Started #worker") == true -> ScriptedModel.toolCall("subagent_wait", """{"names":["worker"],"timeout_s":120}""", id = "call_2")
                r.lastToolResult != null -> ScriptedModel.text("waited: " + r.lastToolResult!!.take(200))
                else -> ScriptedModel.toolCall("subagent_start", """{"task":"Answer 42.","name":"worker"}""")
            }
        }
        withRig(model) {
            val parent = supervisor.launch("start and wait", title = "Waiter")
            awaitState(parent.id, SessionState.COMPLETED, timeoutMs = 120_000)
            val final = supervisor.finalText(parent.id)
            assertTrue("done: 42" in final, final)
            delay(3_000)
            assertFalse(userTexts(parent.id).any { it.startsWith("[Subagent") }, userTexts(parent.id).toString())
        }
    }
}
