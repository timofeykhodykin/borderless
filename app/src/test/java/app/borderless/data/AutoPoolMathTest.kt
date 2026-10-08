package app.borderless.data

import app.borderless.data.AutoPoolMath.Estimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPoolMathTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    private fun ranked(vararg p: Double) = p.mapIndexed { i, v -> "s$i" to Estimate(v, v) }

    @Test
    fun unknownServerGetsDefaultChance() {
        assertEquals(AutoPoolMath.UNKNOWN_P, AutoPoolMath.estimate(null, now).p, 1e-9)
    }

    @Test
    fun recentDayWeighsMoreThanTheWeek() {
        // Dead today, fine over the week: chance clearly below the weekly figure.
        val st = StatsDb.PoolStat(shortCount = 20, shortOk = 0, longCount = 200, longOk = 180, avgMs = 100.0, lastOk = now - 30 * hour)
        val e = AutoPoolMath.estimate(st, now)
        assertTrue(e.p < 0.5)
    }

    @Test
    fun poolIsShortestTopReachingTarget() {
        // Reliable servers: six (the minimum) already expect more than 4 working.
        val reliable = ranked(*DoubleArray(20) { 0.95 })
        assertEquals(6, AutoPoolMath.choose(reliable, AutoState(target = 4), null, now).size)
        // Flaky servers: need more of them for the same expectation (4 / 0.3 → 14).
        val flaky = ranked(*DoubleArray(30) { 0.3 })
        assertEquals(14, AutoPoolMath.choose(flaky, AutoState(target = 4), null, now).size)
    }

    @Test
    fun membersStayWithinMarginAndCurrentIsKept() {
        val list = ranked(*DoubleArray(30) { 0.95 })
        // s7 ranks just below the line (keep zone), s20 far below and joined long ago, s25 is in use.
        val prev = AutoState(
            pool = setOf("s0", "s7", "s20", "s25"),
            joined = mapOf("s0" to now - 10 * hour, "s7" to now - 10 * hour, "s20" to now - 10 * hour, "s25" to now - 10 * hour),
            target = 4,
        )
        val pool = AutoPoolMath.choose(list, prev, current = "s25", now = now)
        assertTrue("s7" in pool)
        assertTrue("s20" !in pool)
        assertTrue("s25" in pool)
    }

    @Test
    fun newMemberStaysForAWhile() {
        val list = ranked(*DoubleArray(30) { 0.95 })
        val prev = AutoState(pool = setOf("s20"), joined = mapOf("s20" to now - 5 * 60_000L), target = 4)
        assertTrue("s20" in AutoPoolMath.choose(list, prev, null, now))
    }

    @Test
    fun targetGrowsWhenFewAnswerAndShrinksWhenCalm() {
        var st = AutoState(target = 4)
        st = AutoPoolMath.adapt(st, answered = 1, poolSize = 8)
        assertEquals(5, st.target)
        repeat(AutoPoolMath.CALM_SCANS) { st = AutoPoolMath.adapt(st, answered = 8, poolSize = 8) }
        assertEquals(4, st.target)
    }
}
