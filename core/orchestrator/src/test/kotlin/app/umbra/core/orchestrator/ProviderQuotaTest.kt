package app.umbra.core.orchestrator

import app.umbra.core.session.AiUsageUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderQuotaTest {
    private fun fixture(fetched: Long = 1000, fraction: String = "\"usedFraction\":0.25", metadata: String = "") = """
        {"reports":[{"provider":"anthropic","fetchedAt":$fetched,"metadata":{"accountId":"dummy"$metadata},"limits":[
        {"id":"five_hour","scope":{"provider":"anthropic","accountId":"dummy"},"window":{"id":"5h","durationMs":18000000,"resetsAt":18001000},"amount":{$fraction,"unit":"percent"}},
        {"id":"weekly","scope":{"provider":"anthropic","accountId":"dummy"},"window":{"id":"7d","resetsAt":604801000},"amount":{"remainingFraction":0.8,"unit":"percent"}}
        ]}]}
    """.trimIndent()

    @Test
    fun readsNormalizedWindowsAndUsesStableIdentityOnly() {
        val readings = ProviderQuota.parse(fixture(), 1000)
        assertEquals(25.0, readings.first { it.window == AiUsageUnit.FIVE_HOUR_PERCENT }.usedPercent)
        assertEquals(20.0, readings.first { it.window == AiUsageUnit.WEEKLY_PERCENT }.usedPercent, 0.0001)
        val changed = ProviderQuota.parse(fixture(fraction = "\"used\":30", metadata = ",\"balance\":42"), 1000)
        assertEquals(readings.first().id, changed.first().id)
        assertEquals(30.0, changed.first().usedPercent)
        assertTrue(ProviderQuota.parse(fixture(fetched = 0), 200_000).isEmpty())
    }
}
