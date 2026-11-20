package app.borderless.bridge

/**
 * The Mac meter's pure math (ENERGY-DESIGN §4): parsing the service's `resources()` line into the four
 * roles' counters, monotonic-counter deltas, the measured-joules-per-CPU-ms estimate (EWMA, clamped),
 * energy-unavailable detection, an action's energy from its CPU share and the split of a sample's
 * untracked CPU/energy into the infrastructure buckets. No platform calls — everything is testable.
 */
object MacEnergyMath {

    /**
     * Cumulative counters of one role (service, helper, main core, or the limited cores summed):
     * [cpuMs] and [nj] grow monotonically per role (dead/replaced pids are folded in on the Swift
     * side); [fbKb] is the live physical footprint, instantaneous.
     */
    data class RoleCounters(val cpuMs: Long, val nj: Long, val fbKb: Long)

    /**
     * One `MacProcesses.resources()` line, decoded: the four roles plus the last client-request
     * stamp (`uiQuietSince` reads it — the socket is the only thing that can make the service
     * non-quiet, as an open screen would on Android).
     */
    data class Snapshot(
        val self: RoleCounters, val helper: RoleCounters,
        val core: RoleCounters, val limited: RoleCounters,
        val lastClientReqMs: Long,
    ) {
        /** Service + helper: the "process" [CpuShare] splits among actions — the probe/scan work runs there. */
        val streamCpuMs: Long get() = self.cpuMs + helper.cpuMs
        val streamNj: Long get() = self.nj + helper.nj

        /** Self + helper + main core: Android's `processCpuMs` analogue ([Engine.measureCost] reads it). */
        val processCpuMs: Long get() = streamCpuMs + core.cpuMs

        /** All four roles — the power row's CPU delta and the footprint sum. */
        val totalCpuMs: Long get() = processCpuMs + limited.cpuMs
        val totalFbKb: Long get() = self.fbKb + helper.fbKb + core.fbKb + limited.fbKb
    }

    /** The fields of one `resources()` line: 4 roles × (cpuMs, nj, fbKb), then the client stamp. */
    const val FIELDS = 13

    /**
     * "selfCpuMs selfNj selfFbKb helper… core… limited… lastClientReqMs", space separated, −1 meaning
     * "unknown" on the Swift side (clamped to 0 here; the stamp keeps its raw value). Malformed or
     * short input → null, like every other `<word>-separated` service line.
     */
    fun parseResources(line: String): Snapshot? {
        val f = line.trim().split(' ').filter { it.isNotEmpty() }
        if (f.size < FIELDS) return null
        val v = f.map { it.toLongOrNull() ?: return null }
        fun role(i: Int) = RoleCounters(v[i].coerceAtLeast(0), v[i + 1].coerceAtLeast(0), v[i + 2].coerceAtLeast(0))
        return Snapshot(role(0), role(3), role(6), role(9), v[12])
    }

    /** A monotonic counter's delta; a reset (the role restarted) reads as 0, never negative. */
    fun delta(prev: Long, cur: Long): Long = (cur - prev).coerceAtLeast(0)

    /** The learned J/ms must stay a plausible per-core power (design: 0.05–30 W). */
    const val MIN_W = 0.05
    const val MAX_W = 30.0

    /** How far each measured window moves the J/ms estimate. */
    const val LEARN = 0.25

    /** mJ per CPU-ms while nothing measured the real figure (≈ watts of one busy core — the design's
     *  ~2 W on arm64; also the `model_mj` column's constant). */
    const val GENERIC_CPU_W = 2.0

    /** CPU time accumulated while the energy counter stayed flat before energy counts as missing:
     *  ~1 min of busy CPU (the design's "running load" — on x86_64 the kernel never fills ri_energy_nj). */
    const val NO_ENERGY_CPU_MS = 60_000L

    /**
     * The rolling measured joules per CPU ms of the stream (self + helper), an EWMA over each
     * observed delta clamped to [MIN_W]…[MAX_W]. A zero or negative delta teaches nothing.
     * [njDelta] in nanojoules, [cpuDelta] in ms: nJ/1e6 = mJ, so mJ/cpuMs = J/s = W.
     */
    fun learnRate(jPerMs: Double, cpuDelta: Long, njDelta: Long): Double =
        if (cpuDelta <= 0 || njDelta <= 0) jPerMs
        else (jPerMs + LEARN * (njDelta / 1_000_000.0 / cpuDelta - jPerMs)).coerceIn(MIN_W, MAX_W)

    /**
     * CPU accumulated while `ri_energy_nj` stayed flat — reset by any real energy reading. Once it
     * passes [NO_ENERGY_CPU_MS] the kernel counter is dead for the session ([energyDead]: an Intel
     * Mac reads 0 forever; an arm64 counter that produced joules resets the accumulation instead).
     */
    fun trackDead(deadCpuMs: Long, cpuDelta: Long, njDelta: Long): Long =
        if (njDelta > 0) 0L else deadCpuMs + cpuDelta.coerceAtLeast(0)

    fun energyDead(deadCpuMs: Long): Boolean = deadCpuMs >= NO_ENERGY_CPU_MS

    /**
     * Energy of an action's share of CPU time, in mJ: [cpuShareMs] × [jPerMs]. Callers pass the
     * measured J/ms when the counter works, [GENERIC_CPU_W] as the display model when it does not.
     */
    fun spanEnergy(cpuShareMs: Long, jPerMs: Double): Long =
        (cpuShareMs.coerceAtLeast(0) * jPerMs).toLong()

    /**
     * A power sample's infrastructure split (Android `Activity.takeSample`'s analogue): the stream's
     * CPU minus what [CpuShare] charged to tracked actions lands in CORE_BASE together with the whole
     * main core; the limited cores are their own bucket. [attributedCpu] may cross a sample's edge
     * (a span ending after it), so the untracked remainder is clamped at zero — and the same share is
     * taken out of the stream's measured nanojoules.
     */
    data class Base(val streamBaseCpu: Long, val coreBaseCpu: Long, val coreBaseNj: Long,
                    val limitedCpu: Long, val limitedNj: Long)

    fun splitBase(
        streamCpu: Long, streamNj: Long, coreCpu: Long, coreNj: Long,
        limitedCpu: Long, limitedNj: Long, attributedCpu: Long,
    ): Base {
        val charged = attributedCpu.coerceIn(0, streamCpu)
        val share = if (streamCpu > 0) charged.toDouble() / streamCpu else 0.0
        val streamBaseCpu = streamCpu - charged
        return Base(
            streamBaseCpu = streamBaseCpu,
            coreBaseCpu = streamBaseCpu + coreCpu.coerceAtLeast(0),
            coreBaseNj = (streamNj - (streamNj * share).toLong()).coerceAtLeast(0) + coreNj.coerceAtLeast(0),
            limitedCpu = limitedCpu.coerceAtLeast(0),
            limitedNj = limitedNj.coerceAtLeast(0),
        )
    }
}
