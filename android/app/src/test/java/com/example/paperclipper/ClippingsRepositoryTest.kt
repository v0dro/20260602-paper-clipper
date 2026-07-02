package com.example.paperclipper

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.paperclipper.data.AppDatabase
import com.example.paperclipper.data.ClippingEntity
import com.example.paperclipper.data.ClippingStatus
import androidx.work.ExistingWorkPolicy
import com.example.paperclipper.data.ClippingsRepository
import com.example.paperclipper.data.DailyUsageCounter
import com.example.paperclipper.data.PendingUsageReportEntity
import com.example.paperclipper.data.clippingsDir
import com.example.paperclipper.gemini.GeminiClient
import com.example.paperclipper.gemini.GeminiResult
import com.example.paperclipper.gemini.UsageReport
import com.example.paperclipper.report.UsageReportScheduler
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The core paranoid coverage: disk<->DB reconciliation, the analysis pipeline (GeminiClient stubbed),
 * tag/comment CRUD, deletion semantics, and the ZIP/HTML/JSON exporter including HTML escaping.
 */
@RunWith(RobolectricTestRunner::class)
class ClippingsRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: ClippingsRepository
    private lateinit var dir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ClippingsRepository(context, db)
        dir = clippingsDir(context)
        dir.listFiles()?.forEach { it.deleteRecursively() }
        mockkObject(GeminiClient)
        // Stub the scheduler so no test ever initializes real WorkManager.
        mockkObject(UsageReportScheduler)
        every { UsageReportScheduler.schedule(any(), any(), any()) } just Runs
    }

    @After
    fun tearDown() {
        unmockkObject(GeminiClient)
        unmockkObject(UsageReportScheduler)
        db.close()
    }

    private fun writeImage(name: String): File =
        File(dir, name).apply { writeBytes(byteArrayOf(0x1, 0x2, 0x3)) }

    private fun stubAnalyze(result: GeminiResult) {
        coEvery { GeminiClient.analyze(any(), any(), any(), any()) } returns result
    }

    // ---- tag / comment CRUD ---------------------------------------------------------------------

    @Test
    fun createAndAssignTag_trimsDeduplicatesAndLinks() = runTest {
        repo.createAndAssignTag("a.jpg", "  Travel  ")
        repo.createAndAssignTag("a.jpg", "Travel") // exact duplicate -> no new global tag

        assertEquals(listOf("Travel"), repo.tagsFor("a.jpg").first().map { it.name })
        assertEquals(1, repo.allTags.first().size)
    }

    @Test
    fun createAndAssignTag_blankNameIsNoOp() = runTest {
        repo.createAndAssignTag("a.jpg", "   ")
        assertTrue(repo.allTags.first().isEmpty())
        assertTrue(repo.tagsFor("a.jpg").first().isEmpty())
    }

    @Test
    fun setTagAssigned_togglesLink() = runTest {
        repo.createAndAssignTag("a.jpg", "Food")
        val tagId = repo.allTags.first().first().id

        repo.setTagAssigned("b.jpg", tagId, assigned = true)
        assertEquals(listOf("Food"), repo.tagsFor("b.jpg").first().map { it.name })

        repo.setTagAssigned("b.jpg", tagId, assigned = false)
        assertTrue(repo.tagsFor("b.jpg").first().isEmpty())
    }

    @Test
    fun addComment_trimsAndDeleteRemoves() = runTest {
        repo.addComment("a.jpg", "  hello  ")
        repo.addComment("a.jpg", "   ") // blank -> no-op

        val comments = repo.commentsFor("a.jpg").first()
        assertEquals(1, comments.size)
        assertEquals("hello", comments[0].text)

        repo.deleteComment(comments[0].id)
        assertTrue(repo.commentsFor("a.jpg").first().isEmpty())
    }

    // ---- clippings flow mapping -----------------------------------------------------------------

    @Test
    fun clippingsFlow_mapsRowsAndFallsBackToPendingOnBadStatus() = runTest {
        db.clippingDao().insertIfAbsent(
            ClippingEntity(fileName = "ok.jpg", createdAt = 2, status = ClippingStatus.SUCCESS.name),
        )
        db.clippingDao().insertIfAbsent(
            ClippingEntity(fileName = "weird.jpg", createdAt = 1, status = "NOT_A_STATUS"),
        )

        val clips = repo.clippings.first().associateBy { it.file.name }
        assertEquals(ClippingStatus.SUCCESS, clips.getValue("ok.jpg").status)
        assertEquals(ClippingStatus.PENDING, clips.getValue("weird.jpg").status)
        // File resolves under the clippings dir.
        assertEquals(File(dir, "ok.jpg"), clips.getValue("ok.jpg").file)
    }

    // ---- reconcileAndProcess --------------------------------------------------------------------

    @Test
    fun reconcile_insertsNewFilesAndAnalyzesThemToSuccess() = runTest {
        writeImage("new.jpg")
        stubAnalyze(GeminiResult.Success("extracted", "summary"))

        repo.reconcileAndProcess()

        val row = db.clippingDao().getAll().single()
        assertEquals("new.jpg", row.fileName)
        assertEquals(ClippingStatus.SUCCESS.name, row.status)
        assertEquals("extracted", row.extractedText)
        assertEquals("summary", row.summary)
        // No usageReport -> the home server answered.
        assertEquals("server", row.model)
    }

    @Test
    fun reconcile_workerServedResultIsRecordedWithWorkerModel() = runTest {
        writeImage("via.jpg")
        val report = UsageReport(
            reportId = "r1",
            ts = "2026-07-02T00:00:00Z",
            userId = "u",
            mimeType = "image/jpeg",
            requestBytes = 3,
            status = 200,
        )
        stubAnalyze(GeminiResult.Success("x", "y", usageReport = report))

        repo.reconcileAndProcess()

        // A usageReport only exists when the Worker fallback answered — provenance must say so.
        assertEquals("worker", db.clippingDao().getAll().single().model)
    }

    @Test
    fun reconcile_analysisErrorIsRecorded() = runTest {
        writeImage("bad.jpg")
        stubAnalyze(GeminiResult.Error("boom"))

        repo.reconcileAndProcess()

        val row = db.clippingDao().getAll().single()
        assertEquals(ClippingStatus.ERROR.name, row.status)
        assertEquals("boom", row.errorMessage)
        assertNull(row.summary)
    }

    @Test
    fun reconcile_deletesRowsWhoseFilesAreGone() = runTest {
        db.clippingDao().insertIfAbsent(
            ClippingEntity(fileName = "ghost.jpg", createdAt = 1, status = ClippingStatus.SUCCESS.name),
        )
        stubAnalyze(GeminiResult.Success("x", "y"))

        repo.reconcileAndProcess()

        assertTrue(db.clippingDao().getAll().isEmpty())
    }

    // ---- retry ----------------------------------------------------------------------------------

    @Test
    fun retry_reprocessesErroredClipping() = runTest {
        writeImage("z.jpg")
        db.clippingDao().insertIfAbsent(
            ClippingEntity(fileName = "z.jpg", createdAt = 1, status = ClippingStatus.ERROR.name),
        )
        stubAnalyze(GeminiResult.Success("text", "sum"))

        repo.retry("z.jpg")

        assertEquals(ClippingStatus.SUCCESS.name, db.clippingDao().getAll().single().status)
    }

    @Test
    fun retry_prunesPendingRowWhenFileMissing() = runTest {
        db.clippingDao().insertIfAbsent(
            ClippingEntity(fileName = "gone.jpg", createdAt = 1, status = ClippingStatus.ERROR.name),
        )
        stubAnalyze(GeminiResult.Success("x", "y"))

        repo.retry("gone.jpg")

        assertTrue(db.clippingDao().getAll().isEmpty())
    }

    // ---- daily counter / fallback gating ----------------------------------------------------------

    @Test
    fun processPending_successIncrementsDailyCounter() = runTest {
        writeImage("count.jpg")
        stubAnalyze(GeminiResult.Success("x", "y"))

        repo.reconcileAndProcess()

        assertEquals(1, DailyUsageCounter(context).countToday())
    }

    @Test
    fun processPending_errorDoesNotIncrementDailyCounter() = runTest {
        writeImage("fail.jpg")
        stubAnalyze(GeminiResult.Error("boom"))

        repo.reconcileAndProcess()

        assertEquals(0, DailyUsageCounter(context).countToday())
    }

    @Test
    fun processPending_allowsFallbackWhileUnderTheLimit() = runTest {
        writeImage("under.jpg")
        stubAnalyze(GeminiResult.Success("x", "y"))

        repo.reconcileAndProcess()

        coVerify { GeminiClient.analyze(any(), any(), any(), true) }
    }

    @Test
    fun processPending_deniesFallbackOnceLimitReached() = runTest {
        // Seed the shared SharedPreferences counter up to the limit; the repository's own instance
        // reads the same file.
        val counter = DailyUsageCounter(context)
        repeat(DailyUsageCounter.LIMIT) { counter.incrementToday() }
        writeImage("over.jpg")
        stubAnalyze(GeminiResult.Success("x", "y"))

        repo.reconcileAndProcess()

        coVerify { GeminiClient.analyze(any(), any(), any(), false) }
    }

    // ---- deferred usage-report queue --------------------------------------------------------------

    @Test
    fun processPending_queuesWorkerReportAndSchedulesUpload() = runTest {
        writeImage("q.jpg")
        val report = UsageReport(
            reportId = "r-queued",
            ts = "2026-07-02T00:00:00Z",
            userId = "u",
            mimeType = "image/jpeg",
            requestBytes = 3,
            status = 200,
        )
        stubAnalyze(GeminiResult.Success("x", "y", usageReport = report))

        repo.reconcileAndProcess()

        val row = db.pendingUsageReportDao().eligible(Long.MAX_VALUE, 10).single()
        assertEquals("r-queued", row.reportId)
        // The stored payload is the finished wire JSON the uploader will send verbatim.
        assertEquals("r-queued", JSONObject(row.payloadJson).getString("reportId"))
        coVerify { UsageReportScheduler.schedule(any(), row.createdAt, ExistingWorkPolicy.KEEP) }
    }

    @Test
    fun processPending_queuesWorkerReportOnErrorToo() = runTest {
        // The worker attaches usage even to its 422/502 (tokens were spent) — those queue as well.
        writeImage("qe.jpg")
        val report = UsageReport(
            reportId = "r-err",
            ts = "2026-07-02T00:00:00Z",
            userId = "u",
            mimeType = "image/jpeg",
            requestBytes = 3,
            status = 422,
            error = "No text found in the image",
        )
        stubAnalyze(GeminiResult.Error("No text found in the image", usageReport = report))

        repo.reconcileAndProcess()

        assertEquals(1, db.pendingUsageReportDao().count())
    }

    @Test
    fun processPending_serverServedResultQueuesNothing() = runTest {
        writeImage("srv.jpg")
        stubAnalyze(GeminiResult.Success("x", "y")) // no usageReport -> home server answered

        repo.reconcileAndProcess()

        assertEquals(0, db.pendingUsageReportDao().count())
        verify(exactly = 0) { UsageReportScheduler.schedule(any(), any(), any()) }
    }

    @Test
    fun reconcile_reschedulesUploadForLeftoverQueuedReports() = runTest {
        // Straggler catch: a queued report with no live work (e.g. WorkManager state lost) gets its
        // upload re-scheduled on the next app open, keyed off the oldest report's createdAt.
        db.pendingUsageReportDao().insert(PendingUsageReportEntity("stale", 123L, "{}"))
        stubAnalyze(GeminiResult.Success("x", "y"))

        repo.reconcileAndProcess()

        verify { UsageReportScheduler.schedule(any(), 123L, ExistingWorkPolicy.KEEP) }
    }

    // ---- delete / clearAll ----------------------------------------------------------------------

    @Test
    fun delete_removesClippingTagsAndCommentsButKeepsGlobalTags() = runTest {
        val file = writeImage("d.jpg")
        db.clippingDao().insertIfAbsent(
            ClippingEntity(fileName = "d.jpg", createdAt = 1, status = ClippingStatus.SUCCESS.name),
        )
        repo.createAndAssignTag("d.jpg", "Keep")
        repo.addComment("d.jpg", "a comment")

        repo.delete(listOf(file))

        assertFalse(file.exists())
        assertTrue(db.clippingDao().getAll().isEmpty())
        assertTrue(repo.tagsFor("d.jpg").first().isEmpty())
        assertTrue(repo.commentsFor("d.jpg").first().isEmpty())
        // The global tag survives a clipping deletion.
        assertEquals(listOf("Keep"), repo.allTags.first().map { it.name })
    }

    @Test
    fun clearAll_wipesFilesRowsTagsAndComments() = runTest {
        val file = writeImage("e.jpg")
        db.clippingDao().insertIfAbsent(
            ClippingEntity(fileName = "e.jpg", createdAt = 1, status = ClippingStatus.SUCCESS.name),
        )
        repo.createAndAssignTag("e.jpg", "Tag")
        repo.addComment("e.jpg", "comment")

        repo.clearAll()

        assertFalse(file.exists())
        assertTrue(db.clippingDao().getAll().isEmpty())
        assertTrue(repo.allTags.first().isEmpty())
        assertTrue(repo.commentsFor("e.jpg").first().isEmpty())
    }

    // ---- exportTo -------------------------------------------------------------------------------

    @Test
    fun exportTo_writesImagesMetadataAndEscapedHtml() = runTest {
        val imageBytes = byteArrayOf(0x9, 0x8, 0x7)
        File(dir, "p.jpg").writeBytes(imageBytes)
        db.clippingDao().insertIfAbsent(
            ClippingEntity(
                fileName = "p.jpg",
                createdAt = 1,
                status = ClippingStatus.SUCCESS.name,
                extractedText = "the text",
                summary = "Tom & \"Jerry\" <b>",
            ),
        )
        repo.createAndAssignTag("p.jpg", "<news>")
        repo.addComment("p.jpg", "great & <stuff>")

        val out = ByteArrayOutputStream()
        repo.exportTo(out)
        val entries = readZip(out.toByteArray())

        // Image is included verbatim.
        assertTrue(entries.containsKey("images/p.jpg"))
        assertArrayEquals(imageBytes, entries.getValue("images/p.jpg"))

        // metadata.json is valid and carries the structured data (raw, unescaped).
        val meta = JSONArray(String(entries.getValue("metadata.json")))
        assertEquals(1, meta.length())
        val obj = meta.getJSONObject(0)
        assertEquals("p.jpg", obj.getString("fileName"))
        assertEquals(ClippingStatus.SUCCESS.name, obj.getString("status"))
        assertEquals("Tom & \"Jerry\" <b>", obj.getString("summary"))
        assertEquals("the text", obj.getString("extractedText"))
        assertEquals("<news>", obj.getJSONArray("tags").getString(0))
        assertEquals(
            "great & <stuff>",
            obj.getJSONArray("comments").getJSONObject(0).getString("text"),
        )

        // index.html HTML-escapes user/AI content (no raw angle brackets from our data leak through).
        val html = String(entries.getValue("index.html"))
        assertTrue(html.contains("Tom &amp; &quot;Jerry&quot; &lt;b&gt;"))
        assertTrue(html.contains("&lt;news&gt;"))
        assertTrue(html.contains("great &amp; &lt;stuff&gt;"))
        assertFalse(html.contains("<b>"))
        assertFalse(html.contains("<news>"))
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                map[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return map
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) =
        org.junit.Assert.assertArrayEquals(expected, actual)
}
