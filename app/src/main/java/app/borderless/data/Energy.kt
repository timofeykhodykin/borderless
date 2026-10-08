package app.borderless.data

import android.os.Process
import app.borderless.core.Power

/**
 * Estimated energy of the app's own actions (a software power model, like Android's own battery
 * statistics): energy = CPU seconds × CPU power + seconds the action kept the radio busy × radio power.
 *
 * CPU: the process's CPU time shared fairly among the actions running at once, without the core's and the
 * screens' own work meanwhile ([CpuShare]).
 *
 * Radio, mobile data: the action plus the radio's ~10 s "tail", and only the part not already covered by an
 * earlier action or by the user's own traffic — that radio time would have been spent anyway.
 *
 * Radio, Wi-Fi: the radio is busy only while packets actually go out and come in (it sleeps between them), so
 * the time is [WIFI_EXCHANGE_MS] per request exchange plus the transfer of the bytes, never more than the
 * action's duration. Charging the whole duration at the profile's rx/tx power overestimated a ping on Wi-Fi
 * about tenfold against the phone's measured current (a median 888 mJ modelled vs 82 mJ measured).
 *
 * Powers come from the phone's power profile ([DeviceProfile]); the shares between actions matter more than the
 * absolute numbers. Battery capacity and voltage for mAh / % come from the phone itself (Power). With current
 * measurement on ([CurrentMeter]) every network action is also measured for real.
 */
object Energy {
    /** One busy CPU core, from the phone's power profile ([DeviceProfile]). */
    val CPU_W: Double get() = DeviceProfile.info.cpu.mid
    /** Mobile radio while active. */
    val CELL_W: Double get() = DeviceProfile.info.cell.mid
    const val CELL_TAIL_MS = 10_000L
    /** Wi-Fi radio while active. */
    val WIFI_W: Double get() = DeviceProfile.info.wifi.mid
    const val WIFI_TAIL_MS = 300L
    /** Wi-Fi radio time of one request exchange (connection, TLS, request and answer of a ping or probe). */
    const val WIFI_EXCHANGE_MS = 60L
    /** Wi-Fi transfer speed for the byte part of the radio time (~20 Mbit/s, a modest real-world link). */
    const val WIFI_BYTES_PER_MS = 2_500L
    /** Nominal Li-ion voltage, used when the phone does not report its battery voltage. */
    const val VOLTAGE = 3.85

    /**
     * One tracked action. [exchanges] (request exchanges, e.g. probes of a scan) and [bytes] transferred size its
     * Wi-Fi radio time; set them before [end] when they are known only then.
     */
    class Span(val kind: Activity.Kind, val start: Long, internal val cpuId: Long, val metered: Boolean) {
        @Volatile var exchanges = 1
        @Volatile var bytes = 0L
    }

    private val cpuShare = CpuShare()

    /** Learns the CPU the process spends outside actions, from a power sample ([Activity]). */
    fun background(cpuMs: Long, wallMs: Long) = cpuShare.background(cpuMs, wallMs)

    /** The radio is in its high-power state (by the app or the user's traffic) until this time. */
    @Volatile
    private var radioOnUntil = 0L

    /** CPU time attributed to tracked actions since the last power sample (the rest is the core and UI). */
    @Volatile
    var attributedCpuMs = 0L
        private set

    /** CPU time attributed to tracked actions since the process started (never reset). */
    @Volatile
    var attributedTotalMs = 0L
        private set

    /** Tracked actions since the last power sample, and what tracking one costs (see [calibrate]). */
    private val spans = java.util.concurrent.atomic.AtomicLong()

    @Volatile
    var spanCostNs = 5_000L
        private set

    /**
     * Measures the bookkeeping itself: times many empty begin/end pairs (two CPU-time reads and the
     * in-memory sums) and keeps the cost of one. Takes a few milliseconds, once per process start.
     */
    fun calibrate() {
        val n = 2_000
        val sink = LongArray(5)
        repeat(200) { Process.getElapsedCpuTime() } // warm up
        val t0 = System.nanoTime()
        repeat(n) {
            val a = Process.getElapsedCpuTime()
            val b = Process.getElapsedCpuTime()
            synchronized(sink) { sink[3] += b - a; sink[0]++ }
        }
        spanCostNs = ((System.nanoTime() - t0) / n).coerceAtLeast(1)
        AppLog.debug("Energy", "bookkeeping costs ${spanCostNs / 1000.0} µs per tracked action")
    }

    /** CPU ms the bookkeeping of the actions since the last call took (counted as its own cost). */
    fun takeTrackingCpuMs(): Long = spans.getAndSet(0) * spanCostNs / 1_000_000

    fun begin(kind: Activity.Kind, exchanges: Int = 1): Span {
        val now = System.currentTimeMillis()
        return Span(kind, now, cpuShare.open(Process.getElapsedCpuTime(), now, kind.name), Power.metered).also { it.exchanges = exchanges }
    }

    fun end(span: Span) {
        val now = System.currentTimeMillis()
        val cpu = cpuShare.close(span.cpuId, Process.getElapsedCpuTime(), now)
        val radio = when {
            span.kind.group !in NETWORK -> 0L
            span.metered -> addRadio(span.start, now, metered = true)
            else -> wifiRadioMs(now - span.start, span.exchanges, span.bytes)
        }
        val mj = estimateMj(cpu, radio, span.metered)
        synchronized(this) { attributedCpuMs += cpu; attributedTotalMs += cpu }
        spans.incrementAndGet()
        Activity.addCost(span.kind, radioMs = radio, cpuMs = cpu, mj = mj)
        CurrentMeter.measure(span, now, mj)
    }

