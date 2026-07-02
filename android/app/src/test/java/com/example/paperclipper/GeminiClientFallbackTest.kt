package com.example.paperclipper

import com.example.paperclipper.gemini.GeminiClient
import com.example.paperclipper.gemini.UsageReport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Locks down the fallback machinery: the unreachable-classifier (the load-bearing decision — a
 * wrong "true" doubles the Gemini spend by retrying an origin failure against the worker), the
 * worker `usage` → [UsageReport] mapping, and the report's JSON wire contract with the server's
 * `POST /report-usage`. Robolectric supplies a real org.json.
 */
@RunWith(RobolectricTestRunner::class)
class GeminiClientFallbackTest {

    // ---- isGatewayUnreachable classifier ----------------------------------------------------------

    @Test
    fun classifier_502WithHtmlBodyIsUnreachable() {
        // Cloudflare's tunnel-down page: gateway status + HTML body -> fall back.
        assertTrue(GeminiClient.isGatewayUnreachable(502, "<html><body>Bad gateway</body></html>"))
    }

    @Test
    fun classifier_502WithJsonErrorBodyIsNotUnreachable() {
        // The home server's own 502 (Gemini failed after retries) is JSON -> do NOT fall back.
        assertFalse(GeminiClient.isGatewayUnreachable(502, """{"error":"Gemini request failed"}"""))
    }

    @Test
    fun classifier_502WithMalformedJsonBodyIsUnreachable() {
        assertTrue(GeminiClient.isGatewayUnreachable(502, """{"error": truncat"""))
    }

    @Test
    fun classifier_502WithJsonBodyLackingErrorKeyIsUnreachable() {
        assertTrue(GeminiClient.isGatewayUnreachable(502, """{"detail":"something"}"""))
    }

    @Test
    fun classifier_gatewayStatusesAreUnreachable() {
        assertTrue(GeminiClient.isGatewayUnreachable(503, "Service Unavailable"))
        assertTrue(GeminiClient.isGatewayUnreachable(504, "Gateway Timeout"))
        assertTrue(GeminiClient.isGatewayUnreachable(520, "<html>Web server returned unknown error</html>"))
        assertTrue(GeminiClient.isGatewayUnreachable(530, "<html>Argo tunnel error</html>"))
    }

    @Test
    fun classifier_nonGatewayStatusesNeverFallBack() {
        // Even with a non-JSON body: these came from an origin that understood the request.
        for (code in intArrayOf(200, 400, 401, 422, 429)) {
            assertFalse("HTTP $code must not classify as unreachable", GeminiClient.isGatewayUnreachable(code, "<html></html>"))
            assertFalse(GeminiClient.isGatewayUnreachable(code, """{"error":"nope"}"""))
        }
    }

    @Test
    fun classifier_nonGateway5xxStatusesNeverFallBack() {
        // The gateway set is exactly {502, 503, 504, 520–530} — NOT "any 5xx". A plain 500 (or a
        // 5xx whose JSON normalization failed) is an origin failure; falling back would double the
        // Gemini spend. 501/505 and the range edges 519/531 pin the boundaries.
        for (code in intArrayOf(500, 501, 505, 519, 531)) {
            assertFalse("HTTP $code must not classify as unreachable", GeminiClient.isGatewayUnreachable(code, "<html>Internal Server Error</html>"))
            assertFalse(GeminiClient.isGatewayUnreachable(code, """{"error":"nope"}"""))
        }
    }

    // ---- worker usage -> UsageReport ---------------------------------------------------------------

    private val successBody = """
        {"extractedText":"the article","summary":"a summary","heading":"h",
         "usage":{"promptTokens":1000,"outputTokens":200,"totalTokens":1200,
                  "thoughtsTokens":10,"cachedTokens":20,"imageTokens":900,
                  "geminiCalls":2,"modelVersion":"gemini-2.5-flash","latencyMs":8123}}
    """.trimIndent()

