package app.umbra.core.safety

import app.umbra.core.model.action.Operation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandPrefixesTest {
    @Test
    fun grantsMatchWholeLiteralWordsAndRejectShellComposition() {
        assertEquals("echo", CommandPrefixes.offered(Operation.Shell("echo dummy-secret")))
        assertEquals("curl", CommandPrefixes.offered(Operation.Shell("curl https://dummy:token@example.test")))
        assertEquals("git status", CommandPrefixes.offered(Operation.Shell("git status --short")))
        assertTrue(CommandPrefixes.matches("git status --porcelain", "git status"))
        for (command in listOf("git statuses", "git status; rm x", "git status\nrm x", "git status && rm x", "git status $(whoami)", "git status > x", "git status `id`")) {
            assertFalse(CommandPrefixes.matches(command, "git status"), command)
        }
        for (operation in listOf(Operation.Shell("sh -c ls"), Operation.Shell("python3 script.py"), Operation.Shell("git status", asRoot = true), Operation.Shell("git status", stdin = "x"), Operation.Shell("git status", env = mapOf("X" to "y")), Operation.Tap)) {
            assertNull(CommandPrefixes.offered(operation), operation.toString())
        }
    }

    @Test
    fun persistentGrantBindsContextAndCannotCoverCriticalFindings() {
        val rule = AllowRule("r", "*", setOf(RiskCategory.SENDS_MESSAGE), operation = "shell", commandPrefix = "git push", literalCommand = true, contextDirectory = "/work/a")
        val command = request(Operation.Shell("git push origin main"))
        assertTrue(rule.matches(command, null, 0, "/work/a"))
        assertFalse(rule.matches(command, null, 0, "/work/b"))
        assertFalse(rule.matches(command.copy(operation = Operation.Shell("git push origin main", stdin = "program")), null, 0, "/work/a"))
        assertFalse(rule.covers(RiskFinding(RiskCategory.SENDS_MESSAGE, RiskLevel.CRITICAL, "critical")))
    }

    @Test
    fun screenInputNeverEarnsAPersistentPrefix() {
        for (command in listOf("input tap 1 2", "/system/bin/input text x", "cmd input keyevent 3", "monkey -p a 1", "sendevent a 1 2 3", "uiautomator dump")) {
            assertNull(CommandPrefixes.offered(Operation.Shell(command)), command)
        }
        val old = AllowRule("r", "*", setOf(RiskCategory.DYNAMIC_COMMAND), operation = "shell", commandPrefix = "input", literalCommand = true)
        assertFalse(old.matches(request(Operation.Shell("input tap 1 2")), null, 0), "grants saved before this rule stop applying")
    }
}
