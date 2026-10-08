package app.borderless.data

import android.os.Debug
import android.os.Process
import app.borderless.R
import app.borderless.core.Power
import java.util.concurrent.ConcurrentHashMap

/**
 * What the app itself does that costs battery: its own network actions (pings, probes, scans,
 * subscription downloads, lookups), core restarts and disk writes, plus periodic power samples
 * (battery, CPU time, memory, screen / network / saver state). Counted in memory and written to
 * [StatsDb] every few minutes, shown on the "App activity" tab of the statistics.
 */
object Activity {
    /** [wake]: usually wakes the radio by itself (not riding along with the user's own traffic). */
    enum class Kind(val label: Int, val group: Group, val wake: Boolean = false) {
        PING_RIDE(R.string.act_ping_ride, Group.PING),
        PING_PERIODIC(R.string.act_ping_periodic, Group.PING, wake = true),
        PING_STALL(R.string.act_ping_stall, Group.PING),
        PING_VERIFY(R.string.act_ping_verify, Group.PING),
        PING_FAILED(R.string.act_ping_failed, Group.PING),
        SCAN_BACKGROUND(R.string.act_scan_background, Group.SCAN, wake = true),
        SCAN_OPEN(R.string.act_scan_open, Group.SCAN, wake = true),
        SCAN_MANUAL(R.string.act_scan_manual, Group.SCAN),
        SCAN_ENGINE(R.string.act_scan_engine, Group.SCAN),
        /** Fresh pings to choose a server by a strategy the user just picked. */
        SCAN_STRATEGY(R.string.act_scan_strategy, Group.SCAN),
        RESCAN_SKIPPED(R.string.act_rescan_skipped, Group.SAVED),
        PROBE(R.string.act_probe, Group.PROBE),
        PROBE_EXPLORE(R.string.act_probe_explore, Group.PROBE),
        PROBE_RESCUE(R.string.act_probe_rescue, Group.PROBE),
        SUB_UPDATE(R.string.act_sub_update, Group.OTHER, wake = true),
        COUNTRY(R.string.act_country, Group.OTHER),
        DOH(R.string.act_doh, Group.OTHER),
        NET_CHECK(R.string.act_net_check, Group.OTHER),
        /** Checking for new routing lists and downloading the changed categories. */
        GEO_UPDATE(R.string.act_geo_update, Group.OTHER, wake = true),
        CORE_START(R.string.act_core_start, Group.LOCAL),
        DB_COMMIT(R.string.act_db_commit, Group.LOCAL),
        FILE_WRITE(R.string.act_file_write, Group.LOCAL),
        STATS_SAMPLE(R.string.act_stats_sample, Group.LOCAL),
        LOG_WRITE(R.string.act_log_write, Group.LOCAL),
        CURRENT_METER(R.string.act_current_meter, Group.LOCAL),
        /** Measuring the CPU time of all the actions above (calibrated on the phone). */
        TRACKING(R.string.act_tracking, Group.LOCAL),
        /** The rest of the app's CPU time while its screens are closed: Xray processing the user's traffic. */
        CORE_BASE(R.string.act_core_base, Group.LOCAL),
        /** The rest of the app's CPU time while it is open: its screens (and the core meanwhile). */
        UI_BASE(R.string.act_ui_base, Group.LOCAL),
    }

    enum class Group { PING, SCAN, PROBE, OTHER, SAVED, LOCAL }

    /** Per kind: count, bytes, radio ms, CPU ms, estimated mJ. */
    private val counts = ConcurrentHashMap<Kind, LongArray>()

    private fun slot(kind: Kind) = counts.getOrPut(kind) { LongArray(5) }

    /** Counts [n] occurrences of [kind] (with [bytes] downloaded, if known). Cheap: memory only. */
    fun count(kind: Kind, n: Int = 1, bytes: Long = 0) {
        val c = slot(kind)
        synchronized(c) { c[0] += n.toLong(); c[1] += bytes }
    }

    /** Adds the estimated cost of one action (see [Energy]). */
    fun addCost(kind: Kind, radioMs: Long, cpuMs: Long, mj: Long) {
        val c = slot(kind)
        synchronized(c) { c[2] += radioMs; c[3] += cpuMs; c[4] += mj }
    }

    /** Estimated energy (mJ) counted in memory and not written yet. */
    fun pendingMj(): Long = counts.values.sumOf { c -> synchronized(c) { c[4] } }

