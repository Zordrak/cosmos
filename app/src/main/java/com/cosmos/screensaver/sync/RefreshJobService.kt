package com.cosmos.screensaver.sync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.cosmos.screensaver.settings.CosmosSettings
import com.cosmos.screensaver.settings.DownloadMode

/**
 * Refreshes the library about once a day when the system runs it. Android may defer or skip
 * jobs while the TV is in standby, so refreshes are also started when the app opens and when
 * the screensaver starts. The job follows the download mode: it is cancelled while saving is
 * paused and only requires an unmetered network when the TV can actually report one.
 */
class RefreshJobService : JobService() {
    private val ownership = JobOwnership<JobParameters>()

    override fun onStartJob(params: JobParameters): Boolean {
        ownership.start(params)
        val started = RefreshCoordinator.requestRefresh(this) {
            if (ownership.finish(params)) jobFinished(params, false)
        }
        if (!started) ownership.finish(params)
        return started
    }

    override fun onStopJob(params: JobParameters): Boolean {
        ownership.stop(params)
        return true
    }

    companion object {
        private const val TAG = "CosmosRefreshJob"
        private const val JOB_ID = 4001
        private const val PERIOD_MILLIS = 24L * 60 * 60 * 1000

        /** Makes sure the daily job matches the download mode; see [reconcile]. */
        fun ensureScheduled(context: Context) = reconcile(context)

        /**
         * Schedules, replaces or cancels the daily job so it matches the download mode. A job that
         * already has the right network requirement is left alone so its period is not restarted.
         * Call whenever the mode changes and whenever the app or screensaver starts.
         */
        fun reconcile(context: Context) {
            try {
                val appContext = context.applicationContext
                val scheduler = appContext.getSystemService(JobScheduler::class.java) ?: return
                val mode = CosmosSettings(appContext).downloadMode
                val meteredness = if (mode == DownloadMode.UNMETERED_ONLY) {
                    RefreshCoordinator.meteredness(appContext)
                } else {
                    Meteredness.UNKNOWN_NOW
                }
                val network = jobNetworkFor(mode, meteredness)
                if (network == null) {
                    if (scheduler.allPendingJobs.any { it.id == JOB_ID }) scheduler.cancel(JOB_ID)
                    return
                }
                val required = when (network) {
                    JobNetwork.ANY -> JobInfo.NETWORK_TYPE_ANY
                    JobNetwork.UNMETERED -> JobInfo.NETWORK_TYPE_UNMETERED
                }
                if (scheduler.allPendingJobs.any { it.id == JOB_ID && it.networkType == required }) return
                val job = JobInfo.Builder(JOB_ID, ComponentName(appContext, RefreshJobService::class.java))
                    .setRequiredNetworkType(required)
                    .setPeriodic(PERIOD_MILLIS)
                    .build()
                if (scheduler.schedule(job) == JobScheduler.RESULT_FAILURE) {
                    Log.w(TAG, "The system refused to schedule the daily refresh")
                }
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to schedule the daily refresh", error)
            }
        }
    }
}

/**
 * Tracks which job invocation currently owns the refresh, so a late completion from an invocation
 * the system already stopped does not finish a newer one.
 */
internal class JobOwnership<T : Any> {
    private var current: T? = null

    @Synchronized
    fun start(token: T) {
        current = token
    }

    /** True if [token] still owned the job; ownership is released. */
    @Synchronized
    fun finish(token: T): Boolean {
        if (current !== token) return false
        current = null
        return true
    }

    @Synchronized
    fun stop(token: T) {
        if (current === token) current = null
    }
}
