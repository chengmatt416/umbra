package app.umbra.runtime.omp

/** `tools.approvalMode`: which capability tiers omp runs without asking (docs/approval-mode.md). */
enum class OmpApprovalMode(val wire: String) {
    /** Auto-approves `read` only; `write` and `exec` prompt. */
    ALWAYS_ASK("always-ask"),

    /** Auto-approves `read` and `write`; `exec` (bash, eval, task, ...) prompts. The product default. */
    WRITE("write"),

    /** Auto-approves everything. Subagents always run like this. */
    YOLO("yolo"),
}

enum class ToolPolicy(val wire: String) { ALLOW("allow"), DENY("deny"), PROMPT("prompt") }

/** `bash.patterns`: ordered, first match wins; `*` is the only wildcard. `deny` is absolute. */
data class BashPattern(val match: String, val approval: ToolPolicy)

/**
 * The per-session settings overlay passed as `--config <file>`. Everything the host decides per
 * session lives here, so it overrides global and project config without touching them.
 */
data class OmpSettings(
    val approvalMode: OmpApprovalMode = OmpApprovalMode.WRITE,
    /** `tools.approval.<tool>`. Host tools are `ALLOW`ed here because the host's own safety gate decides. */
    val toolPolicies: Map<String, ToolPolicy> = emptyMap(),
    val bashPatterns: List<BashPattern> = emptyList(),
    val allowCompoundBash: Boolean = false,
    /** Built-in tools turned off through their `<name>.enabled` setting. */
    val disabledBuiltins: Set<String> = DESKTOP_ONLY_BUILTINS,
    /** Any other settings, as nested maps/lists/scalars, merged last. */
    val extra: Map<String, Any?> = emptyMap(),
) {
    fun toYaml(): String = Yaml.write(toMap())

    internal fun toMap(): Map<String, Any?> {
        val root = LinkedHashMap<String, Any?>()
        root["tools"] = buildMap<String, Any?> {
            put("approvalMode", approvalMode.wire)
            if (toolPolicies.isNotEmpty()) put("approval", toolPolicies.mapValues { it.value.wire })
        }
        if (bashPatterns.isNotEmpty() || allowCompoundBash) {
            root["bash"] = buildMap<String, Any?> {
                if (allowCompoundBash) put("allowCompoundCommands", true)
                if (bashPatterns.isNotEmpty()) put("patterns", bashPatterns.map { mapOf("match" to it.match, "approval" to it.approval.wire) })
            }
        }
        for (name in disabledBuiltins) root[name] = mapOf("enabled" to false)
        return deepMerge(root, extra)
    }

    companion object {
        /** Built-ins that need a desktop (browser automation, window/screen control); host tools replace them. */
        val DESKTOP_ONLY_BUILTINS = setOf("browser", "computer")

        /**
         * Guard rails for the one tool that can reach beyond the workspace, applied in every approval
         * mode and, unlike prompts, also to subagents (which run headless in yolo). Host-side risk
         * classification covers the main agent; these cover what cannot ask.
         */
        val PRODUCT_BASH_PATTERNS = listOf(
            // A pattern without a star matches a whole command, and a star matches anything, slashes included
            // (OmpIntegrationTest checks this against the real omp). So "rm -rf /*" would also forbid
            // "rm -rf /workspace/build", which is everyday work; only the spellings of wiping the root or the home
            // are listed. The host-side risk classification still sees every other deletion.
            BashPattern("rm -rf /", ToolPolicy.DENY),
            BashPattern("rm -fr /", ToolPolicy.DENY),
            BashPattern("rm -rf / *", ToolPolicy.DENY),
            BashPattern("rm -rf ~", ToolPolicy.DENY),
            BashPattern("rm -rf ~/", ToolPolicy.DENY),
            BashPattern("rm -rf \$HOME", ToolPolicy.DENY),
            BashPattern("rm -rf \"\$HOME\"", ToolPolicy.DENY),
            BashPattern("mkfs*", ToolPolicy.DENY),
            BashPattern("dd * of=/dev/block/*", ToolPolicy.DENY),
            BashPattern("dd * of=/dev/sd*", ToolPolicy.DENY),
            BashPattern("dd * of=/dev/mmcblk*", ToolPolicy.DENY),
            BashPattern("dd * of=/dev/nvme*", ToolPolicy.DENY),
            BashPattern("mount *", ToolPolicy.DENY),
            BashPattern("umount *", ToolPolicy.DENY),
            BashPattern("chroot *", ToolPolicy.DENY),
            BashPattern("reboot*", ToolPolicy.DENY),
            BashPattern("shutdown*", ToolPolicy.DENY),
            BashPattern("su *", ToolPolicy.DENY),
            BashPattern("sudo *", ToolPolicy.PROMPT),
            BashPattern("git push*", ToolPolicy.PROMPT),
            BashPattern("curl * | *", ToolPolicy.PROMPT),
            BashPattern("wget * | *", ToolPolicy.PROMPT),
        )

        @Suppress("UNCHECKED_CAST")
        private fun deepMerge(base: Map<String, Any?>, over: Map<String, Any?>): Map<String, Any?> {
            val out = LinkedHashMap(base)
            for ((k, v) in over) {
                val existing = out[k]
                out[k] = if (existing is Map<*, *> && v is Map<*, *>) deepMerge(existing as Map<String, Any?>, v as Map<String, Any?>) else v
            }
            return out
        }
    }
}

