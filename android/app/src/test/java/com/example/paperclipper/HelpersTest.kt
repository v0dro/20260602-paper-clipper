package com.example.paperclipper

import com.example.paperclipper.data.Clipping
import com.example.paperclipper.data.ClippingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone

/**
 * Pure-logic tests for the UI helpers in MainActivity. These freeze the contracts that the home
 * list (date grouping), search highlighting, status labels and date formatting depend on — any
 * behavioral change makes one of these go red. No Android framework is touched, so they run as
 * plain JVM tests. Locale + time zone are pinned so the date assertions are deterministic.
 */
class HelpersTest {

    @Before
    fun pinLocaleAndTimeZone() {
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun utcMillis(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        LocalDateTime.of(year, month, day, hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun clip(createdAt: Long, name: String = "c.jpg"): Clipping =
        Clipping(
            file = File(name),
            createdAt = createdAt,
            status = ClippingStatus.SUCCESS,
            extractedText = null,
            summary = null,
            heading = null,
            errorMessage = null,
        )

    // ---- statusLabel ----------------------------------------------------------------------------

    @Test
    fun statusLabel_isExhaustiveAndStable() {
        // If a ClippingStatus value is added/renamed/relabeled, this fails — the status->UI contract.
        assertEquals("Analyzing…", statusLabel(ClippingStatus.PENDING))
        assertEquals("Analyzing…", statusLabel(ClippingStatus.PROCESSING))
        assertEquals("Summary", statusLabel(ClippingStatus.SUCCESS))
        assertEquals("Analysis failed", statusLabel(ClippingStatus.ERROR))
    }

    // ---- fmt ------------------------------------------------------------------------------------

    @Test
    fun fmt_formatsKnownPatterns() {
        val t = utcMillis(2026, 6, 7)
        assertEquals("2026-06", fmt("yyyy-MM", t))
        assertEquals("June 2026", fmt("MMMM yyyy", t))
        assertEquals("7 June 2026", fmt("d MMMM yyyy", t))
    }

    // ---- dateSections ---------------------------------------------------------------------------

    @Test
    fun dateSections_emptyInputYieldsEmpty() {
        assertTrue(dateSections(emptyList()).isEmpty())
    }

    @Test
    fun dateSections_singleClippingInMonthUsesMonthHeader() {
        val sections = dateSections(listOf(clip(utcMillis(2026, 6, 7))))
        assertEquals(1, sections.size)
        assertEquals("June 2026", sections[0].first)
        assertEquals(1, sections[0].second.size)
    }

    @Test
    fun dateSections_multipleInMonthSplitIntoPerDayHeaders() {
        val a = clip(utcMillis(2026, 6, 7), "a.jpg")
        val b = clip(utcMillis(2026, 6, 7), "b.jpg")
        val c = clip(utcMillis(2026, 6, 8), "c.jpg")
        val sections = dateSections(listOf(a, b, c))

        assertEquals(2, sections.size)
        assertEquals("7 June 2026", sections[0].first)
        assertEquals(2, sections[0].second.size)
        assertEquals("8 June 2026", sections[1].first)
        assertEquals(1, sections[1].second.size)
    }

    @Test
    fun dateSections_spanningTwoMonthsKeepsOrderAndMonthHeaders() {
        // One clipping per month -> each month is a single "MMMM yyyy" header, order preserved.
        val may = clip(utcMillis(2026, 5, 30), "may.jpg")
        val june = clip(utcMillis(2026, 6, 1), "june.jpg")
        val sections = dateSections(listOf(may, june))

        assertEquals(listOf("May 2026", "June 2026"), sections.map { it.first })
    }

    // ---- searchSnippet --------------------------------------------------------------------------

    @Test
    fun searchSnippet_highlightsMatchWithinShortSource() {
        val source = "The quick brown fox jumps over the lazy dog"
        val result = searchSnippet(source, "fox")

        // Short source fits entirely in the window (no ellipses).
        assertEquals(source, result.text)
        assertFalse(result.text.startsWith("…"))
        assertFalse(result.text.endsWith("…"))
        // Exactly one styled span, covering "fox".
        assertEquals(1, result.spanStyles.size)
        val span = result.spanStyles[0]
        assertEquals(source.indexOf("fox"), span.start)
        assertEquals(source.indexOf("fox") + 3, span.end)
    }

    @Test
    fun searchSnippet_truncatesWithEllipsesAroundMatch() {
        val source = "a".repeat(100) + "NEEDLE" + "b".repeat(100)
        val result = searchSnippet(source, "NEEDLE")

        assertTrue(result.text.startsWith("…"))
        assertTrue(result.text.endsWith("…"))
        assertTrue(result.text.contains("NEEDLE"))
        val at = result.text.indexOf("NEEDLE")
        assertEquals(1, result.spanStyles.size)
        assertEquals(at, result.spanStyles[0].start)
        assertEquals(at + "NEEDLE".length, result.spanStyles[0].end)
    }

    @Test
    fun searchSnippet_isCaseInsensitive() {
        val result = searchSnippet("Breaking NEWS today", "news")
        assertEquals(1, result.spanStyles.size)
    }

    @Test
    fun searchSnippet_highlightsEveryOccurrence() {
        val result = searchSnippet("cat cat cat", "cat")
        assertEquals(3, result.spanStyles.size)
    }

    @Test
    fun searchSnippet_noMatchProducesNoSpansAndDoesNotCrash() {
        val result = searchSnippet("nothing to see here", "zzz")
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun searchSnippet_collapsesNewlinesToSpaces() {
        val result = searchSnippet("line1\nline2 fox", "fox")
        assertFalse(result.text.contains('\n'))
        assertTrue(result.text.contains("line1 line2"))
    }
}
