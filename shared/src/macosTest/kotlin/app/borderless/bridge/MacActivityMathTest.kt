package app.borderless.bridge

import app.borderless.data.StatsDb
import app.borderless.data.WorkGroup
import app.borderless.data.WorkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The activity math port (ENERGY-DESIGN §4, `MacActivityMath` = Android `ActivityMath` without the
 * radio terms): the power summary, the hours the conditions count, the breakdown and the charts' math.
 */
class MacActivityMathTest {

    private fun row(
        ts: Long, battery: Int? = 80, charging: Boolean = false, screen: Boolean = true,
        saver: Boolean = false, metered: Boolean = false, cpuMs: Long = 0, pssKb: Long = 60_000,
        capMah: Int? = 5_000, mv: Int? = 11_500,
    ) = StatsDb.PowerRow(ts, battery, charging, screen, saver, metered, cpuMs, pssKb, capMah, mv)

    private fun total(kind: WorkKind, n: Long = 1, cpuMs: Long = 0, mj: Long = 0, bytes: Long = 0, radioMs: Long = 0) =
        StatsDb.ActivityTotal(kind.name, n, bytes, radioMs, cpuMs, mj)

    @Test
    fun summarize() {
        // An hour covered: half of it screen off, a quarter charging; 60 s of CPU in all.
        val rows = listOf(
            row(0, cpuMs = 0), row(15 * 60_000, charging = true, cpuMs = 20_000),
            row(30 * 60_000, screen = false, cpuMs = 20_000), row(45 * 60_000, screen = false, cpuMs = 20_000),
            row(60 * 60_000, cpuMs = 0),
        )
        val s = MacActivityMath.summarize(rows)
        assertEquals(1.0, s.hours, 0.001)
        assertEquals(60.0, s.cpuSeconds, 0.001)
        assertEquals(60.0, s.cpuPerHour!!, 0.001)
        assertEquals(0.5, s.screenOff, 0.001)
        assertEquals(0.25, s.charging, 0.001)
        assertEquals(0.0, s.metered, 0.001)
        assertEquals(60_000.0 / 1024, s.avgMemoryMb!!, 0.1)
        assertEquals(5_000, s.capacityMah)
        assertEquals(11.5, s.volts!!, 0.001)
    }

    @Test
    fun summarizeGap() {
        // A gap over MAX_GAP_MS (the service was gone) doesn't count as covered time.
        val rows = listOf(row(0), row(60_000), row(60_000 + MacActivityMath.MAX_GAP_MS + 60_000))
        val s = MacActivityMath.summarize(rows)
        assertEquals(1 / 60.0, s.hours, 0.001)
    }

    @Test
    fun hoursIn() {
        // Samples every 10 min over the hour (a wider gap would not count as covered at all).
        val rows = (0..6).map { row(it * 10 * 60_000L) }
        // Only the covered stretch inside the intervals counts.
        assertEquals(0.5, MacActivityMath.hoursIn(rows, listOf(30 * 60_000L to 60 * 60_000L)), 0.001)
        assertEquals(0.0, MacActivityMath.hoursIn(rows, emptyList()), 0.001)
        // The app not running before the first sample counts nothing.
        assertEquals(0.0, MacActivityMath.hoursIn(rows, listOf(-60_000L to -30_000L)), 0.001)
    }

    @Test
    fun wakeupsAndGroups() {
        // PING_PERIODIC and SCAN_BACKGROUND wake the radio by themselves on Android — "woke the
        // network" on the Mac; PING_RIDE and the local kinds don't.
        val t = mapOf(
            WorkKind.PING_PERIODIC.name to total(WorkKind.PING_PERIODIC, n = 4),
            WorkKind.SCAN_BACKGROUND.name to total(WorkKind.SCAN_BACKGROUND, n = 2),
            WorkKind.PING_RIDE.name to total(WorkKind.PING_RIDE, n = 9),
            WorkKind.CORE_BASE.name to total(WorkKind.CORE_BASE),
            WorkKind.DB_COMMIT.name to total(WorkKind.DB_COMMIT),
            WorkKind.RESCAN_SKIPPED.name to total(WorkKind.RESCAN_SKIPPED, n = 3),
        )
        assertEquals(6, MacActivityMath.wakeups(t))
        assertEquals(13, MacActivityMath.total(t, WorkGroup.PING))
        assertEquals(2, MacActivityMath.total(t, WorkGroup.SCAN))
        assertEquals(2, MacActivityMath.total(t, WorkGroup.LOCAL))
    }

