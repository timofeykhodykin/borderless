package app.borderless.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone
import kotlin.math.ln

class AxesTest {
    private val moscow = TimeZone.getTimeZone("Europe/Moscow")
    private val hour = 3_600_000L

    @Test
    fun fullLatencyAxisUsesDecadeTicks() {
        val s = YScale.latency()
        assertEquals(listOf(30.0, 100.0, 300.0, 1000.0, 3000.0), s.ticks(s.uMin, s.uMax))
        assertEquals("1.5k", s.label(1500.0))
        assertEquals("3k", s.label(3000.0))
    }

    @Test
    fun zoomedLatencyAxisGetsDenserTicks() {
        val s = YScale.latency()
        assertEquals(listOf(50.0, 100.0, 150.0, 200.0), s.ticks(ln(40.0), ln(250.0)))
        // Narrower than ×4: plain linear steps.
        assertEquals(listOf(80.0, 100.0, 120.0, 140.0), s.ticks(ln(75.0), ln(150.0)))
    }

    @Test
    fun integerAxisNeverUsesFractions() {
        val s = YScale.linear(3.0)
        assertTrue(s.ticks(0.0, 3.0).all { it % 1.0 == 0.0 })
    }

    @Test
    fun timeTicksAlignToLocalClock() {
        // 2026-10-06 10:07 MSK (UTC+3) … 13:07: hourly ticks at 11:00, 12:00, 13:00 local.
        val from = 1_791_270_420_000L
        val (step, ticks) = timeTicks(from, from + 3 * hour, zone = moscow)
        assertEquals(hour, step)
        assertEquals(3, ticks.size)
        ticks.forEach { assertEquals(0L, Math.floorMod(it + 3 * hour, hour)) }
    }

    @Test
    fun fiveMinuteWindowUsesMinuteSteps() {
        val (step, ticks) = timeTicks(0, 5 * 60_000L, zone = moscow)
        assertEquals(2 * 60_000L, step)
        assertEquals(3, ticks.size)
    }

    @Test
    fun clampNeverThrowsOnRoundedEmptyRange() {
        // The crash from the phone: full zoom-out, upper bound a hair below the lower one.
        assertEquals(2.995732273553991, 0.5.clampSafe(2.995732273553991, 2.9957322735539904), 0.0)
        assertEquals(1.0f, 5f.clampSafe(1f, 0.5f), 0f)
    }
}
