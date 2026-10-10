package app.umbra.agent

import app.umbra.agent.data.GuestManager
import app.umbra.core.recovery.Criticality
import app.umbra.core.recovery.Health
import app.umbra.core.recovery.RestartResult
import app.umbra.core.recovery.Subsystem

/**
 * What the agent depends on, watched and repaired while it works (spec §30). A subsystem is only
 * "failed" when something is using it: a service the user switched off on purpose is not fought while
 * no task needs it.
 */
internal fun subsystems(container: AppContainer, taskRunning: suspend () -> Boolean): List<Subsystem> = listOf(
    object : Subsystem {
        override val id = "accessibility"
        override val criticality = Criticality.ESSENTIAL

        override suspend fun probe(): Health = when {
            container.accessibility.isAvailable() -> Health.Healthy
            !taskRunning() -> Health.Healthy
            else -> Health.Failed("The accessibility service stopped while a task was running.")
        }

        // Root, the helper or a granted WRITE_SECURE_SETTINGS can switch it back on; nothing else may.
        override suspend fun restart(): RestartResult {
            if (!container.grants.enableAccessibility()) return RestartResult.NeedsUser("Turn the Umbra accessibility service back on in Settings → Accessibility.")
            val deadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < deadline) {
                if (container.accessibility.isAvailable()) return RestartResult.Restarted
                kotlinx.coroutines.delay(400)
            }
            return RestartResult.Failed("The service was enabled but did not connect.")
        }
    },
    object : Subsystem {
        override val id = "helper"

        override suspend fun probe(): Health = when {
            !container.helper.configured -> Health.Healthy
            container.helper.client.isUp() -> Health.Healthy
            else -> Health.Failed("The privileged helper is not answering.")
        }

        override suspend fun restart(): RestartResult = when {
            container.rootShell.isAvailable() ->
                if (container.helper.startWithRoot(container.rootShell)) RestartResult.Restarted else RestartResult.Failed("The helper did not start.")
            container.shizuku.state() == ShizukuLauncher.State.READY ->
                if (container.shizuku.startHelper()) RestartResult.Restarted else RestartResult.Failed("Shizuku did not start the helper.")
            else -> RestartResult.NeedsUser("Start the privileged helper again: with Shizuku, or over adb (Settings → Privileged helper).")
        }
    },
    object : Subsystem {
        override val id = "linux"

        override suspend fun probe(): Health = when (container.guest.state.value) {
            GuestManager.State.Ready -> if (container.guest.runtime.isMounted()) Health.Healthy else Health.Failed("The Linux environment is not mounted.")
            else -> Health.Healthy // not installed or being installed: nothing to repair
        }

        override suspend fun restart(): RestartResult =
            if (container.guest.ensureReady()) RestartResult.Restarted else RestartResult.Failed("The Linux environment could not be mounted.")
    },
    object : Subsystem {
        override val id = "logs"
        override val criticality = Criticality.OPTIONAL

        override suspend fun probe(): Health = if (container.logMonitor.isRunning) Health.Healthy else Health.Failed("The log monitor is not running.")

        override suspend fun restart(): RestartResult {
            container.startLogMonitor()
            return RestartResult.Restarted
        }
    },
)
