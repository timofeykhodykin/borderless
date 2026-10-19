package app.borderless.data

import android.content.Context
import android.os.health.HealthStats
import android.os.health.SystemHealthManager
import android.os.health.UidHealthStats

/**
 * Android's own battery accounting for this app's uid (the app and the limited-network core process alike), read with
 * `SystemHealthManager.takeMyUidSnapshot()` — no permission needed. The system knows what our model can't: on which
 * cores and at which frequencies the CPU time ran (its CPU charge uses the phone's power profile per cluster and
 * frequency, or measured rail energy where the phone has it). That calibrates our CPU power ([cpuWatts] →
 * [DeviceProfile.calibrate]), the largest part of the error margin.
 *
 * The radio figures are kept for the export only: the system charges the radio to whoever sends the packets, and the
 * user's own traffic through the tunnel is sent by this app, so they are not the app's own cost the model estimates.
 *
 * The counters run only while on battery and the system resets them (after a full charge and the like): only deltas
 * between two samples are used, and a counter going down means a reset ([delta] gives null).
 */
object SystemPower {
    /** Cumulative counters, or (from [delta]) their growth between two samples. mAms = mA × ms. */
    data class Snapshot(
        val cpuMams: Long,
        /** User + system CPU time of the uid. */
        val cpuMs: Long,
        val mobileMams: Long,
        val wifiMams: Long,
        /** Time the system counts the mobile radio as active for the uid. */
        val radioMs: Long,
        val mobileBytes: Long,
        val wifiBytes: Long,
    ) {
        private val all get() = listOf(cpuMams, cpuMs, mobileMams, wifiMams, radioMs, mobileBytes, wifiBytes)

        /** Whether the phone reports any energy at all (all zero on phones / emulators without a usable profile). */
        val hasEnergy: Boolean get() = cpuMams > 0 || mobileMams > 0 || wifiMams > 0

        fun describe(): String =
            "CPU %.2f mAh over %d s, mobile radio %.2f mAh (active %d s), Wi-Fi %.2f mAh, bytes mobile %d / Wi-Fi %d".format(
                cpuMams / 3_600_000.0, cpuMs / 1000, mobileMams / 3_600_000.0, radioMs / 1000, wifiMams / 3_600_000.0,
                mobileBytes, wifiBytes,
            )

        internal fun minus(o: Snapshot): Snapshot? {
            val d = Snapshot(
                cpuMams - o.cpuMams, cpuMs - o.cpuMs, mobileMams - o.mobileMams, wifiMams - o.wifiMams,
                radioMs - o.radioMs, mobileBytes - o.mobileBytes, wifiBytes - o.wifiBytes,
            )
            return d.takeIf { x -> x.all.all { it >= 0 } }
        }
    }

    private var manager: SystemHealthManager? = null

    fun init(context: Context) {
        manager = context.getSystemService(SystemHealthManager::class.java)
    }

    /** The uid's counters now; null when the system doesn't give them. */
    fun take(): Snapshot? = runCatching {
        val h = manager?.takeMyUidSnapshot() ?: return null
        fun m(key: Int) = if (h.hasMeasurement(key)) h.getMeasurement(key) else 0L
        Snapshot(
            cpuMams = m(UidHealthStats.MEASUREMENT_CPU_POWER_MAMS),
            cpuMs = m(UidHealthStats.MEASUREMENT_USER_CPU_TIME_MS) + m(UidHealthStats.MEASUREMENT_SYSTEM_CPU_TIME_MS),
            mobileMams = m(UidHealthStats.MEASUREMENT_MOBILE_POWER_MAMS),
            wifiMams = m(UidHealthStats.MEASUREMENT_WIFI_POWER_MAMS),
            radioMs = timer(h, UidHealthStats.TIMER_MOBILE_RADIO_ACTIVE),
            mobileBytes = m(UidHealthStats.MEASUREMENT_MOBILE_RX_BYTES) + m(UidHealthStats.MEASUREMENT_MOBILE_TX_BYTES),
            wifiBytes = m(UidHealthStats.MEASUREMENT_WIFI_RX_BYTES) + m(UidHealthStats.MEASUREMENT_WIFI_TX_BYTES),
        )
    }.getOrNull()

    private fun timer(h: HealthStats, key: Int): Long = if (h.hasTimer(key)) h.getTimerTime(key) else 0L

    /** Growth from [before] to [now]; null at the first sample or after the system reset its counters. */
    fun delta(before: Snapshot?, now: Snapshot?): Snapshot? = if (before == null || now == null) null else now.minus(before)

    /** Least CPU time (ms) of the uid summed over the rows before [cpuWatts] trusts them: 10 minutes. */
    const val MIN_CPU_MS = 600_000L

    /**
     * CPU power (W per busy core, the unit of [DeviceProfile.Info.cpu]) the system charged this app for, over [rows]
     * (deltas), at [volts]: Σ mA·ms × V / Σ CPU ms. Null with too little CPU time, without energy figures, or with an
     * implausible result. Pure, tested.
     */
    fun cpuWatts(rows: List<Snapshot>, volts: Double): Double? {
        val ms = rows.sumOf { it.cpuMs }
        val mams = rows.sumOf { it.cpuMams }
        if (ms < MIN_CPU_MS || mams <= 0) return null
        // mA × ms × V = µJ; per CPU ms → mJ / ms = W after ÷ 1000.
        val w = mams * volts / ms / 1000.0
        return w.takeIf { it in 0.02..5.0 }
    }

    /** How long a calibration looks back. */
    const val WINDOW_MS = 7 * 86_400_000L

    /**
     * Recomputes the CPU power from the last week of [StatsDb] rows and hands it to [DeviceProfile.calibrate] (local
     * query; at start and hourly). Logged when it changes.
     */
    suspend fun recalibrate() {
        val now = System.currentTimeMillis()
        val rows = StatsDb.sysPowerRows(now - WINDOW_MS, now + 1).map { it.second }
        val volts = app.borderless.core.Power.voltageMv?.let { it / 1000.0 } ?: Energy.VOLTAGE
        val w = cpuWatts(rows, volts)
        val before = DeviceProfile.cpuCalibration
        DeviceProfile.calibrate(w)
        if (w != before) {
            AppLog.debug(
                "Energy",
                if (w == null) "CPU power: the phone's table (Android's accounting: ${rows.sumOf { it.cpuMs } / 1000} s of CPU, not enough or no energy figures)"
                else "CPU power from Android's accounting: %.3f W per busy core over %d s of CPU (the table: %.3f W)".format(
                    w, rows.sumOf { it.cpuMs } / 1000, DeviceProfile.tableCpuW,
                ),
            )
        }
    }
}
