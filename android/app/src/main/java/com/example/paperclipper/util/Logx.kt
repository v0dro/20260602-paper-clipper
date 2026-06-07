package com.example.paperclipper.util

import android.util.Log
import com.example.paperclipper.BuildConfig

/**
 * Logging shim that is silent in release builds. Android's logcat is readable by `adb logcat` (and,
 * on rooted devices, by other apps with READ_LOGS), so anything logged in a shipped build is a
 * potential information leak. Routing logs through here means they compile away to no-ops once
 * [BuildConfig.DEBUG] is false, and the [redactEmail] helper keeps PII out of the few messages we
 * still emit in debug.
 */
object Logx {
    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.d(tag, msg)
    }

    fun i(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.i(tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
        }
    }

    /**
     * Masks an email so it can be logged for debugging without exposing the full address:
     * `jane.doe@example.com` -> `j***@example.com`. Anything that isn't a plausible address (no `@`,
     * empty, or an empty local part) collapses to a constant so we never echo it back verbatim.
     */
    fun redactEmail(email: String?): String {
        if (email.isNullOrBlank()) return "<none>"
        val at = email.indexOf('@')
        if (at <= 0 || at == email.length - 1) return "<redacted>"
        val first = email[0]
        val domain = email.substring(at + 1)
        return "$first***@$domain"
    }
}
