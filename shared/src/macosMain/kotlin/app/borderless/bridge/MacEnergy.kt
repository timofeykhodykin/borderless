@file:OptIn(ExperimentalNativeApi::class)

package app.borderless.bridge

import app.borderless.core.Power
import app.borderless.data.AppLog
import app.borderless.data.CpuShare
import app.borderless.data.EcoMath
import app.borderless.data.Errors
import app.borderless.data.Repo
import app.borderless.data.StatsDb
import app.borderless.data.Work
import app.borderless.data.WorkGroup
import app.borderless.data.WorkKind
import app.borderless.platform.Lock
import app.borderless.platform.nowMs
import app.borderless.platform.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CpuArchitecture
import kotlin.native.Platform

/**
 * The Mac's `Work.Meter` (ENERGY-DESIGN §4): Android's `Activity`/`Energy` pair in one — the tracked
 * actions' counters, their CPU share and the measured energy, the power samples and the off-engine
 * tick, all fed by the service's `MacProcesses.resources()` line (Swift `EnergyProbe` reads
 * `proc_pid_rusage` for the service, its helper, the main core and the limited cores).
 *
 * Differences from Android, by design:
 * - energy is **measured**, not modelled: `ri_energy_nj` of the process (and the action's share of
 *   its CPU time × the learned J/ms). On Intel the kernel never fills it — then the display falls
 *   back to CPU ms × [MacEnergyMath.GENERIC_CPU_W], `measured_mj` stays null and nothing fabricated
 *   enters `core_cost` (that table holds CPU ms, which stays honest).
 * - `radio_ms` is always 0: a Mac has no cellular radio model; `UI_BASE` stays 0 too (the service
 *   has no screens — the app process is out of scope).
 * - `sys_power` and `current` rows are never written: there is no honest Mac analogue (Android uid
 *   accounting, `BATTERY_PROPERTY_CURRENT_NOW`). The tables stay untouched in schema and export.
 */
object MacEnergy {

    /** One tracked action: its CpuShare id, start stamp and the metered flag it started under
     *  (for the measurement row; the screen state reads at its end, as Android's does). */
    private class Span(
        kind: WorkKind, val cpuId: Long, val startAt: Long, val metered: Boolean,
    ) : Work.Span(kind)

