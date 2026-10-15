package app.umbra.agent.update

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import app.umbra.agent.container
import kotlinx.coroutines.launch

/** The system installer's answer to [AppUpdater]'s install session: confirmation needed, done, or failed. */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.container.updater.onInstallStatus(
            status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
            message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java),
        )
    }
}

/**
 * Twice a day, also when Umbra has not been opened: starts the app's process (which is all that is needed) and
 * checks for an update. The download goes on in the app's scope if it outlasts the job, and resumes if it is cut.
 */
class UpdateCheckJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val container = applicationContext.container
        if (!container.settings.value.autoUpdate) return false
        container.scope.launch {
            try {
                container.updater.check(userAsked = false)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val JOB_ID = 7_301
        private const val EVERY_MS = 12 * 60 * 60 * 1000L
        private const val FLEX_MS = 2 * 60 * 60 * 1000L

        fun schedule(context: Context) {
            val jobs = context.getSystemService(JobScheduler::class.java)
            if (jobs.getPendingJob(JOB_ID) != null) return
            jobs.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckJob::class.java))
                    .setPeriodic(EVERY_MS, FLEX_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build(),
            )
        }
    }
}
