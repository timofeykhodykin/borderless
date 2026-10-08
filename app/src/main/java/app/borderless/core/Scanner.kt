package app.borderless.core

import app.borderless.data.Energy
import app.borderless.data.Activity
import app.borderless.data.Errors
import app.borderless.data.LText
import app.borderless.R
import app.borderless.data.AppLog
import app.borderless.Res
import app.borderless.data.Countries
import app.borderless.data.Repo
import app.borderless.data.StatsDb
import app.borderless.data.Server
import app.borderless.data.ProtocolCost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Probes all servers. Only one full scan runs at a time; the UI and the engine share it.
 * Each result is written to [Repo.pings] and emitted on [results] as soon as it is known,
 * so a caller looking for any working server does not have to wait for the slow ones.
 */
object Scanner {
    /** [startedAt]: results at or after it belong to this scan. */
    data class Progress(val done: Int, val total: Int, val startedAt: Long = 0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + Errors.handler)
    private val _results = MutableSharedFlow<Pair<String, Int?>>(extraBufferCapacity = 1024)
    val results: SharedFlow<Pair<String, Int?>> = _results

    private val _progress = MutableStateFlow<Progress?>(null)
    /** Non-null while a scan is running. */
    val progress: StateFlow<Progress?> = _progress

    private val _pending = MutableStateFlow<Set<String>>(emptySet())
    /** Servers queued or being probed right now, whose new result is not known yet. */
    val pending: StateFlow<Set<String>> = _pending

    private val _probing = MutableStateFlow<Set<String>>(emptySet())
    /** Servers being probed at this moment (a subset of [pending]; the rest are queued). */
    val probing: StateFlow<Set<String>> = _probing

    private val _skipped = MutableStateFlow<Set<String>>(emptySet())
    /**
     * Servers without any result whose last probe the scan cut short (it stopped waiting once fast servers had
     * answered): treated as not checked and kept out of sight until a probe gets a result.
     */
    val skipped: StateFlow<Set<String>> = _skipped

    /** Servers the running scan cut short (left out of its totals). */
    private val skippedThisScan = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val _plan = MutableStateFlow<List<String>>(emptyList())
    /**
     * The order the next full scan probes servers in (best recently first). Recomputed after each
     * scan and when servers are added or removed; the auto-sorted groups and heatmap show it.
     */
    val plan: StateFlow<List<String>> = _plan

    @Volatile
    private var current: Job? = null

    /** Why the running scan was started. */
    @Volatile
    private var currentReason: Activity.Kind? = null

    /** When a scan last started or ended (windows measuring the core's own cost must not overlap scans). */
    @Volatile
    var lastActiveAt = 0L
        private set

    /** Set to stop the running scan early: workers take no more servers (see [finishEarly]). */
    @Volatile
    private var stopRequested = false

    init {
        scope.launch {
            Repo.visible.map { list -> list.map { it.id } }.distinctUntilChanged().collect { replan() }
        }
    }

    /**
     * Starts a full scan, or returns the one already running. [first] ids are probed before the rest.
     * [only]: probe just these servers (a targeted rescan in the battery saving mode), in plan order.
     */
    @Synchronized
    fun start(first: List<String> = emptyList(), reason: Activity.Kind = Activity.Kind.SCAN_MANUAL, only: List<Server>? = null): Job {
        current?.takeIf { it.isActive }?.let { return it }
        val full = only == null
        val ordered = planned(only ?: Repo.activeServers, first)
        // Auto-visibility: a few hidden servers ride along (after the visible ones) to keep their stats fresh.
        val explore = if (full) AutoPool.explore() else emptyList()
        val all = ordered + explore
        Activity.count(reason)
        Activity.count(Activity.Kind.PROBE, ordered.size)
        Activity.count(Activity.Kind.PROBE_EXPLORE, explore.size)
        stopRequested = false
        skippedThisScan.clear()
        currentReason = reason
        lastActiveAt = System.currentTimeMillis()
        val job = scope.launch {
            val started = System.currentTimeMillis()
            _progress.value = Progress(0, all.size, started)
            _pending.update { it + all.map(Server::id) }
            try {
                // The whole scan is one burst of network activity for the energy estimate.
                val probed = Energy.track(reason, exchanges = all.size) { probeAll(all) }
                val stopped = probed < all.size
                if (stopped) AppLog.debug("Scanner", "scan stopped early: probed $probed of ${all.size}")
                summarize(ordered.take(minOf(probed, ordered.size)).filter { it.id !in skippedThisScan }, started, reason, stopped, full)
                refreshRanking()
                // Pool decisions need every visible server's answer.
                if (full && !stopped) AutoPool.onScanFinished(ordered, started)
            } finally {
                lastActiveAt = System.currentTimeMillis()
                _progress.value = null
                _pending.update { it - all.map(Server::id).toSet() }
            }
        }
        current = job
        return job
    }