    @Test
    fun isAction() {
        // Discrete actions show by themselves; the infrastructure buckets don't.
        assertTrue(MacActivityMath.isAction(WorkKind.SCAN_MANUAL))
        assertTrue(MacActivityMath.isAction(WorkKind.SUB_UPDATE))
        assertTrue(MacActivityMath.isAction(WorkKind.LIMITED_START).not()) // LIMITED is its own part
        assertTrue(!MacActivityMath.isAction(WorkKind.CORE_BASE))
        assertTrue(!MacActivityMath.isAction(WorkKind.UI_BASE))
        assertTrue(!MacActivityMath.isAction(WorkKind.DB_COMMIT))
    }

    @Test
    fun energyShares() {
        // TRAFFIC (the core) takes most of the energy; everything under 1 % of the total folds into OTHER.
        val t = mapOf(
            WorkKind.CORE_BASE.name to total(WorkKind.CORE_BASE, cpuMs = 1000, mj = 10_000),
            WorkKind.SCAN_MANUAL.name to total(WorkKind.SCAN_MANUAL, cpuMs = 50, mj = 300),
            WorkKind.PING_PERIODIC.name to total(WorkKind.PING_PERIODIC, cpuMs = 20, mj = 200),
            WorkKind.DB_COMMIT.name to total(WorkKind.DB_COMMIT, cpuMs = 5, mj = 10),
        )
        val shares = MacActivityMath.energyShares(t, cpuW = 2.0)
        assertEquals(MacActivityMath.Part.TRAFFIC, shares.first().part)
        assertTrue(shares.first().share > 0.9)
        assertEquals(10_510, shares.sumOf { it.mj })
        // No radio on the Mac: radio_ms stays 0 in every row, and the display's "radio share" is
        // just what the CPU model can't explain of the measured mJ — clamped into 0…1 either way.
        shares.forEach { assertTrue(it.radioShare in 0.0..1.0) }
        // Parts' labels are strings names the app renders (never resource ids).
        assertTrue(shares.all { it.part.label.startsWith("act_") })
    }

    @Test
    fun energyOverTime() {
        // Two writes an hour apart inside one covered stretch: energy lands in both halves' buckets.
        val flushes = listOf(
            MacActivityMath.Flush(0, mapOf(WorkKind.CORE_BASE to 3_600_000, WorkKind.PING_RIDE to 360_000)),
            MacActivityMath.Flush(30 * 60_000, mapOf(WorkKind.CORE_BASE to 3_600_000)),
        )
        val buckets = MacActivityMath.energyOverTime(flushes, 0, 60 * 60_000, 96)
        assertTrue(buckets.isNotEmpty())
        assertTrue(buckets.all { it.mj.keys.all { p -> p == MacActivityMath.Part.TRAFFIC || p == MacActivityMath.Part.CONNECTION } })
        assertTrue(buckets.sumOf { it.total } > 3_000_000)
        // Empty input is an empty chart, not a crash.
        assertTrue(MacActivityMath.energyOverTime(emptyList(), 0, 60_000, 96).isEmpty())
        assertTrue(MacActivityMath.energyOverTime(flushes, 0, 0, 96).isEmpty())
    }

    @Test
    fun percentages() {
        assertEquals(listOf(50, 30, 20), MacActivityMath.percentages(listOf(0.5, 0.3, 0.2)))
        assertEquals(100, MacActivityMath.percentages(listOf(0.333, 0.333, 0.334)).sum())
        assertTrue(MacActivityMath.percentages(emptyList()).isEmpty())
    }

    @Test
    fun rate() {
        // 3.6 J per hour at 11.5 V ≈ 0.087 mAh/h — the Mac's honest figures, no capacity → no %.
        val r = MacActivityMath.rate(mj = 3_600_000.0 / 60, cpuMs = 30_000, hours = 1.0, volts = 11.5, capacityMah = null)!!
        assertTrue(r.mahPerHour > 0)
        assertNull(r.percentPerHour)
        // With a battery it converts to percent of capacity too.
        val laptop = MacActivityMath.rate(60_000.0, 30_000, 1.0, 11.5, 5_000)!!
        assertNotNull(laptop.percentPerHour)
        // No time covered: nothing to report.
        assertNull(MacActivityMath.rate(60_000.0, 0, 0.0, null, null))
    }

    @Test
    fun cpuBuckets() {
        // Rows carry the CPU since the previous one; they land in their own buckets, in seconds
        // (a row on the `to` edge is out of the window, by `in from until to`).
        val rows = listOf(row(0, cpuMs = 0), row(15_000, cpuMs = 5_000), row(45_000, cpuMs = 7_000))
        val buckets = MacActivityMath.cpuBuckets(rows, 0, 60_000, 2)
        assertEquals(2, buckets.size)
        assertEquals(5.0, buckets[0].second, 0.001)
        assertEquals(7.0, buckets[1].second, 0.001)
    }
}
