package com.example.paperclipper.gemini

import android.util.Base64
import androidx.annotation.VisibleForTesting
import com.example.paperclipper.BuildConfig
import com.example.paperclipper.net.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Result of analyzing a clipping. When the Cloudflare Worker fallback served the request (either
 * outcome), [usageReport] carries the stats the home server missed — the caller queues it for a
 * deferred upload. It is null on every home-server response.
 */
sealed interface GeminiResult {
    /** Non-null iff the Worker fallback produced this result — doubles as a provenance flag. */
    val usageReport: UsageReport?

    data class Success(
        val extractedText: String,
        val summary: String,
        val heading: String = "",
        override val usageReport: UsageReport? = null,
    ) : GeminiResult
    data class Error(val message: String, override val usageReport: UsageReport? = null) : GeminiResult
}

/**
 * Client for the Paper Clipper backend proxy (see /server). The app no longer talks to Gemini
 * directly — the Gemini API key lives on the server, off the device. We POST the clipping image
 * to `<SERVER_URL>/analyze` and get back the transcription + summary. The newspaper-clipping prompt
 * and model now live server-side.
 *
 * When the home server is *unreachable* (tunnel down, timeout — see [isGatewayUnreachable]) the
 * same request is retried against the Cloudflare Worker fallback at `<WORKER_URL>/analyze`, which
 * speaks the identical contract plus a `usage` object we turn into a [UsageReport].
 */
object GeminiClient {

    /** Outcome of one attempt against one backend: a definitive result, or "try elsewhere". */
    internal sealed interface Attempt {
        data class Completed(val result: GeminiResult) : Attempt
        data class Unreachable(val message: String) : Attempt
    }

    suspend fun analyze(
        imageBytes: ByteArray,
        mimeType: String,
        userId: String,
        allowFallback: Boolean = true,
    ): GeminiResult = withContext(Dispatchers.IO) {
        val baseUrl = Backend.resolveBaseUrl(BuildConfig.SERVER_URL).getOrElse {
            return@withContext GeminiResult.Error(it.message ?: "Server URL not configured.")
        }

        // Built once — on fallback only the network upload repeats, never the base64 encode.
        val body = JSONObject()
            .put("mimeType", mimeType)
            .put("imageBase64", Base64.encodeToString(imageBytes, Base64.NO_WRAP))
            .toString()

        val primary = analyzeAgainst(baseUrl, body, userId, mimeType, imageBytes.size, fromWorker = false)
        if (primary is Attempt.Completed) return@withContext primary.result
        val unreachable = (primary as Attempt.Unreachable).message

        // Blank WORKER_URL = fallback disabled. Checked BEFORE resolveBaseUrl, whose blank-value
        // error text names SERVER_URL and would mislead here.
        if (BuildConfig.WORKER_URL.isBlank()) {
            return@withContext GeminiResult.Error(unreachable)
        }
        if (!allowFallback) {
            return@withContext GeminiResult.Error(
                "$unreachable Daily limit reached, so the backup server was not tried.",
            )
        }
        val workerUrl = Backend.resolveBaseUrl(BuildConfig.WORKER_URL).getOrElse {
            // Same trap as the blank case: resolveBaseUrl's error text names the *server* URL. Keep
            // the real cause (primary unreachable) and pin the misconfiguration on the backup URL.
            return@withContext GeminiResult.Error(
                "$unreachable Backup URL misconfigured: ${it.message ?: "Worker URL not configured."}",
            )
        }
        when (val fallback = analyzeAgainst(workerUrl, body, userId, mimeType, imageBytes.size, fromWorker = true)) {
            is Attempt.Completed -> fallback.result
            is Attempt.Unreachable ->
                GeminiResult.Error("Both servers unreachable. Server: $unreachable Backup: ${fallback.message}")
        }
    }

