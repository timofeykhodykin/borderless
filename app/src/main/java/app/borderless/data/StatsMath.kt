package app.borderless.data

import app.borderless.core.Phase
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/** Pure calculations over [StatsDb] rows (no Android), so they are unit-testable. */
object StatsMath {

    data class Segment(val start: Long, val end: Long, val phase: Phase, val server: String?)

    /**
     * Turns phase rows into time segments clipped to [from, to). A row lasts until the next row,
     * but at most [StatsDb.HEARTBEAT_MS] × 1.5: rows are re-written every heartbeat while the tunnel
     * runs, so a longer silence means the process was gone and that time is not counted.
     */
    fun segments(rows: List<StatsDb.PhaseRow>, from: Long, to: Long, now: Long): List<Segment> {
        val cap = StatsDb.HEARTBEAT_MS * 3 / 2
        val out = ArrayList<Segment>()
        rows.forEachIndexed { i, r ->
            if (r.phase == Phase.OFF) return@forEachIndexed
            val next = rows.getOrNull(i + 1)?.ts ?: now
            val end = minOf(next, r.ts + cap, to)
            val start = maxOf(r.ts, from)
            if (end > start) {
                val last = out.lastOrNull()
                // Merge heartbeat repeats of the same state into one segment.
                if (last != null && last.phase == r.phase && last.server == r.server && last.end == start) out[out.lastIndex] = last.copy(end = end)
                else out += Segment(start, end, r.phase, r.server)
            }
        }
        return out
    }

    fun durations(segments: List<Segment>): Map<Phase, Long> =
        segments.groupBy { it.phase }.mapValues { (_, s) -> s.sumOf { it.end - it.start } }

    /** Time each server carried traffic (CONNECTED segments). */
    fun usage(segments: List<Segment>): Map<String, Long> =
        segments.filter { it.phase == Phase.CONNECTED && it.server != null }
            .groupBy { it.server!! }.mapValues { (_, s) -> s.sumOf { it.end - it.start } }

    data class Summary(
        val count: Int,
        val ok: Int,
        val median: Int?,
        val p95: Int?,
        val min: Int?,
        val max: Int?,
        val mean: Int?,
        /** Mean absolute difference between consecutive successful samples. */
        val jitter: Int?,
    ) {
        val availability: Double? get() = if (count == 0) null else ok.toDouble() / count
    }

    fun summarize(samples: List<StatsDb.Sample>): Summary {
        val values = samples.mapNotNull { it.ms }
        val sorted = values.sorted()
        val jitter = if (values.size < 2) null else values.zipWithNext { a, b -> abs(a - b) }.average().toInt()
        return Summary(
            count = samples.size,
            ok = values.size,
            median = percentile(sorted, 0.5),
            p95 = percentile(sorted, 0.95),
            min = sorted.firstOrNull(),
            max = sorted.lastOrNull(),
            mean = if (values.isEmpty()) null else values.average().toInt(),
            jitter = jitter,
        )
    }

    fun percentile(sorted: List<Int>, p: Double): Int? {
        if (sorted.isEmpty()) return null
        val idx = (ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    fun stddev(values: List<Int>): Double? {
        if (values.size < 2) return null
        val m = values.average()
        return sqrt(values.sumOf { (it - m) * (it - m) } / (values.size - 1))
    }

    /** A chart point: median latency of a time bucket, and how many samples in it failed. */
    data class Point(val t: Long, val ms: Int?, val failed: Int, val total: Int)

    /** Groups samples into [buckets] equal time slices; empty slices are omitted. */
    fun bucket(samples: List<StatsDb.Sample>, from: Long, to: Long, buckets: Int): List<Point> {
        if (samples.isEmpty() || to <= from) return emptyList()
        val width = maxOf(1L, (to - from) / buckets)
        return samples.groupBy { ((it.ts - from) / width).coerceIn(0, buckets.toLong() - 1) }
            .toSortedMap()
            .map { (b, list) ->
                val ok = list.mapNotNull { it.ms }.sorted()
                Point(from + b * width + width / 2, percentile(ok, 0.5), list.size - ok.size, list.size)
            }
    }

    /** Latency histogram bucket edges used on the server screen. */
    val histogramEdges = listOf(0, 50, 100, 200, 400, 800, 1600, Int.MAX_VALUE)

    fun histogram(samples: List<StatsDb.Sample>): List<Int> {
        val counts = IntArray(histogramEdges.size - 1)
        samples.forEach { s ->
            val ms = s.ms ?: return@forEach
            val i = histogramEdges.indexOfLast { ms >= it }.coerceIn(0, counts.lastIndex)
            counts[i]++
        }
        return counts.toList()
    }

    /**
     * How good a server was: smoothed share of successful probes × a speed factor
     * (1 at 0 ms, ½ at 400 ms; no answers count as 2 s). Used for scan order and chart defaults.
     */
    fun score(count: Int, ok: Int, avgMs: Double?): Double =
        (ok + 1.0) / (count + 2.0) / (1.0 + (avgMs ?: 2000.0) / 400.0)

    /** Median of [values] (0 for none); the middle two averaged for an even count. */
    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val v = values.sorted()
        return if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2
    }
}
