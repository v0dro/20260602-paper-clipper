package com.example.paperclipper.gemini

import android.util.Base64
import androidx.annotation.VisibleForTesting
import com.example.paperclipper.BuildConfig
import com.example.paperclipper.net.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection

/** Result of analyzing a clipping. */
sealed interface GeminiResult {
    data class Success(
        val extractedText: String,
        val summary: String,
        val heading: String = "",
    ) : GeminiResult
    data class Error(val message: String) : GeminiResult
}

/**
 * Client for the Paper Clipper backend proxy (see /server). The app no longer talks to Gemini
 * directly — the Gemini API key lives on the server, off the device. We POST the clipping image
 * to `<SERVER_URL>/analyze` and get back the transcription + summary. The newspaper-clipping prompt
 * and model now live server-side.
 */
object GeminiClient {
    suspend fun analyze(imageBytes: ByteArray, mimeType: String, userId: String): GeminiResult =
        withContext(Dispatchers.IO) {
            val baseUrl = Backend.resolveBaseUrl(BuildConfig.SERVER_URL).getOrElse {
                return@withContext GeminiResult.Error(it.message ?: "Server URL not configured.")
            }

            val body = JSONObject()
                .put("mimeType", mimeType)
                .put("imageBase64", Base64.encodeToString(imageBytes, Base64.NO_WRAP))
                .toString()

            var conn: HttpURLConnection? = null
            try {
                conn = Backend.openPost(
                    url = Backend.buildUrl(baseUrl, "analyze"),
                    connectTimeoutMs = 30_000,
                    readTimeoutMs = 90_000,
                    extraHeaders = mapOf("X-User-Id" to userId),
                )
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

                if (code !in 200..299) {
                    return@withContext GeminiResult.Error(serverError(response, code))
                }
                parseResult(response)
            } catch (e: Exception) {
                GeminiResult.Error(e.message ?: "Network error contacting the server")
            } finally {
                conn?.disconnect()
            }
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
