package app.borderless.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import app.borderless.R
import app.borderless.data.ActivityMath
import app.borderless.data.AppLog
import app.borderless.data.EcoMath
import app.borderless.data.Errors
import app.borderless.data.LText
import app.borderless.data.Repo
import app.borderless.data.StatsDb
import app.borderless.data.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The app's battery saving mode: one switch ([AppSettings.ecoOn]) that the engine, the scanner,
 * subscriptions, statistics and the UI read to do less (what exactly: [EcoMath]). Turned on by the
 * user (button on the main screen) or together with the phone's battery saver; the user can always
 * override it. Changes are logged and recorded in the statistics' settings log (zones on the charts).
 */
object Eco {
    private const val TAG = "Eco"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + Errors.handler)

    val on: Boolean get() = Repo.settings.value.ecoOn

    /** Expected saving in percent of the app's battery use; [ownData] = from this phone's statistics. */
    data class Estimate(val percent: Int, val ownData: Boolean)

    private val _estimate = MutableStateFlow<Estimate?>(null)
    val estimate: StateFlow<Estimate?> = _estimate

    @Volatile
    private var estimatedAt = 0L

    fun init(context: Context) {
        val app = context.applicationContext
        // The phone's battery saver going on / off while the process lives; checked at start as well.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                scope.launch { sync() }
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        sync()
        scope.launch { Repo.settings.map { it.ecoWithSaver }.distinctUntilChanged().drop(1).collect { sync() } }
        refreshEstimate(force = true)
    }

    /**
     * Follows the phone's battery saver: its start turns the mode on (once per saver session, so the
     * user can turn it off again), its end turns the mode off if the saver was what turned it on.
     */
    @Synchronized
    fun sync() {
        val saver = Power.powerSave
        val s = Repo.settings.value
        if (saver && s.ecoWithSaver && !s.saverSeen) {
            Repo.updateSettings { it.copy(saverSeen = true) }
            if (!s.ecoOn) set(true, bySaver = true)
        } else if (!saver && s.saverSeen) {
            Repo.updateSettings { it.copy(saverSeen = false) }
            if (s.ecoOn && s.ecoBySaver) set(false, bySaver = true)
        }
    }

    /** The button on the main screen. */
    fun toggle() = set(!on)

    @Synchronized
    fun set(on: Boolean, bySaver: Boolean = false) {
        val before = Repo.settings.value
        if (before.ecoOn == on) return
        Repo.updateSettings { EcoMath.switch(it, on, bySaver) }
        val after = Repo.settings.value
        AppLog.event(
            LText.of(
                when {
                    on && bySaver -> R.string.ev_eco_on_saver
                    on -> R.string.ev_eco_on
                    bySaver -> R.string.ev_eco_off_saver
                    else -> R.string.ev_eco_off
                }
            ),
            AppLog.Kind.GOOD.takeIf { on } ?: AppLog.Kind.INFO,
        )
        if (after.strategy != before.strategy) {
            AppLog.event(LText.of(R.string.ev_eco_strategy, LText.of(label(before.strategy)), LText.of(label(after.strategy))))
        }
        AppLog.debug(TAG, "battery saving ${if (on) "on" else "off"}${if (bySaver) " (phone's battery saver)" else ""}; strategy ${before.strategy} -> ${after.strategy}")
        refreshEstimate(force = true)
    }

    /** Recomputes [estimate] at most every half hour (or now, if [force]). */
    fun refreshEstimate(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - estimatedAt < ESTIMATE_EVERY_MS) return
        estimatedAt = now
        scope.launch { _estimate.value = Errors.guard("battery saving estimate", null, show = false) { estimateNow() } }
    }

    /**
     * The saving the mode brings, from what the app spent per kind of work over the last week outside
     * the mode (its own statistics; a typical split until there are 3 hours of them).
     */
    private suspend fun estimateNow(): Estimate? {
        val now = System.currentTimeMillis()
        val from = now - WEEK_MS
        StatsDb.flush()
        val history = StatsDb.settingsHistory(KEY, from, now)
        val normal = EcoMath.complement(EcoMath.onIntervals(history, from, now), from, now)
        val mj = HashMap<String, Double>()
        var hours = 0.0
        for ((a, b) in normal) {
            StatsDb.activityTotals(a, b).values.forEach { mj.merge(it.kind, it.mj.toDouble(), Double::plus) }
            hours += ActivityMath.summarize(StatsDb.powerRows(a, b)).hours
        }
        val s = Repo.settings.value
        val normalStrategy = s.strategyBeforeEco ?: s.strategy
        val swapped = s.ecoStrategy && EcoMath.frugal(normalStrategy) != normalStrategy
        val own = hours >= 3 && mj.values.sum() > 0
        val share = EcoMath.savings(if (own) mj else EcoMath.TYPICAL, Repo.activeServers.size, swapped) ?: return null
        return Estimate((share * 100).roundToInt(), own)
    }

    /** The settings-log key the charts read the mode's periods from (see SettingsLog). */
    const val KEY = EcoMath.KEY

    private const val ESTIMATE_EVERY_MS = 30 * 60_000L
    private const val WEEK_MS = 7 * 86_400_000L

    fun label(s: Strategy): Int = when (s) {
        Strategy.STABLE -> R.string.strategy_stable
        Strategy.FASTEST -> R.string.strategy_fastest
        Strategy.FAILOVER -> R.string.strategy_failover
        Strategy.MANUAL -> R.string.strategy_manual
    }
}
