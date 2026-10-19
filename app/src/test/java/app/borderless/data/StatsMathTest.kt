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
    fun deepSleepWithinOneProcessCountsInFull() {
        // Heartbeats 20 minutes apart (phone asleep), same process; then the process dies after the last one.
        val rows = listOf(
            StatsDb.PhaseRow(0, Phase.CONNECTED, "a", run = 1),
            StatsDb.PhaseRow(20 * min, Phase.CONNECTED, "a", run = 1),
            StatsDb.PhaseRow(45 * min, Phase.DIRECT, null, run = 1),
            StatsDb.PhaseRow(120 * min, Phase.SEARCHING, null, run = 2),
        )
        val d = StatsMath.durations(StatsMath.segments(rows, 0, 121 * min, 121 * min))
        assertEquals(45 * min, d[Phase.CONNECTED])
        assertEquals(StatsDb.HEARTBEAT_MS * 3 / 2, d[Phase.DIRECT])
    }

    @Test
    fun rowsWithoutProcessTreatRepeatsAsTheSameProcess() {
        // Written before v3: a repeat of the same state is a late heartbeat, a change after a silence is not.
        val rows = listOf(row(0, Phase.CONNECTED, "a"), row(14, Phase.CONNECTED, "a"), row(40, Phase.SEARCHING), row(41, Phase.CONNECTED, "a"))
        val d = StatsMath.durations(StatsMath.segments(rows, 0, 42 * min, 42 * min))
        assertEquals(14 * min + StatsDb.HEARTBEAT_MS * 3 / 2 + 1 * min, d[Phase.CONNECTED])
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
    @Test
    fun availabilityCountsTimeSlicesNotChecks() {
        val slice = StatsMath.AVAIL_SLICE_MS
        // Five quiet slices with one good check each, then one bad slice with 30 failed retries.
        val good = (0 until 5).map { StatsDb.Sample(it * slice, "a", 100) }
        val bad = (0 until 30).map { StatsDb.Sample(5 * slice + it * 15_000L, "a", null) }
        assertEquals(5.0 / 6.0, StatsMath.availability(good + bad)!!, 1e-9)
        assertEquals(5.0 / 6.0, StatsMath.summarize(good + bad).availability!!, 1e-9)
        assertEquals(35, StatsMath.summarize(good + bad).count) // plain counts stay
        // Within one slice it is the plain share.
        val mixed = listOf(StatsDb.Sample(0, "a", 100), StatsDb.Sample(1, "a", null), StatsDb.Sample(2, "a", null), StatsDb.Sample(3, "a", 90))
        assertEquals(0.5, StatsMath.availability(mixed)!!, 1e-9)
        assertNull(StatsMath.availability(emptyList()))
    }

    @Test
    fun scanShareWeighsByTimeAndSkipsNoNetwork() {
        fun scan(m: Long, a: Int, t: Int = 10) = StatsDb.ScanRow(m * min, a, t, null)
        // 10 min with all answering, then 20 retries 30 s apart where nothing answers (10 min in all).
        val scans = listOf(scan(0, 10)) + (0 until 20).map { StatsDb.ScanRow(10 * min + it * 30_000L, 0, 10, null) }
        val share = StatsMath.scanShare(scans, emptyList(), 20 * min)!!
        assertEquals(0.5, share.share, 1e-9) // per scan it would be 1/21
        assertEquals(5.0, share.available, 1e-9)
        assertEquals(10.0, share.total, 1e-9)
        // The failing stretch had no network: only the good scan is left.
        val off = listOf(StatsMath.Segment(10 * min, 20 * min, Phase.NO_NETWORK, null))
        assertEquals(1.0, StatsMath.scanShare(scans, off, 20 * min)!!.share, 1e-9)
        // One scan doesn't stand for more than the cap; scans that checked nothing are ignored.
        val far = listOf(scan(0, 0), scan(60, 10), scan(80, 0, 0))
        assertEquals(0.5, StatsMath.scanShare(far, emptyList(), 90 * min)!!.share, 1e-9)
        assertNull(StatsMath.scanShare(emptyList(), emptyList(), 0))
    }
}
