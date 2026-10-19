package app.borderless.data

import app.borderless.data.LimitedPower.Conditions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LimitedPowerTest {
    private fun cond(strategy: Strategy = Strategy.STABLE, eco: Boolean = false, screenOn: Boolean = true) =
        Conditions(strategy, eco, screenOn)

    @Test
    fun floorAndCeilingByConditions() {
        assertEquals(2, LimitedPower.floor(Strategy.STABLE, eco = false, screenOn = true))
        assertEquals(1, LimitedPower.floor(Strategy.STABLE, eco = false, screenOn = false))
        assertEquals(1, LimitedPower.floor(Strategy.STABLE, eco = true, screenOn = true))
        assertEquals(3, LimitedPower.floor(Strategy.FASTEST, eco = false, screenOn = true))
        assertEquals(9, LimitedPower.ceiling(Strategy.STABLE, eco = false))
        assertEquals(4, LimitedPower.ceiling(Strategy.STABLE, eco = true))
        assertEquals(6, LimitedPower.ceiling(Strategy.FAILOVER, eco = false))
        assertEquals(18, LimitedPower.ceiling(Strategy.FASTEST, eco = false))
    }

    @Test
    fun demandGrowsWithRate() {
        // Idle: the floor.
        assertEquals(2, LimitedPower.demand(0, cond()))
        // ~1 MB/s needs 4 streams (1 MB / 256 KB = 4) + 1 spare = 5.
        assertEquals(5, LimitedPower.demand(1024L * 1024, cond()))
        // Capped at the strategy's ceiling.
        assertEquals(9, LimitedPower.demand(100L * 1024 * 1024, cond()))
    }

    @Test
    fun intervalsSparserWhenIdle() {
        assertEquals(15 to 10, LimitedPower.intervals(eco = false, screenOn = true))
        assertEquals(25 to 20, LimitedPower.intervals(eco = false, screenOn = false))
        assertEquals(25 to 20, LimitedPower.intervals(eco = true, screenOn = true))
    }

    @Test
    fun controllerRaisesAtOnceLowersAfterDelay() {
        val c = LimitedPower.Controller(manual = 0)
        var t = 0L
        // First update: the start plan.
        val first = c.update(0, cond(), t)!!
        assertEquals(2, first.streams)
        // Traffic rises: more streams at once.
        t += 1000
        assertEquals(9, c.update(5L * 1024 * 1024, cond(), t)!!.streams)
        // Rate drops but not long enough yet: unchanged (null = no change).
        t += 1000
        assertNull(c.update(0, cond(), t))
        // After the hold time: back to the floor.
        t += LimitedPower.DOWN_AFTER_MS
        assertEquals(2, c.update(0, cond(), t)!!.streams)
    }

    @Test
    fun manualCountIsFixed() {
        val c = LimitedPower.Controller(manual = 7)
        assertEquals(7, c.update(0, cond(), 0)!!.streams)
        // Traffic doesn't change a fixed count.
        assertNull(c.update(50L * 1024 * 1024, cond(), 1000))
    }

    @Test
    fun serverLimitCapsAutomaticAndManual() {
        // Automatic demand never exceeds the server's max_workers.
        assertEquals(3, LimitedPower.demand(100L * 1024 * 1024, cond(), limit = 3))
        assertEquals(1, LimitedPower.start(manual = 0, cond(), limit = 1).streams)
        assertEquals(2, LimitedPower.ceilingAtStart(manual = 0, limit = 2))
        assertEquals(4, LimitedPower.start(manual = 9, cond(), limit = 4).streams)
        // A limit learned while running lowers the count at once; clearing it lets it grow again.
        val c = LimitedPower.Controller(manual = 9, limit = 9)
        assertEquals(9, c.update(0, cond(), 0)!!.streams)
        c.setLimit(3)
        assertEquals(3, c.update(0, cond(), 1000)?.streams ?: 3)
        c.setLimit(null)
        assertEquals(9, c.update(0, cond(), 2000)!!.streams)
    }

    @Test
    fun intervalsChangeWhenScreenTurnsOff() {
        val c = LimitedPower.Controller(manual = 3)
        assertEquals(15 to 10, c.update(0, cond(screenOn = true), 0)!!.let { it.keepaliveSec to it.bindingSec })
        val off = c.update(0, cond(screenOn = false), 1000)!!
        assertEquals(25 to 20, off.keepaliveSec to off.bindingSec)
    }

    @Test
    fun radioTimeFollowsKeepalivesAndBytes() {
        // Wi-Fi: a keepalive exchange (60 ms) every 15 s and a binding refresh every 10 s, 60 ms each:
        // 3 s * (60/15000 + 60/10000) = 30 ms per stream, 2 streams = 60 ms.
        assertEquals(60, LimitedPower.radioMs(2, 15, 10, 0, 3_000, metered = false))
        // Carried bytes add their transfer time at the model's link speed.
        assertEquals(10, LimitedPower.radioMs(0, 15, 10, 10 * Energy.WIFI_BYTES_PER_MS, 60_000, metered = false))
        // Mobile: the radio stays high ~10 s after each wake, so streams awake that often keep it busy all window.
        assertEquals(3_000, LimitedPower.radioMs(2, 15, 10, 0, 3_000, metered = true))
        // Never more than the window; no window, no radio.
        assertEquals(3_000, LimitedPower.radioMs(18, 5, 5, 100_000_000, 3_000, metered = true))
        assertEquals(0, LimitedPower.radioMs(2, 15, 10, 1_000, 0, metered = false))
        // Idle (no streams) costs nothing without traffic.
        assertEquals(0, LimitedPower.radioMs(0, 15, 10, 0, 3_000, metered = true))
    }
}
