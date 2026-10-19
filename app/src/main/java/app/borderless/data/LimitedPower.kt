package app.borderless.data

import kotlin.math.ceil

/**
 * How hard a limited-network server's core works (pure, unit-tested). The core carries traffic over several streams (each
 * one a connection through the service, with its own credentials, keepalives and binding refreshes); more streams
 * carry more traffic, and every idle one still wakes the radio. So the app keeps as few as the moment needs:
 *
 * - **Automatic** (the default, [Server.limitedStreams] = 0): a floor while idle (1 with the screen off or in battery
 *   saving, 2 with the screen on, 3 for Fastest), more as traffic grows — one per [PER_STREAM_BPS] of the last
 *   seconds' rate plus one spare — up to the strategy's ceiling (battery saving 4, On failure 6, Stable / Manual 9,
 *   Fastest 18). Up at once, down only after [DOWN_AFTER_MS] below.
 * - **By hand** (1…[MAX_STREAMS]): that many, always.
 * - Keepalives / binding refreshes: 15 / 10 s with the screen on (the core's own values), 25 / 20 s with the screen
 *   off or in battery saving (the server's WireGuard keepalive is 25 s, its sessions live 30 min without traffic).
 * - A core started only to check the server runs one stream (one credential request, the quickest start).
 */
object LimitedPower {
    const val MAX_STREAMS = 36

    /**
     * Radio time the core's streams need over [windowMs] (pure, unit-tested). Every keepalive and every TURN binding
     * refresh wakes the radio for about the radio's tail ([metered]: 10 s of high power; Wi-Fi: one exchange), so a
     * stream awake every [keepaliveSec] keeps it busy for `tail / interval` of the time; streams share the radio, so
     * the sum is capped by the window. [bytes] carried add their transfer time. The union with the user's own traffic
     * is done by `Energy.limited`.
     */
    fun radioMs(streams: Int, keepaliveSec: Int, bindingSec: Int, bytes: Long, windowMs: Long, metered: Boolean): Long {
        if (windowMs <= 0) return 0
        val tail = if (metered) Energy.CELL_TAIL_MS else Energy.WIFI_EXCHANGE_MS
        fun share(sec: Int) = minOf(1.0, tail / (sec.coerceAtLeast(5) * 1000.0))
        val perStream = windowMs * (share(keepaliveSec) + share(bindingSec))
        val transfer = bytes.coerceAtLeast(0) / Energy.WIFI_BYTES_PER_MS
        return (perStream * streams.coerceAtLeast(0) + transfer).toLong().coerceIn(0, windowMs)
    }

    /** Traffic one stream is trusted to carry (bytes / s) before another one is opened. */
    const val PER_STREAM_BPS = 256 * 1024L

    /** Rate below the current streams' worth for this long → fewer streams. */
    const val DOWN_AFTER_MS = 30_000L

    data class Plan(val streams: Int, val keepaliveSec: Int, val bindingSec: Int)

    /** Conditions the stream count depends on (traffic rate is given to [Controller.update] directly). */
    data class Conditions(val strategy: Strategy, val eco: Boolean, val screenOn: Boolean)

    /** The streams a core process is started with as its upper bound (streams not wanted cost nothing to have). */
    fun ceilingAtStart(manual: Int, limit: Int? = null): Int =
        capped(if (manual > 0) manual.coerceAtMost(MAX_STREAMS) else ceiling(Strategy.FASTEST, eco = false), limit)

    fun floor(strategy: Strategy, eco: Boolean, screenOn: Boolean): Int = when {
        eco || !screenOn -> 1
        strategy == Strategy.FASTEST -> 3
        else -> 2
    }

    fun ceiling(strategy: Strategy, eco: Boolean): Int = when {
        eco -> 4
        strategy == Strategy.FASTEST -> 18
        strategy == Strategy.FAILOVER -> 6
        else -> 9
    }

    fun intervals(eco: Boolean, screenOn: Boolean): Pair<Int, Int> = if (eco || !screenOn) 25 to 20 else 15 to 10

    /** The plan for a core only checking its server (no traffic of its own). */
    fun probe(manual: Int, limit: Int? = null): Plan = Plan(capped(if (manual > 0) minOf(manual, 1) else 1, limit), 15, 10)

    /** The plan the core starts with for a server in use (its idle floor). */
    fun start(manual: Int, c: Conditions, limit: Int? = null): Plan {
        val (k, b) = intervals(c.eco, c.screenOn)
        val streams = if (manual > 0) manual.coerceIn(1, MAX_STREAMS) else floor(c.strategy, c.eco, c.screenOn)
        return Plan(capped(streams, limit), k, b)
    }

    /** Streams wanted for traffic [rateBps] (bytes / s), within the strategy's floor, ceiling and the server's limit. */
    fun demand(rateBps: Long, c: Conditions, limit: Int? = null): Int {
        val need = if (rateBps <= 0) 0 else ceil(rateBps.toDouble() / PER_STREAM_BPS).toInt() + 1
        val lo = floor(c.strategy, c.eco, c.screenOn)
        return capped(need.coerceIn(lo, maxOf(lo, ceiling(c.strategy, c.eco))), limit)
    }

    private fun capped(n: Int, limit: Int?): Int = if (limit == null) n else n.coerceIn(1, limit.coerceAtLeast(1))

    /**
     * Follows one core in use and decides its plan over time: raise the stream count the moment traffic needs it,
     * lower it only after the rate has stayed below the current count's worth for [DOWN_AFTER_MS] (so a short gap in
     * a download doesn't drop streams that are about to be used again). Manual stream counts never move.
     */
    class Controller(private val manual: Int, limit: Int? = null) {
        private var streams = 0
        private var belowSince = 0L
        private var limit = limit

        /** Applies a limit learned while the core runs (the server's `max_workers`): lower it at once. */
        fun setLimit(n: Int?) {
            limit = n
            if (n != null && streams > n.coerceAtLeast(1)) {
                streams = n.coerceAtLeast(1)
                belowSince = 0L
            }
        }

        /** The plan in effect now, without waiting for a traffic change (used when an option changes). */
        fun planNow(c: Conditions): Plan {
            val (k, b) = intervals(c.eco, c.screenOn)
            val n = when {
                streams > 0 -> streams
                manual > 0 -> manual
                else -> floor(c.strategy, c.eco, c.screenOn)
            }
            return Plan(capped(n, limit), k, b)
        }

        /**
         * [rateBps]: bytes per second carried since the last update. Returns the new [Plan] when the stream count or
         * the intervals change, else null. [now] is injected for tests.
         */
        fun update(rateBps: Long, c: Conditions, now: Long): Plan? {
            val (k, b) = intervals(c.eco, c.screenOn)
            if (manual > 0) {
                val want = capped(manual.coerceIn(1, MAX_STREAMS), limit)
                return if (want != streams || firstIntervals(k, b)) { streams = want; lastK = k; lastB = b; Plan(want, k, b) } else null
            }
            val want = demand(rateBps, c, limit)
            val next = when {
                want > streams -> { belowSince = 0L; want }
                want < streams -> {
                    if (belowSince == 0L) belowSince = now
                    if (now - belowSince >= DOWN_AFTER_MS) { belowSince = 0L; want } else streams
                }
                else -> { belowSince = 0L; streams }
            }
            return if (next != streams || firstIntervals(k, b)) { streams = next; lastK = k; lastB = b; Plan(next, k, b) } else null
        }

        private var lastK = -1
        private var lastB = -1
        private fun firstIntervals(k: Int, b: Int) = k != lastK || b != lastB
    }
}
