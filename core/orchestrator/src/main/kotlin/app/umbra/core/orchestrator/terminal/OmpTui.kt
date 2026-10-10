package app.umbra.core.orchestrator.terminal

import app.umbra.core.control.Principal
import app.umbra.core.orchestrator.AgentEnvironment
import app.umbra.core.orchestrator.AgentSupervisor

/**
 * Opens a session's conversation in omp's own terminal UI, for what only the TUI offers (git UI, extensions
 * dashboard, agents hub). The GUI gives the conversation up first and takes it back when the TUI's terminal ends.
 */
class OmpTui(private val supervisor: AgentSupervisor, private val terminals: TerminalService, private val environment: AgentEnvironment) {
    suspend fun open(sessionId: String, title: String): TerminalRecord {
        var terminalId: String? = null
        val launch = supervisor.releaseForTui(sessionId) {
            val id = terminalId
            id == null || terminals.terminals.value.any { it.record.id == id && it.status == TerminalStatus.ATTACHED }
        }
        // Variables and credentials go through a private file the pane reads once and deletes, never the command line.
        val envFile = "/tmp/umbra-tui-${System.nanoTime().toString(36)}.env"
        val vars = launch.environment + launch.secrets
        vars.keys.forEach { require(it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "bad variable name $it" } }
        environment.writePrivateFile(envFile, vars.entries.joinToString("") { (k, v) -> "export $k='${v.replace("'", "'\\''")}'\n" })
        val command = listOf("/bin/bash", "-c", "set -a; . \"$1\"; set +a; rm -f \"$1\"; shift; exec \"\$@\"", "umbra-tui", envFile) + launch.command
        val record = try {
            terminals.open("omp TUI · $title", launch.cwd, command, group = "omp", sessionId = sessionId, purpose = "omp-tui", owner = Principal.User)
        } catch (e: Exception) {
            terminalId = "failed"
            throw e
        }
        terminalId = record.id
        return record
    }
}
