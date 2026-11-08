package com.cosmos.screensaver.sync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * Refreshes the library about once a day when the system runs it. Android may defer or skip
 * jobs while the TV is in standby, so refreshes are also started when the app opens and when
 * the screensaver starts.
 */
class RefreshJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val started = RefreshCoordinator.requestRefresh(this) { jobFinished(params, false) }
        return started
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val TAG = "CosmosRefreshJob"
        private const val JOB_ID = 4001
        private const val PERIOD_MILLIS = 24L * 60 * 60 * 1000

        /** Schedules the daily job unless it is already scheduled. */
        fun ensureScheduled(context: Context) {
            try {
                val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
                if (scheduler.allPendingJobs.any { it.id == JOB_ID }) return
                val job = JobInfo.Builder(JOB_ID, ComponentName(context, RefreshJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setPeriodic(PERIOD_MILLIS)
                    .build()
                scheduler.schedule(job)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to schedule the daily refresh", error)
            }
        }
    }
}