    /** Runs [block] as one tracked action of [kind]. */
    inline fun <T> track(kind: Activity.Kind, exchanges: Int = 1, block: () -> T): T {
        val span = begin(kind, exchanges)
        try {
            return block()
        } finally {
            end(span)
        }
    }

    /** The user's own traffic keeps the radio up: actions riding along cost no extra radio time. */
    fun userTraffic(now: Long = System.currentTimeMillis()) {
        val tail = if (Power.metered) CELL_TAIL_MS else WIFI_TAIL_MS
        synchronized(this) { radioOnUntil = maxOf(radioOnUntil, now + tail) }
    }

    /** Radio time added by an action from [start] to [end]: the part of [start, end + tail] not already on. */
    private fun addRadio(start: Long, end: Long, metered: Boolean): Long = synchronized(this) {
        val tail = if (metered) CELL_TAIL_MS else WIFI_TAIL_MS
        val added = radioAdded(radioOnUntil, start, end, tail)
        radioOnUntil = maxOf(radioOnUntil, end + tail)
        added
    }

    /** Takes the CPU time of tracked actions since the last call (for the "core and screens" remainder). */
    fun takeAttributedCpu(): Long = synchronized(this) { attributedCpuMs.also { attributedCpuMs = 0 } }

    fun estimateMj(cpuMs: Long, radioMs: Long, metered: Boolean): Long =
        (cpuMs * CPU_W + radioMs * (if (metered) CELL_W else WIFI_W)).toLong()

    private val NETWORK = setOf(Activity.Group.PING, Activity.Group.SCAN, Activity.Group.PROBE, Activity.Group.OTHER)

    /** Wi-Fi radio time of an action (unit-tested): per exchange plus the bytes, at most its duration + tail. */
    fun wifiRadioMs(durationMs: Long, exchanges: Int, bytes: Long): Long =
        minOf(durationMs.coerceAtLeast(0) + WIFI_TAIL_MS, exchanges.coerceAtLeast(1) * WIFI_EXCHANGE_MS + bytes.coerceAtLeast(0) / WIFI_BYTES_PER_MS)

    /** Pure part of [addRadio] (unit-tested): how much of [start, end + tail] lies after [onUntil]. */
    fun radioAdded(onUntil: Long, start: Long, end: Long, tail: Long): Long =
        (end + tail - maxOf(start, onUntil)).coerceAtLeast(0)

    /** The app's recent battery use: [minutes] of data in the current mode, or a [forecast] from the other mode. */
    data class Recent(val rate: ActivityMath.Rate, val minutes: Int, val forecast: Boolean = false)

    /**
     * The app's estimated battery use over the last [windowMs] (default an hour) in the mode it is in now
     * ([eco]: the battery saving mode): everything counted in the stats DB plus what is still in memory,
     * over the time the app ran in that mode. Right after the mode was switched (less than 10 minutes of
     * it in the window) it is a forecast: the other mode's rate scaled by the expected [saving] (0..1).
     * Null until there is enough data either way.
     */
    suspend fun recentRate(eco: Boolean, saving: Double?, windowMs: Long = 3_600_000L): Recent? {
        val now = System.currentTimeMillis()
        val from = now - windowMs
        val ecoIntervals = EcoMath.onIntervals(StatsDb.settingsHistory(EcoMath.KEY, from, now + 1), from, now)
        val normal = EcoMath.complement(ecoIntervals, from, now)
        val (same, other) = if (eco) ecoIntervals to normal else normal to ecoIntervals
        val rows = StatsDb.powerRows(from, now + 1)
        val summary = ActivityMath.summarize(rows)
        val capacity = app.borderless.core.Power.capacityMah ?: summary.capacityMah
        // The last sample stands for the time since it too (it is at most a few minutes old).
        val tail = rows.lastOrNull()?.let { (now - it.ts).coerceIn(0, ActivityMath.MAX_GAP_MS) } ?: 0L
        suspend fun rate(intervals: List<Pair<Long, Long>>, current: Boolean): Pair<ActivityMath.Rate?, Double> {
            var mj = 0.0
            var cpuMs = 0L
            for ((a, b) in intervals) StatsDb.activityTotals(a, b + 1).values.forEach { mj += it.mj; cpuMs += it.cpuMs }
            // Counted in memory, not written yet: it belongs to the mode in effect now.
            if (current) { mj += Activity.pendingMj(); cpuMs += Activity.pendingCpuMs() }
            val hours = ActivityMath.hoursIn(rows, intervals) +
                (if (current && intervals.lastOrNull()?.second == now) tail / 3_600_000.0 else 0.0)
            return (if (hours > 0) ActivityMath.rate(mj, cpuMs, hours, summary.volts, capacity) else null) to hours
        }
        val (mine, hours) = rate(same, current = true)
        if (mine != null && hours >= MIN_RATE_HOURS) return Recent(mine, (hours * 60).toInt())
        val (theirs, otherHours) = rate(other, current = false)
        if (theirs == null || otherHours < MIN_RATE_HOURS || saving == null) return null
        return Recent(theirs.scaled(if (eco) 1 - saving else 1 / (1 - saving).coerceAtLeast(0.05)), (otherHours * 60).toInt(), forecast = true)
    }

    private const val MIN_RATE_HOURS = 10 / 60.0

    /** Millijoules to mAh of the battery at [volts]. */
    fun mah(mj: Double, volts: Double = VOLTAGE): Double = mj / 1000.0 / volts / 3.6
}
