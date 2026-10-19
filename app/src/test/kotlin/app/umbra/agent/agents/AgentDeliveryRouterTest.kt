package app.umbra.agent.agents

import app.umbra.core.decision.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class AgentDeliveryRouterTest {
    private val candidates = listOf(AgentDeliveryRouter.Recipient("work", "Work", "Work and meetings"), AgentDeliveryRouter.Recipient("home", "Home", "Family and travel"))
    private class Engine(var choices: List<String>) : DecisionEngine {
        override val name = "laya"
        var calls = 0
        var failure: Exception? = null
        var confidence = 0.95
        override suspend fun isAvailable() = true
        override suspend fun decide(request: DecisionRequest): DecisionResponse {
            calls++
            failure?.let { throw it }
            return DecisionResponse(request.questions.keys.mapIndexed { i, k -> k to Answer.ChoiceAnswer(choices[i], emptyMap(), confidence) }.toMap(), name, 1)
        }
    }

    @Test
    fun selectsRelevantAgentsAndCanBroadcastAndCachesTheSameItem() = runBlocking {
        val engine = Engine(listOf("deliver", "skip"))
        val router = AgentDeliveryRouter(engine) { true }
        assertEquals(setOf("work"), router.recipients("1", "Meeting", candidates))
        assertEquals(setOf("work"), router.recipients("1", "Meeting", candidates))
        assertEquals(1, engine.calls)
        engine.choices = listOf("deliver", "deliver")
        assertEquals(setOf("work", "home"), router.recipients("2", "Emergency affecting everyone", candidates))
        engine.choices = listOf("skip", "skip")
        assertTrue(router.recipients("3", "Unrelated noise", candidates).isEmpty())
    }

    @Test
    fun uncertaintyAndFailurePreserveOnlyOptedInRecipients() = runBlocking {
        val engine = Engine(listOf("skip"))
        val router = AgentDeliveryRouter(engine) { true }
        engine.confidence = 0.2
        assertEquals(setOf("work"), router.recipients("1", "Notice", candidates.take(1)))
        engine.failure = IllegalStateException("offline")
        assertEquals(setOf("work"), router.recipients("2", "Notice", candidates.take(1)))
        engine.failure = CancellationException("cancelled")
        assertFailsWith<CancellationException> { router.recipients("3", "Notice", candidates.take(1)) }
        Unit
    }

    @Test
    fun withoutLayaUsesSubscriptionsAndChangesInRolesInvalidateDecisions() = runBlocking {
        val engine = Engine(listOf("skip", "skip"))
        var available = false
        val router = AgentDeliveryRouter(engine) { available }
        assertEquals(setOf("work", "home"), router.recipients("1", "Notice", candidates))
        assertEquals(0, engine.calls)
        available = true
        assertTrue(router.recipients("1", "Notice", candidates).isEmpty())
        engine.choices = listOf("deliver")
        assertEquals(setOf("work"), router.recipients("1", "Notice", listOf(candidates.first().copy(role = "All notices"))))
    }
}
