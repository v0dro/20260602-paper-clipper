package com.example.paperclipper

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.paperclipper.data.AppDatabase
import com.example.paperclipper.data.PendingUsageReportDao
import com.example.paperclipper.data.PendingUsageReportEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PendingUsageReportDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PendingUsageReportDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.pendingUsageReportDao()
    }

    @After
    fun tearDown() = db.close()

    private fun entity(id: String, createdAt: Long) =
        PendingUsageReportEntity(reportId = id, createdAt = createdAt, payloadJson = """{"reportId":"$id"}""")

    @Test
    fun insert_ignoresDuplicateReportId() = runTest {
        dao.insert(entity("r1", createdAt = 100))
        dao.insert(entity("r1", createdAt = 999))

        assertEquals(1, dao.count())
        // IGNORE keeps the original row, not the second insert.
        assertEquals(100L, dao.eligible(cutoff = Long.MAX_VALUE, limit = 10).single().createdAt)
    }

    @Test
    fun eligible_filtersByCutoffOrdersOldestFirstAndLimits() = runTest {
        dao.insert(entity("r3", createdAt = 3))
        dao.insert(entity("r1", createdAt = 1))
        dao.insert(entity("r4", createdAt = 4))
        dao.insert(entity("r2", createdAt = 2))

        // Cutoff is inclusive; r4 is too young.
        assertEquals(
            listOf("r1", "r2", "r3"),
            dao.eligible(cutoff = 3, limit = 10).map { it.reportId },
        )
        // Limit trims from the young end — the oldest reports always go first.
        assertEquals(
            listOf("r1", "r2"),
            dao.eligible(cutoff = 3, limit = 2).map { it.reportId },
        )
    }

    @Test
    fun oldestCreatedAt_nullWhenEmptyElseMin() = runTest {
        assertNull(dao.oldestCreatedAt())

        dao.insert(entity("r2", createdAt = 20))
        dao.insert(entity("r1", createdAt = 10))
        assertEquals(10L, dao.oldestCreatedAt())
    }

    @Test
    fun deleteByIds_removesOnlyListedRows() = runTest {
        dao.insert(entity("r1", createdAt = 1))
        dao.insert(entity("r2", createdAt = 2))
        dao.insert(entity("r3", createdAt = 3))

        dao.deleteByIds(listOf("r1", "r3", "not-there"))

        assertEquals(listOf("r2"), dao.eligible(cutoff = Long.MAX_VALUE, limit = 10).map { it.reportId })
        assertEquals(1, dao.count())
    }
}
