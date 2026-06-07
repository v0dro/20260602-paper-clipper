package com.example.paperclipper

import com.example.paperclipper.data.Clipping
import com.example.paperclipper.data.ClippingEntity
import com.example.paperclipper.data.ClippingStatus
import com.example.paperclipper.data.CommentEntity
import com.example.paperclipper.data.TagEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Contract tests for the persisted enum + entity shapes. ClippingStatus is stored in the DB as a
 * plain string, so reordering/renaming its constants would silently corrupt existing rows — this
 * freezes the set and the default-field values.
 */
class EntitiesContractTest {

    @Test
    fun clippingStatus_hasExactlyTheExpectedValues() {
        assertEquals(
            listOf("PENDING", "PROCESSING", "SUCCESS", "ERROR"),
            ClippingStatus.entries.map { it.name },
        )
    }

    @Test
    fun clippingEntity_defaultsToPendingWithNullAnalysisFields() {
        val e = ClippingEntity(fileName = "a.jpg", createdAt = 1_000L)
        assertEquals(ClippingStatus.PENDING.name, e.status)
        assertNull(e.extractedText)
        assertNull(e.summary)
        assertNull(e.errorMessage)
        assertNull(e.model)
        assertNull(e.processedAt)
    }

    @Test
    fun entities_dataClassEqualityAndCopyBehave() {
        val a = ClippingEntity(fileName = "a.jpg", createdAt = 1L)
        assertEquals(a, a.copy())
        assertNotEquals(a, a.copy(status = ClippingStatus.SUCCESS.name))

        val tag = TagEntity(id = 0, name = "News")
        assertEquals(tag, tag.copy())
        assertEquals("News", tag.name)

        val comment = CommentEntity(id = 1, fileName = "a.jpg", text = "hi", createdAt = 5L)
        assertEquals(comment, comment.copy())
        assertNotEquals(comment, comment.copy(text = "bye"))

        val clip = Clipping(File("a.jpg"), 1L, ClippingStatus.SUCCESS, "t", "s", null)
        assertEquals(clip, clip.copy())
        assertNotEquals(clip, clip.copy(status = ClippingStatus.ERROR))
    }
}
