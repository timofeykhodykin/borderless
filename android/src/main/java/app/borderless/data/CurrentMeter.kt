package app.borderless.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import app.borderless.core.Power
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Diagnostic mode: measures the battery current for real. While on, the current is sampled every
 * 2 s whenever the phone is awake anyway (no wakelock: sampling pauses in deep sleep), and every
 * tracked action ([Energy]) gets a measured energy: Σ (current − current before it) × voltage × time
 * over the action and its radio tail. One measurement is noisy (other apps, screen), the average over
 * many is what counts. Everything goes to the stats DB for the export.
 */
object CurrentMeter {
    private const val PERIOD_MS = 2_000L
    private const val KEEP_MS = 10 * 60_000L
    private const val BASELINE_MS = 15_000L

    private class Sample(val ts: Long, val ua: Long, val mv: Int?)

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + Errors.handler)
    private var job: Job? = null
    private val samples = ArrayDeque<Sample>()

    /** The unit this phone reports, learned from its own readings: true = µA, false = mA, null = not known yet. */
    @Volatile
    private var micro: Boolean? = null
    private var seen = 0
    private var maxAbs = 0L

    val running: Boolean get() = job?.isActive == true

    fun init(context: Context) {
        app = context.applicationContext
    }

    @Synchronized
    fun start() {
        if (running || !::app.isInitialized) return
        job = scope.launch {
            AppLog.debug("CurrentMeter", "current measurement on")
            while (isActive) {
                Energy.track(Activity.Kind.CURRENT_METER) { sample() }
                delay(PERIOD_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        synchronized(samples) { samples.clear() }
    }

    private fun sample() {
        val bm = app.getSystemService(BatteryManager::class.java) ?: return
        val raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (raw == Int.MIN_VALUE || raw == 0) return
        val mv = runCatching {
            app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        }.getOrNull()?.takeIf { it > 0 }
        learnUnit(raw)
        val now = System.currentTimeMillis()
        StatsDb.current(now, raw, mv)
        synchronized(samples) {
            samples.addLast(Sample(now, normalize(raw, micro), mv))
            while (samples.isNotEmpty() && samples.first().ts < now - KEEP_MS) samples.removeFirst()
        }
    }

    private fun learnUnit(raw: Int) {
        if (micro != null) return
        seen++
        maxAbs = maxOf(maxAbs, abs(raw.toLong()))
        micro = unitOf(seen, maxAbs)
        micro?.let { AppLog.debug("CurrentMeter", "this phone reports current in " + if (it) "µA" else "mA") }
    }

    /**
     * Pure part (unit-tested): the unit from this phone's readings so far. The API says µA, some
     * firmwares report mA. 30 000 mA is impossible, so one reading that large means µA; a minute of
     * readings that all stay under 3 000 can't be µA (an awake phone draws far more than 3 mA).
     */
    fun unitOf(seen: Int, maxAbs: Long): Boolean? = when {
        maxAbs >= 30_000 -> true
        seen >= 30 && maxAbs < 3_000 -> false
        else -> null
    }

    /**
     * Current in µA, positive. [micro] = the unit learned for this phone; until it is known: an awake
     * phone draws at least ~20 mA, so small magnitudes are taken as mA. Sign differs between phones too.
     */
    fun normalize(raw: Int, micro: Boolean? = null): Long {
        val a = abs(raw.toLong())
        return when (micro) {
            true -> a
            false -> a * 1000
            null -> if (a in 1 until 20_000) a * 1000 else a
        }
    }

    /** Called when an action ends: after its radio tail has passed, its energy is measured and stored. */
    fun measure(span: Energy.Span, end: Long, modelMj: Long) {
        // Network actions only: local work (writes, samples) is tiny and measuring it would feed itself.
        if (!running || span.kind.group == Activity.Group.LOCAL) return
        val charging = Power.charging
        val screen = Power.screenOn
        val tail = if (span.metered) Energy.CELL_TAIL_MS else Energy.WIFI_TAIL_MS
        scope.launch {
            delay(end + tail + PERIOD_MS - System.currentTimeMillis())
            // A screen turned on / off or a charger plugged in during the action changes the phone's own
            // draw by far more than the action itself: such a reading says nothing (often hugely negative).
            val steady = !charging && !Power.charging && Power.screenOn == screen
            val measured = if (!steady) null else measuredMj(span.start, end + tail)
            StatsDb.measurement(
                StatsDb.Measurement(
                    span.start, span.kind.name, end - span.start, modelMj, measured?.first, measured?.second,
                    span.metered, screen,
                )
            )
        }
    }

    /** (extra mJ over [from, to], baseline µA), or null without enough samples. */
    private fun measuredMj(from: Long, to: Long): Pair<Long, Long>? {
        val list = synchronized(samples) { samples.toList() }
        val before = list.filter { it.ts in (from - BASELINE_MS) until from }
        // Baseline: right before the action; if the phone was asleep then, a low level of recent minutes.
        val base = if (before.size >= 2) before.map { it.ua }.sorted()[before.size / 2]
        else list.filter { it.ts < from }.map { it.ua }.sorted().let { if (it.size >= 5) it[it.size / 5] else return null }
        val during = list.filter { it.ts in from..to }
        if (during.isEmpty()) return null
        return extraMj(during.map { Triple(it.ts, it.ua, it.mv) }, base, to) to base
    }

    /**
     * Pure part (unit-tested): energy above [baseUa] of samples (time, µA, mV), each standing for the
     * time until the next one (at most 3 s), the last one until [end].
     */
    fun extraMj(samples: List<Triple<Long, Long, Int?>>, baseUa: Long, end: Long): Long {
        var mj = 0.0
        samples.forEachIndexed { i, (ts, ua, mv) ->
            val next = samples.getOrNull(i + 1)?.first ?: end
            val dt = (next - ts).coerceIn(0, 3_000)
            val volts = (mv ?: 3850) / 1000.0
            mj += (ua - baseUa) / 1_000_000.0 * volts * dt
        }
        return mj.toLong()
    }
}
