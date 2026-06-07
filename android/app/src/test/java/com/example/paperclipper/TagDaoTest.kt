package com.example.paperclipper

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.paperclipper.data.AppDatabase
import com.example.paperclipper.data.ClippingTagCrossRef
import com.example.paperclipper.data.TagDao
import com.example.paperclipper.data.TagEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TagDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: TagDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.tagDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insert(name: String): Long {
        dao.insertIfAbsent(TagEntity(name = name))
        return dao.findByName(name)!!.id
    }

    @Test
    fun insertIfAbsent_ignoresExactDuplicateName() = runTest {
        dao.insertIfAbsent(TagEntity(name = "News"))
        dao.insertIfAbsent(TagEntity(name = "News"))
        assertEquals(1, dao.observeAll().first().size)
    }

    @Test
    fun findByName_isCaseInsensitive() = runTest {
        dao.insertIfAbsent(TagEntity(name = "News"))
        assertNotNull(dao.findByName("news"))
        assertNotNull(dao.findByName("NEWS"))
        assertNull(dao.findByName("sports"))
    }

    @Test
    fun observeAll_orderedByNameNoCase() = runTest {
        dao.insertIfAbsent(TagEntity(name = "Banana"))
        dao.insertIfAbsent(TagEntity(name = "apple"))
        dao.insertIfAbsent(TagEntity(name = "Cherry"))
        assertEquals(
            listOf("apple", "Banana", "Cherry"),
            dao.observeAll().first().map { it.name },
        )
    }

    @Test
    fun assignAndUnassign_reflectInObserveTagsFor() = runTest {
        val travel = insert("Travel")
        val food = insert("Food")

        dao.assign(ClippingTagCrossRef(fileName = "a.jpg", tagId = travel))
        dao.assign(ClippingTagCrossRef(fileName = "a.jpg", tagId = food))
        // Only tags linked to a.jpg, ordered NOCASE.
        assertEquals(listOf("Food", "Travel"), dao.observeTagsFor("a.jpg").first().map { it.name })
        assertEquals(listOf("Food", "Travel"), dao.tagsForOnce("a.jpg").map { it.name })

        dao.unassign("a.jpg", travel)
        assertEquals(listOf("Food"), dao.observeTagsFor("a.jpg").first().map { it.name })
    }

    @Test
    fun assign_duplicateCrossRefIsIgnored() = runTest {
        val travel = insert("Travel")
        dao.assign(ClippingTagCrossRef(fileName = "a.jpg", tagId = travel))
        dao.assign(ClippingTagCrossRef(fileName = "a.jpg", tagId = travel))
        assertEquals(1, dao.tagsForOnce("a.jpg").size)
    }

    @Test
    fun deleteRefsForFiles_removesLinksButKeepsGlobalTags() = runTest {
        val travel = insert("Travel")
        dao.assign(ClippingTagCrossRef(fileName = "a.jpg", tagId = travel))
        dao.assign(ClippingTagCrossRef(fileName = "b.jpg", tagId = travel))

        dao.deleteRefsForFiles(listOf("a.jpg"))
        assertEquals(0, dao.tagsForOnce("a.jpg").size)
        assertEquals(1, dao.tagsForOnce("b.jpg").size)
        // Tag itself survives.
        assertEquals(1, dao.observeAll().first().size)
    }

    @Test
    fun deleteAllRefsAndTags_wipesEverything() = runTest {
        val travel = insert("Travel")
        dao.assign(ClippingTagCrossRef(fileName = "a.jpg", tagId = travel))

        dao.deleteAllRefs()
        assertEquals(0, dao.tagsForOnce("a.jpg").size)
        assertEquals(1, dao.observeAll().first().size)

        dao.deleteAllTags()
        assertEquals(0, dao.observeAll().first().size)
    }
}
