package app.umbra.runtime.omp

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.yaml.snakeyaml.Yaml as SnakeYaml

class OmpConfigTest {
    @Suppress("UNCHECKED_CAST")
    private fun parse(yaml: String): Map<String, Any?> = SnakeYaml().load<Any?>(yaml) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.map(key: String) = this[key] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.list(key: String) = this[key] as List<Any?>

    // ---- settings overlay ----

    @Test
    fun theOverlayIsValidYamlWithTheExpectedStructure() {
        val settings = OmpSettings(
            approvalMode = OmpApprovalMode.WRITE,
            toolPolicies = mapOf("android_tap" to ToolPolicy.ALLOW, "eval" to ToolPolicy.PROMPT),
            bashPatterns = listOf(BashPattern("rm -rf /", ToolPolicy.DENY), BashPattern("git push*", ToolPolicy.PROMPT)),
            allowCompoundBash = true,
        )
        val doc = parse(settings.toYaml())
        val tools = doc.map("tools")
        assertEquals("write", tools["approvalMode"])
        assertEquals(mapOf("android_tap" to "allow", "eval" to "prompt"), tools["approval"])

        val bash = doc.map("bash")
        assertEquals(true, bash["allowCompoundCommands"])
        assertEquals(
            listOf(mapOf("match" to "rm -rf /", "approval" to "deny"), mapOf("match" to "git push*", "approval" to "prompt")),
            bash.list("patterns"),
        )
    }

    @Test
    fun desktopOnlyBuiltinsAreDisabledByDefault() {
        val doc = parse(OmpSettings().toYaml())
        assertEquals(mapOf("enabled" to false), doc["browser"])
        assertEquals(mapOf("enabled" to false), doc["computer"])
        assertEquals("write", doc.map("tools")["approvalMode"])
        assertNull(doc["bash"], "no bash section when there are no patterns")
    }

    @Test
    fun extraSettingsAreMergedDeeplyAndWin() {
        val doc = parse(
            OmpSettings(
                toolPolicies = mapOf("a" to ToolPolicy.ALLOW),
                extra = mapOf("tools" to mapOf("xdev" to false, "approvalMode" to "yolo"), "advisor" to mapOf("enabled" to true)),
            ).toYaml(),
        )
        val tools = doc.map("tools")
        assertEquals("yolo", tools["approvalMode"], "extra overrides")
        assertEquals(false, tools["xdev"])
        assertEquals(mapOf("a" to "allow"), tools["approval"], "unrelated keys survive the merge")
        assertEquals(mapOf("enabled" to true), doc["advisor"])
    }

    @Test
    fun awkwardStringsSurviveTheRoundTrip() {
        val tricky = listOf(
            "echo \"hi\" && ls", "a: b", "# not a comment", "tab\there", "line1\nline2", "back\\slash", "yes", "true", "null", "~",
            "  leading", "trailing  ", "", "'single'", "日本語", "*", "{braces}", "[brackets]", "- dash", "key: value\nother: x",
        )
        val doc = parse(OmpSettings(bashPatterns = tricky.map { BashPattern(it, ToolPolicy.PROMPT) }).toYaml())
        val roundTripped = doc.map("bash").list("patterns").map { (it as Map<*, *>)["match"] }
        assertEquals(tricky, roundTripped)
    }

    @Test
    fun productBashPatternsDenyTheCatastrophicAndPromptTheOutward() {
        val patterns = OmpSettings.PRODUCT_BASH_PATTERNS
        fun policy(match: String) = patterns.first { it.match == match }.approval
        assertEquals(ToolPolicy.DENY, policy("rm -rf /"))
        assertEquals(ToolPolicy.DENY, policy("mkfs*"))
        assertEquals(ToolPolicy.DENY, policy("su *"))
        assertEquals(ToolPolicy.PROMPT, policy("git push*"))
        assertTrue(patterns.indexOfFirst { it.match == "rm -rf /" } < patterns.indexOfFirst { it.match == "git push*" })
        // They must also produce valid YAML.
        parse(OmpSettings(bashPatterns = patterns).toYaml())
    }

