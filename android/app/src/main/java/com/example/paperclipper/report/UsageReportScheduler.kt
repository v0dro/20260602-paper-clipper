package com.example.paperclipper.report

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Enqueues the unique [UsageReportWorker] run that uploads queued usage reports to the home server.
 * The delay is computed so the *oldest* queued report is at least [MIN_AGE_MS] old when the worker
 * fires — the requirement is "upload ≥24 h after the fallback served the request", not "as soon as
 * possible". Unique work + WorkManager persistence means one pending run at a time that survives
 * process death and reboot; the exponential backoff retries indefinitely while the server is down.
 */
object UsageReportScheduler {

    /** Unique work name — at most one pending upload run exists at a time. */
    const val WORK_NAME = "usage-report-upload"

    /**
     * Minimum age of a report before it may be uploaded. `var` (not const) so a debug build can
     * shrink it to smoke-test the end-to-end flow without waiting a day; production never writes it.
     */
    @VisibleForTesting
    internal var MIN_AGE_MS: Long = 24 * 60 * 60 * 1000L

    /**
     * Schedules the upload run for when the report created at [oldestCreatedAt] turns eligible.
     * [policy] is [ExistingWorkPolicy.KEEP] from the repository (an already-pending run for an even
     * older report always fires first) and APPEND_OR_REPLACE when the worker re-enqueues itself —
     * KEEP would no-op against the still-RUNNING work and drop the follow-up.
     */
    fun schedule(context: Context, oldestCreatedAt: Long, policy: ExistingWorkPolicy) {
        val delayMs = (oldestCreatedAt + MIN_AGE_MS - System.currentTimeMillis()).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<UsageReportWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            // 30 min, 1 h, 2 h, ... capped by WorkManager at 5 h; retries until the server accepts.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }
}
