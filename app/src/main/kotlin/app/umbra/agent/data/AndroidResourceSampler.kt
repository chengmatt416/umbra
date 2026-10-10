package app.umbra.agent.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.StatFs
import app.umbra.core.model.Clock
import app.umbra.core.perf.ResourceSample
import app.umbra.core.perf.ResourceSampler
import app.umbra.core.perf.ThermalStatus

/** Reads the phone's real condition from the platform APIs every [app.umbra.core.perf.ResourceGovernor] tick. */
class AndroidResourceSampler(private val context: Context, private val clock: Clock = Clock.System) : ResourceSampler {
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override suspend fun sample(): ResourceSample {
        val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        return ResourceSample(
            timestampMs = clock.nowMs(),
            memAvailMb = memory.availMem / MB,
            memTotalMb = memory.totalMem / MB,
            lowMemory = memory.lowMemory,
            storageFreeMb = StatFs(context.filesDir.path).availableBytes / MB,
            batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else null,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            thermal = thermal(powerManager.currentThermalStatus),
            networkOnline = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            metered = capabilities?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) },
        )
    }

    private fun thermal(status: Int): ThermalStatus = when (status) {
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
        PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.CRITICAL
        PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalStatus.EMERGENCY
        PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.SHUTDOWN
        else -> ThermalStatus.NONE
    }

    private companion object {
        const val MB = 1024L * 1024L
    }
}
