package com.example.paperclipper

import com.example.paperclipper.net.Backend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the backend URL contract. The HTTPS guard is a security control — if it regresses, the
 * proxy token / user id / feedback email could be sent in cleartext over an http:// SERVER_URL. Pure
 * logic, so it runs as a plain JVM test.
 */
class BackendTest {

    @Test
    fun acceptsHttpsAndTrimsTrailingSlash() {
        val r = Backend.resolveBaseUrl("https://clipper.example.com/")
        assertTrue(r.isSuccess)
        assertEquals("https://clipper.example.com", r.getOrNull())
    }

    @Test
    fun acceptsHttpsWithoutTrailingSlash() {
        assertEquals("https://a.b", Backend.resolveBaseUrl("https://a.b").getOrNull())
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals("https://a.b", Backend.resolveBaseUrl("  https://a.b/  ").getOrNull())
    }

    @Test
    fun rejectsBlank() {
        assertTrue(Backend.resolveBaseUrl("").isFailure)
        assertTrue(Backend.resolveBaseUrl("   ").isFailure)
    }

    @Test
    fun rejectsCleartextHttp() {
        assertTrue(Backend.resolveBaseUrl("http://a.b").isFailure)
    }

    @Test
    fun rejectsHttpRegardlessOfCase() {
        // Case-insensitive so "HTTP://" / "Https://" can't slip past the guard.
        assertTrue(Backend.resolveBaseUrl("HTTP://a.b").isFailure)
        assertTrue(Backend.resolveBaseUrl("https://a.b").isSuccess)
        assertTrue(Backend.resolveBaseUrl("HTTPS://a.b").isSuccess)
    }

    @Test
    fun rejectsSchemelessUrl() {
        assertTrue(Backend.resolveBaseUrl("clipper.example.com").isFailure)
        assertTrue(Backend.resolveBaseUrl("ftp://a.b").isFailure)
    }

    @Test
    fun buildUrlJoinsWithSingleSlash() {
        assertEquals("https://a.b/analyze", Backend.buildUrl("https://a.b", "analyze"))
        assertEquals("https://a.b/analyze", Backend.buildUrl("https://a.b/", "analyze"))
        assertEquals("https://a.b/analyze", Backend.buildUrl("https://a.b", "/analyze"))
        assertEquals("https://a.b/analyze", Backend.buildUrl("https://a.b/", "/analyze"))
    }

    @Test
    fun buildUrlDoesNotDoubleSlash() {
        assertFalse(Backend.buildUrl("https://a.b/", "/feedback").contains("//feedback"))
    }
}
