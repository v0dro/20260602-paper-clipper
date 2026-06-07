package com.example.paperclipper

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.paperclipper.data.clippingsDir
import com.example.paperclipper.data.listClippingFiles
import com.example.paperclipper.data.mimeTypeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ClippingStorageTest {

    private lateinit var context: Context
    private lateinit var dir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir = clippingsDir(context)
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun touch(name: String, lastModified: Long): File =
        File(dir, name).apply {
            writeBytes(byteArrayOf(1, 2, 3))
            setLastModified(lastModified)
        }

    @Test
    fun clippingsDir_existsAndIsCreated() {
        assertTrue(dir.exists())
        assertTrue(dir.isDirectory)
    }

    @Test
    fun listClippingFiles_includesOnlyImagesIgnoringCase() {
        touch("a.jpg", 1_000)
        touch("b.JPG", 1_000)
        touch("c.png", 1_000)
        touch("d.PNG", 1_000)
        touch("notes.txt", 1_000)
        File(dir, "subdir").mkdirs()

        val names = listClippingFiles(context).map { it.name }.toSet()
        assertEquals(setOf("a.jpg", "b.JPG", "c.png", "d.PNG"), names)
    }

    @Test
    fun listClippingFiles_sortedNewestFirst() {
        touch("old.jpg", 1_000)
        touch("newest.jpg", 3_000)
        touch("middle.jpg", 2_000)

        val names = listClippingFiles(context).map { it.name }
        assertEquals(listOf("newest.jpg", "middle.jpg", "old.jpg"), names)
    }

    @Test
    fun mimeTypeFor_mapsExtensions() {
        assertEquals("image/png", mimeTypeFor(File("x.png")))
        assertEquals("image/png", mimeTypeFor(File("x.PNG")))
        assertEquals("image/jpeg", mimeTypeFor(File("x.jpg")))
        assertEquals("image/jpeg", mimeTypeFor(File("x.jpeg")))
        // Unknown extensions fall back to jpeg.
        assertEquals("image/jpeg", mimeTypeFor(File("x.gif")))
    }
}
