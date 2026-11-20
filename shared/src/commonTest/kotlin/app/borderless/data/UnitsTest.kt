package app.borderless.data

import app.borderless.S
import kotlin.test.Test
import kotlin.test.assertEquals

class UnitsTest {
    @Test
    fun unitsNeverOverflow() {
        assertEquals(S.act_seconds, Units.duration(-5).first)
        assertEquals(S.dur_ms, Units.duration(90).first)
        assertEquals(S.dur_m, Units.duration(3000).first)
        assertEquals(S.dur_hm, Units.duration(3 * 3600 + 120).first)
        assertEquals(S.dur_d, Units.duration(7 * 86_400).first)
        // Absurd values (damaged data) still give a day count, never an overflowed one.
        assertEquals(Int.MAX_VALUE, Units.duration(Long.MAX_VALUE).second[0])
    }

    @Test
    fun numbersFollowTheLanguage() {
        assertEquals("0.4", app.borderless.platform.Locales.decimal(0.36, 1, "en"))
        assertEquals("0,4", app.borderless.platform.Locales.decimal(0.36, 1, "ru"))
        assertEquals("2,865", app.borderless.platform.Locales.integer(2865, "en"))
    }
}
