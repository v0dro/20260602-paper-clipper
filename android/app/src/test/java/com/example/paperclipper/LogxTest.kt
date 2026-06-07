package com.example.paperclipper

import com.example.paperclipper.util.Logx
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [Logx.redactEmail] keeps PII out of logs, so a regression here is a silent privacy leak. Pure
 * string logic, so it runs as a plain JVM test with no Android framework.
 */
class LogxTest {

    @Test
    fun redactsNormalEmail() {
        assertEquals("j***@example.com", Logx.redactEmail("jane.doe@example.com"))
    }

    @Test
    fun keepsOnlyFirstCharAndDomain() {
        assertEquals("a***@b.co", Logx.redactEmail("a@b.co"))
    }

    @Test
    fun nullOrBlankCollapses() {
        assertEquals("<none>", Logx.redactEmail(null))
        assertEquals("<none>", Logx.redactEmail(""))
        assertEquals("<none>", Logx.redactEmail("   "))
    }

    @Test
    fun nonAddressesNeverEchoedVerbatim() {
        // No '@', '@' at the start, or '@' at the end -> never reveal the input.
        assertEquals("<redacted>", Logx.redactEmail("not-an-email"))
        assertEquals("<redacted>", Logx.redactEmail("@example.com"))
        assertEquals("<redacted>", Logx.redactEmail("user@"))
    }
}