data class OmpModelCost(val input: Double, val output: Double, val cacheRead: Double = 0.0, val cacheWrite: Double = 0.0) {
    init { require(listOf(input, output, cacheRead, cacheWrite).all { it.isFinite() && it >= 0 }) }
}

data class OmpModel(
    val id: String,
    val name: String? = null,
    val contextWindow: Int? = null,
    val maxTokens: Int? = null,
    val reasoning: Boolean? = null,
    val inputImages: Boolean? = null,
    val cost: OmpModelCost? = null,
)

/** One entry of `models.yml` (docs/models.md). */
data class OmpProvider(
    val id: String,
    val baseUrl: String,
    /** `openai-completions`, `openai-responses`, `anthropic-messages`, `google-generative-ai`, ... */
    val api: String = "openai-completions",
    /** Environment variable that holds the key (the value is read by omp, never written to disk). */
    val apiKeyEnv: String? = null,
    /** `none` for keyless local servers. */
    val auth: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val models: List<OmpModel> = emptyList(),
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9._-]+"))) { "invalid provider id '$id'" }
        require(apiKeyEnv != null || auth == "none" || auth == "oauth" || models.isEmpty()) {
            "provider '$id' needs apiKeyEnv, or auth none/oauth"
        }
    }
}

object OmpModelsFile {
    fun toYaml(providers: List<OmpProvider>): String = Yaml.write(
        mapOf(
            "providers" to providers.associate { p ->
                p.id to buildMap<String, Any?> {
                    put("baseUrl", p.baseUrl)
                    put("api", p.api)
                    p.auth?.let { put("auth", it) }
                    p.apiKeyEnv?.let { put("apiKey", it) }
                    if (p.headers.isNotEmpty()) put("headers", p.headers)
                    if (p.models.isNotEmpty()) {
                        put(
                            "models",
                            p.models.map { m ->
                                buildMap<String, Any?> {
                                    put("id", m.id)
                                    m.name?.let { put("name", it) }
                                    m.cost?.let { put("cost", mapOf("input" to it.input, "output" to it.output, "cacheRead" to it.cacheRead, "cacheWrite" to it.cacheWrite)) }
                                    m.contextWindow?.let { put("contextWindow", it) }
                                    m.maxTokens?.let { put("maxTokens", it) }
                                    m.reasoning?.let { put("reasoning", it) }
                                    m.inputImages?.let { put("input", if (it) listOf("text", "image") else listOf("text")) }
                                }
                            },
                        )
                    }
                }
            },
        ),
    )
}

/**
 * How to start one omp process. [executable] is the prefix that runs omp wherever the guest lives:
 * `["omp"]` on a dev machine, a chroot/glibc launcher on the phone.
 */
