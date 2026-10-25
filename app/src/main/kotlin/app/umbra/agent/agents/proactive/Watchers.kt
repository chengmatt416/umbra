package app.umbra.agent.agents.proactive

import app.umbra.core.model.UmbraJson
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/** GET-only, cookie-free fetches. Redirects cannot silently expand an approved origin. */
class ResearchHttp(private val http: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()) {
    suspend fun fetch(url: String, selector: String = "", headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val parsed = url.toHttpUrl()
        require(parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.scheme in setOf("http", "https")) { "Use an HTTP URL without credentials" }
        require(headers.keys.all { it.lowercase() in setOf("accept", "accept-language", "user-agent") }) { "Only public content headers are allowed" }
        http.newCall(Request.Builder().url(parsed).apply { headers.forEach { (k, v) -> header(k, v) } }.build()).execute().use { response ->
            check(response.isSuccessful) { "Research fetch failed (HTTP ${response.code})" }
            require(response.body.contentLength() <= MAX_BYTES) { "Research response is too large" }
            val bytes = ByteArrayOutputStream()
            response.body.byteStream().use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    require(bytes.size() + n <= MAX_BYTES) { "Research response is too large" }
                    bytes.write(buffer, 0, n)
                }
            }
            val text = bytes.toString(Charsets.UTF_8.name())
            extract(text, selector, response.header("Content-Type").orEmpty()).take(64_000)
        }
    }
    companion object {
        const val MAX_BYTES = 512_000
        fun origin(url: String): String = url.toHttpUrl().let { "${it.scheme}://${it.host}:${it.port}" }
        fun extract(text: String, selector: String, contentType: String): String {
            if (selector.startsWith('$')) {
                require(selector == "$" || selector.matches(Regex("\\$(?:\\.[A-Za-z0-9_-]+|\\[\\d+]){1,20}"))) { "Use a JSON path such as $.price or $.items[0].value" }
                var value: JsonElement = UmbraJson.parseToJsonElement(text)
                Regex("\\.([A-Za-z0-9_-]+)|\\[(\\d+)]").findAll(selector).forEach { token ->
                    value = if (token.groupValues[1].isNotEmpty()) (value as? JsonObject)?.get(token.groupValues[1]) ?: error("JSON field is missing")
                    else (value as? JsonArray)?.getOrNull(token.groupValues[2].toInt()) ?: error("JSON index is missing")
                }
                return (value as? JsonPrimitive)?.content ?: value.toString()
            }
            if ("html" in contentType || selector.isNotBlank()) {
                val doc = Jsoup.parse(text)
                doc.select("script,style,noscript").remove()
                if (selector.isBlank()) return doc.text()
                val selected = doc.select(selector)
                require(selected.isNotEmpty()) { "The CSS selector matched no content" }
                return selected.joinToString("\n") { it.text() }
            }
            return text
        }
    }
}

object ReadOnlyProbeCommands {
    fun command(argv: List<String>): List<String> {
        require(argv.isNotEmpty())
        val gitPrefix = listOf("/usr/bin/git", "--no-pager", "-c", "core.fsmonitor=false", "-c", "core.hooksPath=/dev/null", "-c", "core.untrackedCache=false")
        return when {
            argv == listOf("git", "status") -> gitPrefix + listOf("status", "--porcelain=v1")
            argv == listOf("git", "rev-parse", "HEAD") -> gitPrefix + listOf("rev-parse", "--verify", "HEAD")
            argv == listOf("git", "log") -> gitPrefix + listOf("log", "-1", "--format=%H %s")
            argv.size == 2 && argv[0] in setOf("stat", "sha256sum") -> {
                val path = argv[1]
                require(path.startsWith("/workspace/") && path.split('/').none { it in setOf(".", "..") } && '\u0000' !in path) { "File probes must stay under /workspace" }
                if (argv[0] == "stat") listOf("/usr/bin/stat", "--format=%s", "--", path) else listOf("/usr/bin/sha256sum", "--", path)
            }
            else -> error("Only git status, git rev-parse HEAD, git log, stat and sha256sum are read-only probes")
        }
    }
}

class WatcherEngine(
    private val store: ProactiveStore,
    private val probe: suspend (ProbeSpec) -> String,
    private val layaVerify: suspend (question: String, evidence: String) -> Boolean?,
    private val onMatch: suspend (AgentWatcher, String) -> Unit,
) {
    private val tickLock = Mutex()
    suspend fun tick(agentId: String, now: Long) = tickLock.withLock {
        for (candidate in store.watchers(agentId).filter { it.enabled && it.authorizedAtMs != null && now - it.lastCheckedMs >= it.intervalMinutes * 60_000L }) {
            var observed = candidate
            try {
                val value = (withTimeoutOrNull(12_000) { probe(candidate.probe) } ?: error("Probe timed out")).take(64_000)
                if (store.watchers(agentId).find { it.id == candidate.id } != candidate) continue
                val hash = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
                val matches = when (val c = candidate.condition) {
                    ConditionSpec.Changed -> candidate.lastHash != null && hash != candidate.lastHash
                    is ConditionSpec.Contains -> value.contains(c.substring, c.ignoreCase)
                    is ConditionSpec.NumericCompare -> {
                        require(c.threshold.isFinite())
                        val number = Regex("[-+]?(?:\\d[\\d,]*)(?:\\.\\d+)?").find(value)?.value?.replace(",", "")?.toDoubleOrNull()
                        number != null && number.isFinite() && when (c.op) { "<" -> number < c.threshold; "<=" -> number <= c.threshold; ">" -> number > c.threshold; ">=" -> number >= c.threshold; "==" -> number == c.threshold; else -> error("Unsupported comparison") }
                    }
                    is ConditionSpec.LayaVerify -> layaVerify(c.question, value.take(6000)) == true
                }
                val fire = matches && (candidate.condition == ConditionSpec.Changed || !candidate.lastMatched || candidate.lastHash != hash)
                // Persist the observation before dispatch so a duplicate tick cannot wake twice.
                val next = candidate.copy(lastCheckedMs = now, lastValue = value.take(2000), lastHash = hash, lastMatched = matches,
                    enabled = !(fire && candidate.triggerOnce), error = null)
                if (!store.updateWatcher(candidate, next)) continue
                observed = next
                if (fire && store.watchers(agentId).find { it.id == next.id } == next) onMatch(next, value)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { store.updateWatcher(observed, observed.copy(lastCheckedMs = now, error = (e.message ?: "Probe failed").take(300))) }
        }
    }
}
