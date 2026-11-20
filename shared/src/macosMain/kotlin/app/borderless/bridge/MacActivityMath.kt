package app.borderless.bridge

import app.borderless.S
import app.borderless.data.StatsDb
import app.borderless.data.WorkGroup
import app.borderless.data.WorkKind

/**
 * The "App activity" statistics' math for the Mac (ENERGY-DESIGN §4): a port of Android's
 * `ActivityMath` (the documented fallback — the proper move of `ActivityMath` to commonMain is a
 * SHARED-CORE seam, since it would change Android files). Same shapes, three deliberate changes:
 *
 * - `Part.label` is a strings **name** (`S.*`), not an `R.string` id — the service ships the key and
 *   the app resolves it through `L()`, as `MacBridge`'s `LText` texts already work;
 * - the model constants (`Energy.CPU_W`, `Energy.mah`, `Energy.VOLTAGE`, `DeviceProfile`) are
 *   parameters with Mac values — the Mac's mJ are measured, and its battery voltage comes from
 *   `AppleSmartBattery` when there is one;
 * - `radio_ms` is 0 in every Mac row, so `radioShare` and `rate`'s radio spread are still computed
 *   but reduce to the CPU term on real data.
 */
object MacActivityMath {

    /** Two power samples further apart than this are not joined (the service was not running in between). */
    const val MAX_GAP_MS = 15 * 60_000L

    /** What the first flush in view stands for when the one before it isn't known: one heartbeat. */
    const val FIRST_FLUSH_MS = 5 * 60_000L

    /** Battery voltage assumed when `AppleSmartBattery` reports none (a desktop, or a missing key). */
    const val FALLBACK_VOLTS = 11.9

    data class PowerSummary(
        /** Hours covered by samples. */
        val hours: Double,
        /** Battery drop per hour while not charging, null if there is too little data. */
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
        /** Battery capacity as the Mac reports it (median of samples), null if unknown. */
        val capacityMah: Int? = null,
        /** Average battery voltage, null if unknown. */
        val volts: Double? = null,
    )

