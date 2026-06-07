package com.example.paperclipper

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.paperclipper.data.AppDatabase
import com.example.paperclipper.data.CommentDao
import com.example.paperclipper.data.CommentEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CommentDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: CommentDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.commentDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun add(fileName: String, text: String, createdAt: Long) =
        dao.insert(CommentEntity(fileName = fileName, text = text, createdAt = createdAt))

    @Test
    fun observeFor_isNewestFirst_commentsForOnce_isOldestFirst() = runTest {
        add("a.jpg", "first", 1)
        add("a.jpg", "second", 2)
        add("a.jpg", "third", 3)
        add("other.jpg", "elsewhere", 5)

        assertEquals(
            listOf("third", "second", "first"),
            dao.observeFor("a.jpg").first().map { it.text },
        )
        assertEquals(
            listOf("first", "second", "third"),
            dao.commentsForOnce("a.jpg").map { it.text },
        )
    }

    @Test
    fun delete_removesSingleCommentById() = runTest {
        add("a.jpg", "keep", 1)
        add("a.jpg", "remove", 2)
        val toRemove = dao.commentsForOnce("a.jpg").first { it.text == "remove" }

        dao.delete(toRemove.id)
        assertEquals(listOf("keep"), dao.commentsForOnce("a.jpg").map { it.text })
    }

    @Test
    fun deleteForFiles_andDeleteAll() = runTest {
        add("a.jpg", "a1", 1)
        add("b.jpg", "b1", 2)

        dao.deleteForFiles(listOf("a.jpg"))
        assertEquals(0, dao.commentsForOnce("a.jpg").size)
        assertEquals(1, dao.commentsForOnce("b.jpg").size)

        dao.deleteAll()
        assertEquals(0, dao.commentsForOnce("b.jpg").size)
    }
}
