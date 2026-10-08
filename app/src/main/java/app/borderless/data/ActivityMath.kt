package app.borderless.data

/** Calculations for the "App activity" statistics (pure, unit-tested). */
object ActivityMath {
    /** Two power samples further apart than this are not joined (the app was not running in between). */
    const val MAX_GAP_MS = 15 * 60_000L

    data class PowerSummary(
        /** Hours covered by samples. */
        val hours: Double,
        /** Battery drop per hour while not charging (whole phone), null if there is too little data. */
        val drainPerHour: Double?,
        val cpuSeconds: Double,
        val cpuPerHour: Double?,
        val avgMemoryMb: Double?,
        val maxMemoryMb: Double?,
        /** Shares of the covered time, 0..1. */
        val screenOff: Double,
        val metered: Double,
        val saver: Double,
        val charging: Double,
        /** Battery capacity as the phone reports it (median of samples), null if unknown. */
        val capacityMah: Int? = null,
        /** Average battery voltage, null if unknown. */
        val volts: Double? = null,
    )

    fun summarize(rows: List<StatsDb.PowerRow>): PowerSummary {
        var covered = 0L
        var drainMs = 0L
        var drained = 0.0
        var off = 0L
        var metered = 0L
        var saver = 0L
        var charging = 0L
        for ((a, b) in rows.zipWithNext()) {
            val gap = b.ts - a.ts
            if (gap <= 0 || gap > MAX_GAP_MS) continue
            covered += gap
            // The state at the start of an interval stands for the interval.
            if (!a.screen) off += gap
            if (a.metered) metered += gap
            if (a.saver) saver += gap
            if (a.charging) charging += gap
            if (!a.charging && !b.charging && a.battery != null && b.battery != null) {
                drainMs += gap
                drained += (a.battery - b.battery).coerceAtLeast(0)
            }
        }
        val hours = covered / 3_600_000.0
        val cpu = rows.drop(1).sumOf { it.cpuMs } / 1000.0
        val mem = rows.map { it.pssKb }.filter { it > 0 }
        fun share(ms: Long) = if (covered == 0L) 0.0 else ms.toDouble() / covered
        return PowerSummary(
            hours = hours,
            drainPerHour = if (drainMs >= 30 * 60_000L) drained / (drainMs / 3_600_000.0) else null,
            cpuSeconds = cpu,
            cpuPerHour = if (hours >= 0.25) cpu / hours else null,
            avgMemoryMb = mem.takeIf { it.isNotEmpty() }?.average()?.div(1024),
            maxMemoryMb = mem.maxOrNull()?.div(1024.0),
            screenOff = share(off), metered = share(metered), saver = share(saver), charging = share(charging),
            capacityMah = rows.mapNotNull { it.capacityMah }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] },
            volts = rows.mapNotNull { it.mv }.takeIf { it.isNotEmpty() }?.average()?.div(1000),
        )
    }

    /**
     * Hours the app ran (joined power samples, like [summarize]) that fall inside [intervals]: e.g. the time
     * spent in the battery saving mode. Each sample interval is cut to its overlap with every interval.
     */
    fun hoursIn(rows: List<StatsDb.PowerRow>, intervals: List<Pair<Long, Long>>): Double {
        if (intervals.isEmpty()) return 0.0
        var ms = 0L
        for ((a, b) in rows.zipWithNext()) {
            val gap = b.ts - a.ts
            if (gap <= 0 || gap > MAX_GAP_MS) continue
            for ((f, t) in intervals) ms += (minOf(b.ts, t) - maxOf(a.ts, f)).coerceAtLeast(0)
        }
        return ms / 3_600_000.0
    }

    /** Network actions that likely woke the radio on their own (see [Activity.Kind.wake]). */
    fun wakeups(totals: Map<String, StatsDb.ActivityTotal>): Long =
        Activity.Kind.entries.filter { it.wake }.sumOf { totals[it.name]?.n ?: 0L }

    /** Total count of the kinds in [group]. */
    fun total(totals: Map<String, StatsDb.ActivityTotal>, group: Activity.Group): Long =
        Activity.Kind.entries.filter { it.group == group }.sumOf { totals[it.name]?.n ?: 0L }

    /** One line of the estimated energy breakdown; [radioShare]: part of it spent on the radio (0..1). */
    data class Share(val kinds: List<Activity.Kind>, val mj: Long, val share: Double, val radioShare: Double = 0.0)

    /**
     * Estimated energy by part of the app, largest first: scans (with their server checks), pings,
     * hidden-server checks, other network actions, core starts and disk, the core with the app's screens.
     */
    fun energyShares(totals: Map<String, StatsDb.ActivityTotal>): List<Share> {
        val parts = listOf(
            listOf(Activity.Kind.SCAN_BACKGROUND), listOf(Activity.Kind.SCAN_OPEN), listOf(Activity.Kind.SCAN_MANUAL, Activity.Kind.SCAN_STRATEGY), listOf(Activity.Kind.SCAN_ENGINE),
            listOf(Activity.Kind.PING_RIDE), listOf(Activity.Kind.PING_PERIODIC),
            listOf(Activity.Kind.PING_STALL, Activity.Kind.PING_VERIFY, Activity.Kind.PING_FAILED),
            listOf(Activity.Kind.PROBE_RESCUE),
            listOf(Activity.Kind.SUB_UPDATE, Activity.Kind.GEO_UPDATE, Activity.Kind.COUNTRY, Activity.Kind.DOH, Activity.Kind.NET_CHECK),
            listOf(Activity.Kind.CORE_START, Activity.Kind.FILE_WRITE),
            // What collecting statistics and logs costs by itself.
            listOf(Activity.Kind.DB_COMMIT, Activity.Kind.STATS_SAMPLE, Activity.Kind.LOG_WRITE, Activity.Kind.TRACKING),
            listOf(Activity.Kind.CURRENT_METER),
            listOf(Activity.Kind.UI_BASE),
            listOf(Activity.Kind.CORE_BASE),
        )
        val mjs = parts.map { kinds -> kinds to kinds.sumOf { totals[it.name]?.mj ?: 0L } }
        val sum = mjs.sumOf { it.second }.coerceAtLeast(1)
        return mjs.filter { it.second > 0 }.map { (k, mj) ->
            // CPU energy follows from the CPU time; the rest of the estimate is the radio.
            val cpuMj = k.sumOf { totals[it.name]?.cpuMs ?: 0L } * Energy.CPU_W
            Share(k, mj, mj.toDouble() / sum, radioShare = ((mj - cpuMj) / mj).coerceIn(0.0, 1.0))
        }.sortedByDescending { it.mj }
    }

    /**
     * Whole percentages for [shares] (fractions summing to 1) that add up to exactly 100: each gets its
     * floor, the points left go to the largest remainders.
     */
    fun percentages(shares: List<Double>): List<Int> {
        if (shares.isEmpty()) return emptyList()
        val raw = shares.map { it * 100 }
        val base = raw.map { it.toInt() }.toMutableList()
        var left = 100 - base.sum()
        raw.indices.sortedByDescending { raw[it] - base[it] }.forEach { i -> if (left > 0) { base[i]++; left-- } }
        return base
    }

    /**
     * Battery use of the app as a rate: mAh per hour with its plausible range, and the same as % of
     * the battery per hour if the capacity is known. [plusMinus] is the absolute error to show.
     */
    data class Rate(val mahPerHour: Double, val mahLow: Double, val mahHigh: Double, val capacityMah: Int?) {
        val percentPerHour: Double? get() = capacityMah?.let { mahPerHour / it * 100 }
        val mahPlusMinus: Double get() = maxOf(mahHigh - mahPerHour, mahPerHour - mahLow)
        val percentPlusMinus: Double? get() = capacityMah?.let { mahPlusMinus / it * 100 }

        /** The same rate scaled by [f] (a forecast from another mode's rate). */
        fun scaled(f: Double) = Rate(mahPerHour * f, mahLow * f, mahHigh * f, capacityMah)
    }

    /**
     * [mj] (of which [cpuMs] of CPU time) spent over [hours] of the app running, at [volts], with a
     * battery of [capacityMah]. The range: CPU power between the phone's slowest and fastest cores,
     * radio power ± its spread, radio time (the tail after each exchange) 0.6…1.4 of the model.
     */
    fun rate(mj: Double, cpuMs: Long, hours: Double, volts: Double?, capacityMah: Int?, p: DeviceProfile.Info = DeviceProfile.info): Rate? {
        if (hours <= 0) return null
        val v = volts ?: Energy.VOLTAGE
        val cpuMj = cpuMs * p.cpu.mid
        val radioMj = (mj - cpuMj).coerceAtLeast(0.0)
        val radioLow = minOf(p.cell.low / p.cell.mid, p.wifi.low / p.wifi.mid) * 0.6
        val radioHigh = maxOf(p.cell.high / p.cell.mid, p.wifi.high / p.wifi.mid) * 1.4
        val low = cpuMs * p.cpu.low + radioMj * radioLow
        val high = cpuMs * p.cpu.high + radioMj * radioHigh
        fun perHour(x: Double) = Energy.mah(x, v) / hours
        // Capacity: measured by the phone (charge counter, reflects wear), else its design value from the power profile.
        return Rate(perHour(mj), perHour(minOf(low, mj)), perHour(maxOf(high, mj)), (capacityMah ?: p.capacityMah)?.takeIf { it > 0 })
    }

    /** CPU seconds per time bucket from power samples (each sample holds the CPU time since the previous one). */
    fun cpuBuckets(rows: List<StatsDb.PowerRow>, from: Long, to: Long, buckets: Int): List<Pair<Long, Double>> {
        if (to <= from) return emptyList()
        val width = maxOf(1L, (to - from) / buckets)
        return rows.drop(1).filter { it.ts in from until to }
            .groupBy { (it.ts - from) / width }
            .toSortedMap()
            .map { (b, list) -> (from + b * width + width / 2) to list.sumOf { it.cpuMs } / 1000.0 }
    }
}
