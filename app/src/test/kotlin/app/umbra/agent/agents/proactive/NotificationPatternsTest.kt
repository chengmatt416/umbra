package app.umbra.agent.agents.proactive

import app.umbra.core.model.UmbraJson
import kotlin.test.*

class NotificationPatternsTest {
    @Test fun reviewedRegexesMatchAlternativesNumbersAndUnicode() {
        val pattern = NotificationPatterns.compile(ProbeSpec.NotificationPattern(null, "(?:invoice|帳單)\\s*#?\\d{2,4}", literal = false))
        assertTrue(pattern.matcher("New INVOICE #412 is ready").find())
        assertTrue(pattern.matcher("收到帳單 1234").find())
        assertFalse(pattern.matcher("A receipt is ready").find())
    }
    @Test fun existingSerializedWatchersRemainLiteral() {
        val old = UmbraJson.decodeFromString(ProbeSpec.NotificationPattern.serializer(), """{"packageName":null,"regex":"price [42].*"}""")
        assertTrue(old.literal)
        val pattern = NotificationPatterns.compile(old)
        assertTrue(pattern.matcher("PRICE [42].* received").find())
        assertFalse(NotificationPatterns.matches(old, pattern, "Price 4 anything"))
        val unicode = old.copy(regex = "I")
        assertTrue(NotificationPatterns.matches(unicode, NotificationPatterns.compile(unicode), "ı"))
    }
    @Test fun invalidOrUnsupportedPatternsAreRejectedBeforeAuthorization() {
        for (regex in listOf("[", "(?=invoice)", "(a)\\1", "a{1000}b{1000}c{1000}", "x".repeat(301)))
            assertFailsWith<IllegalArgumentException> { NotificationPatterns.compile(ProbeSpec.NotificationPattern(null, regex, literal = false)) }
        val store = ProactiveStore(java.nio.file.Files.createTempDirectory("watcher-regex-").toFile())
        assertFailsWith<IllegalArgumentException> { store.saveWatcher(AgentWatcher("w", "a", "Invalid", ProbeSpec.NotificationPattern(null, "(?=x)", literal = false), ConditionSpec.Changed, onMatchInstruction = "Review")) }
        assertTrue(store.watchers("a").isEmpty())
    }
    @Test fun nestedQuantifiersDoNotBacktrackOnLongNonmatches() {
        val pattern = NotificationPatterns.compile(ProbeSpec.NotificationPattern(null, "^(a+)+$", literal = false))
        assertFalse(pattern.matcher("a".repeat(100_000) + "!").find())
    }
}