    /** CPU time (ms) of tracked actions counted in memory and not written yet. */
    fun pendingCpuMs(): Long = counts.values.sumOf { c -> synchronized(c) { c[3] } }

    /** Writes the counts gathered so far (called with the heartbeat, before stats queries, on stop). */
    fun flush() {
        val now = System.currentTimeMillis()
        val rows = counts.keys.mapNotNull { k ->
            val c = counts[k] ?: return@mapNotNull null
            synchronized(c) {
                if (c.all { it == 0L }) null
                else StatsDb.ActivityTotal(k.name, c[0], c[1], c[2], c[3], c[4]).also { c.fill(0) }
            }
        }
        if (rows.isNotEmpty()) StatsDb.activity(now, rows)
    }

    // Time the app's screens were open since the last power sample.
    private var visibleMs = 0L
    private var visibleSince = 0L
    private var windowStart = System.currentTimeMillis()

    /** The app's screens are open now. */
    val uiVisible: Boolean get() = visibleSince != 0L

    /** When the screens were last opened or closed. */
    @Volatile
    var uiChangedAt = 0L
        private set

    /** Called by the activity on start / stop. */
    @Synchronized
    fun uiShown(visible: Boolean) {
        uiChangedAt = System.currentTimeMillis()
        val now = System.currentTimeMillis()
        if (visible && visibleSince == 0L) visibleSince = now
        if (!visible && visibleSince != 0L) { visibleMs += now - visibleSince; visibleSince = 0L }
    }

    /** Share of the time since the last sample the app was open (then resets the count). */
    @Synchronized
    private fun takeVisibleShare(now: Long): Double {
        val shown = visibleMs + if (visibleSince != 0L) now - visibleSince else 0L
        val window = (now - windowStart).coerceAtLeast(1)
        visibleMs = 0L
        if (visibleSince != 0L) visibleSince = now
        windowStart = now
        return (shown.toDouble() / window).coerceIn(0.0, 1.0)
    }

    private var lastCpuMs = Process.getElapsedCpuTime()
    private var lastSampleAt = 0L
    private var lastBaseAt = 0L
    private const val SAMPLE_MS = 4 * 60_000L

    /**
     * Records the power state now (at most every few minutes): battery level and charging, screen,
     * battery saver, metered network, CPU time used by the app since the last sample, memory.
     */
    fun samplePower(force: Boolean = false) {
        val now = System.currentTimeMillis()
        // Sparser in the battery saving mode (each sample reads memory use, which costs CPU).
        if (!force && now - lastSampleAt < (if (Repo.settings.value.ecoOn) EcoMath.SAMPLE_MS else SAMPLE_MS)) return
        lastSampleAt = now
        // Collecting statistics costs battery too: the sample itself is counted.
        Energy.track(Kind.STATS_SAMPLE) { takeSample(now) }
    }

    private fun takeSample(now: Long) {
        val cpu = Process.getElapsedCpuTime()
        val cpuDelta = (cpu - lastCpuMs).coerceAtLeast(0)
        lastCpuMs = cpu
        // CPU not spent in tracked actions: the core forwarding traffic, and the app's own screens.
        val tracking = Energy.takeTrackingCpuMs()
        addCost(Kind.TRACKING, radioMs = 0, cpuMs = tracking, mj = Energy.estimateMj(tracking, 0, false))
        val base = (cpuDelta - Energy.takeAttributedCpu() - tracking).coerceAtLeast(0)
        // What the process does outside actions per second: taken out of the actions running meanwhile.
        if (lastBaseAt > 0) Energy.background(base, now - lastBaseAt)
        lastBaseAt = now
        // Split by how long the app was open: that part is its screens, the rest the core at work.
        val ui = (base * takeVisibleShare(now)).toLong()
        addCost(Kind.UI_BASE, radioMs = 0, cpuMs = ui, mj = Energy.estimateMj(ui, 0, false))
        addCost(Kind.CORE_BASE, radioMs = 0, cpuMs = base - ui, mj = Energy.estimateMj(base - ui, 0, false))
        val pssKb = runCatching { Debug.getPss() }.getOrDefault(0L)
        StatsDb.power(
            StatsDb.PowerRow(
                ts = now, battery = Power.batteryPercent, charging = Power.charging, screen = Power.screenOn,
                saver = Power.powerSave, metered = Power.metered, cpuMs = cpuDelta, pssKb = pssKb,
                capacityMah = Power.capacityMah, mv = Power.voltageMv,
            )
        )
    }
}
