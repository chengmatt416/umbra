package app.umbra.core.orchestrator.terminal

import app.umbra.core.control.ControlStore
import app.umbra.core.control.InputLeases
import app.umbra.core.control.LeaseState
import app.umbra.core.control.Principal
import app.umbra.runtime.linux.tmux.LocalTmuxHost
import app.umbra.runtime.linux.tmux.TerminalScreen
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Terminals against the real tmux (skipped when tmux is not installed): the registry, the input dispatcher and its
 * lease fencing, owner-driven resize, and the continuity cases of spec §16 (AC-001 UI gone, AC-002 server killed,
 * AC-003 non-owner resize, AC-004 immediate takeover).
 */
class TerminalServiceTest {
    @TempDir
    lateinit var dir: Path

    private val tmux = listOf("/opt/homebrew/bin/tmux", "/usr/local/bin/tmux", "/usr/bin/tmux").firstOrNull { File(it).canExecute() }
    private val group = "t" + (System.nanoTime() % 1_000_000)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cleanUp() {
        if (tmux != null) ProcessBuilder(tmux, "-L", "umbra-$group", "kill-server").start().waitFor()
        scope.cancel()
    }

    private fun service(): Triple<TerminalService, InputLeases, ControlStore> {
        assumeTrue(tmux != null, "tmux is not installed")
        val store = ControlStore(dir.resolve("control"))
        val leases = InputLeases(store)
        val host = LocalTmuxHost(tmux!!, mapOf("HOME" to dir.toString(), "PS1" to "$ ", "TERM" to "xterm-256color"))
        return Triple(TerminalService(host, store, leases, scope, dir.resolve("logs"), stableCols = 100, stableRows = 30), leases, store)
    }

    private suspend fun TerminalService.awaitScreen(id: String, timeoutMs: Long = 15_000, until: (TerminalScreen) -> Boolean): TerminalScreen =
        withTimeout(timeoutMs) { screen(id).first { it != null && until(it) }!! }

    private suspend fun TerminalService.awaitStatus(id: String, status: TerminalStatus, timeoutMs: Long = 15_000) =
        withTimeout(timeoutMs) { terminals.first { list -> list.any { it.record.id == id && it.status == status } } }

    private val shell = listOf("/bin/sh")

    @Test
    fun theUsersInputReachesTheShellAndTheScreenShowsIt(): Unit = runBlocking {
        val (svc, _, _) = service()
        val t = svc.open("shell", dir.toString(), shell, group)
        assertIs<InputResult.Sent>(svc.sendText(t.id, "echo umbra-$((6*7))\r", Principal.User))
        val screen = svc.awaitScreen(t.id) { "umbra-42" in it.plainText }
        assertEquals(100, screen.cols, "a terminal without a foreground owner view keeps the stable virtual size")
        assertTrue(Files.readString(svc.logFile(t.id)).contains("umbra-42"), "output is also kept in the terminal's log")
    }

    @Test
    fun deletingASessionClosesItsTerminalsAndNoOthers(): Unit = runBlocking {
        val (svc, _, _) = service()
        val mine = svc.open("session shell", dir.toString(), shell, group, sessionId = "s-del")
        val also = svc.open("session shell 2", dir.toString(), shell, group, sessionId = "s-del")
        val other = svc.open("someone else's", dir.toString(), shell, group, sessionId = "s-keep")
        val free = svc.open("Linux shell", dir.toString(), shell, group)
        svc.closeSession("s-del")
        val left = svc.terminals.value.map { it.record.id }.toSet()
        assertTrue(mine.id !in left && also.id !in left, "the deleted session's terminals are closed")
        assertEquals(setOf(other.id, free.id), left, "other sessions' terminals and free terminals stay")
        fun alive(id: String) = ProcessBuilder(tmux, "-L", "umbra-$group", "has-session", "-t", id).start().waitFor() == 0
        assertTrue(!alive(mine.id) && !alive(also.id), "their tmux sessions are gone too, not only the app's records")
        assertTrue(alive(other.id) && alive(free.id))
    }

