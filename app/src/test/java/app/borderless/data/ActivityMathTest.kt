package app.borderless.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityMathTest {
    private val min = 60_000L

    private fun row(t: Long, battery: Int, charging: Boolean = false, screen: Boolean = true, cpu: Long = 0) =
        StatsDb.PowerRow(t * min, battery, charging, screen, saver = false, metered = false, cpuMs = cpu, pssKb = 100 * 1024L)

    @Test
    fun hoursInIntervals() {
        // Samples every 5 minutes for 30 minutes, then a gap of an hour (not joined), then 10 more minutes.
        val rows = (0..6).map { row(it * 5L, 50) } + listOf(row(90, 50), row(100, 50))
        assertEquals(40 / 60.0, ActivityMath.hoursIn(rows, listOf(0L to 200 * min)), 1e-9)
        assertEquals(10 / 60.0 + 10 / 60.0, ActivityMath.hoursIn(rows, listOf(20 * min to 40 * min, 85 * min to 100 * min)), 1e-9)
        assertEquals(0.0, ActivityMath.hoursIn(rows, emptyList()), 1e-9)
    }

    @Test
    fun drainIsPerHourOnBatteryOnly() {
        // 1 h on battery losing 4 %, then 1 h charging (gained, ignored).
        val rows = (0..12).map { row(it * 5L, 80 - it * 4 / 12) } + (13..24).map { row(it * 5L, 76 + it - 12, charging = true) }
        val s = ActivityMath.summarize(rows)
        assertEquals(4.0, s.drainPerHour!!, 0.01)
        assertEquals(0.46, s.charging, 0.02)
    }

    @Test
    fun gapsAreNotCounted() {
        // Two samples three hours apart: the app was not running, nothing is known.
        val s = ActivityMath.summarize(listOf(row(0, 80), row(180, 60)))
        assertEquals(0.0, s.hours, 0.0)
        assertNull(s.drainPerHour)
    }

    @Test
    fun cpuAndScreenShares() {
        // 30 min with the screen off then 30 min on; 3 s of CPU per 5 min.
        val rows = (0..12).map { row(it * 5L, 80, screen = it >= 6, cpu = 3000) }
        val s = ActivityMath.summarize(rows)
        assertEquals(36.0, s.cpuSeconds, 0.01)
        assertEquals(36.0, s.cpuPerHour!!, 0.01)
        assertEquals(0.5, s.screenOff, 0.01)
    }

    @Test
    fun wakeupsCountOnlyStandaloneActions() {
        fun t(k: String, n: Long, mj: Long = 0) = k to StatsDb.ActivityTotal(k, n, 0, 0, 0, mj)
        val totals = mapOf(t("PING_RIDE", 50), t("PING_PERIODIC", 6), t("SCAN_BACKGROUND", 3), t("PROBE", 30))
        assertEquals(9L, ActivityMath.wakeups(totals))
        assertEquals(56L, ActivityMath.total(totals, Activity.Group.PING))
    }

    @Test
    fun energySharesAddUp() {
        fun t(k: String, mj: Long) = k to StatsDb.ActivityTotal(k, 1, 0, 0, 0, mj)
        val shares = ActivityMath.energyShares(mapOf(t("SCAN_BACKGROUND", 600), t("PING_RIDE", 100), t("CORE_BASE", 300)))
        assertEquals(listOf(600L, 300L, 100L), shares.map { it.mj })
        assertEquals(1.0, shares.sumOf { it.share }, 1e-9)
    }

    @Test
    fun radioTimeIsNotCountedTwice() {
        // A ping 2 s after another one on mobile data adds only 2 s of radio, not another 10 s tail.
        assertEquals(10_500L, Energy.radioAdded(onUntil = 0, start = 0, end = 500, tail = 10_000))
        assertEquals(2_000L, Energy.radioAdded(onUntil = 10_500, start = 2_000, end = 2_500, tail = 10_000))
        // Riding along with the user's traffic: nothing extra while the radio is up anyway.
        assertEquals(0L, Energy.radioAdded(onUntil = 30_000, start = 5_000, end = 5_400, tail = 10_000))
    }

    @Test
    fun wifiRadioIsPerExchange() {
        // A 634 ms ping on Wi-Fi keeps the radio busy for one exchange, not the whole time.
        assertEquals(Energy.WIFI_EXCHANGE_MS, Energy.wifiRadioMs(634, 1, 0))
        // A scan of 40 probes over 10 s: one exchange each.
        assertEquals(40 * Energy.WIFI_EXCHANGE_MS, Energy.wifiRadioMs(10_000, 40, 0))
        // Bytes add transfer time; never more than the action took (+ tail).
        assertEquals(Energy.WIFI_EXCHANGE_MS + 400, Energy.wifiRadioMs(5_000, 1, 1_000_000))
        assertEquals(200 + Energy.WIFI_TAIL_MS, Energy.wifiRadioMs(200, 50, 0))
    }

    @Test
    fun cpuIsSharedAmongConcurrentActions() {
        val share = CpuShare()
        // Nothing known yet: an equal split. A alone for 100 ms of CPU, then A and B together for 200, then B alone for 50.
        val a = share.open(cpu = 0, at = 0)
        val b = share.open(cpu = 100, at = 1_000)
        assertEquals(200L, share.close(a, cpu = 300, at = 2_000))
        assertEquals(150L, share.close(b, cpu = 350, at = 3_000))
    }

    @Test
    fun backgroundIsLearnedBetweenActions() {
        val share = CpuShare()
        share.background(cpuMs = 600, wallMs = 60_000) // from the power samples: 1 % of a core
        val a = share.open(cpu = 0, at = 0)
        // 10 s span, 300 ms CPU: 100 ms of it is the background.
        assertEquals(200L, share.close(a, cpu = 300, at = 10_000))
        // 40 s with nothing running at 20 % of a core (the screens): that is the background now.
        val b = share.open(cpu = 8_300, at = 50_000)
        assertEquals(0.2, share.backgroundRate, 1e-9)
        // A 10 s action during which the process used 2.5 s: 2 s were the background, all of it, not just half.
        assertEquals(500L, share.close(b, cpu = 10_800, at = 60_000))
    }

    @Test
    fun overlapsAreSplitByHowBusyEachKindIs() {
        val share = CpuShare()
        // Learned alone: a scan keeps the CPU half busy, a download waiting for the network hardly at all.
        val s1 = share.open(cpu = 0, at = 0, kind = "SCAN")
        share.close(s1, cpu = 5_000, at = 10_000)
        val d1 = share.open(cpu = 5_000, at = 10_000, kind = "SUB")
        share.close(d1, cpu = 5_200, at = 30_000)
        assertEquals(0.5, share.intensityOf("SCAN")!!, 1e-9)
        assertEquals(0.01, share.intensityOf("SUB")!!, 1e-9)
        // Together for 10 s with 5.1 s of CPU: nearly all of it is the scan's.
        val s2 = share.open(cpu = 5_200, at = 30_000, kind = "SCAN")
        val d2 = share.open(cpu = 5_200, at = 30_000, kind = "SUB")
        val scan = share.close(s2, cpu = 10_300, at = 40_000)
        val sub = share.close(d2, cpu = 10_300, at = 40_000)
        assertEquals(5_100L, scan + sub)
        assertEquals(100L, sub)
    }

    @Test
    fun measuredEnergyAboveBaseline() {
        // 300 mA above a 100 mA baseline for 4 s at 4 V = 1.2 W × 4 s = 4800 mJ.
        val samples = listOf(Triple(0L, 400_000L, 4000), Triple(2_000L, 400_000L, 4000))
        assertEquals(4800L, CurrentMeter.extraMj(samples, 100_000L, end = 4_000L))
    }

    @Test
    fun percentagesAlwaysAddUpTo100() {
        assertEquals(listOf(34, 33, 33), ActivityMath.percentages(listOf(1 / 3.0, 1 / 3.0, 1 / 3.0)))
        assertEquals(100, ActivityMath.percentages(listOf(0.666, 0.211, 0.083, 0.04)).sum())
        assertEquals(listOf(100), ActivityMath.percentages(listOf(1.0)))
    }

    @Test
    fun settingsLogWritesSnapshotThenOnlyChanges() {
        val a = mapOf("strategy" to "STABLE", "latencyCheckMin" to "5")
        assertEquals(a, SettingsLog.diff(null, a))
        assertEquals(mapOf("strategy" to "MANUAL"), SettingsLog.diff(a, a + ("strategy" to "MANUAL")))
        assertEquals(emptyMap<String, String>(), SettingsLog.diff(a, a))
    }

    @Test
    fun currentUnitIsLearnedFromTheDevice() {
        assertEquals(true, CurrentMeter.unitOf(1, 250_000))   // one µA reading is enough
        assertEquals(null, CurrentMeter.unitOf(5, 400))       // too early to say
        assertEquals(false, CurrentMeter.unitOf(30, 900))      // a minute of small numbers: mA
        assertEquals(null, CurrentMeter.unitOf(60, 12_000))    // ambiguous: keep the guess
        assertEquals(15_000L, CurrentMeter.normalize(-15_000, micro = true))
        assertEquals(15_000_000L, CurrentMeter.normalize(15_000, micro = false))
        assertEquals(400_000L, CurrentMeter.normalize(-400))   // unknown yet: small = mA
    }
}
