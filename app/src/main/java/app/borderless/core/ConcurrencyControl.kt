package app.borderless.core

import kotlinx.coroutines.delay

/**
 * How many probes run at once, adjusted during a scan. Probes mostly wait for the network, so more
 * of them in parallel finish a scan sooner — until the CPU can't keep up (each probe starts its own
 * Xray instance and does a TLS handshake) and measured pings get inflated. So, after every answer:
 * the ping is compared with that server's usual one; if recent answers are clearly inflated the limit
 * drops, if they are not and a whole wave went through, it grows. A fast phone ends up probing more
 * servers at once than a slow one (or the emulator). With [adaptive] off the limit stays at [start].
 */
class ConcurrencyControl(start: Int, val adaptive: Boolean, val min: Int = 3, maxLimit: Int = 24) {
    val max = if (adaptive) maxLimit else start.coerceAtLeast(1)

    @Volatile
    var limit = start.coerceIn(if (adaptive) min else 1, max)
        private set

    /** Short history of the changes, for the log. */
    var changes = ""
        private set

    private var active = 0
    private val ratios = ArrayDeque<Double>()
    private var sinceChange = 0
    private val lock = Any()

    /** Waits for a free slot under the current limit. */
    suspend fun acquire() {
        while (true) {
            synchronized(lock) { if (active < limit) { active++; return } }
            delay(40)
        }
    }

    fun release() = synchronized(lock) { active-- }

    /** One probe finished: [ms] measured now (null = no answer), [typical] its usual latency. */
    fun onResult(ms: Int?, typical: Int?) {
        if (!adaptive) return
        synchronized(lock) {
            sinceChange++
            if (ms != null && typical != null && typical > 0) {
                ratios.addLast(ms.toDouble() / typical)
                while (ratios.size > WINDOW) ratios.removeFirst()
            }
            val median = ratios.sorted().let { if (it.size >= 3) it[it.size / 2] else null }
            when {
                // Answers come back clearly slower than usual: too much at once for this phone.
                median != null && median > INFLATED -> change(-STEP)
                // A whole wave went through without inflation: there is room for more.
                sinceChange >= limit && (median == null || median <= CALM) -> change(+STEP)
            }
        }
    }

    private fun change(by: Int) {
        val new = (limit + by).coerceIn(min, max)
        if (new != limit) {
            limit = new
            changes = (changes + (if (by > 0) "+" else "-") + new + " ").takeLast(80)
        }
        sinceChange = 0
        ratios.clear()
    }

    private companion object {
        const val WINDOW = 6
        const val STEP = 2
        const val INFLATED = 1.5
        const val CALM = 1.2
    }
}
