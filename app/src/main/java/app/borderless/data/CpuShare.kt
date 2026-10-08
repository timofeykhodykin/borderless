package app.borderless.data

import kotlin.math.roundToLong

/**
 * Splits the process's CPU time among tracked actions running at the same time (pure, unit-tested).
 *
 * Android reports CPU time per process, not per action. Every change of the set of running actions closes a
 * slice; the slice's CPU, minus what the process spends outside actions meanwhile (the core forwarding, the
 * screens: the background), goes to the actions running in it. The sum handed out never exceeds the process CPU.
 *
 * Both parts are learned on the phone, not assumed:
 * - the background rate from slices with no action running (the time between actions), falling back to
 *   [background] (from the power samples) until there is one;
 * - how busy each kind of action keeps the CPU, from slices where it ran alone. When actions overlap, the slice is
 *   split in proportion to that: a subscription download waiting 25 s for the network next to a scan running a
 *   dozen probes got half of the scan's CPU with an equal split (the 2026-10-08 phone export: the same failed
 *   download cost 0.6 s of CPU alone at night and up to 27 s next to scans in the day).
 */
class CpuShare {
    private class Open(val kind: String?) {
        var cpu = 0.0
    }

    private val open = HashMap<Long, Open>()
    private var nextId = 0L
    private var lastCpu = 0L
    private var lastAt = 0L
    private var started = false

    /** From the power samples; used until a slice without actions has been seen. */
    @Volatile
    private var sampledRate = 0.0
    private var idleRate: Double? = null

    /** CPU ms per wall-clock ms of each kind of action running alone, beyond the background. */
    private val intensity = HashMap<String, Double>()

    /** CPU ms per wall-clock ms the process spends outside tracked actions (the core forwarding, the screens). */
    val backgroundRate: Double get() = idleRate ?: sampledRate

    /** Learns the background rate from [cpuMs] of untracked CPU over [wallMs] (until slices between actions teach it). */
    fun background(cpuMs: Long, wallMs: Long) {
        if (wallMs > 0) sampledRate = (cpuMs.toDouble() / wallMs).coerceIn(0.0, MAX_BACKGROUND)
    }

    /** What has been learned about a kind of action (CPU ms per wall ms beyond the background), if anything. */
    @Synchronized
    fun intensityOf(kind: String): Double? = intensity[kind]

    /** An action of [kind] starts; [cpu] = process CPU ms now, [at] = clock ms now. Returns its id. */
    @Synchronized
    fun open(cpu: Long, at: Long, kind: String? = null): Long {
        advance(cpu, at)
        return nextId++.also { open[it] = Open(kind) }
    }

    /** An action ends: its CPU ms. */
    @Synchronized
    fun close(id: Long, cpu: Long, at: Long): Long {
        advance(cpu, at)
        return (open.remove(id)?.cpu ?: 0.0).roundToLong()
    }

    private fun advance(cpu: Long, at: Long) {
        val delta = (cpu - lastCpu).coerceAtLeast(0).toDouble()
        val wall = (at - lastAt).coerceAtLeast(0)
        if (started && wall > 0) {
            if (open.isEmpty()) learnIdle(delta, wall) else share(delta, wall)
        }
        started = true
        lastCpu = cpu
        lastAt = at
    }

    /** Nothing running: all of it is background. A long gap says more than a short one. */
    private fun learnIdle(delta: Double, wall: Long) {
        val rate = (delta / wall).coerceIn(0.0, MAX_BACKGROUND)
        val weight = minOf(1.0, wall.toDouble() / IDLE_WINDOW_MS)
        idleRate = idleRate?.let { it + weight * (rate - it) } ?: rate
    }

    private fun share(delta: Double, wall: Long) {
        val work = (delta - backgroundRate * wall).coerceAtLeast(0.0)
        if (open.size == 1) {
            val only = open.values.first()
            only.cpu += work
            // Alone in a slice long enough to mean something: how busy this kind keeps the CPU.
            if (only.kind != null && wall >= MIN_LEARN_MS) {
                val rate = work / wall
                intensity[only.kind] = intensity[only.kind]?.let { it + LEARN_WEIGHT * (rate - it) } ?: rate
            }
            return
        }
        // Several at once: in proportion to how busy each kind is; kinds not learned yet weigh as the average.
        val known = open.values.mapNotNull { o -> o.kind?.let(intensity::get) }
        val fallback = if (known.isEmpty()) 1.0 else known.average()
        val weights = open.values.map { o -> maxOf(MIN_WEIGHT, o.kind?.let(intensity::get) ?: fallback) }
        val total = weights.sum()
        open.values.forEachIndexed { i, o -> o.cpu += work * weights[i] / total }
    }

    companion object {
        /** One core fully busy outside actions is the most the background can be. */
        const val MAX_BACKGROUND = 1.0

        /** A gap between actions this long replaces the background rate learned so far. */
        const val IDLE_WINDOW_MS = 30_000L

        /** Shorter slices are too coarse (CPU time ticks) to learn a kind's intensity from. */
        const val MIN_LEARN_MS = 200L

        /** Each new observation of a kind moves its intensity this far. */
        const val LEARN_WEIGHT = 0.25

        /** Even an idle-looking kind gets a sliver of a shared slice. */
        const val MIN_WEIGHT = 0.001
    }
}
