package com.example.paperclipper

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker.Result
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.paperclipper.data.AppDatabase
import com.example.paperclipper.data.PendingUsageReportDao
import com.example.paperclipper.data.PendingUsageReportEntity
import com.example.paperclipper.report.UsageReportScheduler
import com.example.paperclipper.report.UsageReportUploader
import com.example.paperclipper.report.UsageReportWorker
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Drives the real [UsageReportWorker] via WorkManager's test harness, with the [daoProvider] seam
 * swapped to in-memory Room (the suite's hard rule: never touch the encrypted production DB) and
 * only [UsageReportUploader.postBatch] stubbed. Proves the WorkManager wiring end-to-end: worker
 * construction, DAO resolution, flush delegation and the success/retry mapping.
 */
@RunWith(RobolectricTestRunner::class)
class UsageReportWorkerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: PendingUsageReportDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.pendingUsageReportDao()
        UsageReportWorker.daoProvider = { db.pendingUsageReportDao() }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        mockkObject(UsageReportUploader)
    }

    @After
    fun tearDown() {
        unmockkObject(UsageReportUploader)
        UsageReportWorker.daoProvider = { AppDatabase.get(it).pendingUsageReportDao() }
        db.close()
    }

    private suspend fun insertEligible(id: String) {
        dao.insert(
            PendingUsageReportEntity(
                reportId = id,
                createdAt = System.currentTimeMillis() - UsageReportScheduler.MIN_AGE_MS - 1_000,
                payloadJson = """{"reportId":"$id","status":200}""",
            ),
        )
    }

    @Test
    fun doWork_drainsQueueAndSucceedsWhenServerAcks() = runTest {
        insertEligible("r1")
        every { UsageReportUploader.postBatch(any()) } returns
            """{"accepted":["r1"],"duplicate":[]}"""

        val worker = TestListenableWorkerBuilder<UsageReportWorker>(context).build()
        val result = worker.doWork()

        assertTrue(result is Result.Success)
        assertEquals(0, dao.count())
    }

    @Test
    fun doWork_retriesAndKeepsQueueWhenServerIsDown() = runTest {
        insertEligible("r1")
        every { UsageReportUploader.postBatch(any()) } returns null

        val worker = TestListenableWorkerBuilder<UsageReportWorker>(context).build()
        val result = worker.doWork()

        assertTrue(result is Result.Retry)
        assertEquals(1, dao.count())
    }
}
