package com.example.paperclipper.data

import android.content.Context
import com.example.paperclipper.gemini.GeminiClient
import com.example.paperclipper.gemini.GeminiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** UI-facing model for a clipping: the image file plus its analysis state. */
data class Clipping(
    val file: File,
    val createdAt: Long,
    val status: ClippingStatus,
    val extractedText: String?,
    val summary: String?,
    val errorMessage: String?,
)

/**
 * Single source of truth for clippings. The DB row set is reconciled against the image files on
 * disk; any clipping without a successful analysis is sent to Gemini.
 */
class ClippingsRepository(
    private val context: Context,
    db: AppDatabase,
) {
    private val dao: ClippingDao = db.clippingDao()
    private val tagDao: TagDao = db.tagDao()
    private val commentDao: CommentDao = db.commentDao()

    /** All global tags, usable from any clipping. */
    val allTags: Flow<List<TagEntity>> = tagDao.observeAll()

    fun tagsFor(fileName: String): Flow<List<TagEntity>> = tagDao.observeTagsFor(fileName)

    fun commentsFor(fileName: String): Flow<List<CommentEntity>> = commentDao.observeFor(fileName)

    /** Creates the tag globally if new, then links it to the clipping. */
    suspend fun createAndAssignTag(fileName: String, rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty()) return
        tagDao.insertIfAbsent(TagEntity(name = name))
        val tag = tagDao.findByName(name) ?: return
        tagDao.assign(ClippingTagCrossRef(fileName = fileName, tagId = tag.id))
    }

    suspend fun setTagAssigned(fileName: String, tagId: Long, assigned: Boolean) {
        if (assigned) {
            tagDao.assign(ClippingTagCrossRef(fileName = fileName, tagId = tagId))
        } else {
            tagDao.unassign(fileName, tagId)
        }
    }

    suspend fun addComment(fileName: String, rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return
        commentDao.insert(
            CommentEntity(fileName = fileName, text = text, createdAt = System.currentTimeMillis()),
        )
    }

    suspend fun deleteComment(id: Long) = commentDao.delete(id)

    val clippings: Flow<List<Clipping>> =
        dao.observeAll().map { rows ->
            val dir = clippingsDir(context)
            rows.map { row ->
                Clipping(
                    file = File(dir, row.fileName),
                    createdAt = row.createdAt,
                    status = runCatching { ClippingStatus.valueOf(row.status) }
                        .getOrDefault(ClippingStatus.PENDING),
                    extractedText = row.extractedText,
                    summary = row.summary,
                    errorMessage = row.errorMessage,
                )
            }
        }

    /** Syncs DB rows with the files on disk, then analyzes anything still pending. */
    suspend fun reconcileAndProcess() {
        val files = listClippingFiles(context)
        val fileNames = files.map { it.name }.toSet()
        val known = dao.allFileNames().toSet()

        files.filter { it.name !in known }.forEach { file ->
            dao.insertIfAbsent(
                ClippingEntity(fileName = file.name, createdAt = file.lastModified()),
            )
        }
        (known - fileNames).toList().takeIf { it.isNotEmpty() }?.let { dao.deleteByNames(it) }

        processPending()
    }

    /** Re-queues a single clipping (e.g. after an error) and processes it. */
    suspend fun retry(fileName: String) {
        dao.updateStatus(fileName, ClippingStatus.PENDING.name)
        processPending()
    }

    suspend fun delete(files: List<File>) {
        val names = files.map { it.name }
        files.forEach { it.delete() }
        dao.deleteByNames(names)
        // Tags are global and kept; only this clipping's links and comments are removed.
        tagDao.deleteRefsForFiles(names)
        commentDao.deleteForFiles(names)
    }

    /** Wipes ALL user data: every clipping image on disk plus all clippings, tags and comments. */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        listClippingFiles(context).forEach { it.delete() }
        commentDao.deleteAll()
        tagDao.deleteAllRefs()
        tagDao.deleteAllTags()
        dao.deleteAll()
    }

    /**
     * Writes a user-readable ZIP to [out]: every clipping image under images/, a structured
     * metadata.json, and an index.html gallery that shows each image with its text/summary/
     * tags/comments. Throws on I/O error.
     */
    suspend fun exportTo(out: OutputStream) = withContext(Dispatchers.IO) {
        val dir = clippingsDir(context)
        val clips = dao.getAll()
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val metaArray = JSONArray()
        val html = StringBuilder()
        html.append(
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
                "<title>Paper Clipper export</title><style>" +
                "body{font-family:sans-serif;margin:24px;background:#f5f5f5}" +
                ".c{background:#fff;border-radius:8px;padding:16px;margin:0 0 16px;box-shadow:0 1px 3px rgba(0,0,0,.15)}" +
                "img{max-width:100%;border-radius:6px}.tag{display:inline-block;background:#e0e0ff;" +
                "border-radius:12px;padding:2px 10px;margin:2px;font-size:13px}" +
                "h3{margin:8px 0 4px}.meta{color:#666;font-size:13px}pre{white-space:pre-wrap}</style></head><body>",
        )
        html.append("<h1>Paper Clipper — ${clips.size} clippings</h1>")

        ZipOutputStream(out).use { zip ->
            for (clip in clips) {
                val file = File(dir, clip.fileName)
                if (file.exists()) {
                    zip.putNextEntry(ZipEntry("images/${clip.fileName}"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                val tags = tagDao.tagsForOnce(clip.fileName).map { it.name }
                val comments = commentDao.commentsForOnce(clip.fileName)

                metaArray.put(
                    JSONObject().apply {
                        put("fileName", clip.fileName)
                        put("createdAt", clip.createdAt)
                        put("status", clip.status)
                        put("summary", clip.summary ?: "")
                        put("extractedText", clip.extractedText ?: "")
                        put("tags", JSONArray(tags))
                        put(
                            "comments",
                            JSONArray().apply {
                                comments.forEach {
                                    put(
                                        JSONObject()
                                            .put("text", it.text)
                                            .put("createdAt", it.createdAt),
                                    )
                                }
                            },
                        )
                    },
                )

                html.append("<div class=\"c\">")
                if (file.exists()) html.append("<img src=\"images/${esc(clip.fileName)}\">")
                html.append("<div class=\"meta\">${esc(df.format(Date(clip.createdAt)))}</div>")
                if (tags.isNotEmpty()) {
                    html.append("<div>")
                    tags.forEach { html.append("<span class=\"tag\">${esc(it)}</span>") }
                    html.append("</div>")
                }
                if (!clip.summary.isNullOrBlank()) {
                    html.append("<h3>Summary</h3><div>${esc(clip.summary)}</div>")
                }
                if (!clip.extractedText.isNullOrBlank()) {
                    html.append("<h3>Extracted text</h3><pre>${esc(clip.extractedText)}</pre>")
                }
                if (comments.isNotEmpty()) {
                    html.append("<h3>Comments</h3>")
                    comments.forEach { html.append("<div>• ${esc(it.text)}</div>") }
                }
                html.append("</div>")
            }
            html.append("</body></html>")

            zip.putNextEntry(ZipEntry("metadata.json"))
            zip.write(metaArray.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("index.html"))
            zip.write(html.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    private suspend fun processPending() {
        // Sequential to respect the free-tier rate limit (~15 rpm).
        val userId = com.example.paperclipper.UserId.get(context)
        val pending = dao.withStatus(ClippingStatus.PENDING.name)
        for (row in pending) {
            val file = File(clippingsDir(context), row.fileName)
            if (!file.exists()) {
                dao.deleteByNames(listOf(row.fileName))
                continue
            }
            dao.updateStatus(row.fileName, ClippingStatus.PROCESSING.name)
            val result = GeminiClient.analyze(file.readBytes(), mimeTypeFor(file), userId)
            when (result) {
                is GeminiResult.Success -> dao.updateResult(
                    fileName = row.fileName,
                    status = ClippingStatus.SUCCESS.name,
                    extractedText = result.extractedText,
                    summary = result.summary,
                    errorMessage = null,
                    model = "server",
                    processedAt = System.currentTimeMillis(),
                )
                is GeminiResult.Error -> dao.updateResult(
                    fileName = row.fileName,
                    status = ClippingStatus.ERROR.name,
                    extractedText = null,
                    summary = null,
                    errorMessage = result.message,
                    model = "server",
                    processedAt = System.currentTimeMillis(),
                )
            }
        }
    }
}
