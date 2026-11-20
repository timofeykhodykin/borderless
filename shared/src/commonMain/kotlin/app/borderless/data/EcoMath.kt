package app.borderless.data

import kotlin.math.min

/**
 * Pure part of the battery saving mode ([app.borderless.core.Eco]), unit-tested: how each kind of
 * work is thinned out, how the strategy is swapped and restored, and how much the mode saves.
 *
 * What the mode changes (and why it is safe):
 * - traffic counters are looked at half as often; the current server is pinged 3× less often, and
 *   pings riding along with the user's traffic 4× less often (failures are still noticed: stuck
 *   traffic triggers a check at once);
 * - automatic strategies look for a faster server 3× less often, only with the screen on and only
 *   while traffic flows (the radio is awake anyway, and a faster server matters only then), probe
 *   only the servers that could be faster ([TARGETED_MAX] at most) and need a bigger gain to move;
 * - a failover scan stops as soon as a server is chosen instead of finishing the whole list;
 * - with no working server, retries keep their usual pace: a working connection comes first;
 * - no hidden servers are explored; subscriptions update half as often and not on mobile data
 *   (unless long overdue); opening the app rescans only an older map;
 * - statistics are written once a minute instead of every 10 s, power samples every 10 min;
 * - the main screen's breathing glow stands still.
 */
object EcoMath {
    const val TICK_FACTOR = 2
    const val LATENCY_FACTOR = 3
    const val RIDE_FACTOR = 4
    const val RESCAN_FACTOR = 3
    const val OPEN_SCAN_FACTOR = 3
    const val SUB_FACTOR = 2
    /** At most this many servers are probed by a background rescan. */
    const val TARGETED_MAX = 8
    const val STATS_BATCH_MS = 60_000L
    const val SAMPLE_MS = 10 * 60_000L

    /** Switching rules in the mode: rarer rescans, a bigger gain needed, longer stays (each switch restarts the core). */
    fun policy(p: SwitchPolicy): SwitchPolicy = p.copy(
        rescanMin = p.rescanMin * RESCAN_FACTOR,
        gainPercent = (p.gainPercent + 10).coerceAtMost(90),
        gainMs = p.gainMs * 3 / 2,
        minStayMin = maxOf(p.minStayMin * 3, 3),
    )

    /** The most frugal strategy that still keeps the tunnel working: optimising ones become "only on failure". */
    fun frugal(s: Strategy): Strategy = if (s == Strategy.STABLE || s == Strategy.FASTEST) Strategy.FAILOVER else s

    /**
     * Turns the mode on or off. With [AppSettings.ecoStrategy] the strategy goes to [frugal] (the old
     * one is kept) and comes back when the mode ends, unless the user chose another one meanwhile.
     */
    fun switch(s: AppSettings, on: Boolean, bySaver: Boolean): AppSettings {
        // Already there: keep who turned it on (a saver starting must not take over the user's choice).
        if (on == s.ecoOn) return s
        return if (on) {
            val frugal = frugal(s.strategy)
            val swap = s.ecoStrategy && frugal != s.strategy
            s.copy(
                ecoOn = true, ecoBySaver = bySaver,
                strategy = if (swap) frugal else s.strategy,
                strategyBeforeEco = if (swap) s.strategy else null,
            )
        } else {
            val back = s.strategyBeforeEco?.takeIf { s.strategy == frugal(it) }
            s.copy(ecoOn = false, ecoBySaver = false, strategy = back ?: s.strategy, strategyBeforeEco = null)
        }
    }

    /** The strategy the tunnel starts with (the default one), made frugal if the mode is on. */
    fun startStrategy(s: AppSettings): AppSettings {
        val frugal = frugal(s.defaultStrategy)
        val swap = s.ecoOn && s.ecoStrategy && frugal != s.defaultStrategy
        return s.copy(strategy = if (swap) frugal else s.defaultStrategy, strategyBeforeEco = if (swap) s.defaultStrategy else null)
    }

    /**
     * What is left of each kind of work in the mode (by [Activity.Kind] name); missing kinds stay as
     * they are (the core forwarding traffic — cheaper protocols are not counted, so the estimate is
     * conservative — the user's own scans, rescue probes, measurements).
     */
    private fun factors(servers: Int, strategySwapped: Boolean): Map<String, Double> = mapOf(
        "PING_PERIODIC" to 1.0 / LATENCY_FACTOR,
        "PING_RIDE" to 1.0 / RIDE_FACTOR,
        // Only while traffic flows and only promising servers; none at all once the strategy is swapped.
        "SCAN_BACKGROUND" to if (strategySwapped) 0.0 else (1.0 / RESCAN_FACTOR * min(1.0, TARGETED_MAX.toDouble() / servers.coerceAtLeast(1))).coerceAtLeast(0.05),
        // Stops at the first good server (retries keep their pace).
        "SCAN_ENGINE" to 0.5,
        "SCAN_OPEN" to 1.0 / OPEN_SCAN_FACTOR,
        "SUB_UPDATE" to 1.0 / SUB_FACTOR,
        "DB_COMMIT" to 0.25,
        "STATS_SAMPLE" to 0.4,
        "LOG_WRITE" to 0.7,
    )

    /**
     * Expected saving as a share of the app's battery use (0..1): the energy per kind of work the app
     * actually spent outside the mode ([mjByKind], from its own statistics), each scaled by what the
     * mode leaves of it. [strategySwapped]: the mode would replace an optimising strategy.
     */
    fun savings(mjByKind: Map<String, Double>, servers: Int, strategySwapped: Boolean): Double? {
        val total = mjByKind.values.sum()
        if (total <= 0) return null
        val f = factors(servers, strategySwapped)
        val eco = mjByKind.entries.sumOf { (k, mj) -> mj * (f[k] ?: 1.0) }
        return (1 - eco / total).coerceIn(0.0, 1.0)
    }

    /** Shares of the app's battery use on a typical day, for an estimate before there are own statistics. */
    val TYPICAL: Map<String, Double> = mapOf(
        "CORE_BASE" to 0.30, "UI_BASE" to 0.08,
        "PING_PERIODIC" to 0.10, "PING_RIDE" to 0.05, "PING_VERIFY" to 0.02,
        "SCAN_BACKGROUND" to 0.25, "SCAN_ENGINE" to 0.06, "SCAN_OPEN" to 0.04,
        "SUB_UPDATE" to 0.02, "DB_COMMIT" to 0.05, "STATS_SAMPLE" to 0.02, "LOG_WRITE" to 0.01,
    )

    /** The settings-log key the mode's periods are read from (see SettingsLog). */
    const val KEY = "ecoOn"

    /**
     * Intervals within [from, to) when a setting was "true", from its history (time, value) sorted by
     * time, the first entry possibly before [from] (the state the period started in).
     */
    fun onIntervals(history: List<Pair<Long, String>>, from: Long, to: Long): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        var since: Long? = null
        for ((t, v) in history.sortedBy { it.first }) {
            val on = v == "true"
            if (on && since == null) since = maxOf(t, from)
            else if (!on && since != null) {
                val end = minOf(t, to)
                if (end > since) out += since to end
                since = null
            }
        }
        since?.let { if (to > it) out += it to to }
        return out
    }

    /** The parts of [from, to) not covered by [intervals] (sorted, non-overlapping). */
    fun complement(intervals: List<Pair<Long, Long>>, from: Long, to: Long): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        var cursor = from
        for ((a, b) in intervals) {
            if (a > cursor) out += cursor to minOf(a, to)
            cursor = maxOf(cursor, b)
        }
        if (cursor < to) out += cursor to to
        return out
    }
}