    val isRunning: Boolean get() = current?.isActive == true

    /**
     * Stops the running scan if [reason] started it: probes in flight finish, no new ones start.
     * The battery saving mode uses it once the engine has chosen a server.
     */
    fun finishEarly(reason: Activity.Kind) {
        if (isRunning && currentReason == reason) stopRequested = true
    }

    suspend fun probeOne(server: Server, timeoutMs: Long? = null): Int? {
        _pending.update { it + server.id }
        _probing.update { it + server.id }
        try {
            val s = Repo.settings.value
            val base = s.probeTimeoutSec * 1000L
            val o = Prober.probeDetailed(server, s, timeoutMs ?: base)
            // Cut short by the adaptive timeout: we only know it is slower than the good ones,
            // not that it is down, so keep its previous result instead of marking it unavailable.
            val cutShort = o.timedOut && (timeoutMs ?: base) < base
            // Cut short: as if it had not been checked at all (no result, no sample, not in the scan's totals;
            // a server without any result yet stays out of sight until it has one).
            if (!cutShort) {
                Repo.recordPing(server.id, o.ms, probe = true)
                StatsDb.sample(server.id, o.ms, StatsDb.Kind.PROBE)
                _skipped.update { it - server.id }
            } else {
                skippedThisScan += server.id
                if (Repo.pings.value[server.id] == null) _skipped.update { it + server.id }
            }
            _results.emit(server.id to o.ms)
            return o.ms
        } finally {
            _probing.update { it - server.id }
            _pending.update { it - server.id }
        }
    }

    /** Parallel probes learned by the last scans (0 = not yet): the next scan starts from it. */
    @Volatile
    private var learnedConcurrency = 0

    /** Parallel probes the next scan starts with (for the settings screen). */
    val concurrencyNow: Int get() = learnedConcurrency.takeIf { it > 0 } ?: Repo.settings.value.effectiveConcurrency

    /** Probes [servers]; returns how many were probed (fewer if stopped early). */
    private suspend fun probeAll(servers: List<Server>): Int {
        val manual = Repo.settings.value.probeConcurrency
        val control = ConcurrencyControl(
            start = if (manual > 0) manual else learnedConcurrency.takeIf { it > 0 } ?: Repo.settings.value.effectiveConcurrency,
            adaptive = manual == 0,
        )
        val probed = probeWith(control, servers)
        if (control.adaptive) {
            if (control.limit != learnedConcurrency) AppLog.debug("Scanner", "parallel probes: ${control.limit} (${control.changes})")
            learnedConcurrency = control.limit
        }
        return probed
    }

    private suspend fun probeWith(control: ConcurrencyControl, servers: List<Server>): Int {
        val next = AtomicInteger(0)
        coroutineScope {
            val found = java.util.concurrent.ConcurrentLinkedQueue<Int>()
            var done = 0
            // A fixed pool of workers takes servers strictly in list order, so probes start in plan order
            // (one coroutine per server racing for a semaphore started them in random order). How many of
            // them probe at once is adjusted on the fly (ConcurrencyControl).
            repeat(minOf(control.max, servers.size)) {
                launch {
                    while (true) {
                        control.acquire()
                        val i = if (stopRequested) servers.size else next.getAndIncrement()
                        if (i >= servers.size) { control.release(); break }
                        val server = servers[i]
                        val typical = Repo.pings.value[server.id]?.typical
                        val timeout = adaptiveTimeout(found.toList(), synchronized(this@Scanner) { done }, servers.size)
                        val ms = try { probeOne(server, timeout) } finally { control.release() }
                        ms?.let { found += it }
                        control.onResult(ms, typical)
                        val d = synchronized(this@Scanner) { ++done }
                        _progress.value = Progress(d, servers.size, _progress.value?.startedAt ?: 0)
                    }
                }
            }
        }
        // Every index handed out below the list size was probed.
        return minOf(next.get(), servers.size)
    }