    @Test
    fun takingControlFencesTheAgentImmediatelyAndReturningItRestoresIt(): Unit = runBlocking {
        val (svc, leases, _) = service()
        val agent = Principal.agent("s-1")
        val t = svc.open("agent shell", dir.toString(), shell, group, sessionId = "s-1", purpose = "agent", owner = agent)
        assertIs<InputResult.Sent>(svc.sendText(t.id, "echo first\r", agent))
        val before = leases.current(t.resourceId)!!.generation

        svc.takeControl(t.id)
        val fenced = svc.sendText(t.id, "echo should-not-run\r", agent)
        assertIs<InputResult.Fenced>(fenced, "after take-back the old owner's input is refused (AC-004)")
        assertTrue(leases.current(t.resourceId)!!.generation > before, "a take-back is a new generation")
        assertIs<InputResult.Sent>(svc.sendText(t.id, "echo mine\r", Principal.User))
        svc.awaitScreen(t.id) { "mine" in it.plainText }
        assertTrue("should-not-run" !in svc.awaitScreen(t.id) { true }.plainText)

        svc.returnControl(t.id)
        assertIs<InputResult.Sent>(svc.sendText(t.id, "echo agent-again\r", agent))
        svc.awaitScreen(t.id) { "agent-again" in it.plainText }
        assertIs<InputResult.Fenced>(svc.send(t.id, "x".toByteArray(), agent, generation = before), "a stale generation is refused")
        assertTrue(leases.recent.value.map { it.kind }.containsAll(listOf("acquire", "takeback", "restore")), "the control timeline records it")
    }

    @Test
    fun onlyTheInputOwnersViewResizesThePane(): Unit = runBlocking {
        val (svc, _, _) = service()
        val t = svc.open("shell", dir.toString(), shell, group)
        svc.viewSize(t.id, 80, 24, Principal.agent("someone-else"))
        delay(600)
        assertEquals(100, svc.awaitScreen(t.id) { true }.cols, "a read-only viewer does not change the size (AC-003)")
        svc.viewSize(t.id, 80, 24, Principal.User)
        svc.awaitScreen(t.id) { it.cols == 80 && it.rows == 24 }
    }

    @Test
    fun aRestartedUiReattachesTheSamePaneAndAKilledServerIsReportedLost(): Unit = runBlocking {
        val (svc, leases, store) = service()
        val t = svc.open("shell", dir.toString(), shell, group)
        svc.sendText(t.id, "echo kept\r", Principal.User)
        svc.awaitScreen(t.id) { "kept" in it.plainText }

        // AC-001: the UI's client goes away; a new service instance finds the same pane in the same epoch.
        svc.detachAll()
        val again = TerminalService(LocalTmuxHost(tmux!!, mapOf("HOME" to dir.toString())), store, leases, scope, dir.resolve("logs"), 100, 30)
        again.reconcile()
        again.awaitStatus(t.id, TerminalStatus.ATTACHED)
        again.awaitScreen(t.id) { "kept" in it.plainText }

        // AC-002: the server is killed; nothing pretends the shell lives, and the lease cannot be vouched for.
        ProcessBuilder(tmux, "-L", "umbra-$group", "kill-server").start().waitFor()
        again.reconcile()
        again.awaitStatus(t.id, TerminalStatus.SERVER_LOST)
        assertEquals(LeaseState.UNCERTAIN, leases.current(t.resourceId)!!.state)
        assertIs<InputResult.Fenced>(again.sendText(t.id, "echo x\r", Principal.User))
        val fresh = again.open("new", dir.toString(), shell, group)
        assertNotEquals(t.serverEpoch, fresh.serverEpoch, "a new server is a new epoch")
    }

    @Test
    fun anExitedShellIsReportedWithItsStatus(): Unit = runBlocking {
        val (svc, _, _) = service()
        val t = svc.open("shell", dir.toString(), shell, group)
        svc.sendText(t.id, "exit 3\r", Principal.User)
        val screen = svc.awaitScreen(t.id) { it.dead }
        assertEquals(3, screen.deadStatus)
        svc.awaitStatus(t.id, TerminalStatus.PANE_EXITED)
        svc.close(t.id)
        assertTrue(svc.terminals.value.none { it.record.id == t.id })
    }
}
