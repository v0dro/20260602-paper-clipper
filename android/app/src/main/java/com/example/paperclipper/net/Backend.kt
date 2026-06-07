package com.example.paperclipper.net

import com.example.paperclipper.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared plumbing for the two backend calls (`/analyze`, `/feedback`). Centralizing URL handling and
 * the auth header here removes the duplication between [com.example.paperclipper.gemini.GeminiClient]
 * and [com.example.paperclipper.FeedbackClient] and gives one place to enforce HTTPS.
 *
 * The URL helpers are pure (no I/O, no BuildConfig) so they are unit-testable; [openPost] is the only
 * part that touches the network and the embedded proxy token.
 */
object Backend {

    /**
     * Validates the configured backend base URL. Trims a trailing slash, rejects a blank value, and
     * — critically — rejects any non-HTTPS scheme. A misconfigured `http://` SERVER_URL would send
     * the proxy token, user id and feedback email in cleartext, so we refuse it outright rather than
     * relying solely on the platform's cleartext defaults.
     */
    fun resolveBaseUrl(raw: String): Result<String> {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) {
            return Result.failure(
                IllegalStateException(
                    "Server URL not configured. Add SERVER_URL to local.properties and rebuild.",
                ),
            )
        }
        if (!trimmed.startsWith("https://", ignoreCase = true)) {
            return Result.failure(
                IllegalStateException("Server URL must use https:// — refusing to send data in cleartext."),
            )
        }
        return Result.success(trimmed)
    }

    /** Joins a validated base URL and an endpoint path with exactly one separating slash. */
    fun buildUrl(base: String, path: String): String =
        base.trimEnd('/') + "/" + path.trimStart('/')

    /**
     * Opens a configured POST connection: JSON content type, bearer auth with the proxy token, plus
     * any [extraHeaders]. The caller owns writing the body, reading the response and calling
     * `disconnect()`.
     */
    fun openPost(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${BuildConfig.PROXY_TOKEN}")
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
}