    /** omp's rule (see OmpIntegrationTest): no star means the whole command, a star matches anything including slashes. */
    private fun matches(pattern: String, command: String): Boolean =
        Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) }).matches(command)

    private fun verdict(command: String): ToolPolicy? =
        OmpSettings.PRODUCT_BASH_PATTERNS.firstOrNull { matches(it.match, command) }?.approval

    @Test
    fun productPatternsForbidWipingTheRootOrHomeButNotEverydayDeletion() {
        for (wipe in listOf("rm -rf /", "rm -fr /", "rm -rf / --no-preserve-root", "rm -rf ~", "rm -rf ~/", "rm -rf \$HOME", "rm -rf \"\$HOME\"", "mkfs.ext4 /dev/sda1", "dd if=/dev/zero of=/dev/block/sda bs=1M")) {
            assertEquals(ToolPolicy.DENY, verdict(wipe), "'$wipe' must be forbidden")
        }
        for (everyday in listOf(
            "rm -rf /workspace/projects/app/build", "rm -rf /tmp/build-cache", "rm -rf ~/.cache/pip", "rm -rf build", "rm -rf ./out /workspace/old",
            "dd if=/dev/zero of=/dev/null bs=1M count=10", "dd if=in.img of=/workspace/out.img", "mountpoint /workspace",
        )) {
            assertEquals(null, verdict(everyday), "'$everyday' is ordinary work and must not be caught")
        }
        assertEquals(ToolPolicy.PROMPT, verdict("git push origin main"))
    }

    // ---- models.yml ----

    @Test
    fun aKeylessLocalProviderMatchesTheDocumentedShape() {
        val yaml = OmpModelsFile.toYaml(
            listOf(
                OmpProvider(
                    "local-openai", "http://127.0.0.1:8000/v1", auth = "none",
                    models = listOf(OmpModel("Qwen/Qwen2.5-Coder-32B-Instruct", "Qwen 2.5 Coder", contextWindow = 32768)),
                ),
            ),
        )
        val provider = parse(yaml).map("providers").map("local-openai")
        assertEquals("http://127.0.0.1:8000/v1", provider["baseUrl"])
        assertEquals("openai-completions", provider["api"])
        assertEquals("none", provider["auth"])
        val model = provider.list("models").single() as Map<*, *>
        assertEquals("Qwen/Qwen2.5-Coder-32B-Instruct", model["id"])
        assertEquals(32768, model["contextWindow"])
    }

    @Test
    fun theApiKeyIsWrittenAsAnEnvironmentVariableNameNeverAsASecret() {
        val yaml = OmpModelsFile.toYaml(
            listOf(OmpProvider("hosted", "https://api.example.com/v1", apiKeyEnv = "UMBRA_KEY_HOSTED", models = listOf(OmpModel("m", inputImages = true)))),
        )
        val provider = parse(yaml).map("providers").map("hosted")
        assertEquals("UMBRA_KEY_HOSTED", provider["apiKey"])
        assertEquals(listOf("text", "image"), (provider.list("models").single() as Map<*, *>)["input"])
    }

    @Test
    fun providersNeedACredentialSourceAndAValidId() {
        assertFailsWith<IllegalArgumentException> { OmpProvider("x", "http://h", models = listOf(OmpModel("m"))) }
        assertFailsWith<IllegalArgumentException> { OmpProvider("bad id!", "http://h", auth = "none") }
    }

    // ---- launch command ----

    @Test
    fun theCommandUsesRpcUiAndSafeDefaults() {
        val cmd = OmpLaunch(listOf("omp"), "/work", model = "fake/fake-model", settingsOverlay = "/cfg/s.yml", noSession = true).command()
        assertEquals(listOf("omp", "--mode", "rpc-ui"), cmd.take(3), "rpc-ui, not rpc --no-ui: approvals need the UI")
        assertFalse("--no-ui" in cmd)
        assertContains(cmd, "--no-extensions")
        assertContains(cmd, "--no-lsp")
        assertContains(cmd, "--no-session")
        assertEquals("/work", cmd[cmd.indexOf("--cwd") + 1])
        assertEquals("fake/fake-model", cmd[cmd.indexOf("--model") + 1])
        assertEquals("/cfg/s.yml", cmd[cmd.indexOf("--config") + 1])
    }

    @Test
    fun optInsRemoveTheRestrictiveFlags() {
        val cmd = OmpLaunch(listOf("omp"), "/w", loadExtensions = true, lsp = true, loadSkills = false, sessionDir = "/s", maxTime = "2h").command()
        assertFalse("--no-extensions" in cmd)
        assertFalse("--no-lsp" in cmd)
        assertContains(cmd, "--no-skills")
        assertEquals("/s", cmd[cmd.indexOf("--session-dir") + 1])
        assertEquals("2h", cmd[cmd.indexOf("--max-time") + 1])
    }

    @Test
    fun theExecutablePrefixCanBeAGuestLauncher() {
        val cmd = OmpLaunch(listOf("/system/bin/su", "0", "chroot-run", "/opt/umbra/bin/omp"), "/workspace").command()
        assertEquals(listOf("/system/bin/su", "0", "chroot-run", "/opt/umbra/bin/omp", "--mode", "rpc-ui"), cmd.take(6))
    }

    @Test
    fun conflictingSessionOptionsAreRejected() {
        assertFailsWith<IllegalArgumentException> { OmpLaunch(listOf("omp"), "/w", sessionDir = "/s", noSession = true) }
        assertFailsWith<IllegalArgumentException> { OmpLaunch(emptyList(), "/w") }
    }
}
