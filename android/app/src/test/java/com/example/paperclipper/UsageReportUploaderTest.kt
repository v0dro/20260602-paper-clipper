package com.example.paperclipper

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import com.example.paperclipper.data.AppDatabase
import com.example.paperclipper.data.PendingUsageReportDao
import com.example.paperclipper.data.PendingUsageReportEntity
import com.example.paperclipper.report.UsageReportScheduler
import com.example.paperclipper.report.UsageReportUploader
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Drives the real [UsageReportUploader.flush] loop against in-memory Room, with [UsageReportUploader]
 * spied so only [UsageReportUploader.postBatch] (the single network line) is stubbed and
 * [UsageReportScheduler] stubbed out entirely (no WorkManager in unit tests). Locks down the
 * eligibility cutoff, delete-on-accepted+duplicate, retry on failed delivery, batch draining, and
 * the reschedule handoff for still-too-young reports.
 */
@RunWith(RobolectricTestRunner::class)
class UsageReportUploaderTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: PendingUsageReportDao

    // Fixed "now"; ages are expressed relative to the scheduler's MIN_AGE_MS.
    private val now = 2_000_000_000_000L
    private val minAge = UsageReportScheduler.MIN_AGE_MS

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.pendingUsageReportDao()
        mockkObject(UsageReportUploader)
        mockkObject(UsageReportScheduler)
        every { UsageReportScheduler.schedule(any(), any(), any()) } just Runs
    }

    @After
    fun tearDown() {
        unmockkObject(UsageReportUploader)
        unmockkObject(UsageReportScheduler)
        db.close()
    }

    private suspend fun insert(id: String, createdAt: Long) =
        dao.insert(PendingUsageReportEntity(id, createdAt, """{"reportId":"$id","status":200}"""))

    /** Stubs postBatch to ack every sent report as accepted, like the real server. */
    private fun stubServerAcceptsEverything() {
        every { UsageReportUploader.postBatch(any()) } answers {
            val reports = JSONObject(firstArg<String>()).getJSONArray("reports")
            val ids = JSONArray()
            for (i in 0 until reports.length()) ids.put(reports.getJSONObject(i).getString("reportId"))
            JSONObject().put("accepted", ids).put("duplicate", JSONArray()).toString()
        }
    }

    // ---- flush ----------------------------------------------------------------------------------

    @Test
    fun flush_uploadsEligibleReportsAndDeletesAcceptedAndDuplicate() = runTest {
        insert("acc", createdAt = now - minAge - 1)
        insert("dup", createdAt = now - minAge - 2)
        every { UsageReportUploader.postBatch(any()) } returns
            """{"accepted":["acc"],"duplicate":["dup"]}"""

        val result = UsageReportUploader.flush(dao, context, now)

        // Duplicate = "server already has it" (lost ack on an earlier retry) -> also deleted.
        assertTrue(result is Result.Success)
        assertEquals(0, dao.count())
        verify(exactly = 0) { UsageReportScheduler.schedule(any(), any(), any()) }
    }

    @Test
    fun flush_respectsTheEligibilityCutoff() = runTest {
        insert("young", createdAt = now - minAge + 1_000) // 1s short of eligible
        stubServerAcceptsEverything()

        val result = UsageReportUploader.flush(dao, context, now)

        assertTrue(result is Result.Success)
        assertEquals(1, dao.count())
        verify(exactly = 0) { UsageReportUploader.postBatch(any()) }
    }

    @Test
    fun flush_reschedulesWhenYoungerReportsRemain() = runTest {
        insert("old", createdAt = now - minAge - 1)
        val youngCreatedAt = now - 1_000
        insert("young", createdAt = youngCreatedAt)
        stubServerAcceptsEverything()

        val result = UsageReportUploader.flush(dao, context, now)

        assertTrue(result is Result.Success)
        assertEquals(1, dao.count())
        // Self-reschedule must use APPEND_OR_REPLACE: KEEP would no-op against this RUNNING work.
        verify {
            UsageReportScheduler.schedule(any(), youngCreatedAt, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
    }

    @Test
    fun flush_drainsMultipleBatches() = runTest {
        // More than one 25-item batch; the loop must keep going until eligible() is empty.
        repeat(30) { insert("r$it", createdAt = now - minAge - 1_000 + it) }
        stubServerAcceptsEverything()

        val result = UsageReportUploader.flush(dao, context, now)

        assertTrue(result is Result.Success)
        assertEquals(0, dao.count())
        verify(exactly = 2) { UsageReportUploader.postBatch(any()) }
    }

    @Test
    fun flush_retriesWhenDeliveryFails() = runTest {
        insert("r1", createdAt = now - minAge - 1)
        // null covers every failure mode postBatch swallows: IOException, non-2xx, bad URL.
        every { UsageReportUploader.postBatch(any()) } returns null

        val result = UsageReportUploader.flush(dao, context, now)

        assertTrue(result is Result.Retry)
        assertEquals(1, dao.count()) // nothing deleted without an ack
    }

    @Test
    fun flush_retriesOnUnparseableOrEmptyAck() = runTest {
        insert("r1", createdAt = now - minAge - 1)

        every { UsageReportUploader.postBatch(any()) } returns "<html>not json</html>"
        assertTrue(UsageReportUploader.flush(dao, context, now) is Result.Retry)

        // Valid JSON that acknowledges nothing would loop the same batch forever -> retry instead.
        every { UsageReportUploader.postBatch(any()) } returns "{}"
        assertTrue(UsageReportUploader.flush(dao, context, now) is Result.Retry)

        assertEquals(1, dao.count())
    }

    // ---- pure seams -----------------------------------------------------------------------------

    @Test
    fun buildBatchBody_wrapsStoredPayloadsVerbatim() {
        val body = UsageReportUploader.buildBatchBody(
            listOf(
                PendingUsageReportEntity("a", 1, """{"reportId":"a","status":200}"""),
                PendingUsageReportEntity("b", 2, """{"reportId":"b","status":422}"""),
            ),
        )

        val reports = JSONObject(body).getJSONArray("reports")
        assertEquals(2, reports.length())
        assertEquals("a", reports.getJSONObject(0).getString("reportId"))
        assertEquals(422, reports.getJSONObject(1).getInt("status"))
    }

    @Test
    fun parseAck_mergesAcceptedAndDuplicate() {
        assertEquals(
            listOf("a", "b", "c"),
            UsageReportUploader.parseAck("""{"accepted":["a","b"],"duplicate":["c"]}"""),
        )
        assertEquals(emptyList<String>(), UsageReportUploader.parseAck("{}"))
        assertNull(UsageReportUploader.parseAck("<html>530</html>"))
    }
}
