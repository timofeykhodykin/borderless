package app.borderless.data

import kotlin.math.max
import kotlin.math.min

/** Pure decisions of the auto-visibility pool (kept free of Android so they can be unit-tested). */
object AutoPoolMath {
    /** Fewest servers kept in the pool (or all of them, if there are fewer). */
    const val MIN_POOL = 6
    const val MIN_TARGET = 3
    const val MAX_TARGET = 12
    /** A new member stays at least this long, whatever its rank. */
    const val MIN_STAY_MS = 30 * 60_000L
    /** Scans in a row with almost the whole pool answering before the target shrinks. */
    const val CALM_SCANS = 6
    /** Availability assumed for a server without any history. */
    const val UNKNOWN_P = 0.3

    /** What we expect from a server: [p] = chance it answers, [score] = order of preference. */
    data class Estimate(val p: Double, val score: Double)

    /**
     * Chance to answer from the last day (weight 0.6) and the last week (0.4), each smoothed so a few
     * samples don't decide; score = chance × speed (½ at 400 ms) × a bonus for answering in the last hour.
     */
    fun estimate(st: StatsDb.PoolStat?, now: Long): Estimate {
        if (st == null || st.longCount == 0) return Estimate(UNKNOWN_P, UNKNOWN_P * 0.5)
        val pLong = (st.longOk + 1.0) / (st.longCount + 2.0)
        val p = if (st.shortCount >= 3) 0.6 * (st.shortOk + 1.0) / (st.shortCount + 2.0) + 0.4 * pLong else pLong
        val speed = 1.0 / (1.0 + (st.avgMs ?: 2000.0) / 400.0)
        val recent = if (st.lastOk != null && now - st.lastOk < 3_600_000L) 1.3 else 1.0
        return Estimate(p, p * speed * recent)
    }

    /**
     * The new pool. Servers are ranked by score; the pool is the shortest top of that ranking whose
     * expected number of working servers (sum of chances) reaches [target], but at least [MIN_POOL].
     * To avoid flapping, current members stay while they rank within a margin below that line or
     * joined less than [MIN_STAY_MS] ago, and the server in use is never dropped.
     */
    fun choose(
        ranked: List<Pair<String, Estimate>>,
        previous: AutoState,
        current: String?,
        now: Long,
    ): Set<String> {
        if (ranked.isEmpty()) return emptySet()
        val minPool = min(MIN_POOL, ranked.size)
        var expected = 0.0
        var n = 0
        while (n < ranked.size && (n < minPool || expected < previous.target)) {
            expected += ranked[n].second.p
            n++
        }
        val keepZone = n + max(2, n / 3)
        return ranked.withIndex().filter { (i, e) ->
            val id = e.first
            i < n || id == current ||
                (id in previous.pool && (i < keepZone || now - (previous.joined[id] ?: 0L) < MIN_STAY_MS))
        }.map { it.value.first }.toSet()
    }

    /**
     * Adapts the target after a scan in which [answered] of the [poolSize] pool servers answered.
     * Few answers → expect more; almost all answering for several scans → expect less.
     * Scans where nothing at all answered are not passed here (offline, not a pool problem).
     */
    fun adapt(state: AutoState, answered: Int, poolSize: Int): AutoState = when {
        answered < 2 -> state.copy(target = min(state.target + 1, MAX_TARGET), calm = 0)
        answered >= state.target + 2 && answered >= poolSize * 0.8 -> {
            val calm = state.calm + 1
            if (calm >= CALM_SCANS) state.copy(target = max(state.target - 1, MIN_TARGET), calm = 0) else state.copy(calm = calm)
        }
        else -> state.copy(calm = 0)
    }

    /** Joined times for the new pool: new members get [now], dropped ones are forgotten. */
    fun joined(previous: AutoState, pool: Set<String>, now: Long): Map<String, Long> =
        pool.associateWith { previous.joined[it] ?: now }
}
