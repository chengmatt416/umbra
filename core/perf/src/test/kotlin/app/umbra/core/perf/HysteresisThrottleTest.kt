package app.umbra.core.perf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HysteresisThrottleTest {
    private val good = ResourceSample(0, memAvailMb = 3000, batteryPct = 80, charging = false, storageFreeMb = 20_000)
    private val hot = good.copy(thermal = ThermalStatus.SEVERE)
    private val critical = good.copy(thermal = ThermalStatus.CRITICAL)
    private val warm = good.copy(thermal = ThermalStatus.MODERATE)

    @Test
    fun pressureRisesImmediately() {
        val t = HysteresisThrottle()
        assertEquals(PressureLevel.NORMAL, t.next(good).level)
        assertEquals(PressureLevel.HIGH, t.next(hot).level)
        assertEquals(PressureLevel.CRITICAL, t.next(critical).level)
    }

    @Test
    fun pressureRelaxesOneLevelAfterARunOfGoodSamples() {
        val t = HysteresisThrottle(relaxAfter = 3)
        t.next(critical)
        assertEquals(PressureLevel.CRITICAL, t.next(good).level)
        assertEquals(PressureLevel.CRITICAL, t.next(good).level)
        assertEquals(PressureLevel.HIGH, t.next(good).level, "third good sample drops one level")
        assertEquals(PressureLevel.HIGH, t.next(good).level)
        assertEquals(PressureLevel.HIGH, t.next(good).level)
        assertEquals(PressureLevel.ELEVATED, t.next(good).level)
        repeat(2) { assertEquals(PressureLevel.ELEVATED, t.next(good).level) }
        assertEquals(PressureLevel.NORMAL, t.next(good).level)
    }

    @Test
    fun aFlappingDeviceDoesNotRelax() {
        val t = HysteresisThrottle(relaxAfter = 3)
        t.next(hot)
        repeat(10) {
            t.next(good)
            t.next(good)
            assertEquals(PressureLevel.HIGH, t.next(hot).level, "a bad sample resets the streak")
        }
        assertEquals(PressureLevel.HIGH, t.decision.level)
    }

    @Test
    fun relaxingNeverGoesBelowWhatTheCurrentSampleRequires() {
        val t = HysteresisThrottle(relaxAfter = 1)
        t.next(critical)
        // The device is better but still warm: it may drop to HIGH, then ELEVATED, but not to NORMAL.
        assertEquals(PressureLevel.HIGH, t.next(warm).level)
        assertEquals(PressureLevel.ELEVATED, t.next(warm).level)
        assertEquals(PressureLevel.ELEVATED, t.next(warm).level)
    }

    @Test
    fun whileRecoveringTheDecisionExplainsIt() {
        val t = HysteresisThrottle(relaxAfter = 3)
        t.next(hot)
        val d = t.next(good)
        assertTrue(d.reasons.any { "recovering" in it }, d.reasons.toString())
    }
}
