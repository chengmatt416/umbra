package app.umbra.core.control

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class VaultBackupTest {
    @TempDir
    lateinit var dir: Path

    private val body = VaultBackupBody(
        createdAtMs = 1,
        secrets = listOf(
            BackedUpSecret("ANTHROPIC_API_KEY", "sk-ant-backup-value", "Anthropic", "anthropic", CredentialScope.ITS_PROVIDER_ONLY),
            BackedUpSecret("OPENAI_API_KEY", "sk-openai-backup", "OpenAI", "openai"),
        ),
        git = listOf(BackedUpGitCredential("forge", "https://git.example.com/o/r", "x-access-token", setOf(GitOperation.READ, GitOperation.PUBLISH), null, "tok_backup")),
    )

    @Test
    fun theCodeIsIndependentMaterialAndTheFileOpensOnlyWithItUnchanged() {
        val code = VaultBackup.newRecoveryCode()
        assertTrue(code.matches(Regex("[0-9A-Z]{5}(-[0-9A-Z]{5}){4}")), code)
        assertTrue(code != VaultBackup.newRecoveryCode(), "random each time")
        val sealed = VaultBackup.seal(body, code)
        assertTrue(String(sealed).startsWith(VaultBackup.HEADER))
        assertFalse("sk-ant" in String(sealed) || "tok_backup" in String(sealed), "nothing readable without the code")
        // Typed loosely: lower case, spaces, O for 0.
        val loose = code.lowercase().replace("-", " ").replace('0', 'o')
        assertEquals(body, VaultBackup.open(sealed, loose))
        val other = VaultBackup.newRecoveryCode()
        assertFailsWith<Exception> { VaultBackup.open(sealed, other) }
        val lines = String(sealed).lines().toMutableList()
        lines[3] = lines[3].replaceRange(20, 22, if (lines[3].substring(20, 22) == "AA") "BB" else "AA")
        assertFailsWith<Exception>("a changed file does not open") { VaultBackup.open(lines.joinToString("\n").toByteArray(), code) }
        assertFailsWith<Exception>("an evidence file is not a vault backup") { VaultBackup.open(Evidence.seal("{}".toByteArray(), code.toCharArray()), code) }
        assertFailsWith<IllegalArgumentException> { VaultBackup.normalizeCode("too-short") }
    }

    @Test
    fun restoringBringsSecretsBackButNotTheAuthorityToPublish() {
        val store = ControlStore(dir)
        val vault = CredentialVault(store)
        val git = GitCredentials(store)
        val secrets = HashMap<String, String>()
        val tokens = HashMap<String, String>()
        secrets["OPENAI_API_KEY"] = "the-current-value"
        val report = VaultBackup.restore(body, secrets.keys, { n, v -> secrets[n] = v }, vault, git, { id, t -> tokens[id] = t }, store.journal("credentials"))
        assertEquals(listOf("ANTHROPIC_API_KEY"), report.restored)
        assertEquals(listOf("OPENAI_API_KEY"), report.kept)
        assertEquals("the-current-value", secrets["OPENAI_API_KEY"], "a value already present is kept")
        assertEquals("sk-ant-backup-value", secrets["ANTHROPIC_API_KEY"])
        assertEquals(CredentialScope.ITS_PROVIDER_ONLY, vault["ANTHROPIC_API_KEY"]?.scope, "its scope came back with it")

        val c = git.forRemote("https://git.example.com/o/r")!!
        assertEquals("tok_backup", tokens[c.id])
        assertTrue(c.publishSuspended)
        assertFailsWith<GitCredentialException> { git.authorize(c.id, GitOperation.PUBLISH) }
        assertEquals(c.id, git.authorize(c.id, GitOperation.READ).id, "reading is not authority")
        git.restorePublish(c.id)
        assertEquals(c.id, git.authorize(c.id, GitOperation.PUBLISH).id, "until the user grants it again")

        val again = VaultBackup.restore(body, secrets.keys, { n, v -> secrets[n] = v }, vault, git, { id, t -> tokens[id] = t }, store.journal("credentials"))
        assertEquals(listOf("https://git.example.com/o/r"), again.gitKept)
        assertTrue(store.journal("credentials").readAll().any { it.kind == "vault.restored" })
        assertTrue(store.journal("git-broker").readAll().any { it.kind == "git.credential_restored" })
    }
}