    /** Android `ActivityMath.summarize`, unchanged. */
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
            val (from, to) = a.battery to b.battery
            if (!a.charging && !b.charging && from != null && to != null) {
                drainMs += gap
                drained += (from - to).coerceAtLeast(0)
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

    /** Android `ActivityMath.hoursIn`, unchanged. */
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

    /** Actions that would wake the radio on their own on Android ([WorkKind.wake]); counted the same
     *  on the Mac — they are the "woke the network" actions, even if there is no radio to wake. */
    fun wakeups(totals: Map<String, StatsDb.ActivityTotal>): Long =
        WorkKind.entries.filter { it.wake }.sumOf { totals[it.name]?.n ?: 0L }

    /** Total count of the kinds in [group]. */
    fun total(totals: Map<String, StatsDb.ActivityTotal>, group: WorkGroup): Long =
        WorkKind.entries.filter { it.group == group }.sumOf { totals[it.name]?.n ?: 0L }

    /**
     * The lines of the battery breakdown as the user thinks of the app's work (Android `ActivityMath.Part`):
     * same grouping, [label] a strings name the app resolves (`L()`), not an `R.string` id.
     */
    enum class Part(val label: String, val kinds: List<WorkKind>) {
        /** Keeping an eye on the server in use and on the internet. */
        CONNECTION(
            S.act_part_connection,
            listOf(
                WorkKind.PING_RIDE, WorkKind.PING_PERIODIC, WorkKind.PING_STALL, WorkKind.PING_VERIFY,
                WorkKind.PING_FAILED, WorkKind.PING_WAKE, WorkKind.NET_CHECK,
            ),
        ),
        /** Finding a working server when there is none (failover, retries, the hidden servers' rescue). */
        SEARCH(S.act_part_search, listOf(WorkKind.SCAN_ENGINE, WorkKind.PROBE_RESCUE)),
        /** Rescans for a faster server. */
        FASTER(
            S.act_part_faster,
            listOf(WorkKind.SCAN_BACKGROUND, WorkKind.PROBE_EXPLORE, WorkKind.PROBE, WorkKind.RESCAN_SKIPPED),
        ),
        /** Scans the user caused: opening the app, "Ping all", a new strategy. */
        ASKED(S.act_part_asked, listOf(WorkKind.SCAN_OPEN, WorkKind.SCAN_MANUAL, WorkKind.SCAN_STRATEGY)),
        SUBSCRIPTIONS(S.act_part_subs, listOf(WorkKind.SUB_UPDATE, WorkKind.SCAN_NEW)),
        LIMITED(S.act_part_limited, listOf(WorkKind.LIMITED_START)),
        TRAFFIC(S.act_core_base, listOf(WorkKind.CORE_BASE, WorkKind.CORE_START)),
        SCREENS(S.act_ui_base, listOf(WorkKind.UI_BASE)),
        /** The app's bookkeeping: statistics, logs, data files. */
        UPKEEP(
            S.act_part_stats,
            listOf(
                WorkKind.DB_COMMIT, WorkKind.STATS_SAMPLE, WorkKind.LOG_WRITE, WorkKind.CURRENT_METER,
                WorkKind.FILE_WRITE,
            ),
        ),
        /** Lines too small to matter, added up (see [energyShares]). */
        OTHER(S.act_part_other, emptyList());

        companion object {
            fun of(kind: WorkKind): Part = entries.firstOrNull { kind in it.kinds } ?: OTHER
        }
    }

    /** Android `ActivityMath.isAction`: discrete actions shown one by one. */
    fun isAction(kind: WorkKind): Boolean =
        Part.of(kind) !in setOf(Part.UPKEEP, Part.TRAFFIC, Part.SCREENS, Part.LIMITED)

    /** One line of the energy breakdown; [radioShare]: part of it spent on the radio (0 on Mac). */
    data class Share(val part: Part, val mj: Long, val share: Double, val radioShare: Double = 0.0)

    /** Lines under this share of the total are added up as [Part.OTHER]. */
    const val SMALL_SHARE = 0.01

    /** Android `ActivityMath.energyShares`, with the CPU power parameterised ([cpuW] = mJ per CPU-ms). */
    fun energyShares(
        totals: Map<String, StatsDb.ActivityTotal>,
        cpuW: Double = MacEnergyMath.GENERIC_CPU_W,
    ): List<Share> {
        fun sumOf(kinds: List<WorkKind>, f: (StatsDb.ActivityTotal) -> Long) = kinds.sumOf { totals[it.name]?.let(f) ?: 0L }
        val loose = WorkKind.entries.filter { k -> Part.entries.none { k in it.kinds } }
        val parts = Part.entries.map { p -> p to (if (p == Part.OTHER) loose else p.kinds) }
            .map { (p, kinds) -> Triple(p, sumOf(kinds) { it.mj }, sumOf(kinds) { it.cpuMs }) }
            .filter { it.second > 0 }
        val sum = parts.sumOf { it.second }.coerceAtLeast(1)
        val small = parts.filter { it.first == Part.OTHER || it.second.toDouble() / sum < SMALL_SHARE }
        val lines = if (small.size >= 2) parts - small.toSet() + Triple(Part.OTHER, small.sumOf { it.second }, small.sumOf { it.third }) else parts
        return lines.map { (p, mj, cpuMs) ->
            Share(p, mj, mj.toDouble() / sum, radioShare = ((mj - cpuMs * cpuW) / mj).coerceIn(0.0, 1.0))
        }.sortedWith(compareBy<Share> { it.part == Part.OTHER }.thenByDescending { it.mj })
    }

    /** One write of the activity counters: when, and the energy (mJ) per kind since the previous write. */
    data class Flush(val ts: Long, val mj: Map<WorkKind, Long>)

    /** A time bucket of the energy chart: its middle, how long the app ran in it, the energy (mJ) per part. */
    data class EnergyBucket(val t: Long, val coveredMs: Long, val mj: Map<Part, Double>) {
        val total: Double get() = mj.values.sum()
    }

    /** Android `ActivityMath.energyOverTime`, unchanged. */
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

    /** Android `ActivityMath.percentages`, unchanged. */
    fun percentages(shares: List<Double>): List<Int> {
        if (shares.isEmpty()) return emptyList()
        val raw = shares.map { it * 100 }
        val base = raw.map { it.toInt() }.toMutableList()
        var left = 100 - base.sum()
        raw.indices.sortedByDescending { raw[it] - base[it] }.forEach { i -> if (left > 0) { base[i]++; left-- } }
        return base
    }

    /**
     * Battery use of the app as a rate (Android `ActivityMath.Rate`): mAh per hour with its plausible
     * range, and the same as % of the battery per hour if the capacity is known.
     */
    data class Rate(val mahPerHour: Double, val mahLow: Double, val mahHigh: Double, val capacityMah: Int?) {
        val percentPerHour: Double? get() = capacityMah?.let { mahPerHour / it * 100 }
        val mahPlusMinus: Double get() = maxOf(mahHigh - mahPerHour, mahPerHour - mahLow)
        val percentPlusMinus: Double? get() = capacityMah?.let { mahPlusMinus / it * 100 }
        fun scaled(f: Double) = Rate(mahPerHour * f, mahLow * f, mahHigh * f, capacityMah)
    }

    /** The CPU power band a Mac's core may draw (low/mid/high mJ per CPU-ms) — the device-profile
     *  spread's Mac stand-in; the mid is the display model's value. */
    val CPU_BAND = Triple(1.0, MacEnergyMath.GENERIC_CPU_W, 8.0)

    /** The radio-time model's ±spread kept generic (no phone power profile): 0.6…1.4 of the model. */
    private const val RADIO_LOW = 0.6
    private const val RADIO_HIGH = 1.4

    /**
     * Android `ActivityMath.rate`: [mj] (of which [cpuMs] of CPU time) spent over [hours] of the app
     * running, at [volts], with a battery of [capacityMah]. On the Mac the mJ are measured, so the
     * range mostly says how the CPU-band and the (unused) radio terms may mis-share it.
     */
    fun rate(
        mj: Double, cpuMs: Long, hours: Double, volts: Double?, capacityMah: Int?,
        cpuBand: Triple<Double, Double, Double> = CPU_BAND,
    ): Rate? {
        if (hours <= 0) return null
        val v = volts ?: FALLBACK_VOLTS
        val cpuMj = cpuMs * cpuBand.second
        val radioMj = (mj - cpuMj).coerceAtLeast(0.0)
        val low = cpuMs * cpuBand.first + radioMj * RADIO_LOW
        val high = cpuMs * cpuBand.third + radioMj * RADIO_HIGH
        fun perHour(x: Double) = mah(x, v) / hours
        return Rate(perHour(mj), perHour(minOf(low, mj)), perHour(maxOf(high, mj)), capacityMah?.takeIf { it > 0 })
    }

    /** Millijoules to mAh of the battery at [volts]. */
    fun mah(mj: Double, volts: Double): Double = mj / 1000.0 / volts / 3.6

    /** CPU seconds per time bucket from power samples (each sample holds the CPU time since the previous one). */
    fun cpuBuckets(rows: List<StatsDb.PowerRow>, from: Long, to: Long, buckets: Int): List<Pair<Long, Double>> {
        if (to <= from) return emptyList()
        val width = maxOf(1L, (to - from) / buckets)
        return rows.drop(1).filter { it.ts in from until to }
            .groupBy { (it.ts - from) / width }
            .entries.sortedBy { it.key }
            .map { e -> (from + e.key * width + width / 2) to e.value.sumOf { it.cpuMs } / 1000.0 }
    }
}
