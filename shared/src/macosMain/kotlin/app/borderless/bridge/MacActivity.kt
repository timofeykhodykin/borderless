package app.borderless.bridge

import app.borderless.core.Eco
import app.borderless.data.EcoMath
import app.borderless.data.ProtocolCost
import app.borderless.data.StatsDb
import app.borderless.data.StatsMath
import app.borderless.data.Strategy
import app.borderless.data.WorkGroup
import app.borderless.data.WorkKind
import app.borderless.data.coreCostByClass
import app.borderless.data.coreCostWindows
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The `stats` target `"activity"` (ENERGY-DESIGN §6): what Android's `loadActivity` assembles for its
 * `ActivityTab`, shipped as one JSON the app decodes leniently. The overview's reply stays untouched —
 * this payload is only asked for while the activity tab is on.
 *
 * Raw fields (totals per kind, power rows, per-bucket action counts, core costs) mirror the Android
 * `ActivityData` members by name; the derived figures the tab needs ([MacActivityMath]'s work, the same
 * code Android's `ActivityMath` runs) ship computed — the app has no `WorkKind` enum to group by. Empty
 * tables yield a valid empty payload ([ActivityOut.totals] empty), the tab's `act_no_data` state.
 */
object MacActivity {

    /** Android's chart bucket count (BUCKETS in StatsScreen.kt). */
    private const val BUCKETS = 96

    private val json = Json { encodeDefaults = true }

    @Serializable
    private class TotalOut(val n: Long, val bytes: Long, val radioMs: Long, val cpuMs: Long, val mj: Long)

    /** A kind with measured readings: count, median measured mJ and mean modelled mJ ([StatsDb.MeasuredKind]). */
    @Serializable
    private class MeasuredOut(val kind: String, val count: Int, val measuredMj: Double, val modelMj: Double)

    /** Action counts of one [WorkGroup] in one time bucket (the network chart's series). */
    @Serializable
    private class BucketOut(val t: Long, val group: String, val n: Long)

    @Serializable
    private class PowerOut(
        val ts: Long, val battery: Int?, val charging: Boolean, val screen: Boolean,
        val saver: Boolean, val metered: Boolean, val cpuMs: Long, val pssKb: Long,
        val capMah: Int? = null, val mv: Int? = null,
    )

    @Serializable
    private class SummaryOut(
        val hours: Double, val drainPerHour: Double?, val cpuSeconds: Double, val cpuPerHour: Double?,
        val avgMemoryMb: Double?, val maxMemoryMb: Double?, val screenOff: Double, val metered: Double,
        val saver: Double, val charging: Double, val capacityMah: Int?, val volts: Double?,
        /** Hours of the covered time the battery saving mode was on ([MacActivityMath.hoursIn]). */
        val ecoHours: Double,
        /** The app's measured energy over the covered time, as average watts (null without energy). */
        val watts: Double?,
    )

    /** A connection-state stretch ([StatsMath.Segment]). */
    @Serializable
    private class SegmentOut(val start: Long, val end: Long, val phase: String, val server: String? = null)

    @Serializable
    private class IntervalOut(val start: Long, val end: Long)

    /** A strategy change ([Eco.label] resolves to a strings name the app renders). */
    @Serializable
    private class MarkerOut(val t: Long, val label: String, val edge: Boolean = false, val line: Boolean = true)

    @Serializable
    private class ProtocolOut(val bytes: Long, val cpuMs: Long, val windows: Int)

    @Serializable
    private class FactorOut(val factor: Double, val windows: Int)

    /** One energy-breakdown line ([MacActivityMath.Share]); [label] is a strings name (`act_part_*`). */
    @Serializable
    private class PartOut(val part: String, val label: String, val mj: Long, val share: Double, val radioShare: Double = 0.0)

    /** One bucket of the energy chart ([MacActivityMath.EnergyBucket]): mJ per [MacActivityMath.Part] name. */
    @Serializable
    private class EnergyOut(val t: Long, val coveredMs: Long, val mj: Map<String, Double>)

    @Serializable
    private class ActivityOut(
        val from: Long,
        val to: Long,
        /** Statistics exist only from here on ([StatsDb.firstTimestamp]). */
        val dataStart: Long,
        /** Per kind: count, bytes, radio ms (always 0 on the Mac), CPU ms and mJ. */
        val totals: Map<String, TotalOut> = emptyMap(),
        /** Per kind the median measured energy and the CPU model's, when the energy counter works. */
        val measured: List<MeasuredOut> = emptyList(),
        /** Grouped action counts over the period (the network actions tile and chart). */
        val groups: Map<String, Long> = emptyMap(),
        /** Pings that rode on the user's own traffic ([WorkKind.PING_RIDE]). */
        val pingsRide: Long = 0,
        /** Actions that would wake a radio on their own ([WorkKind.wake]); "woke the network" on the Mac. */
        val wakeups: Long = 0,
        /** Rescans the battery saving skipped ([WorkKind.RESCAN_SKIPPED]). */
        val skipped: Long = 0,
        /** (t, group, n) action counts per ~[BUCKETS] buckets. */
        val buckets: List<BucketOut> = emptyList(),
        val power: List<PowerOut> = emptyList(),
        val summary: SummaryOut,
        /** Connection states over the period (the charts' bands). */
        val segments: List<SegmentOut> = emptyList(),
        /** Battery-saving intervals (the charts' hatching) and strategy markers. */
        val eco: List<IntervalOut> = emptyList(),
        val markers: List<MarkerOut> = emptyList(),
        /** `core_cost` per protocol class and each class's relative factor (Android `ProtocolsCard`). */
        val protocols: Map<String, ProtocolOut> = emptyMap(),
        val protocolsRelative: Map<String, FactorOut> = emptyMap(),
        /** The energy breakdown's lines ([MacActivityMath.energyShares], biggest first). */
        val parts: List<PartOut> = emptyList(),
        /** Energy over time per part ([MacActivityMath.energyOverTime]). */
        val energy: List<EnergyOut> = emptyList(),
        /** Whether `ri_energy_nj` produced real readings this session (false: the mJ are the CPU model). */
        val measuredEnergy: Boolean = false,
    )

    /**
     * The activity counters' writes in [from, to) with their energy per kind, for the energy chart
     * (Android `StatsDb.activityFlushes`, keyed by [WorkKind] here).
     */
    private suspend fun activityFlushes(from: Long, to: Long): List<MacActivityMath.Flush> = StatsDb.read(emptyList()) {
        val known = WorkKind.entries.associateBy { it.name }
        rawQuery(
            "SELECT ts, kind, SUM(mj) FROM activity WHERE ts >= ? AND ts < ? GROUP BY ts, kind ORDER BY ts",
            arrayOf(from.toString(), to.toString()),
        ).use { c ->
            val out = ArrayList<MacActivityMath.Flush>()
            var ts = Long.MIN_VALUE
            var cur = HashMap<WorkKind, Long>()
            while (c.moveToNext()) {
                if (c.getLong(0) != ts) {
                    if (ts != Long.MIN_VALUE) out += MacActivityMath.Flush(ts, cur)
                    ts = c.getLong(0)
                    cur = HashMap()
                }
                known[c.getString(1).orEmpty()]?.let { k ->
                    c.getLong(2).takeIf { it > 0 }?.let { cur[k] = (cur[k] ?: 0L) + it }
                }
            }
            if (ts != Long.MIN_VALUE) out += MacActivityMath.Flush(ts, cur)
            out
        }
    }

    /**
     * The whole activity payload (called from `MacEngine.statsOut`, which already flushed the write
     * queue — the meter's own counters flush there too through `StatsDb.beforeFlush`).
     */
    suspend fun out(from: Long, to: Long): String {
        val totals = StatsDb.activityTotals(from, to)
        val measured = StatsDb.measuredKinds(from, to)
        val power = StatsDb.powerRows(from, to)
        val summary = MacActivityMath.summarize(power)
        val segments = StatsMath.segments(StatsDb.phases(from, to), from, to, to)
        val eco = EcoMath.onIntervals(StatsDb.settingsHistory(Eco.KEY, from, to), from, to)
        // The strategy history's real changes (the log repeats the snapshot at every start), as Android's
        // loadZones: the period's edge value first as a label-only marker, then each change a line.
        val markers = ArrayList<MarkerOut>()
        var last: String? = null
        for ((t, v) in StatsDb.settingsHistory("strategy", from, to)) {
            if (v == last) continue
            val first = last == null
            last = v
            val strategy = runCatching { Strategy.valueOf(v) }.getOrNull() ?: continue
            markers += MarkerOut(maxOf(t, from), Eco.label(strategy), edge = t <= from, line = !first)
        }
        val groupOf = WorkKind.entries.associate { it.name to it.group }
        val groups = totals.entries.groupBy({ groupOf[it.key] ?: WorkGroup.OTHER }, { it.value.n })
            .mapValues { it.value.sum() }
        val sumMj = totals.values.sumOf { it.mj }
        return json.encodeToString(ActivityOut(
            from = from,
            to = to,
            dataStart = StatsDb.firstTimestamp() ?: to,
            totals = totals.mapValues { (_, t) -> TotalOut(t.n, t.bytes, t.radioMs, t.cpuMs, t.mj) },
            measured = measured.map { MeasuredOut(it.kind, it.count, it.measuredMj, it.modelMj) },
            groups = groups.mapKeys { it.key.name },
            pingsRide = totals[WorkKind.PING_RIDE.name]?.n ?: 0,
            wakeups = MacActivityMath.wakeups(totals),
            skipped = totals[WorkKind.RESCAN_SKIPPED.name]?.n ?: 0,
            buckets = StatsDb.activityBuckets(from, to, BUCKETS).map { (t, kind, n) ->
                BucketOut(t, (groupOf[kind] ?: WorkGroup.OTHER).name, n)
            },
            power = power.map {
                PowerOut(it.ts, it.battery, it.charging, it.screen, it.saver, it.metered,
                    it.cpuMs, it.pssKb, it.capacityMah, it.mv)
            },
            summary = SummaryOut(
                hours = summary.hours, drainPerHour = summary.drainPerHour,
                cpuSeconds = summary.cpuSeconds, cpuPerHour = summary.cpuPerHour,
                avgMemoryMb = summary.avgMemoryMb, maxMemoryMb = summary.maxMemoryMb,
                screenOff = summary.screenOff, metered = summary.metered, saver = summary.saver,
                charging = summary.charging, capacityMah = summary.capacityMah, volts = summary.volts,
                ecoHours = MacActivityMath.hoursIn(power, eco),
                watts = if (summary.hours > 0 && sumMj > 0) sumMj / 1000.0 / (summary.hours * 3600) else null,
            ),
            segments = segments.map { SegmentOut(it.start, it.end, it.phase.name, it.server) },
            eco = eco.map { IntervalOut(it.first, it.second) },
            markers = markers,
            protocols = StatsDb.coreCostByClass(from, to).mapValues { (_, m) -> ProtocolOut(m.bytes, m.cpuMs, m.windows) },
            protocolsRelative = ProtocolCost.relative(StatsDb.coreCostWindows(from, to))
                .mapValues { (_, v) -> FactorOut(v.first, v.second) },
            parts = MacActivityMath.energyShares(totals)
                .map { PartOut(it.part.name, it.part.label, it.mj, it.share, it.radioShare) },
            // From a little earlier: the first write in view needs the one before it to bound its stretch.
            energy = MacActivityMath.energyOverTime(
                activityFlushes(from - MacActivityMath.MAX_GAP_MS, to), from, to, BUCKETS,
            ).map { EnergyOut(it.t, it.coveredMs, it.mj.mapKeys { (p, _) -> p.name }) },
            measuredEnergy = MacEnergy.energyMeasured,
        ))
    }
}
