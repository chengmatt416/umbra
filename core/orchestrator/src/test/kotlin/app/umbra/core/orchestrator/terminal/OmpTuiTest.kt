package app.umbra.core.orchestrator.terminal

import app.umbra.core.control.ControlStore
import app.umbra.core.control.InputLeases
import app.umbra.core.orchestrator.AgentSupervisor
import app.umbra.core.orchestrator.OrchestratorRig
import app.umbra.core.orchestrator.ScriptedModel
import app.umbra.core.session.SessionState
import app.umbra.runtime.linux.tmux.LocalTmuxHost
import java.io.File
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/** The omp TUI on a session's conversation, in a tmux terminal, against the real omp and tmux. */
class OmpTuiTest {
    @TempDir
    lateinit var dir: Path

    private val tmux = listOf("/opt/homebrew/bin/tmux", "/usr/local/bin/tmux", "/usr/bin/tmux").firstOrNull { File(it).canExecute() }
    private val omp = listOf(System.getenv("OMP_BIN"), "${System.getProperty("user.home")}/.bun/bin/omp").filterNotNull().map(::File).firstOrNull { it.canExecute() }

    @AfterTest
    fun cleanUp() {
        if (tmux != null) ProcessBuilder(tmux, "-L", "umbra-omp", "kill-server").start().waitFor()
    }

    @Test
    fun theTuiTakesTheConversationAndTheGuiGetsItBackWhenTheTuiEnds(): Unit {
        assumeTrue(tmux != null && omp != null, "tmux and omp are needed")
        val model = ScriptedModel { request -> ScriptedModel.text("tui-test answer to ${request.lastUserText}") }
        model.use {
            OrchestratorRig(dir, omp!!, it.url).use { rig ->
                runBlocking {
                    val home = dir.resolve("home").toString()
                    val terminals = TerminalService(
                        LocalTmuxHost(tmux!!, mapOf("HOME" to home, "TERM" to "xterm-256color", "PATH" to "${System.getProperty("user.home")}/.bun/bin:/opt/homebrew/bin:/usr/bin:/bin")),
                        ControlStore(dir.resolve("control")), InputLeases(ControlStore(dir.resolve("control"))), rig.scope, dir.resolve("logs"),
                    )
                    val id = rig.supervisor.launch("first question").id
                    rig.awaitState(id, SessionState.COMPLETED)

                    val t = OmpTui(rig.supervisor, terminals, rig.environment).open(id, "first question")
                    // The real omp TUI draws the conversation it resumed.
                    var last: app.umbra.runtime.linux.tmux.TerminalScreen? = null
                    val shown = kotlinx.coroutines.withTimeoutOrNull(90_000) {
                        terminals.screen(t.id).first { s -> last = s ?: last; s != null && "tui-test answer to" in s.plainText && "first question" in s.plainText }
                    }
                    if (shown == null) {
                        System.err.println("--- TUI screen ---\n" + last?.plainText + "\n--- log ---\n" + runCatching { java.nio.file.Files.readString(terminals.logFile(t.id)) }.getOrNull()?.takeLast(3000))
                        error("the omp TUI did not show the conversation")
                    }
                    assertFailsWith<AgentSupervisor.OpenInTuiException>("the GUI waits while the TUI holds the conversation") { rig.supervisor.send(id, "from the gui") }

                    // The TUI ends (its terminal closes); the GUI continues the same conversation.
                    terminals.close(t.id)
                    rig.supervisor.send(id, "second question")
                    rig.awaitEvent(id) { e -> e is app.umbra.core.session.SessionEvent.AgentMessage && e.text == "tui-test answer to second question" }
                    val messages = model.requests.last().body["messages"].toString()
                    assertTrue("first question" in messages, "the GUI continued the conversation the TUI had")
                    assertTrue(terminals.leases.current(t.resourceId) == null)
                }
            }
        }
    }
}
