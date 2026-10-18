package app.umbra.agent.data

import app.umbra.agent.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Signing in with a provider's account (OpenAI, Anthropic, Google Antigravity) through omp's own `omp login`: omp
 * starts its loopback callback server inside the Linux environment, which shares the phone's network, so the phone's
 * browser lands back on it by itself when the sign-in finishes. Nothing is pasted. The credentials stay in omp's store
 * in the agent directory every session uses; the app keeps only who signed in.
 */
class OmpLogin(private val container: AppContainer) {
    sealed interface State {
        data object Idle : State
        data object Starting : State
        /** The sign-in page is open in the browser; omp waits for the browser to come back. */
        data class Browser(val url: String) : State
        data class Done(val account: String?) : State
        data class Failed(val message: String) : State
    }

    private val mutable = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = mutable.asStateFlow()
    private val running = Mutex()
    @Volatile private var pidPath: String? = null
    @Volatile private var cancelled = false

    fun state(provider: String): State = mutable.value[provider] ?: State.Idle

    private fun set(provider: String, state: State) {
        mutable.value = mutable.value + (provider to state)
    }

    /**
     * Runs the sign-in for [provider] (omp's id: "openai-codex", "anthropic", "google-antigravity"). [openUrl] opens the
     * sign-in page once omp prints it. Returns who signed in; throws with omp's own reason when it fails.
     */
    suspend fun login(provider: String, openUrl: (String) -> Unit): String? = running.withLock {
        set(provider, State.Starting)
        try {
            val guest = container.guest.state.value
            check(guest == GuestManager.State.Ready || guest == GuestManager.State.Installed) { "The Linux environment is not ready yet." }
            check(container.guest.ensureReady()) { "The Linux environment is not ready yet." }
            val rt = container.guest.runtime
            val agentDir = container.guestAgentFiles(PROFILE).ompPath
            val log = "$HOME/.umbra/login-$provider.log"
            val pidFile = "$HOME/.umbra/login-$provider.pid"
            // omp tries to open the page itself with xdg-open; inside the environment that would open a Linux browser,
            // so a no-op opener comes first on the PATH and the app opens the phone's browser instead.
            val prepare = rt.exec(
                listOf(
                    "bash", "-c",
                    "mkdir -p $HOME/.umbra/noopen '$agentDir'; printf '#!/bin/sh\\nexit 0\\n' > $HOME/.umbra/noopen/xdg-open; " +
                        "chmod 755 $HOME/.umbra/noopen/xdg-open; rm -f '$log' '$pidFile'",
                ),
                cwd = HOME, timeoutMs = 30_000,
            )
            check(prepare.ok) { prepare.combined.take(300) }
            cancelled = false
            pidPath = pidFile
            coroutineScope {
                // omp runs as this exec, in the foreground: under proot a process left in the background ends with the
                // exec that started it. The exec returns when omp ends; meanwhile its output is read from the log. Its
                // stdin stays open (an end of input cancels omp's login), but nothing is ever typed into it.
                val runner = async(Dispatchers.IO) {
                    runCatching {
                        rt.exec(
                            listOf(
                                "bash", "-c",
                                "echo \$\$ > '$pidFile'; PATH=$HOME/.umbra/noopen:\$PATH PI_CODING_AGENT_DIR='$agentDir' NO_COLOR=1 exec omp login '$provider' > '$log' 2>&1 < <(exec sleep ${TIMEOUT_MS / 1000 + 30})",
                            ),
                            cwd = HOME, timeoutMs = TIMEOUT_MS + 30_000,
                        )
                    }
                }
                var opened = false
                val deadline = System.currentTimeMillis() + TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    delay(500)
                    val text = rt.readFile(log, 64 * 1024).orEmpty()
                    if (!opened) parseUrl(text)?.let { url -> opened = true; set(provider, State.Browser(url)); openUrl(url) }
                    parseDone(text)?.let { account ->
                        set(provider, State.Done(account.ifBlank { null }))
                        return@coroutineScope account.ifBlank { null }
                    }
                    parseFailure(text)?.let { error(it) }
                    if (runner.isCompleted) {
                        val again = rt.readFile(log, 64 * 1024).orEmpty()
                        parseDone(again)?.let { account -> set(provider, State.Done(account.ifBlank { null })); return@coroutineScope account.ifBlank { null } }
                        if (cancelled) throw CancellationException("cancelled")
                        val ran = runner.await().getOrNull()
                        error(
                            parseFailure(again) ?: again.lines().filter { it.isNotBlank() }.takeLast(3).joinToString(" ").take(300).ifBlank { null }
                                ?: ran?.combined?.take(300)?.ifBlank { null } ?: "omp stopped without signing in.",
                        )
                    }
                }
                cancelNow()
                error("The sign-in was not finished in time.")
            }
        } catch (e: CancellationException) {
            cancelNow()
            set(provider, State.Idle)
            throw e
        } catch (e: Exception) {
            set(provider, State.Failed(e.message ?: e::class.simpleName.orEmpty()))
            throw e
        } finally {
            pidPath = null
        }
    }

    /** Stops a sign-in that is waiting for the browser. */
    suspend fun cancel(provider: String) {
        cancelNow()
        set(provider, State.Idle)
    }

    /**
     * Signs out: omp has no logout command, so the account's rows in omp's store (`agent.db`) get the tombstone omp
     * itself uses for a credential it tore down (`disabled_cause`); omp then no longer offers them to any session.
     */
    suspend fun logout(provider: String) {
        val db = container.guestAgentFiles(PROFILE).ompPath.trimEnd('/') + "/agent.db"
        val script = "const { Database } = require('bun:sqlite'); const db = new Database(process.env.UMBRA_DB); " +
            "db.run(\"UPDATE auth_credentials SET disabled_cause = 'signed out in Umbra', updated_at = unixepoch() WHERE provider = ? AND disabled_cause IS NULL\", [process.env.UMBRA_PROVIDER]);"
        runCatching {
            container.guest.runtime.exec(listOf("bun", "-e", script), env = mapOf("UMBRA_DB" to db, "UMBRA_PROVIDER" to provider), cwd = HOME, timeoutMs = 30_000)
        }
        set(provider, State.Idle)
    }

    /** Ends a waiting `omp login` (its pid is the shell's, which became omp with `exec`). */
    private suspend fun cancelNow() {
        val file = pidPath ?: return
        cancelled = true
        runCatching { container.guest.runtime.exec(listOf("bash", "-c", "kill \$(cat '$file') 2>/dev/null; true"), cwd = HOME, timeoutMs = 10_000) }
    }

    companion object {
        /** The agent directory every session uses (AgentSession's provider profile). */
        const val PROFILE = "default"
        private const val HOME = "/home/agent"
        private const val TIMEOUT_MS = 10 * 60_000L

        /** The first full URL omp prints ("Open this URL in your browser:" then the URL on its own line). */
        internal fun parseUrl(text: String): String? = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("https://") }

        /** "Logged in to OpenAI as name@example.com (Org)" → "name@example.com (Org)", "" when omp named nobody. */
        internal fun parseDone(text: String): String? {
            val line = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("Logged in to ") } ?: return null
            return line.substringAfter(" as ", "").trim()
        }

        internal fun parseFailure(text: String): String? = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("Login failed:") }?.removePrefix("Login failed:")?.trim()
    }
}
