package com.example.paperclipper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Posts user feedback to the same backend (behind the Cloudflare tunnel) as /analyze. */
object FeedbackClient {
    suspend fun send(message: String, email: String?): Boolean = withContext(Dispatchers.IO) {
        val base = BuildConfig.SERVER_URL.trimEnd('/')
        if (base.isBlank()) return@withContext false
        val body = JSONObject()
            .put("message", message)
            .put("email", email ?: JSONObject.NULL)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .toString()

        var conn: HttpURLConnection? = null
        try {
            conn = (URL("$base/feedback").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${BuildConfig.PROXY_TOKEN}")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }
}
