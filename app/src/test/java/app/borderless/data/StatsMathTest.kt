package app.borderless.data

import app.borderless.core.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsMathTest {
    private val min = 60_000L

    private fun row(t: Long, p: Phase, s: String? = null) = StatsDb.PhaseRow(t * min, p, s)

    @Test
    fun segmentsMergeHeartbeatsAndStopAtOff() {
        val rows = listOf(
            row(0, Phase.SEARCHING),
            row(1, Phase.CONNECTED, "a"),
            row(6, Phase.CONNECTED, "a"), // heartbeat
            row(11, Phase.DIRECT),
            row(14, Phase.OFF),
        )
        val seg = StatsMath.segments(rows, 0, 60 * min, 60 * min)
        assertEquals(listOf(Phase.SEARCHING, Phase.CONNECTED, Phase.DIRECT), seg.map { it.phase })
        val d = StatsMath.durations(seg)
        assertEquals(1 * min, d[Phase.SEARCHING])
        assertEquals(10 * min, d[Phase.CONNECTED])
        assertEquals(3 * min, d[Phase.DIRECT])
        assertEquals(mapOf("a" to 10 * min), StatsMath.usage(seg))
    }

    @Test
    fun killedProcessIsNotCountedPastOneAndAHalfHeartbeats() {
        // Connected at 0, then nothing for an hour (process killed), then a new session.
        val rows = listOf(row(0, Phase.CONNECTED, "a"), row(60, Phase.CONNECTED, "b"))
        val seg = StatsMath.segments(rows, 0, 61 * min, 61 * min)
        assertEquals(StatsDb.HEARTBEAT_MS * 3 / 2 + 1 * min, StatsMath.durations(seg)[Phase.CONNECTED])
    }

    @Test
    fun segmentsAreClippedToThePeriod() {
        val rows = listOf(row(0, Phase.CONNECTED, "a"), row(4, Phase.CONNECTED, "a"))
        val seg = StatsMath.segments(rows, 2 * min, 6 * min, 6 * min)
        assertEquals(4 * min, StatsMath.durations(seg)[Phase.CONNECTED])
    }

    @Test
    fun summary() {
        val samples = listOf(100, null, 50, 200, 150, null).mapIndexed { i, ms -> StatsDb.Sample(i.toLong(), "a", ms) }
        val s = StatsMath.summarize(samples)
        assertEquals(6, s.count)
        assertEquals(4, s.ok)
        assertEquals(100, s.median)
        assertEquals(200, s.p95)
        assertEquals(50, s.min)
        assertEquals(200, s.max)
        // |100-50| + |50-200| + |200-150| = 250, over 3 pairs
        assertEquals(83, s.jitter)
        assertNull(StatsMath.summarize(emptyList()).median)
    }

    @Test
    fun bucketsUseMedianAndCountFailures() {
        val samples = listOf(
            StatsDb.Sample(0, "a", 10), StatsDb.Sample(1, "a", 30), StatsDb.Sample(2, "a", null),
            StatsDb.Sample(60, "a", 99),
        )
        val pts = StatsMath.bucket(samples, 0, 100, 10)
        assertEquals(2, pts.size)
        assertEquals(10, pts[0].ms) // lower median of {10, 30}
        assertEquals(1, pts[0].failed)
        assertEquals(99, pts[1].ms)
    }

    @Test
    fun histogram() {
        val h = StatsMath.histogram(listOf(10, 60, 120, 5000, null).map { StatsDb.Sample(0, "a", it) })
        assertEquals(listOf(1, 1, 1, 0, 0, 0, 1), h)
    }

    @Test
    fun medianIgnoresOutliers() {
        // A few hugely negative readings (the phone's own draw fell) don't drag the result below zero.
        assertEquals(110.0, StatsMath.median(listOf(100.0, 120.0, 140.0, -3000.0, 130.0, 110.0, -2500.0)), 1e-9)
        assertEquals(15.0, StatsMath.median(listOf(10.0, 20.0)), 1e-9)
        assertEquals(0.0, StatsMath.median(emptyList()), 1e-9)
    }
}
