package com.example.paperclipper

import com.example.paperclipper.gemini.GeminiClient
import com.example.paperclipper.gemini.GeminiResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Freezes the JSON contract with the proxy server. Robolectric supplies a real org.json so the
 * private parse helpers can be exercised. (The full HTTP round-trip is not covered because the base
 * URL is baked into BuildConfig with no injection point — see plan's documented gap.)
 */
@RunWith(RobolectricTestRunner::class)
class GeminiClientParseTest {

    @Test
    fun parseResult_success_trimsBothFields() {
        val r = GeminiClient.parseResult("""{"extractedText":"  hello  ","summary":"  sum  "}""")
        assertTrue(r is GeminiResult.Success)
        r as GeminiResult.Success
        assertEquals("hello", r.extractedText)
        assertEquals("sum", r.summary)
    }

    @Test
    fun parseResult_succeedsWithOnlyOneField() {
        assertTrue(GeminiClient.parseResult("""{"summary":"only summary"}""") is GeminiResult.Success)
        assertTrue(GeminiClient.parseResult("""{"extractedText":"only text"}""") is GeminiResult.Success)
    }

    @Test
    fun parseResult_bothEmptyIsError() {
        val r = GeminiClient.parseResult("""{"extractedText":"","summary":""}""")
        assertTrue(r is GeminiResult.Error)
        assertEquals("Server returned no text", (r as GeminiResult.Error).message)
    }

    @Test
    fun parseResult_invalidJsonIsError() {
        val r = GeminiClient.parseResult("this is not json")
        assertTrue(r is GeminiResult.Error)
        assertEquals("Unexpected response from server", (r as GeminiResult.Error).message)
    }

    @Test
    fun serverError_prefersErrorFieldElseFallsBackToHttpCode() {
        assertEquals("nope", GeminiClient.serverError("""{"error":"nope"}""", 500))
        assertEquals("Analysis request failed (HTTP 503)", GeminiClient.serverError("garbage", 503))
        assertEquals("Analysis request failed (HTTP 404)", GeminiClient.serverError("{}", 404))
    }
}
