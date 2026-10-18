package app.umbra.core.perf

import kotlinx.serialization.Serializable

/** Mirrors `PowerManager.THERMAL_STATUS_*`, in increasing severity. */
@Serializable
enum class ThermalStatus { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN }

@Serializable
data class ResourceSample(
    val timestampMs: Long,
    val cpuPct: Float? = null,
    val memAvailMb: Long? = null,
    val memTotalMb: Long? = null,
    val lowMemory: Boolean = false,
    val storageFreeMb: Long? = null,
    val batteryPct: Int? = null,
    val charging: Boolean? = null,
    val thermal: ThermalStatus = ThermalStatus.NONE,
    val networkOnline: Boolean? = null,
    val metered: Boolean? = null,
    val backgroundProcesses: Int? = null,
)

@Serializable
enum class PressureLevel { NORMAL, ELEVATED, HIGH, CRITICAL }

/** What kind of work is asking to run. */
enum class WorkClass {
    /** Directly requested by the user; never throttled except at CRITICAL for new sessions. */
    INTERACTIVE,

    /** Cheap agent work: observing, tapping, small commands. */
    LIGHT,

    /** Builds, test runs, indexing, GUI desktops, model downloads. */
    EXPENSIVE,
}

@Serializable
data class ThrottleDecision(
    val level: PressureLevel,
    val maxConcurrentSessions: Int,
    val maxConcurrentExpensive: Int,
    val allowNewSessions: Boolean,
    val pauseExpensive: Boolean,
    /** Everything except interactive work is held until pressure drops. */
    val pauseAll: Boolean,
    val reasons: List<String> = emptyList(),
) {
    fun allows(workClass: WorkClass): Boolean = when (workClass) {
        WorkClass.INTERACTIVE -> true
        WorkClass.LIGHT -> !pauseAll
        WorkClass.EXPENSIVE -> !pauseAll && !pauseExpensive
    }
}

data class ThrottleConfig(
    val normalSessions: Int = 3,
    val normalExpensive: Int = 2,
    val memElevatedMb: Long = 1024,
    val memHighMb: Long = 600,
    val memCriticalMb: Long = 300,
    val storageElevatedMb: Long = 1024,
    val storageHighMb: Long = 300,
    val storageCriticalMb: Long = 100,
    val batteryElevatedPct: Int = 25,
    val batteryHighPct: Int = 15,
    val batteryCriticalPct: Int = 5,
    val cpuElevatedPct: Float = 90f,
    /** Consecutive better samples required before pressure is relaxed by one level. */
    val relaxAfterGoodSamples: Int = 3,
)

/** Pure mapping from one sample to a decision (spec §31). No memory of the past. */
class ThrottlePolicy(private val config: ThrottleConfig = ThrottleConfig()) {
    fun evaluate(sample: ResourceSample): ThrottleDecision {
        val reasons = mutableListOf<String>()
        var level = PressureLevel.NORMAL

        fun raise(to: PressureLevel, reason: String) {
            reasons += reason
            if (to > level) level = to
        }

        when (sample.thermal) {
            ThermalStatus.NONE, ThermalStatus.LIGHT -> Unit
            ThermalStatus.MODERATE -> raise(PressureLevel.ELEVATED, "device is warm (thermal moderate)")
            ThermalStatus.SEVERE -> raise(PressureLevel.HIGH, "device is hot (thermal severe)")
            else -> raise(PressureLevel.CRITICAL, "device is overheating (thermal ${sample.thermal.name.lowercase()})")
        }

        sample.memAvailMb?.let { avail ->
            when {
                avail < config.memCriticalMb -> raise(PressureLevel.CRITICAL, "only ${avail} MB RAM available")
                avail < config.memHighMb -> raise(PressureLevel.HIGH, "low RAM: ${avail} MB available")
                avail < config.memElevatedMb -> raise(PressureLevel.ELEVATED, "RAM is getting tight: ${avail} MB available")
            }
        }
        if (sample.lowMemory) raise(PressureLevel.HIGH, "the system reports low memory")

        val onBattery = sample.charging == false
        sample.batteryPct?.let { pct ->
            if (onBattery) {
                when {
                    pct <= config.batteryCriticalPct -> raise(PressureLevel.CRITICAL, "battery at $pct% and not charging")
                    pct <= config.batteryHighPct -> raise(PressureLevel.HIGH, "battery low ($pct%) and not charging")
                    pct <= config.batteryElevatedPct -> raise(PressureLevel.ELEVATED, "battery at $pct% and not charging")
                }
            }
        }

        sample.storageFreeMb?.let { free ->
            when {
                free < config.storageCriticalMb -> raise(PressureLevel.CRITICAL, "only $free MB storage free")
                free < config.storageHighMb -> raise(PressureLevel.HIGH, "storage nearly full: $free MB free")
                free < config.storageElevatedMb -> raise(PressureLevel.ELEVATED, "storage is low: $free MB free")
            }
        }

        sample.cpuPct?.let { cpu ->
            if (cpu >= config.cpuElevatedPct) raise(PressureLevel.ELEVATED, "CPU saturated (${cpu.toInt()}%)")
        }

        if (sample.networkOnline == false) reasons += "offline: cloud model calls cannot progress"
        return decisionFor(level, reasons)
    }

    fun decisionFor(level: PressureLevel, reasons: List<String> = emptyList()): ThrottleDecision = when (level) {
        PressureLevel.NORMAL -> ThrottleDecision(level, config.normalSessions, config.normalExpensive, true, false, false, reasons)
        PressureLevel.ELEVATED -> ThrottleDecision(level, minOf(2, config.normalSessions), 1, true, false, false, reasons)
        // A task the person asks for is interactive work: under high pressure it still starts, one at a time (Android
        // keeps its RAM nearly full by design, so "high" is a normal state on many phones); only critical holds it.
        PressureLevel.HIGH -> ThrottleDecision(level, 1, 0, true, true, false, reasons)
        PressureLevel.CRITICAL -> ThrottleDecision(level, 1, 0, false, true, true, reasons)
    }
}

/**
 * Raises pressure immediately and relaxes it one level at a time after a run of better samples, so
 * a device hovering around a threshold does not make the agent flap between modes.
 */
class HysteresisThrottle(
    private val policy: ThrottlePolicy = ThrottlePolicy(),
    private val relaxAfter: Int = ThrottleConfig().relaxAfterGoodSamples,
) {
    private var current: ThrottleDecision = policy.decisionFor(PressureLevel.NORMAL)
    private var goodStreak = 0

    val decision: ThrottleDecision get() = current

    fun next(sample: ResourceSample): ThrottleDecision {
        val raw = policy.evaluate(sample)
        current = when {
            raw.level > current.level -> {
                goodStreak = 0
                raw
            }
            raw.level == current.level -> {
                goodStreak = 0
                raw
            }
            else -> {
                goodStreak++
                if (goodStreak >= relaxAfter) {
                    goodStreak = 0
                    val relaxed = PressureLevel.entries[current.level.ordinal - 1]
                    policy.decisionFor(maxOf(relaxed, raw.level), raw.reasons)
                } else {
                    current.copy(reasons = current.reasons + "recovering (${goodStreak}/$relaxAfter good samples)")
                }
            }
        }
        return current
    }
}
