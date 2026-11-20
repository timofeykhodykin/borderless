package app.borderless.ui.stats

import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * Y axis. Zooming works in "units": ln(value) for a log scale, the value itself for a linear one,
 * so a pinch on the latency chart zooms evenly in log space. [min]..[max] is the full (zoomed-out) range.
 */
class YScale(
    val min: Double,
    val max: Double,
    val log: Boolean,
    val integer: Boolean = false,
    /** Labels show values divided by this (e.g. 1000 to label milliseconds in seconds). */
    val divide: Double = 1.0,
) {
    fun unit(v: Double): Double = v.coerceIn(min, max).let { if (log) ln(it) else it }
    fun value(u: Double): Double = if (log) exp(u) else u
    val uMin get() = unit(min)
    val uMax get() = unit(max)

    /** Smallest visible span in units: about ±12 % around a latency, a tenth of a linear axis. */
    val minSpan get() = if (log) ln(1.25) else maxOf((max - min) / 10, if (integer) 2.0 else 0.0)

    /** Tick values for the visible units [u0]..[u1]. */
    fun ticks(u0: Double, u1: Double): List<Double> {
        val lo = value(u0)
        val hi = value(u1)
        if (log && hi / lo >= 4) return logTicks(lo, hi)
        return niceTicks(lo, hi, integer)
    }

    fun label(raw: Double): String {
        val v = raw / divide
        return if (divide != 1.0 && abs(v) < 10 && abs(v - Math.round(v)) > 1e-6) "%.2f".format(v).trimEnd('0').trimEnd('.', ',') else plainLabel(v)
    }

    /** Short enough for the narrow label column whatever the amount: "950", "1.5k", "12k", "3.2M". */
    private fun plainLabel(v: Double): String = when {
        abs(v) >= 1_000_000 -> (v / 1_000_000).let { if (abs(it - Math.round(it)) < 1e-6) "${Math.round(it)}M" else "%.1fM".format(it) }
        abs(v) >= 1000 -> (v / 1000).let { if (abs(it - Math.round(it)) < 1e-6) "${Math.round(it)}k" else "%.1fk".format(it) }
        abs(v - Math.round(v)) < 1e-6 -> Math.round(v).toString()
        else -> "%.1f".format(v)
    }

    companion object {
        /** Latency, logarithmic like the app's colour scale: 20 ms … 3 s. */
        fun latency() = YScale(20.0, 3000.0, log = true)

        fun linear(max: Double, integer: Boolean = true, divide: Double = 1.0) =
            YScale(0.0, if (max <= 0) 1.0 else max, log = false, integer = integer, divide = divide)
    }
}

/** 1-2-5-style ticks per decade, the densest set that still leaves at most five ticks. */
internal fun logTicks(lo: Double, hi: Double): List<Double> {
    val sets = listOf(listOf(1.0), listOf(1.0, 3.0), listOf(1.0, 2.0, 5.0), listOf(1.0, 1.5, 2.0, 3.0, 5.0))
    var best = emptyList<Double>()
    for (set in sets) {
        val ticks = ArrayList<Double>()
        var p = 10.0.pow(floor(log10(lo)))
        while (p <= hi) {
            set.forEach { m -> val v = m * p; if (v >= lo - 1e-9 && v <= hi + 1e-9) ticks += v }
            p *= 10
        }
        if (ticks.size > 5) break
        best = ticks
    }
    return best
}

/** Linear ticks with a 1/2/2.5/5 × 10ⁿ step, about four to six of them. */
internal fun niceTicks(lo: Double, hi: Double, integer: Boolean): List<Double> {
    val span = hi - lo
    if (span <= 0) return listOf(lo)
    val raw = span / 5
    val mag = 10.0.pow(floor(log10(raw)))
    var step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
    if (integer) step = maxOf(1.0, Math.round(step).toDouble())
    val out = ArrayList<Double>()
    var v = ceil(lo / step - 1e-9) * step
    while (v <= hi + 1e-9) { out += v; v += step }
    return out
}

private const val MIN = 60_000L
private const val HOUR = 60 * MIN
private const val DAY = 24 * HOUR

private val timeSteps = listOf(
    MIN, 2 * MIN, 5 * MIN, 10 * MIN, 15 * MIN, 30 * MIN,
    HOUR, 2 * HOUR, 3 * HOUR, 6 * HOUR, 12 * HOUR,
    DAY, 2 * DAY, 7 * DAY, 14 * DAY, 30 * DAY, 91 * DAY, 182 * DAY, 365 * DAY,
)

/**
 * Time ticks for the visible window: the smallest round step that gives at most [max] ticks,
 * aligned to local clock time (whole hours, local midnight). Returns the step and the ticks.
 */
internal fun timeTicks(from: Long, to: Long, max: Int = 5, zone: TimeZone = TimeZone.getDefault()): Pair<Long, List<Long>> {
    val span = (to - from).coerceAtLeast(1)
    val step = timeSteps.firstOrNull { span / it <= max - 1 } ?: timeSteps.last()
    val offset = zone.getOffset(from).toLong()
    val out = ArrayList<Long>()
    var t = Math.floorDiv(from + offset + step - 1, step) * step - offset
    while (t <= to) { out += t; t += step }
    return step to out
}
