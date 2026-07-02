package com.example.paperclipper.gemini

import com.example.paperclipper.BuildConfig
import org.json.JSONObject

/**
 * One deferred usage report: everything the home server would have logged in `request_log` had it
 * (rather than the Cloudflare Worker fallback) served an /analyze call. Built by [GeminiClient]
 * whenever the worker returns a parseable `usage` object, cached on-device, and later uploaded to
 * the server's `POST /report-usage` — so the field names here are a wire contract with
 * `server/app.py`'s `UsageReportItem` and must not drift.
 *
 * Nullable fields are *omitted* from the JSON (the server's item model treats them as optional);
 * `reportId`/`ts`/`status` are the server-required trio and always present.
 */
data class UsageReport(
    val reportId: String,
    val ts: String,
    val userId: String,
    val appVersion: String = BuildConfig.VERSION_NAME,
    val mimeType: String,
    val requestBytes: Int,
    val status: Int,
    val latencyMs: Long? = null,
    val error: String? = null,
    val extractedText: String? = null,
    val summary: String? = null,
    val promptTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val thoughtsTokens: Int? = null,
    val cachedTokens: Int? = null,
    val imageTokens: Int? = null,
    val geminiCalls: Int? = null,
    val modelVersion: String? = null,
) {
    /** Serializes to the `/report-usage` item shape. Null fields are omitted, never JSON null. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("reportId", reportId)
        put("ts", ts)
        put("userId", userId)
        put("appVersion", appVersion)
        put("mimeType", mimeType)
        put("requestBytes", requestBytes)
        put("status", status)
        latencyMs?.let { put("latencyMs", it) }
        error?.let { put("error", it) }
        extractedText?.let { put("extractedText", it) }
        summary?.let { put("summary", it) }
        promptTokens?.let { put("promptTokens", it) }
        outputTokens?.let { put("outputTokens", it) }
        totalTokens?.let { put("totalTokens", it) }
        thoughtsTokens?.let { put("thoughtsTokens", it) }
        cachedTokens?.let { put("cachedTokens", it) }
        imageTokens?.let { put("imageTokens", it) }
        geminiCalls?.let { put("geminiCalls", it) }
        modelVersion?.let { put("modelVersion", it) }
    }

    companion object {
        /** Inverse of [toJson]; missing/null optional fields come back as Kotlin nulls. */
        fun fromJson(json: JSONObject): UsageReport = UsageReport(
            reportId = json.getString("reportId"),
            ts = json.getString("ts"),
            userId = json.getString("userId"),
            appVersion = json.stringOrNull("appVersion") ?: BuildConfig.VERSION_NAME,
            mimeType = json.getString("mimeType"),
            requestBytes = json.getInt("requestBytes"),
            status = json.getInt("status"),
            latencyMs = json.longOrNull("latencyMs"),
            error = json.stringOrNull("error"),
            extractedText = json.stringOrNull("extractedText"),
            summary = json.stringOrNull("summary"),
            promptTokens = json.intOrNull("promptTokens"),
            outputTokens = json.intOrNull("outputTokens"),
            totalTokens = json.intOrNull("totalTokens"),
            thoughtsTokens = json.intOrNull("thoughtsTokens"),
            cachedTokens = json.intOrNull("cachedTokens"),
            imageTokens = json.intOrNull("imageTokens"),
            geminiCalls = json.intOrNull("geminiCalls"),
            modelVersion = json.stringOrNull("modelVersion"),
        )
    }
}

// Null-safe readers shared with GeminiClient's usage parsing. JSONObject.isNull() covers both a
// missing key and an explicit JSON null, so either shape maps to a Kotlin null.
internal fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else optInt(key)
internal fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else optLong(key)
internal fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key)
