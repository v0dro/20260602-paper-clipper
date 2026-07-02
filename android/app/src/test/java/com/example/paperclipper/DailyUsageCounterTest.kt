package com.example.paperclipper

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.paperclipper.data.DailyUsageCounter
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * The device-local daily counter gates the Worker fallback, so its reset semantics matter: a stale
 * day must read as 0 and an increment after rollover must start a fresh count. The clock is
 * injected so the UTC day boundary is exercised deterministically.
 */
@RunWith(RobolectricTestRunner::class)
class DailyUsageCounterTest {

    private lateinit var context: Context
    private var now: Instant = Instant.parse("2026-07-02T10:00:00Z")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun counter() = DailyUsageCounter(context) { now }

    @Test
    fun startsAtZeroAndIncrements() {
        val c = counter()
        assertEquals(0, c.countToday())
        c.incrementToday()
        c.incrementToday()
        assertEquals(2, c.countToday())
    }

    @Test
    fun countPersistsAcrossInstances() {
        counter().incrementToday()
        // A fresh instance reads the same SharedPreferences file.
        assertEquals(1, counter().countToday())
    }

    @Test
    fun dayRolloverReadsAsZero() {
        counter().incrementToday()
        now = Instant.parse("2026-07-03T00:00:01Z") // just past UTC midnight
        assertEquals(0, counter().countToday())
    }

    @Test
    fun incrementAfterRolloverStartsFreshCount() {
        val c = counter()
        c.incrementToday()
        c.incrementToday()
        now = Instant.parse("2026-07-03T09:00:00Z")
        c.incrementToday()
        assertEquals(1, c.countToday())
    }

    @Test
    fun dayStringIsUtcIsoDate() {
        // 23:30 UTC on the 2nd is already the 3rd in JST — the counter must stay on UTC.
        now = Instant.parse("2026-07-02T23:30:00Z")
        assertEquals("2026-07-02", counter().todayUtc())
    }
}