    /**
     * Timeout for the next probe of a scan. Once several fast servers answered, slow ones are not
     * worth waiting for; if nothing answered after half the list, the network itself is probably
     * slow, so wait longer than usual.
     */
    fun adaptiveTimeout(found: List<Int>, done: Int, total: Int): Long {
        val base = Repo.settings.value.probeTimeoutSec * 1000L
        val fast = found.filter { it < 1000 }.sorted()
        return when {
            fast.size >= 3 -> (fast[fast.size / 2] * 4L + 800).coerceIn(1500L, base)
            found.isEmpty() && done >= total / 2 && total >= 4 -> (base * 3 / 2).coerceAtMost(15_000L)
            else -> base
        }
    }

    /**
     * Logs and records a scan. Not every scan checks every server (auto-visibility leaves hidden ones out,
     * the battery saving mode checks only promising ones or stops once a server is chosen): the event says
     * how many were checked of how many there are, and the statistics keep both.
     */
    private fun summarize(servers: List<Server>, started: Long, reason: Activity.Kind, stopped: Boolean, full: Boolean) {
        val pings = Repo.pings.value
        val ok = servers.mapNotNull { s -> pings[s.id]?.takeIf { it.ok && it.at >= started }?.let { s to it.ms!! } }
        val best = ok.minByOrNull { it.second }
        val secs = (System.currentTimeMillis() - started) / 1000
        val all = Repo.servers.value.size
        StatsDb.scan(ok.size, servers.size, best?.second, all, reason.name)
        val scope = when {
            stopped -> LText.of(R.string.ev_scan_stopped, all - servers.size)
            !full -> LText.of(R.string.ev_scan_targeted, all - servers.size)
            all > servers.size -> LText.of(R.string.ev_scan_hidden, all - servers.size)
            else -> null
        }
        val text = if (best == null) LText.of(R.string.ev_scan_none, secs.toInt(), servers.size)
        else LText.of(R.string.ev_scan, secs.toInt(), ok.size, servers.size, Repo.displayName(best.first), best.second)
        AppLog.event(scope?.let { LText.concat(text, " · ", it) } ?: text, if (best == null) AppLog.Kind.WARN else AppLog.Kind.INFO)
    }

    /** Per-server score from the last days of statistics, refreshed after each scan. */
    @Volatile
    private var ranking: Map<String, Double> = emptyMap()

    fun refreshRanking() {
        scope.launch {
            StatsDb.flush()
            runCatching { ranking = StatsDb.ranking(System.currentTimeMillis() - RANKING_WINDOW_MS) }
            Errors.guard("protocol cost", Unit, show = false) { ProtocolCost.refresh() }
            replan()
        }
    }

    private fun replan() {
        _plan.value = order(Repo.activeServers).map { it.id }
    }

    /** Requested servers first, then the published [plan] (recomputed if it misses a server). */
    private fun planned(servers: List<Server>, first: List<String>): List<Server> {
        if (servers.any { it.id !in _plan.value }) replan()
        val index = _plan.value.withIndex().associate { (i, id) -> id to i }
        val head = first.mapNotNull { id -> servers.firstOrNull { it.id == id } }
        val headIds = head.map { it.id }.toSet()
        return head + servers.filter { it.id !in headIds }.sortedBy { index[it.id] ?: Int.MAX_VALUE }
    }

    /**
     * Servers that answered in the last few minutes first; then the rest by their score over the
     * last days (reliable and fast first). Servers without history sit in the middle: before
     * servers known to be bad.
     */
    private fun order(servers: List<Server>): List<Server> {
        val pings = Repo.pings.value
        val now = System.currentTimeMillis()
        val rank = ranking
        val eco = Repo.settings.value.ecoOn
        return servers.sortedWith(
            compareBy<Server> { s -> pings[s.id]?.let { it.ok && now - it.at < FRESH_MS } != true }
                // Battery saving mode: cheaper protocols earlier (they get the first answers).
                .thenByDescending { s -> (rank[s.id] ?: UNKNOWN_SCORE) / (if (eco) ProtocolCost.factor(s) else 1.0) }
                .thenBy { s -> pings[s.id]?.ms ?: Int.MAX_VALUE }
        )
    }

    private const val RANKING_WINDOW_MS = 3 * 86_400_000L
    private const val FRESH_MS = 10 * 60_000L
    private const val UNKNOWN_SCORE = 0.3
}
