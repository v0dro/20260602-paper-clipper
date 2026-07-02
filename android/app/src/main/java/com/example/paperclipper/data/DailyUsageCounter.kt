package com.example.paperclipper.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Device-local count of analyses per UTC day, kept in plain SharedPreferences (deliberately not
 * Room — no migration coupling for a two-key counter). Defense-in-depth for the Cloudflare Worker
 * fallback: the home server enforces its own per-user quota, but the worker's KV counter is
 * eventually consistent, so once this counter hits [LIMIT] the repository stops offering the
 * fallback. The clock is injectable so day rollover is unit-testable.
 */
class DailyUsageCounter(
    context: Context,
    @get:VisibleForTesting internal val now: () -> Instant = Instant::now,
) {
    private val prefs = context.getSharedPreferences("paperclipper_usage", Context.MODE_PRIVATE)

    /** Analyses recorded today (UTC); a stored count from a previous day reads as 0. */
    fun countToday(): Int =
        if (prefs.getString(KEY_DAY, null) == todayUtc()) prefs.getInt(KEY_COUNT, 0) else 0

    /** Records one analysis, resetting the count first if the UTC day has rolled over. */
    fun incrementToday() {
        prefs.edit()
            .putString(KEY_DAY, todayUtc())
            .putInt(KEY_COUNT, countToday() + 1)
            .apply()
    }

    /** The UTC day string used as the reset key, e.g. "2026-07-02". */
    @VisibleForTesting
    internal fun todayUtc(): String =
        DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(now())

    companion object {
        /** Mirrors the server's DAILY_LIMIT default (server/app.py) — keep the two in sync. */
        const val LIMIT = 100

        private const val KEY_DAY = "day"
        private const val KEY_COUNT = "count"
    }
}
