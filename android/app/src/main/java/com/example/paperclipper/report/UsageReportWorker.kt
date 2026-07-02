package com.example.paperclipper.report

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.paperclipper.data.AppDatabase
import com.example.paperclipper.data.PendingUsageReportDao

/**
 * Thin WorkManager entry point: all logic lives in [UsageReportUploader]. Opening the real
 * (SQLCipher/Keystore) database from a background worker is safe here — the app is not
 * directBootAware, so any post-boot run happens after first unlock.
 */
class UsageReportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        UsageReportUploader.flush(daoProvider(applicationContext), applicationContext, System.currentTimeMillis())

    companion object {
        /**
         * Seam for tests: swapped for an in-memory Room DAO so `TestListenableWorkerBuilder` can
         * drive the real worker without touching the encrypted production database (the unit-test
         * suite's hard rule: never call [AppDatabase.get]).
         */
        @VisibleForTesting
        internal var daoProvider: (Context) -> PendingUsageReportDao =
            { AppDatabase.get(it).pendingUsageReportDao() }
    }
}
