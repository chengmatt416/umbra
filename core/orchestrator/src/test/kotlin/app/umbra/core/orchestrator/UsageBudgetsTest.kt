package app.umbra.core.orchestrator

import app.umbra.core.model.Clock
import app.umbra.core.session.AiUsageLimit
import app.umbra.core.session.AiUsageUnit
import app.umbra.core.session.Session
import app.umbra.core.session.UsageReset
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir

class UsageBudgetsTest {
    @TempDir lateinit var dir: Path
    private fun report(cost: Double, transcript: String = "one", priced: Boolean = true) = buildJsonObject {
        put("provider", "test"); put("transcript", transcript); put("totalCost", cost); put("priced", priced); put("costKnown", true)
    }
    private fun session(id: String = "s", limit: AiUsageLimit? = null, agent: String? = null) = Session(id, id, createdAtMs = 0, request = "task", usageLimit = limit, agentId = agent)

    @Test
    fun dollarLimitSurvivesRestartAndCannotDoubleChargeReplayedReports(): Unit = runBlocking {
        val file = dir.resolve("usage.json")
        val limit = AiUsageLimit(AiUsageUnit.API_USD, 0.5, startedAtMs = 0)
        val s = session(limit = limit)
        var meter = UsageBudgets(file, { null }, { emptyList() })
        assertNull(meter.admit(s, report(0.0)))
        assertNull(meter.admit(s, report(0.4)))
        meter = UsageBudgets(file, { null }, { emptyList() })
        assertNull(meter.admit(s, report(0.4)))
        assertEquals(0.4, meter.spent(s).getValue("task:s"))
        assertNotNull(meter.admit(s, report(0.5)))
        assertNotNull(meter.reason(s))
    }

    @Test
    fun anAgentsSessionsShareItsCapAndResetOnTheChosenSchedule(): Unit = runBlocking {
        var now = 0L
        val limit = AiUsageLimit(AiUsageUnit.API_USD, 1.0, UsageReset.TEN_MINUTES, 0)
        val meter = UsageBudgets(dir.resolve("usage.json"), { limit }, { emptyList() }, Clock { now })
        val a = session("a", agent = "owner"); val b = session("b", agent = "owner")
        meter.admit(a, report(0.0)); meter.admit(b, report(0.0))
        assertNull(meter.admit(a, report(0.6)))
        assertNotNull(meter.admit(b, report(0.4)))
        assertNotNull(meter.reason(a))
        now = 600_000
        assertNull(meter.reason(a))
        assertNull(meter.admit(a, report(0.6)))
        assertEquals(0.0, meter.spent(a).getValue("agent:owner"))
    }

    @Test
    fun quotaCountsPositiveChangesAndWindowResetsWithoutRechargingTheBudget(): Unit = runBlocking {
        var percentage = 20.0
        var resetAt = 18_000_000L
        val meter = UsageBudgets(dir.resolve("usage.json"), { null }, { listOf(QuotaReading("account", AiUsageUnit.FIVE_HOUR_PERCENT, percentage, resetAt)) }, Clock { 0 })
        val s = session(limit = AiUsageLimit(AiUsageUnit.FIVE_HOUR_PERCENT, 10.0, startedAtMs = 0))
        assertNull(meter.admit(s, report(0.0)))
        percentage = 25.0
        assertNull(meter.admit(s, report(0.0)))
        percentage = 23.0
        assertNull(meter.admit(s, report(0.0)))
        percentage = 25.0
        assertEquals(null, meter.admit(s, report(0.0)))
        resetAt += 18_000_000
        percentage = 5.0
        assertNotNull(meter.admit(s, report(0.0)))
        assertEquals(10.0, meter.spent(s).getValue("task:s"))
    }

    @Test
    fun childTasksCannotBypassTheParentsLimit(): Unit = runBlocking {
        val parent = session("parent", AiUsageLimit(AiUsageUnit.API_USD, 1.0, startedAtMs = 0))
        val a = session("a").copy(spawnedBy = parent.id)
        val b = session("b").copy(spawnedBy = parent.id)
        val meter = UsageBudgets(dir.resolve("usage.json"), { null }, { emptyList() }, sessionLookup = { if (it == parent.id) parent else null })
        meter.admit(a, report(0.0)); meter.admit(b, report(0.0))
        assertNull(meter.admit(a, report(0.6)))
        assertNotNull(meter.admit(b, report(0.4)))
        assertNotNull(meter.reason(parent))
        assertNotNull(meter.reason(a))
        assertEquals(1.0, meter.spent(parent).getValue("task:parent"))
    }

    @Test
    fun aSpecialistPaysBothItsOwnAgentCapAndTheCoordinatorAndSharedTaskCaps(): Unit = runBlocking {
        val coordinatorLimit = AiUsageLimit(AiUsageUnit.API_USD, 1.0, startedAtMs = 0)
        val specialistLimit = AiUsageLimit(AiUsageUnit.API_USD, 0.8, startedAtMs = 0)
        val parent = session("group", AiUsageLimit(AiUsageUnit.API_USD, 2.0, startedAtMs = 0), "coordinator")
        val a = session("specialist-a", agent = "specialist").copy(spawnedBy = parent.id)
        val b = session("specialist-b", agent = "specialist").copy(spawnedBy = a.id)
        val all = listOf(parent, a, b).associateBy { it.id }
        val meter = UsageBudgets(dir.resolve("usage.json"), { if (it == "coordinator") coordinatorLimit else specialistLimit }, { emptyList() }, sessionLookup = { all[it] })
        meter.admit(a, report(0.0)); meter.admit(b, report(0.0))
        assertNull(meter.admit(a, report(0.4)))
        assertNotNull(meter.admit(b, report(0.4)))
        assertEquals(0.8, meter.spent(b).getValue("agent:specialist"))
        assertEquals(0.8, meter.spent(parent).getValue("agent:coordinator"))
        assertEquals(0.8, meter.spent(parent).getValue("task:group"))
    }

    @Test
    fun missingParentsAndCyclicAncestryStopRequests(): Unit = runBlocking {
        val child = session("child").copy(spawnedBy = "missing")
        val meter = UsageBudgets(dir.resolve("usage.json"), { null }, { emptyList() })
        assertFailsWith<IllegalArgumentException> { meter.admit(child, report(0.0)) }
        val a = session("a").copy(spawnedBy = "b")
        val b = session("b").copy(spawnedBy = "a")
        val cycle = UsageBudgets(dir.resolve("cycle.json"), { null }, { emptyList() }, sessionLookup = { if (it == "a") a else b })
        assertFailsWith<IllegalStateException> { cycle.configured(a) }
        assertFailsWith<IllegalStateException> { cycle.admit(a, report(0.0)) }
    }

    @Test
    fun unknownPricingAndMissingQuotaFailClosedButUnlimitedSessionsWork(): Unit = runBlocking {
        val meter = UsageBudgets(dir.resolve("usage.json"), { null }, { emptyList() })
        assertNotNull(meter.admit(session(limit = AiUsageLimit(AiUsageUnit.API_USD, 1.0, startedAtMs = 0)), report(0.0, priced = false)))
        assertNotNull(meter.admit(session(limit = AiUsageLimit(AiUsageUnit.WEEKLY_PERCENT, 10.0, startedAtMs = 0)), report(0.0)))
        assertNull(meter.admit(session(), report(0.0, priced = false)))
    }
}
