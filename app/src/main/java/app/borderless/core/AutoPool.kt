package app.borderless.core

import app.borderless.data.Energy
import app.borderless.data.Activity
import app.borderless.data.Errors
import app.borderless.R
import app.borderless.data.AppLog
import app.borderless.data.AutoPoolMath
import app.borderless.data.AutoState
import app.borderless.data.Countries
import app.borderless.data.LText
import app.borderless.data.Repo
import app.borderless.data.Server
import app.borderless.data.StatsDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Auto-visibility: keeps the set of servers that take part in scans and selection small enough
 * for quick scans, yet large enough that one of them almost always works.
 *
 * - After every full scan the pool is recomputed from statistics ([AutoPoolMath]): last day and last
 *   week, with failures from offline moments ignored, so an outage does not inflate the pool later.
 * - The expected number of working servers adapts: few answers in a scan → more, almost all → fewer.
 * - Each scan also probes a few hidden servers ([explore]) so recovered ones get noticed.
 * - If nothing in the pool answers, [rescue] first checks that the internet works directly; only then
 *   all hidden servers are probed in one scan and every one that answers joins at once. Offline → nothing changes.
 */
object AutoPool {
    private const val TAG = "AutoPool"
    private const val SHORT_MS = 86_400_000L
    private const val LONG_MS = 7 * 86_400_000L
    private const val EXPLORE = 3
    /** A rescue that found nothing waits 30 s, 1, 2, 4, then 5 minutes ([resetBackoff] when the network changes). */
    private const val RESCUE_BACKOFF_MS = 30_000L
    private const val RESCUE_BACKOFF_MAX_MS = 5 * 60_000L

    /** How old a direct internet check may be to decide whether a rescue makes sense. */
    private const val REACH_FRESH_MS = 30_000L

