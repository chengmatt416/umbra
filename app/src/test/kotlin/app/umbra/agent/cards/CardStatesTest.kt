package app.umbra.agent.cards

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardStatesTest {
    @Test fun roundTripKeepsChecksAndSentAnswersInTheCardsDirectory() {
        val dir = Files.createTempDirectory("card-states").toFile()
        try {
            val state = CardState(mapOf("checks/oats" to true), mapOf("poll" to "Tuesday"))
            CardStates(dir).use { store ->
                assertEquals(CardState(), store.get("missing"))
                store.put("session/call", state)
                assertEquals(state, store.get("session/call"))
                store.flush()
                assertTrue(File(dir, "cards/states.json").isFile)
                assertEquals(null, store.lastWriteError)
            }
            CardStates(dir).use { assertEquals(state, it.get("session/call")) }
            assertFalse(File(dir, "cards").listFiles().orEmpty().any { it.name.endsWith(".tmp") })
        } finally { dir.deleteRecursively() }
    }

    @Test fun leastRecentlyUsedEntriesAreEvictedAndAccessOrderSurvivesRestart() {
        val dir = Files.createTempDirectory("card-lru").toFile()
        val state = CardState(sent = mapOf("poll" to "Answered"))
        try {
            CardStates(dir).use { store ->
                repeat(2_000) { store.put("card-$it", state) }
                assertEquals(state, store.get("card-0"))
                store.put("card-2000", state)
                assertEquals(CardState(), store.get("card-1"))
            }
            CardStates(dir).use { store ->
                store.put("card-2001", state)
                assertEquals(CardState(), store.get("card-2"))
                assertEquals(state, store.get("card-0"))
                assertEquals(state, store.get("card-2001"))
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun callerMutationCannotChangeTheSavedStateAndBurstsSaveTheLatestValue() {
        val dir = Files.createTempDirectory("card-snapshot").toFile()
        try {
            val checks = mutableMapOf("checks/a" to true)
            CardStates(dir).use { store ->
                store.put("immutable", CardState(checks))
                checks["checks/a"] = false
                assertTrue(store.get("immutable").checks.getValue("checks/a"))
                repeat(20) { n -> store.put("latest", CardState(sent = mapOf("form" to n.toString()))) }
            }
            CardStates(dir).use { store -> assertEquals("19", store.get("latest").sent["form"]) }
        } finally { dir.deleteRecursively() }
    }
}