    private val lock = Lock()
    private lateinit var p: MacProcesses
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + Errors.handler)

    /** Per kind: count, bytes, radio ms (always 0 on Mac), CPU ms, mJ — [Activity]'s slots, lock-guarded
     *  (engine threads, socket workers and the tick all count at once; K/N has no ConcurrentHashMap). */
    private val counts = HashMap<WorkKind, LongArray>()
    private val cpuShare = CpuShare()

    // ------------------------------------------------------------------ resources / rate learning

    /** The last resources() snapshot and when it was taken (the 200 ms cache of the design —
     *  spans can start and end within the same hundred ms during scans). */
    private var snap: MacEnergyMath.Snapshot? = null
    private var snapAt = 0L

    /** The previous snapshot's stream counters — every fresh read moves the J/ms estimate and the
     *  energy-unavailable detection (power samples and span closes alike, per the design). */
    private var prevRate: MacEnergyMath.Snapshot? = null

    /** Measured joules per CPU ms of the stream, seeded by arch (design: ~2 W arm64, ~6 W x86_64). */
    @Volatile
    private var jPerMs = if (Platform.cpuArchitecture == CpuArchitecture.ARM64) 2.0 else 6.0

    /** CPU time seen while the energy counter stayed flat ([MacEnergyMath.energyDead] at a minute). */
    private var deadCpuMs = 0L

    /** A real energy reading was seen at least once — `measured_mj` rows and the payload's flag. */
    @Volatile
    internal var energyMeasured = false
        private set
    private var deadLogged = false

    // ------------------------------------------------------------------ sample bookkeeping

    private var prevSample: MacEnergyMath.Snapshot? = null
    private var lastBaseAt = 0L
    private var lastSampleAt = 0L
    private var lastFlushAt = 0L

    /** CPU charged to tracked actions since the last power sample (Android `Energy.attributedCpuMs`). */
    private var attributedCpuMs = 0L

    /** CPU charged to tracked actions since the service started (Android `Energy.attributedTotalMs`). */
    @Volatile
    private var attributedTotalMs = 0L

    private const val SAMPLE_MS = 4 * 60_000L
    private const val TICK_MS = 60_000L
    private const val FLUSH_MS = 5 * 60_000L
    private const val CACHE_MS = 200L

    /** What the shared code's [Work] reports (installed by [MacEngine.setUp]). */
    val meter = object : Work.Meter {
        override fun count(kind: WorkKind, n: Int, bytes: Long) = lock.withLock {
            val c = counts.getOrPut(kind) { LongArray(5) }
            c[0] += n.toLong(); c[1] += bytes
        }

        override fun begin(kind: WorkKind, exchanges: Int): Work.Span {
            val s = resources()
            // cpuId 0 (never issued — CpuShare numbers from 1): no snapshot to open against. Feeding
            // 0 would reset the share's lastCpu and make the span inherit the daemon's whole CPU
            // history when the next resources() succeeds; a span with no measurement ends at 0.
            return Span(kind, if (s != null) cpuShare.open(s.streamCpuMs, nowMs(), kind.name) else 0L,
                nowMs(), Power.metered).also { it.exchanges = exchanges }
        }

        override fun end(span: Work.Span) {
            if (span !is Span) return
            val s = resources()
            val cpu = if (span.cpuId != 0L && s != null) cpuShare.close(span.cpuId, s.streamCpuMs, nowMs()) else 0L
            lock.withLock { this@MacEnergy.attributedCpuMs += cpu; attributedTotalMs += cpu }
            val measured = if (energyMeasured) MacEnergyMath.spanEnergy(cpu, jPerMs) else null
            val mj = measured ?: MacEnergyMath.spanEnergy(cpu, MacEnergyMath.GENERIC_CPU_W)
            addCost(span.kind, radioMs = 0, cpuMs = cpu, mj = mj)
            // A measurement row per network-group span (Android's CurrentMeter set): the CPU model
            // against the measured mJ (null when the energy counter is dead — never a made-up figure).
            if (cpu > 0 && span.kind.group in NETWORK) {
                StatsDb.measurement(
                    StatsDb.Measurement(
                        span.startAt, span.kind.name, (nowMs() - span.startAt).coerceAtLeast(0),
                        modelMj = MacEnergyMath.spanEnergy(cpu, MacEnergyMath.GENERIC_CPU_W),
                        measuredMj = measured, baseUa = null, metered = span.metered, screen = Power.screenOn,
                    )
                )
            }
        }

        override fun heartbeat() {
            samplePower()
            flush()
        }

        /** The user's traffic wakes no radio on a Mac — a no-op marker, as the design says. */
        override fun userTraffic() {}

        /** self + helper + main core, cumulative: Android's `getElapsedCpuTime` analogue. */
        override val processCpuMs: Long get() = resources()?.processCpuMs ?: -1

        override val attributedCpuMs: Long get() = attributedTotalMs

        /** The socket is the only thing that can make the service non-quiet (the app's screens). */
        override fun uiQuietSince(t: Long): Boolean = resources()?.let { it.lastClientReqMs < t } != false
    }

    /** Groups Android's CurrentMeter measures (the network actions a user cares about). */
    private val NETWORK = setOf(WorkGroup.PING, WorkGroup.SCAN, WorkGroup.PROBE, WorkGroup.OTHER)

    /**
     * Once, from [MacEngine.setUp] (after `Power.source` and the stats DB exist): the off-engine tick
     * samples power and flushes counters while the tunnel is off — the daemon lives 24/7, so this also
     * captures subscription/stats work Android would miss outside its process's lifetime.
     */
    fun setUp(processes: MacProcesses) {
        p = processes
        scope.launch {
            while (true) {
                delay(TICK_MS)
                Errors.guard("energy", Unit, show = false) {
                    samplePower()
                    if (nowMs() - lastFlushAt >= FLUSH_MS) flush()
                }
            }
        }
    }

    /** The service's counters line, parsed; ~200 ms cached ([CACHE_MS] covers scan-time span bursts).
     *  A failed read keeps the last good snapshot — spans still close against (slightly stale) counters
     *  rather than never closing; null only until the service answers once. */
    private fun resources(): MacEnergyMath.Snapshot? {
        val now = nowMs()
        lock.withLock {
            if (now - snapAt <= CACHE_MS) return snap
        }
        val cur = MacEnergyMath.parseResources(runCatching { p.resources() }.getOrDefault(""))
            ?: return lock.withLock { snap }
        lock.withLock {
            snap = cur
            snapAt = now
            learnRate(cur)
        }
        return cur
    }

    /** Every fresh snapshot moves the J/ms estimate and the energy-dead accumulation (under [lock]). */
    private fun learnRate(cur: MacEnergyMath.Snapshot) {
        val prev = prevRate
        prevRate = cur
        if (prev == null) return
        val cpu = MacEnergyMath.delta(prev.streamCpuMs, cur.streamCpuMs)
        val nj = MacEnergyMath.delta(prev.streamNj, cur.streamNj)
        if (nj > 0) energyMeasured = true
        jPerMs = MacEnergyMath.learnRate(jPerMs, cpu, nj)
        deadCpuMs = MacEnergyMath.trackDead(deadCpuMs, cpu, nj)
        if (!deadLogged && MacEnergyMath.energyDead(deadCpuMs)) {
            deadLogged = true
            AppLog.debug("energy", "no energy counter (CPU time only this session)")
        }
    }

    /** CPU ms → mJ for infrastructure buckets: the measured nanojoules when the counter works. */
    private fun mjOf(cpuMs: Long, nj: Long): Long =
        if (energyMeasured) nj / 1_000_000 else MacEnergyMath.spanEnergy(cpuMs, MacEnergyMath.GENERIC_CPU_W)

    private fun addCost(kind: WorkKind, radioMs: Long, cpuMs: Long, mj: Long) = lock.withLock {
        val c = counts.getOrPut(kind) { LongArray(5) }
        c[2] += radioMs; c[3] += cpuMs; c[4] += mj
    }

    /** CPU charged to actions since the last call (Android `Energy.takeAttributedCpu`). */
    private fun takeAttributedCpu(): Long = lock.withLock { attributedCpuMs.also { attributedCpuMs = 0 } }

    /**
     * Records the power state now (Android `Activity.samplePower`): battery level and charging, display,
     * Low Power Mode, metered network, the CPU time and footprint of all four roles since the last
     * sample. Sparser in the battery saving mode, as on Android.
     */
    fun samplePower(force: Boolean = false) {
        val now = nowMs()
        if (!force && now - lastSampleAt < (if (Repo.settings.value.ecoOn) EcoMath.SAMPLE_MS else SAMPLE_MS)) return
        lastSampleAt = now
        // Collecting statistics costs energy too: the sample itself is counted.
        Work.track(WorkKind.STATS_SAMPLE) { takeSample(now) }
    }

    private fun takeSample(now: Long) {
        val cur = resources() ?: return
        val prev = prevSample
        prevSample = cur
        if (prev == null) {
            lastBaseAt = now
            return
        }
        val streamCpu = MacEnergyMath.delta(prev.streamCpuMs, cur.streamCpuMs)
        val streamNj = MacEnergyMath.delta(prev.streamNj, cur.streamNj)
        val coreCpu = MacEnergyMath.delta(prev.core.cpuMs, cur.core.cpuMs)
        val coreNj = MacEnergyMath.delta(prev.core.nj, cur.core.nj)
        val limCpu = MacEnergyMath.delta(prev.limited.cpuMs, cur.limited.cpuMs)
        val limNj = MacEnergyMath.delta(prev.limited.nj, cur.limited.nj)
        val base = MacEnergyMath.splitBase(streamCpu, streamNj, coreCpu, coreNj, limCpu, limNj, takeAttributedCpu())
        // What the stream spends outside actions per wall second — the share's learned background.
        if (lastBaseAt > 0) cpuShare.background(base.streamBaseCpu, now - lastBaseAt)
        lastBaseAt = now
        // No UI_BASE: the service has no screens (the app process is out of scope, like Android's
        // PSS rule of counting only the own process).
        addCost(WorkKind.CORE_BASE, radioMs = 0, cpuMs = base.coreBaseCpu, mj = mjOf(base.coreBaseCpu, base.coreBaseNj))
        addCost(WorkKind.LIMITED_START, radioMs = 0, cpuMs = base.limitedCpu, mj = mjOf(base.limitedCpu, base.limitedNj))
        val cpuDelta = MacEnergyMath.delta(prev.totalCpuMs, cur.totalCpuMs)
        StatsDb.power(
            StatsDb.PowerRow(
                ts = now, battery = Power.batteryPercent, charging = Power.charging, screen = Power.screenOn,
                saver = Power.powerSave, metered = Power.metered, cpuMs = cpuDelta, pssKb = cur.totalFbKb,
                capacityMah = Power.capacityMah, mv = Power.voltageMv,
            )
        )
    }

    /** Writes the counters gathered so far (the heartbeat, the tick, `StatsDb.beforeFlush`). */
    fun flush() {
        val rows = lock.withLock {
            counts.mapNotNull { (k, c) ->
                if (c.all { it == 0L }) null
                else StatsDb.ActivityTotal(k.name, c[0], c[1], c[2], c[3], c[4]).also { c.fill(0) }
            }
        }
        if (rows.isNotEmpty()) StatsDb.activity(nowMs(), rows)
        lastFlushAt = nowMs()
    }
}
