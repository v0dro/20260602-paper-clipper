package com.example.paperclipper

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.paperclipper.data.AppDatabase
import com.example.paperclipper.data.ClippingDao
import com.example.paperclipper.data.ClippingEntity
import com.example.paperclipper.data.ClippingStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClippingDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ClippingDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.clippingDao()
    }

    @After
    fun tearDown() = db.close()

    private fun entity(name: String, createdAt: Long, status: ClippingStatus = ClippingStatus.PENDING) =
        ClippingEntity(fileName = name, createdAt = createdAt, status = status.name)

    @Test
    fun insertIfAbsent_ignoresDuplicatePrimaryKey() = runTest {
        dao.insertIfAbsent(entity("a.jpg", createdAt = 100))
        dao.insertIfAbsent(entity("a.jpg", createdAt = 999))

        val all = dao.getAll()
        assertEquals(1, all.size)
        // IGNORE keeps the original row, not the second insert.
        assertEquals(100L, all[0].createdAt)
    }

    @Test
    fun observeAll_andGetAll_orderedByCreatedAtDesc() = runTest {
        dao.insertIfAbsent(entity("a.jpg", createdAt = 1))
        dao.insertIfAbsent(entity("b.jpg", createdAt = 3))
        dao.insertIfAbsent(entity("c.jpg", createdAt = 2))

        val expected = listOf("b.jpg", "c.jpg", "a.jpg")
        assertEquals(expected, dao.observeAll().first().map { it.fileName })
        assertEquals(expected, dao.getAll().map { it.fileName })
    }

    @Test
    fun withStatus_filtersAndOrdersByCreatedAtAsc() = runTest {
        dao.insertIfAbsent(entity("a.jpg", createdAt = 3, status = ClippingStatus.PENDING))
        dao.insertIfAbsent(entity("b.jpg", createdAt = 1, status = ClippingStatus.PENDING))
        dao.insertIfAbsent(entity("c.jpg", createdAt = 2, status = ClippingStatus.PENDING))
        dao.insertIfAbsent(entity("done.jpg", createdAt = 0, status = ClippingStatus.SUCCESS))

        val pending = dao.withStatus(ClippingStatus.PENDING.name)
        assertEquals(listOf("b.jpg", "c.jpg", "a.jpg"), pending.map { it.fileName })
    }

    @Test
    fun updateStatus_changesOnlyTheStatus() = runTest {
        dao.insertIfAbsent(entity("a.jpg", createdAt = 1))
        dao.updateStatus("a.jpg", ClippingStatus.PROCESSING.name)
        assertEquals(ClippingStatus.PROCESSING.name, dao.getAll()[0].status)
    }

    @Test
    fun updateResult_writesAllResultFields() = runTest {
        dao.insertIfAbsent(entity("a.jpg", createdAt = 1))
        dao.updateResult(
            fileName = "a.jpg",
            status = ClippingStatus.SUCCESS.name,
            extractedText = "extracted",
            summary = "summary",
            heading = "the heading",
            errorMessage = null,
            model = "server",
            processedAt = 555L,
        )
        val row = dao.getAll()[0]
        assertEquals(ClippingStatus.SUCCESS.name, row.status)
        assertEquals("extracted", row.extractedText)
        assertEquals("summary", row.summary)
        assertEquals("the heading", row.heading)
        assertNull(row.errorMessage)
        assertEquals("server", row.model)
        assertEquals(555L, row.processedAt)
    }

    @Test
    fun allFileNames_returnsEveryName() = runTest {
        dao.insertIfAbsent(entity("a.jpg", createdAt = 1))
        dao.insertIfAbsent(entity("b.jpg", createdAt = 2))
        assertEquals(setOf("a.jpg", "b.jpg"), dao.allFileNames().toSet())
    }

    @Test
    fun deleteByNames_andDeleteAll() = runTest {
        dao.insertIfAbsent(entity("a.jpg", createdAt = 1))
        dao.insertIfAbsent(entity("b.jpg", createdAt = 2))
        dao.insertIfAbsent(entity("c.jpg", createdAt = 3))

        dao.deleteByNames(listOf("a.jpg", "c.jpg"))
        assertEquals(listOf("b.jpg"), dao.getAll().map { it.fileName })

        dao.deleteAll()
        assertEquals(0, dao.getAll().size)
    }
}
