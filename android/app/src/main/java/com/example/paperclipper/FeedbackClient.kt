package com.example.paperclipper

import com.example.paperclipper.net.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection

/** Posts user feedback to the same backend (behind the Cloudflare tunnel) as /analyze. */
object FeedbackClient {
    suspend fun send(message: String, email: String?): Boolean = withContext(Dispatchers.IO) {
        val base = Backend.resolveBaseUrl(BuildConfig.SERVER_URL).getOrElse { return@withContext false }
        val body = JSONObject()
            .put("message", message)
            .put("email", email ?: JSONObject.NULL)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .toString()

        var conn: HttpURLConnection? = null
        try {
            conn = Backend.openPost(
                url = Backend.buildUrl(base, "feedback"),
                connectTimeoutMs = 20_000,
                readTimeoutMs = 30_000,
            )
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }
}