    @Test
    fun buildUsageReport_mapsSuccessBodyFields() {
        val r = GeminiClient.buildUsageReport(
            response = successBody,
            userId = "dev:abc",
            mimeType = "image/jpeg",
            requestBytes = 123456,
            status = 200,
            error = null,
        )
        assertNotNull(r)
        r!!
        assertEquals("dev:abc", r.userId)
        assertEquals("image/jpeg", r.mimeType)
        assertEquals(123456, r.requestBytes)
        assertEquals(200, r.status)
        assertNull(r.error)
        assertEquals("the article", r.extractedText)
        assertEquals("a summary", r.summary)
        assertEquals(1000, r.promptTokens)
        assertEquals(200, r.outputTokens)
        assertEquals(1200, r.totalTokens)
        assertEquals(10, r.thoughtsTokens)
        assertEquals(20, r.cachedTokens)
        assertEquals(900, r.imageTokens)
        assertEquals(2, r.geminiCalls)
        assertEquals("gemini-2.5-flash", r.modelVersion)
        assertEquals(8123L, r.latencyMs)
        assertEquals(BuildConfig.VERSION_NAME, r.appVersion)
        assertTrue(r.reportId.isNotBlank())
        // ts is ISO-8601 UTC truncated to seconds, e.g. 2026-07-02T04:12:33Z.
        assertTrue(r.ts, Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""").matches(r.ts))
    }

    @Test
    fun buildUsageReport_errorBodyWithUsageKeepsTokensAndError() {
        // The worker's 422/502 still spent tokens, so it ships `usage` alongside `error`.
        val body = """{"error":"No text found","usage":{"promptTokens":900,"geminiCalls":1}}"""
        val r = GeminiClient.buildUsageReport(body, "dev:abc", "image/png", 99, 422, "No text found")
        assertNotNull(r)
        r!!
        assertEquals(422, r.status)
        assertEquals("No text found", r.error)
        assertEquals(900, r.promptTokens)
        assertEquals(1, r.geminiCalls)
        // Fields the body doesn't carry come back null (omitted from the queued JSON).
        assertNull(r.extractedText)
        assertNull(r.summary)
        assertNull(r.outputTokens)
        assertNull(r.latencyMs)
        assertNull(r.modelVersion)
    }

    @Test
    fun buildUsageReport_missingUsageObjectIsNull() {
        // 400/401 bodies carry no usage — nothing was spent, nothing to report.
        assertNull(GeminiClient.buildUsageReport("""{"error":"Invalid or missing token"}""", "u", "image/jpeg", 1, 401, "x"))
        assertNull(GeminiClient.buildUsageReport("""{"extractedText":"t","summary":"s"}""", "u", "image/jpeg", 1, 200, null))
    }

    @Test
    fun buildUsageReport_unparseableBodyIsNull() {
        assertNull(GeminiClient.buildUsageReport("<html>oops</html>", "u", "image/jpeg", 1, 200, null))
    }

    // ---- UsageReport JSON round-trip ---------------------------------------------------------------

    private fun fullReport() = UsageReport(
        reportId = "11111111-2222-3333-4444-555555555555",
        ts = "2026-07-02T04:12:33Z",
        userId = "dev:abc",
        appVersion = "1.0.0",
        mimeType = "image/jpeg",
        requestBytes = 123456,
        status = 200,
        latencyMs = 8123,
        error = null,
        extractedText = "text",
        summary = "sum",
        promptTokens = 1000,
        outputTokens = 200,
        totalTokens = 1200,
        thoughtsTokens = 10,
        cachedTokens = 20,
        imageTokens = 900,
        geminiCalls = 2,
        modelVersion = "gemini-2.5-flash",
    )

    @Test
    fun usageReport_toJsonUsesExactWireFieldNames() {
        // These names are the /report-usage contract with server/app.py — a rename breaks ingest.
        val json = fullReport().toJson()
        val expected = setOf(
            "reportId", "ts", "userId", "appVersion", "mimeType", "requestBytes", "status",
            "latencyMs", "extractedText", "summary",
            "promptTokens", "outputTokens", "totalTokens", "thoughtsTokens", "cachedTokens",
            "imageTokens", "geminiCalls", "modelVersion",
        )
        assertEquals(expected, json.keys().asSequence().toSet())
    }

    @Test
    fun usageReport_jsonRoundTripPreservesEveryField() {
        val original = fullReport().copy(error = "boom")
        assertEquals(original, UsageReport.fromJson(JSONObject(original.toJson().toString())))
    }

    @Test
    fun usageReport_nullFieldsAreOmittedAndRoundTripAsNull() {
        val sparse = UsageReport(
            reportId = "r1",
            ts = "2026-07-02T00:00:00Z",
            userId = "u",
            mimeType = "image/png",
            requestBytes = 1,
            status = 502,
            error = "upstream failed",
        )
        val json = sparse.toJson()
        // "error" is absent from the all-fields fixture above, so pin its wire name here.
        assertEquals("upstream failed", json.getString("error"))
        // Nulls are omitted entirely (never JSON null) — the server model treats them as optional.
        assertFalse(json.has("latencyMs"))
        assertFalse(json.has("extractedText"))
        assertFalse(json.has("summary"))
        assertFalse(json.has("promptTokens"))
        assertFalse(json.has("modelVersion"))
        assertEquals(sparse, UsageReport.fromJson(JSONObject(json.toString())))
    }
}
