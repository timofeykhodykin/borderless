package app.borderless.data

import app.borderless.R

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

    /**
     * The lines of the battery breakdown as the user thinks of the app's work. The kinds stay separate in the
     * statistics (counted, exported, measured one by one); here the ones that are the same thing to the user are
     * added up.
     */
    enum class Part(val label: Int, val kinds: List<Activity.Kind>) {
        /** Keeping an eye on the server in use and on the internet. */
        CONNECTION(
            R.string.act_part_connection,
            listOf(
                Activity.Kind.PING_RIDE, Activity.Kind.PING_PERIODIC, Activity.Kind.PING_STALL, Activity.Kind.PING_VERIFY,
                Activity.Kind.PING_FAILED, Activity.Kind.PING_WAKE, Activity.Kind.NET_CHECK,
            ),
        ),
        /** Finding a working server when there is none (failover, retries, the hidden servers' rescue). */
        SEARCH(R.string.act_part_search, listOf(Activity.Kind.SCAN_ENGINE, Activity.Kind.PROBE_RESCUE)),
        /** Rescans for a faster server. */
        FASTER(
            R.string.act_part_faster,
            listOf(Activity.Kind.SCAN_BACKGROUND, Activity.Kind.PROBE_EXPLORE, Activity.Kind.PROBE, Activity.Kind.RESCAN_SKIPPED),
        ),
        /** Scans the user caused: opening the app, "Ping all", a new strategy. */
        ASKED(R.string.act_part_asked, listOf(Activity.Kind.SCAN_OPEN, Activity.Kind.SCAN_MANUAL, Activity.Kind.SCAN_STRATEGY)),
        SUBSCRIPTIONS(R.string.act_part_subs, listOf(Activity.Kind.SUB_UPDATE, Activity.Kind.SCAN_NEW)),
        LIMITED(R.string.act_part_limited, listOf(Activity.Kind.LIMITED_START)),
        TRAFFIC(R.string.act_core_base, listOf(Activity.Kind.CORE_BASE, Activity.Kind.CORE_START)),
        SCREENS(R.string.act_ui_base, listOf(Activity.Kind.UI_BASE)),
        /** The app's bookkeeping: statistics, logs, data files. */
        UPKEEP(
            R.string.act_part_stats,
            listOf(
                Activity.Kind.DB_COMMIT, Activity.Kind.STATS_SAMPLE, Activity.Kind.LOG_WRITE, Activity.Kind.CURRENT_METER,
                Activity.Kind.FILE_WRITE,
            ),
        ),
        /** Lines too small to matter, added up (see [energyShares]). */
        OTHER(R.string.act_part_other, emptyList());

        companion object {
            fun of(kind: Activity.Kind): Part = entries.firstOrNull { kind in it.kinds } ?: OTHER
        }
    }

    /**
     * Kinds the user sees one by one ("Cost of one action", "In detail"): discrete actions. The app's bookkeeping
     * and the continuous costs (the core at work, the screens, a limited-network server's running core) have no
     * meaningful count or cost per time.
     */
    fun isAction(kind: Activity.Kind): Boolean =
        Part.of(kind) !in setOf(Part.UPKEEP, Part.TRAFFIC, Part.SCREENS, Part.LIMITED)

    /** One line of the estimated energy breakdown; [radioShare]: part of it spent on the radio (0..1). */
    data class Share(val part: Part, val mj: Long, val share: Double, val radioShare: Double = 0.0)

    /** Lines under this share of the total are added up as [Part.OTHER]. */
    const val SMALL_SHARE = 0.01

    /**
     * Estimated energy by [Part], largest first; parts under [SMALL_SHARE] go into one [Part.OTHER] line (last) when
     * there are at least two of them. Every kind ends up in some line, so the shares sum to 1.
     */
    fun energyShares(totals: Map<String, StatsDb.ActivityTotal>): List<Share> {
        fun sumOf(kinds: List<Activity.Kind>, f: (StatsDb.ActivityTotal) -> Long) = kinds.sumOf { totals[it.name]?.let(f) ?: 0L }
        // Kinds of no part (none today, but a new one must not fall out of the sum) count as "other".
        val loose = Activity.Kind.entries.filter { k -> Part.entries.none { k in it.kinds } }
        val parts = Part.entries.map { p -> p to (if (p == Part.OTHER) loose else p.kinds) }
            .map { (p, kinds) -> Triple(p, sumOf(kinds) { it.mj }, sumOf(kinds) { it.cpuMs }) }
            .filter { it.second > 0 }
        val sum = parts.sumOf { it.second }.coerceAtLeast(1)
        val small = parts.filter { it.first == Part.OTHER || it.second.toDouble() / sum < SMALL_SHARE }
        val lines = if (small.size >= 2) parts - small.toSet() + Triple(Part.OTHER, small.sumOf { it.second }, small.sumOf { it.third }) else parts
        return lines.map { (p, mj, cpuMs) ->
            // CPU energy follows from the CPU time; the rest of the estimate is the radio.
            Share(p, mj, mj.toDouble() / sum, radioShare = ((mj - cpuMs * Energy.CPU_W) / mj).coerceIn(0.0, 1.0))
        }.sortedWith(compareBy<Share> { it.part == Part.OTHER }.thenByDescending { it.mj })
    }

    /** One write of the activity counters: when, and the energy (mJ) per kind since the previous write. */
    data class Flush(val ts: Long, val mj: Map<Activity.Kind, Long>)

    /** A time bucket of the energy chart: its middle, how long the app ran in it, the energy (mJ) per part. */
    data class EnergyBucket(val t: Long, val coveredMs: Long, val mj: Map<Part, Double>) {
        val total: Double get() = mj.values.sum()
    }

    /** What the first write in view stands for when the one before it isn't known: one heartbeat. */
    const val FIRST_FLUSH_MS = 5 * 60_000L

    /**
     * The energy chart's buckets: the counters are written every few minutes (and before stats queries), each write
     * holding what happened since the previous one, so its energy is spread evenly over that stretch (at most
     * [MAX_GAP_MS]: the app wasn't running before) and cut into [buckets] buckets of [from, to). Buckets the app
     * didn't run in are left out; [EnergyBucket.coveredMs] lets the caller turn energy into an average power while it
     * ran. Pure, tested.
     */
    fun energyOverTime(flushes: List<Flush>, from: Long, to: Long, buckets: Int): List<EnergyBucket> {
        if (to <= from || buckets <= 0) return emptyList()
        val width = maxOf(1L, (to - from) / buckets)
        val covered = LongArray(buckets)
        val mj = Array(buckets) { DoubleArray(Part.entries.size) }
        var prev: Long? = null
        for (f in flushes.sortedBy { it.ts }) {
            val start = maxOf(f.ts - MAX_GAP_MS, prev ?: (f.ts - FIRST_FLUSH_MS))
            prev = f.ts
            val span = f.ts - start
            if (span <= 0) continue
            val byPart = DoubleArray(Part.entries.size)
            f.mj.forEach { (k, v) -> byPart[Part.of(k).ordinal] += v.toDouble() }
            // Every bucket the stretch overlaps gets its share.
            var b = ((maxOf(start, from) - from) / width).toInt()
            while (b < buckets) {
                val bFrom = from + b * width
                val bTo = bFrom + width
                if (bFrom >= minOf(f.ts, to)) break
                val overlap = minOf(bTo, f.ts, to) - maxOf(bFrom, start, from)
                if (overlap > 0) {
                    covered[b] += overlap
                    for (i in byPart.indices) mj[b][i] += byPart[i] * overlap / span
                }
                b++
            }
        }
        return (0 until buckets).filter { covered[it] > 0 }.map { b ->
            EnergyBucket(
                from + b * width + width / 2, minOf(covered[b], width),
                Part.entries.filter { mj[b][it.ordinal] > 0 }.associateWith { mj[b][it.ordinal] },
            )
        }
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
