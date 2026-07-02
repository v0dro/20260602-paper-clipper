package com.example.paperclipper.report

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import com.example.paperclipper.BuildConfig
import com.example.paperclipper.data.PendingUsageReportDao
import com.example.paperclipper.data.PendingUsageReportEntity
import com.example.paperclipper.net.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection

/**
 * The testable brain of the deferred upload: drains every eligible queued report to the home
 * server's `POST /report-usage` in batches. Deliberately targets **SERVER_URL only** — delivering
 * the Worker-fallback stats to the home server is the whole point, so a down server means retry,
 * never a second destination.
 *
 * [buildBatchBody]/[parseAck] are pure seams; [postBatch] is the single line that touches the
 * network, so tests spy the object and stub only that.
 */
object UsageReportUploader {

    /** Server rejects >100 items per batch; 25 keeps request bodies small (reports carry text). */
    private const val BATCH_LIMIT = 25

    /**
     * Uploads all reports eligible at [now] (older than [UsageReportScheduler.MIN_AGE_MS]), oldest
     * first. Any delivery failure → [Result.retry] (WorkManager backs off and re-runs; the server's
     * report_id dedup makes lost-ack retries safe). Once drained, reports still too young are
     * covered by re-enqueueing from [UsageReportScheduler] before returning [Result.success].
     */
    suspend fun flush(dao: PendingUsageReportDao, context: Context, now: Long): Result {
        while (true) {
            val batch = dao.eligible(now - UsageReportScheduler.MIN_AGE_MS, BATCH_LIMIT)
            if (batch.isEmpty()) break
            // CoroutineWorker runs doWork on Default; the blocking HttpURLConnection I/O belongs on IO.
            val ack = withContext(Dispatchers.IO) { postBatch(buildBatchBody(batch)) }
                ?: return Result.retry()
            val acknowledged = parseAck(ack) ?: return Result.retry()
            // The server acks every sent item as accepted or duplicate; an empty ack would loop
            // this batch forever, so treat it as a failed delivery instead.
            if (acknowledged.isEmpty()) return Result.retry()
            dao.deleteByIds(acknowledged)
        }
        // Reports younger than MIN_AGE_MS remain: hand off to a fresh scheduled run. KEEP would
        // no-op against this still-RUNNING unique work, hence APPEND_OR_REPLACE.
        val oldest = dao.oldestCreatedAt()
        if (oldest != null) {
            UsageReportScheduler.schedule(context, oldest, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
        return Result.success()
    }

    /** Wraps the stored wire-JSON payloads into the `/report-usage` batch shape. */
    @VisibleForTesting
    internal fun buildBatchBody(batch: List<PendingUsageReportEntity>): String =
        JSONObject()
            .put("reports", JSONArray().apply { batch.forEach { put(JSONObject(it.payloadJson)) } })
            .toString()

    /**
     * POSTs one batch to `<SERVER_URL>/report-usage`. Returns the response body on 2xx, or null on
     * any failure (network error, non-2xx, misconfigured URL) — the caller retries later.
     */
    @VisibleForTesting
    internal fun postBatch(body: String): String? {
        val base = Backend.resolveBaseUrl(BuildConfig.SERVER_URL).getOrElse { return null }
        var conn: HttpURLConnection? = null
        return try {
            conn = Backend.openPost(
                url = Backend.buildUrl(base, "report-usage"),
                connectTimeoutMs = 20_000,
                readTimeoutMs = 30_000,
            )
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Extracts every acknowledged reportId from a `{"accepted":[...],"duplicate":[...]}` response.
     * BOTH lists mean "the server has this row" (duplicate = an earlier batch's ack got lost), so
     * both are deleted locally. Returns null when the body isn't the expected JSON.
     */
    @VisibleForTesting
    internal fun parseAck(response: String): List<String>? {
        val json = runCatching { JSONObject(response) }.getOrNull() ?: return null
        val ids = mutableListOf<String>()
        for (key in listOf("accepted", "duplicate")) {
            val arr = json.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) ids.add(arr.getString(i))
        }
        return ids
    }
}
