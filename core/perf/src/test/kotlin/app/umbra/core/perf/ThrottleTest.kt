package app.umbra.core.perf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThrottleTest {
    private val policy = ThrottlePolicy()
    private fun sample(
        thermal: ThermalStatus = ThermalStatus.NONE,
        memAvail: Long? = 3000,
        battery: Int? = 80,
        charging: Boolean? = false,
        storage: Long? = 20_000,
        cpu: Float? = 20f,
        lowMemory: Boolean = false,
        online: Boolean? = true,
    ) = ResourceSample(
        timestampMs = 0, cpuPct = cpu, memAvailMb = memAvail, lowMemory = lowMemory, storageFreeMb = storage,
        batteryPct = battery, charging = charging, thermal = thermal, networkOnline = online,
    )

    @Test
    fun aHealthyDeviceRunsAtFullConcurrency() {
        val d = policy.evaluate(sample())
        assertEquals(PressureLevel.NORMAL, d.level)
        assertEquals(3, d.maxConcurrentSessions)
        assertEquals(2, d.maxConcurrentExpensive)
        assertTrue(d.allowNewSessions)
        for (w in WorkClass.entries) assertTrue(d.allows(w), "$w")
        assertTrue(d.reasons.isEmpty())
    }

    @Test
    fun thermalStatusMapsToPressureLevels() {
        assertEquals(PressureLevel.NORMAL, policy.evaluate(sample(thermal = ThermalStatus.LIGHT)).level)
        assertEquals(PressureLevel.ELEVATED, policy.evaluate(sample(thermal = ThermalStatus.MODERATE)).level)
        assertEquals(PressureLevel.HIGH, policy.evaluate(sample(thermal = ThermalStatus.SEVERE)).level)
        for (t in listOf(ThermalStatus.CRITICAL, ThermalStatus.EMERGENCY, ThermalStatus.SHUTDOWN)) {
            assertEquals(PressureLevel.CRITICAL, policy.evaluate(sample(thermal = t)).level, "$t")
        }
    }

    @Test
    fun levelsReduceConcurrencyProgressively() {
        val elevated = policy.decisionFor(PressureLevel.ELEVATED)
        assertEquals(2, elevated.maxConcurrentSessions)
        assertEquals(1, elevated.maxConcurrentExpensive)
        assertTrue(elevated.allows(WorkClass.EXPENSIVE))

        val high = policy.decisionFor(PressureLevel.HIGH)
        assertEquals(1, high.maxConcurrentSessions)
        assertTrue(high.allowNewSessions, "a task the person asks for still starts, one at a time")
        assertFalse(high.allows(WorkClass.EXPENSIVE))
        assertTrue(high.allows(WorkClass.LIGHT))

        val critical = policy.decisionFor(PressureLevel.CRITICAL)
        assertFalse(critical.allowNewSessions)
        assertFalse(critical.allows(WorkClass.LIGHT))
        assertTrue(critical.allows(WorkClass.INTERACTIVE), "the user can always act")
    }

    @Test
    fun memoryThresholds() {
        assertEquals(PressureLevel.NORMAL, policy.evaluate(sample(memAvail = 2000)).level)
        assertEquals(PressureLevel.ELEVATED, policy.evaluate(sample(memAvail = 900)).level)
        assertEquals(PressureLevel.HIGH, policy.evaluate(sample(memAvail = 500)).level)
        assertEquals(PressureLevel.CRITICAL, policy.evaluate(sample(memAvail = 200)).level)
        assertEquals(PressureLevel.HIGH, policy.evaluate(sample(lowMemory = true)).level)
        assertEquals(PressureLevel.NORMAL, policy.evaluate(sample(memAvail = null)).level, "unknown is not pressure")
    }

    @Test
    fun batteryOnlyMattersWhenNotCharging() {
        assertEquals(PressureLevel.CRITICAL, policy.evaluate(sample(battery = 4, charging = false)).level)
        assertEquals(PressureLevel.HIGH, policy.evaluate(sample(battery = 12, charging = false)).level)
        assertEquals(PressureLevel.ELEVATED, policy.evaluate(sample(battery = 22, charging = false)).level)
        assertEquals(PressureLevel.NORMAL, policy.evaluate(sample(battery = 4, charging = true)).level)
        assertEquals(PressureLevel.NORMAL, policy.evaluate(sample(battery = 4, charging = null)).level)
    }

    @Test
    fun storageThresholds() {
        assertEquals(PressureLevel.ELEVATED, policy.evaluate(sample(storage = 800)).level)
        assertEquals(PressureLevel.HIGH, policy.evaluate(sample(storage = 200)).level)
        assertEquals(PressureLevel.CRITICAL, policy.evaluate(sample(storage = 50)).level)
    }

    @Test
    fun saturatedCpuIsOnlyElevated() {
        assertEquals(PressureLevel.ELEVATED, policy.evaluate(sample(cpu = 95f)).level)
        assertEquals(PressureLevel.NORMAL, policy.evaluate(sample(cpu = 89f)).level)
    }

    @Test
    fun theWorstFactorWinsAndEveryReasonIsReported() {
        val d = policy.evaluate(sample(thermal = ThermalStatus.MODERATE, memAvail = 400, battery = 10, charging = false))
        assertEquals(PressureLevel.HIGH, d.level)
        assertEquals(3, d.reasons.size, d.reasons.toString())
        assertTrue(d.reasons.any { "warm" in it } && d.reasons.any { "RAM" in it } && d.reasons.any { "battery" in it })
    }

    @Test
    fun beingOfflineIsNotedButDoesNotThrottle() {
        val d = policy.evaluate(sample(online = false))
        assertEquals(PressureLevel.NORMAL, d.level)
        assertTrue(d.reasons.any { "offline" in it })
    }
}