data class OmpLaunch(
    val executable: List<String>,
    val workingDirectory: String,
    /** `provider/id`; null leaves omp's own default. */
    val model: String? = null,
    /** Path (as seen by omp) of the overlay written from [OmpSettings.toYaml]. */
    val settingsOverlay: String? = null,
    val sessionDir: String? = null,
    val noSession: Boolean = false,
    /** Project extensions can execute arbitrary code, so they are off unless asked for. */
    val loadExtensions: Boolean = false,
    val loadSkills: Boolean = true,
    val loadRules: Boolean = true,
    /** Language servers are heavy on a phone; opt in per project. */
    val lsp: Boolean = false,
    /** Stop the session after this long (e.g. "2h"). */
    val maxTime: String? = null,
    val extraArgs: List<String> = emptyList(),
) {
    init {
        require(executable.isNotEmpty()) { "executable is empty" }
        require(!(sessionDir != null && noSession)) { "sessionDir and noSession are mutually exclusive" }
    }

    /**
     * `--mode rpc-ui` (not `rpc --no-ui`): tool approvals use the extension runner's UI, and under
     * `--no-ui` any tool that needs approval fails closed instead of asking the host (docs/rpc.md).
     */
    fun command(): List<String> = buildList {
        addAll(executable)
        addAll(listOf("--mode", "rpc-ui"))
        addAll(listOf("--cwd", workingDirectory))
        model?.let { addAll(listOf("--model", it)) }
        settingsOverlay?.let { addAll(listOf("--config", it)) }
        sessionDir?.let { addAll(listOf("--session-dir", it)) }
        if (noSession) add("--no-session")
        if (!loadExtensions) add("--no-extensions")
        if (!loadSkills) add("--no-skills")
        if (!loadRules) add("--no-rules")
        if (!lsp) add("--no-lsp")
        maxTime?.let { addAll(listOf("--max-time", it)) }
        addAll(extraArgs)
    }

    /** The same omp, as its interactive terminal UI, continuing the conversation in [resume] when given. */
    fun interactiveCommand(resume: String? = null): List<String> = buildList {
        addAll(executable)
        addAll(listOf("--cwd", workingDirectory))
        model?.let { addAll(listOf("--model", it)) }
        settingsOverlay?.let { addAll(listOf("--config", it)) }
        sessionDir?.let { addAll(listOf("--session-dir", it)) }
        resume?.let { addAll(listOf("--resume", it)) }
        if (!loadExtensions) add("--no-extensions")
        if (!loadSkills) add("--no-skills")
        if (!loadRules) add("--no-rules")
        if (!lsp) add("--no-lsp")
        addAll(extraArgs)
    }
}

/** Minimal YAML emitter for settings: nested maps, lists, strings, numbers, booleans, null. */
internal object Yaml {
    fun write(value: Any?): String = lines(value, 0).joinToString("\n", postfix = "\n")

    private fun isContainer(v: Any?) = (v is Map<*, *> && v.isNotEmpty()) || (v is List<*> && v.isNotEmpty())

    private fun lines(value: Any?, indent: Int): List<String> {
        val pad = "  ".repeat(indent)
        val out = mutableListOf<String>()
        when (value) {
            is Map<*, *> -> for ((k, v) in value) {
                if (isContainer(v)) {
                    out += "$pad${key(k.toString())}:"
                    out += lines(v, indent + 1)
                } else {
                    out += "$pad${key(k.toString())}: ${inline(v)}"
                }
            }
            is List<*> -> for (item in value) {
                if (isContainer(item)) {
                    // Render the item one level deeper, then turn its first line into "- ...".
                    val rendered = lines(item, indent + 1)
                    out += "$pad- ${rendered.first().trimStart()}"
                    out += rendered.drop(1)
                } else {
                    out += "$pad- ${inline(item)}"
                }
            }
            else -> out += "$pad${inline(value)}"
        }
        return out
    }

    private fun inline(v: Any?): String = when (v) {
        is Map<*, *> -> "{}"
        is List<*> -> "[]"
        else -> scalar(v)
    }

    private fun key(k: String) = if (SAFE_KEY.matches(k)) k else quote(k)

    private fun scalar(v: Any?): String = when (v) {
        null -> "null"
        is Boolean, is Int, is Long, is Double, is Float -> v.toString()
        else -> quote(v.toString())
    }

    private fun quote(s: String): String {
        if (s.isNotEmpty() && SAFE_PLAIN.matches(s) && s.lowercase() !in RESERVED) return s
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private val SAFE_KEY = Regex("^[A-Za-z_][A-Za-z0-9_.-]*$")
    private val SAFE_PLAIN = Regex("^[A-Za-z0-9_./][A-Za-z0-9_./-]*$")
    private val RESERVED = setOf("true", "false", "yes", "no", "on", "off", "null", "~")
}