    /**
     * One HTTP round-trip against one backend. Connectivity failures (IOException) and
     * gateway-generated error pages classify as [Attempt.Unreachable]; everything else — including
     * origin-produced errors — is [Attempt.Completed] via the usual parse/error helpers. When
     * [fromWorker], the response's optional `usage` object (present on 200 and on the worker's
     * 422/502, where tokens were still spent) is attached as a [UsageReport].
     */
    @VisibleForTesting
    internal suspend fun analyzeAgainst(
        baseUrl: String,
        bodyJson: String,
        userId: String,
        mimeType: String,
        requestBytes: Int,
        fromWorker: Boolean,
    ): Attempt {
        var conn: HttpURLConnection? = null
        try {
            conn = Backend.openPost(
                url = Backend.buildUrl(baseUrl, "analyze"),
                connectTimeoutMs = 30_000,
                readTimeoutMs = 90_000,
                extraHeaders = mapOf("X-User-Id" to userId),
            )
            conn.outputStream.use { it.write(bodyJson.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                if (isGatewayUnreachable(code, response)) {
                    return Attempt.Unreachable("Server unreachable (HTTP $code).")
                }
                val message = serverError(response, code)
                val report =
                    if (fromWorker) buildUsageReport(response, userId, mimeType, requestBytes, code, message)
                    else null
                return Attempt.Completed(GeminiResult.Error(message, report))
            }
            val report =
                if (fromWorker) buildUsageReport(response, userId, mimeType, requestBytes, code, error = null)
                else null
            return Attempt.Completed(
                when (val result = parseResult(response)) {
                    is GeminiResult.Success -> result.copy(usageReport = report)
                    is GeminiResult.Error -> result.copy(usageReport = report)
                },
            )
        } catch (e: IOException) {
            return Attempt.Unreachable(e.message ?: "Network error contacting the server")
        } catch (e: Exception) {
            return Attempt.Completed(GeminiResult.Error(e.message ?: "Network error contacting the server"))
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * True iff [code] is a gateway status (502/503/504/520–530) whose body is NOT a JSON object
     * carrying an "error" key. Our backends always wrap errors in `{"error": ...}` — so the home
     * server's own 502 ("Gemini failed after retries") must NOT trigger the fallback (that would
     * double the Gemini spend), while Cloudflare's tunnel-down 502/530 HTML pages must.
     */
    @VisibleForTesting
    internal fun isGatewayUnreachable(code: Int, body: String): Boolean {
        if (code != 502 && code != 503 && code != 504 && code !in 520..530) return false
        val json = runCatching { JSONObject(body) }.getOrNull()
        return json == null || !json.has("error")
    }

    /**
     * Builds the deferred [UsageReport] from a worker response body, or null when the body carries
     * no parseable `usage` object (e.g. the worker's own 400/401 — no tokens were spent).
     * `extractedText`/`summary` come from the same body for full `request_log` parity.
     */
    @VisibleForTesting
    internal fun buildUsageReport(
        response: String,
        userId: String,
        mimeType: String,
        requestBytes: Int,
        status: Int,
        error: String?,
    ): UsageReport? {
        val json = runCatching { JSONObject(response) }.getOrNull() ?: return null
        val usage = json.optJSONObject("usage") ?: return null
        return UsageReport(
            reportId = UUID.randomUUID().toString(),
            ts = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
            userId = userId,
            mimeType = mimeType,
            requestBytes = requestBytes,
            status = status,
            latencyMs = usage.longOrNull("latencyMs"),
            error = error,
            extractedText = json.stringOrNull("extractedText"),
            summary = json.stringOrNull("summary"),
            promptTokens = usage.intOrNull("promptTokens"),
            outputTokens = usage.intOrNull("outputTokens"),
            totalTokens = usage.intOrNull("totalTokens"),
            thoughtsTokens = usage.intOrNull("thoughtsTokens"),
            cachedTokens = usage.intOrNull("cachedTokens"),
            imageTokens = usage.intOrNull("imageTokens"),
            geminiCalls = usage.intOrNull("geminiCalls"),
            modelVersion = usage.stringOrNull("modelVersion"),
        )
    }

    @VisibleForTesting
    internal fun parseResult(response: String): GeminiResult {
        val json = runCatching { JSONObject(response) }.getOrNull()
            ?: return GeminiResult.Error("Unexpected response from server")
        val extracted = json.optString("extractedText").trim()
        val summary = json.optString("summary").trim()
        val heading = json.optString("heading").trim()
        if (extracted.isEmpty() && summary.isEmpty()) {
            return GeminiResult.Error("Server returned no text")
        }
        return GeminiResult.Success(extracted, summary, heading)
    }

    @VisibleForTesting
    internal fun serverError(response: String, code: Int): String {
        val message = runCatching { JSONObject(response).getString("error") }.getOrNull()
        return message ?: "Analysis request failed (HTTP $code)"
    }
}