    /** After the first hidden server answers, faster ones get this long before the choice is made. */
    private const val GRACE_MS = 1_200L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + Errors.handler)
    private val mutex = Mutex()

    /** Ranking from the last recomputation, best first (also used to pick explore/rescue candidates). */
    @Volatile
    private var ranked: List<String> = emptyList()

    private var rescueFails = 0
    private var rescueAfter = 0L

    /** "Offline, keeping the list" was said; not again until the internet answered once. */
    private var offlineSaid = false

    /** The network got better (changed, back, reaches further): the next rescue may run at once. */
    fun resetBackoff() {
        rescueFails = 0
        rescueAfter = 0L
    }

    /** Off while countries are chosen: then their servers take part as the user shows them (see [Repo.isActive]). */
    val enabled: Boolean get() = Repo.settings.value.let { it.autoActive && it.countries.isEmpty() }

    /** Recomputes the pool now (e.g. when the mode is switched on). */
    fun refresh() {
        scope.launch { curate() }
    }

    /** After a full scan of [pool] that started at [started]: adapt the target, then recompute. */
    suspend fun onScanFinished(pool: List<Server>, started: Long) {
        if (!enabled) return
        val pings = Repo.pings.value
        val answered = pool.count { pings[it.id]?.let { p -> p.ok && p.at >= started } == true }
        val anything = pings.values.any { it.ok && it.at >= started }
        // Nothing at all answered: offline (or no route anywhere); not a reason to grow the pool.
        if (anything && pool.isNotEmpty()) {
            val before = Repo.auto.value
            val after = AutoPoolMath.adapt(before, answered, pool.size)
            if (after != before) {
                Repo.updateAuto { after }
                if (after.target != before.target) AppLog.debug(TAG, "target ${before.target} -> ${after.target} ($answered of ${pool.size} answered)")
            }
        }
        curate()
    }

    private suspend fun curate() = mutex.withLock {
        if (!enabled) return@withLock
        val servers = Repo.servers.value
        val now = System.currentTimeMillis()
        StatsDb.flush() // include the scan that just finished
        val stats = StatsDb.poolStats(now - LONG_MS, now - SHORT_MS)
        val estimates = servers.map { it.id to AutoPoolMath.estimate(stats[it.id], now) }.sortedByDescending { it.second.score }
        ranked = estimates.map { it.first }
        val before = Repo.auto.value
        val current = TunnelState.status.value.serverId?.takeIf { TunnelState.status.value.active }
        val pool = AutoPoolMath.choose(estimates, before, current, now)
        apply(before, pool, now, null)
    }

    private fun apply(before: AutoState, pool: Set<String>, now: Long, why: LText?) {
        if (pool == before.pool) return
        val added = pool - before.pool
        val removed = before.pool - pool
        Repo.updateAuto { it.copy(pool = pool, joined = AutoPoolMath.joined(it, pool, now)) }
        fun names(ids: Set<String>) = ids.joinToString { Repo.server(it)?.let(Repo::realName) ?: it }
        AppLog.debug(TAG, "pool ${pool.size} (target ${Repo.auto.value.target}): +[${names(added)}] -[${names(removed)}]")
        if (before.pool.isNotEmpty()) {
            AppLog.event(why ?: LText.of(R.string.ev_auto_pool, added.size, removed.size, pool.size))
        }
    }

    /**
     * A few hidden servers to probe along with a full scan: the most promising hidden one and the
     * ones probed longest ago, so the statistics of hidden servers do not go stale.
     */
    fun explore(): List<Server> {
        // Optional work: not on battery saver or with the screen off (unless charging).
        if (!enabled || !Power.spareBattery || Eco.on) return emptyList()
        // Careful checks: hidden servers are not shown to the network without need (SECURITY.md S9).
        if (Repo.settings.value.carefulScans) return emptyList()
        val pool = Repo.auto.value.pool
        val hidden = Repo.servers.value.filter { it.id !in pool }
        if (hidden.isEmpty()) return emptyList()
        val order = ranked.withIndex().associate { (i, id) -> id to i }
        val best = hidden.minByOrNull { order[it.id] ?: Int.MAX_VALUE }
        val pings = Repo.pings.value
        val stale = hidden.filter { it != best }.sortedBy { pings[it.id]?.at ?: 0L }.take(EXPLORE - 1)
        return listOfNotNull(best) + stale
    }

    /**
     * Nothing in the pool answered. If the internet works directly, probe every hidden server in one scan (most
     * promising first, the scan's own parallelism and timeouts): each one that answers joins the pool at once (the
     * map shows it), and the fastest that answered within [GRACE_MS] of the first is returned while the scan goes on
     * (stopped there in the battery saving mode). Offline: leave the pool alone. A rescue that found nothing backs off.
     */
    suspend fun rescue(avoid: String?): Server? = coroutineScope {
        if (!enabled) return@coroutineScope null
        val now = System.currentTimeMillis()
        if (now < rescueAfter) return@coroutineScope null
        if (NetCheck.reach(REACH_FRESH_MS) == NetCheck.Reach.NONE) {
            if (!offlineSaid) AppLog.event(LText.of(R.string.ev_auto_offline), AppLog.Kind.WARN)
            offlineSaid = true
            return@coroutineScope null
        }
        offlineSaid = false
        val pool = Repo.auto.value.pool
        val order = ranked.withIndex().associate { (i, id) -> id to i }
        val hidden = Repo.servers.value.filter { it.id !in pool && it.id != avoid }.sortedBy { order[it.id] ?: Int.MAX_VALUE }
        if (hidden.isEmpty()) return@coroutineScope null
        AppLog.event(LText.of(R.string.ev_auto_rescue, hidden.size), AppLog.Kind.WARN)
        val ids = hidden.map { it.id }.toSet()
        val found = ConcurrentHashMap<String, Int>()
        val decided = CompletableDeferred<Unit>()
        // Lives as long as the scan, not this call: servers answering after the choice still join.
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            Scanner.results.collect { (id, ms) ->
                if (ms == null || id !in ids) return@collect
                if (found.putIfAbsent(id, ms) != null) return@collect
                join(id, raise = found.size == 1)
                if (found.size == 1) launch { delay(GRACE_MS); decided.complete(Unit) }
            }
        }
        // Joins a running scan's queue if there is one.
        val scan = Scanner.start(reason = Activity.Kind.PROBE_RESCUE, only = hidden)
        scope.launch {
            scan.join()
            decided.complete(Unit)
            collector.cancel()
            if (found.isEmpty()) {
                rescueFails++
                rescueAfter = System.currentTimeMillis() + min(RESCUE_BACKOFF_MAX_MS, RESCUE_BACKOFF_MS shl min(rescueFails - 1, 4))
                AppLog.event(LText.of(R.string.ev_auto_rescue_none), AppLog.Kind.ERROR)
            } else {
                AppLog.event(LText.of(R.string.ev_auto_rescued, found.size))
            }
        }
        decided.await()
        if (found.isEmpty()) return@coroutineScope null
        rescueFails = 0
        if (Eco.on) Scanner.finishEarly(Activity.Kind.PROBE_RESCUE)
        Repo.server(found.minBy { it.value }.key)
    }

    /** A hidden server answered during a rescue: it joins the pool now ([raise]: the first one also raises the target). */
    private suspend fun join(id: String, raise: Boolean) = mutex.withLock {
        val now = System.currentTimeMillis()
        Repo.updateAuto { a ->
            if (id in a.pool) a
            else (a.pool + id).let { pool ->
                a.copy(target = if (raise) min(a.target + 1, AutoPoolMath.MAX_TARGET) else a.target, pool = pool, joined = AutoPoolMath.joined(a, pool, now))
            }
        }
        AppLog.debug(TAG, "rescue: ${Repo.server(id)?.let(Repo::realName) ?: id} answered, added (pool ${Repo.auto.value.pool.size})")
    }
}
