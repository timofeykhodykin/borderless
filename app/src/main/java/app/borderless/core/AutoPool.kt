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
import app.borderless.data.Regions
import app.borderless.data.Server
import app.borderless.data.StatsDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
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
 *   hidden servers are probed in batches and the ones that answer join. Offline → nothing changes.
 */
object AutoPool {
    private const val TAG = "AutoPool"
    private const val SHORT_MS = 86_400_000L
    private const val LONG_MS = 7 * 86_400_000L
    private const val EXPLORE = 3
    private const val RESCUE_BACKOFF_MS = 60_000L
    private const val RESCUE_BACKOFF_MAX_MS = 15 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + Errors.handler)
    private val mutex = Mutex()

    /** Ranking from the last recomputation, best first (also used to pick explore/rescue candidates). */
    @Volatile
    private var ranked: List<String> = emptyList()

    private var rescueFails = 0
    private var rescueAfter = 0L

    val enabled: Boolean get() = Repo.settings.value.autoActive

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
     * Nothing in the pool answered. If the internet works directly, probe hidden servers (most
     * promising first) in batches until some answer; those join the pool and the fastest is returned.
     * If it does not, the phone is offline: leave the pool alone. Repeated misses back off.
     */
    suspend fun rescue(avoid: String?): Server? {
        if (!enabled) return null
        val now = System.currentTimeMillis()
        if (now < rescueAfter) return null
        if (!directInternet()) {
            AppLog.event(LText.of(R.string.ev_auto_offline), AppLog.Kind.WARN)
            rescueAfter = now + RESCUE_BACKOFF_MS
            return null
        }
        val pool = Repo.auto.value.pool
        val order = ranked.withIndex().associate { (i, id) -> id to i }
        val hidden = Repo.servers.value.filter { it.id !in pool && it.id != avoid }.sortedBy { order[it.id] ?: Int.MAX_VALUE }
        if (hidden.isEmpty()) return null
        AppLog.event(LText.of(R.string.ev_auto_rescue, hidden.size), AppLog.Kind.WARN)
        for (batch in hidden.chunked(Repo.settings.value.effectiveConcurrency)) {
            Activity.count(Activity.Kind.PROBE_RESCUE, batch.size)
            val results = Energy.track(Activity.Kind.PROBE_RESCUE, exchanges = batch.size) {
                coroutineScope { batch.map { s -> async { s to Scanner.probeOne(s) } }.awaitAll() }
            }
            val found = results.filter { it.second != null }
            if (found.isEmpty()) continue
            rescueFails = 0
            mutex.withLock {
                val before = Repo.auto.value
                val grown = before.copy(target = min(before.target + 1, AutoPoolMath.MAX_TARGET))
                Repo.updateAuto { grown }
                apply(grown, grown.pool + found.map { it.first.id }, System.currentTimeMillis(), LText.of(R.string.ev_auto_rescued, found.size))
            }
            return found.minBy { it.second!! }.first
        }
        rescueFails++
        rescueAfter = now + min(RESCUE_BACKOFF_MAX_MS, RESCUE_BACKOFF_MS shl min(rescueFails, 4))
        AppLog.event(LText.of(R.string.ev_auto_rescue_none), AppLog.Kind.ERROR)
        return null
    }

    /**
     * Does the internet work outside the proxy? The chosen zones' own sites first (they are the most likely to
     * answer locally), then common ones.
     */
    private suspend fun directInternet(): Boolean = withContext(Dispatchers.IO) {
        Activity.count(Activity.Kind.NET_CHECK)
        Energy.track(Activity.Kind.NET_CHECK) { directInternetNow() }
    }

    private fun directInternetNow(): Boolean =
        (Regions.of(Repo.settings.value.regions).flatMap { it.checkUrls } + COMMON_CHECK_URLS).distinct().any { url ->
            runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 3000
                c.readTimeout = 3000
                c.instanceFollowRedirects = false
                c.requestMethod = "HEAD"
                val code = c.responseCode
                c.disconnect()
                code in 100..599
            }.getOrDefault(false)
        }

    private val COMMON_CHECK_URLS = listOf("https://www.gstatic.com/generate_204", "https://cp.cloudflare.com/generate_204")
}
