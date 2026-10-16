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
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
     * The servers of the running scan: a queue later requests extend ([start] while a scan runs), so nobody joins a
     * scan that doesn't cover what they need (a failover joining a targeted rescan used to conclude "nothing works"
     * from 8 servers). Workers take servers by index; once one finds the queue empty it is closed, and a request
     * after that runs as the next scan.
     */
    private class Run(servers: List<Server>, reason: Activity.Kind, visible: Collection<String>) {
        private val list = ArrayList(servers)
        private var next = 0
        private var closed = false
        private var inFlight = 0
        val reasons = mutableSetOf(reason)

        /** Only automatic background reasons asked for it: probes are spread out in time (see [probeWith]). */
        @Synchronized
        fun spaced(): Boolean = reasons.all { it in SPACED }

        /** A server is waiting to be taken (a worker paces itself only then, not to find the queue empty). */
        @Synchronized
        fun hasNext(): Boolean = next < list.size

        /** Servers taking part (not hidden ones riding along or rescued), in the order they were queued. */
        val visible = LinkedHashSet(visible)
        /** Changes when servers are added or a probe ends: a worker with nothing to take waits for it. */
        val signal = MutableStateFlow(0)

        /**
         * The next server; [WAIT] while the queue is empty but probes are still out (servers may still be added);
         * null when it is over (empty and nothing out, or [stop]) — closed then, later requests start a new scan.
         */
        @Synchronized
        fun take(stop: Boolean): Any? {
            if (stop || (next >= list.size && inFlight == 0)) {
                closed = true
                return null
            }
            if (next >= list.size) return WAIT
            inFlight++
            return list[next++]
        }

        @Synchronized
        fun ended() {
            inFlight--
            signal.value++
        }

        companion object {
            val WAIT = Any()
        }

        /** Adds what isn't queued yet; [first] goes to the front of what hasn't started. False: closed already. */
        @Synchronized
        fun add(more: List<Server>, first: List<String>, reason: Activity.Kind, active: Set<String>): List<Server>? {
            if (closed) return null
            reasons += reason
            val known = list.map { it.id }.toSet()
            val added = more.filter { it.id !in known }
            val queued = list.subList(next, list.size).toList() + added
            val head = first.mapNotNull { id -> queued.firstOrNull { it.id == id } }
            list.subList(next, list.size).clear()
            list.addAll(head + queued.filter { it !in head })
            added.forEach { if (it.id in active) visible += it.id }
            signal.value++
            return added
        }

        @Synchronized
        fun size() = list.size

        @Synchronized
        fun all(): List<Server> = list.toList()
    }

    @Volatile
    private var run: Run? = null

    /**
     * Starts a scan of [only] (default: every server taking part, a full scan) with [first] ahead, in plan order. If a
     * scan runs already, the servers it lacks join its queue ([first] moved to the front of what hasn't started) and
     * its job is returned; it ends once everything queued, these too, is done.
     */
    @Synchronized
    fun start(first: List<String> = emptyList(), reason: Activity.Kind = Activity.Kind.SCAN_MANUAL, only: List<Server>? = null): Job {
        // Careful checks (SECURITY.md S9): automatic scans look at the server in use and the next few only, so the
        // network doesn't see the whole list at once. Failover and "Ping all" still check everything.
        val careful = only == null && reason in CAREFUL && Repo.settings.value.carefulScans
        val targets = if (careful) carefulTargets() else only
        if (careful) AppLog.debug("Scanner", "careful checks: ${reason.name} narrowed to ${targets?.size} server(s)")
        val wanted = planned(targets ?: Repo.activeServers, first)
        val active = Repo.activeServers.map { it.id }.toSet()
        current?.takeIf { it.isActive }?.let { job ->
            val added = run?.add(wanted, first, reason, active)
                // The running scan is closing: this one runs right after it.
                ?: return scope.launch { job.join(); start(first, reason, only).join() }
            if (added.isNotEmpty()) {
                if (reason.group == Activity.Group.PROBE) Activity.count(reason, added.size) else Activity.count(Activity.Kind.PROBE, added.size)
                _pending.update { it + added.map(Server::id) }
                order = run?.let { r -> r.all().map { it.id }.filter { it in r.visible } } ?: order
                AppLog.debug("Scanner", "${added.size} server(s) added to the running scan (${reason.name})")
            }
            // Someone else needs servers now: a request to stop early (battery saving) no longer holds.
            if (stopRequested && reason !in setOfNotNull(currentReason)) stopRequested = false
            return job
        }
        val full = targets == null
        val ordered = wanted
        order = ordered.map(Server::id)
        // Auto-visibility: a few hidden servers ride along (after the visible ones) to keep their stats fresh.
        val explore = if (full) AutoPool.explore() else emptyList()
        val all = ordered + explore
        // Probes of hidden servers (a rescue) are counted one by one under their own kind, the others as one scan.
        if (reason.group == Activity.Group.PROBE) Activity.count(reason, ordered.size)
        else {
            Activity.count(reason)
            Activity.count(Activity.Kind.PROBE, ordered.size)
        }
        Activity.count(Activity.Kind.PROBE_EXPLORE, explore.size)
        stopRequested = false
        currentReason = reason
        lastActiveAt = System.currentTimeMillis()
        val r = Run(all, reason, ordered.map(Server::id).filter { it in active })
        run = r
        if (r.spaced()) AppLog.debug("Scanner", "background scan of ${all.size} server(s): probes spread out ${SPACING_MS / 1000}–${(SPACING_MS + SPACING_JITTER_MS) / 1000} s apart")
        val job = scope.launch {
            val started = System.currentTimeMillis()
            _progress.value = Progress(0, all.size, started)
            _pending.update { it + all.map(Server::id) }
            try {
                // The whole scan is one burst of network activity for the energy estimate.
                val probed = Energy.track(reason, exchanges = all.size) { probeAll(r) }
                val queued = r.all()
                val stopped = probed < queued.size
                if (stopped) AppLog.debug("Scanner", "scan stopped early: probed $probed of ${queued.size}")
                val done = queued.take(probed)
                val checked = done.filter { it.id in r.visible }
                // Covered everything taking part (whoever asked for what): a full scan for the statistics and the pool.
                val complete = !stopped && Repo.activeServers.all { srv -> done.any { it.id == srv.id } }
                // A rescue of hidden servers reports itself (AutoPool) and isn't a scan of the servers in use: kept out of
                // the scan statistics ("servers available" chart).
                if (r.reasons != setOf(Activity.Kind.PROBE_RESCUE)) summarize(checked, started, reason, stopped, complete)
                // Nothing answered at all: if the network itself doesn't reach out, the failures don't count (NetCheck).
                if (complete && checked.none { Repo.pings.value[it.id]?.let { p -> p.ok && p.at >= started } == true }) NetCheck.reach()
                refreshRanking()
                // Pool decisions need every visible server's answer.
                if (complete) AutoPool.onScanFinished(checked, started)
            } finally {
                lastActiveAt = System.currentTimeMillis()
                _progress.value = null
                _pending.update { it - r.all().map(Server::id).toSet() }
            }
        }
        current = job
        return job
    }

    val isRunning: Boolean get() = current?.isActive == true

    /** The check order of the latest scan (servers it probes, in the order it starts them; hidden ones excluded). */
    @Volatile
    private var order: List<String> = emptyList()

    /**
     * Where [id] stands (1-based) in the check order that found it, and of how many: the latest scan's order, or
     * the order a scan would use now ([first] first) when the choice came from fresh results without a scan.
     */
    fun positionOf(id: String, first: List<String> = emptyList()): Pair<Int, Int>? {
        val o = order.takeIf { id in it } ?: planned(Repo.activeServers, first).map(Server::id)
        val i = o.indexOf(id)
        return if (i < 0) null else (i + 1) to o.size
    }

    /**
     * Servers that just appeared (a subscription that downloaded for the first time or grew): probed as soon as
     * no scan runs, so they don't sit on the main screen without a result. A scan that runs anyway (often the
     * caller's own full one, started right after) may already cover them: only the ones still without a result
     * are probed.
     */
    fun probeNew(ids: Collection<String>) {
        if (ids.isEmpty()) return
        scope.launch {
            delay(NEW_SERVERS_DELAY_MS)
            while (true) current?.takeIf { it.isActive }?.join() ?: break
            val todo = ids.mapNotNull(Repo::server).filter { Repo.isActive(it) && Repo.pings.value[it.id] == null }
            if (todo.isEmpty()) return@launch
            AppLog.debug("Scanner", "probing ${todo.size} new server(s)")
            start(reason = Activity.Kind.SCAN_NEW, only = todo).join()
        }
    }

    /**
     * Stops the running scan if [reason] started it: probes in flight finish, no new ones start.
     * The battery saving mode uses it once the engine has chosen a server.
     */
    fun finishEarly(reason: Activity.Kind) {
        if (isRunning && run?.reasons == setOf(reason)) stopRequested = true
    }

    suspend fun probeOne(server: Server, timeoutMs: Long? = null): Int? {
        _pending.update { it + server.id }
        _probing.update { it + server.id }
        try {
            val s = Repo.settings.value
            val o = Prober.probeDetailed(server, s, timeoutMs ?: s.probeTimeoutSec * 1000L)
            Repo.recordPing(server.id, o.ms, probe = true)
            StatsDb.sample(server.id, o.ms, StatsDb.Kind.PROBE)
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

    /** Probes the servers of [r] (its queue may grow meanwhile); returns how many were probed (fewer if stopped early). */
    private suspend fun probeAll(r: Run): Int {
        val manual = Repo.settings.value.probeConcurrency
        val control = ConcurrencyControl(
            start = if (manual > 0) manual else learnedConcurrency.takeIf { it > 0 } ?: Repo.settings.value.effectiveConcurrency,
            adaptive = manual == 0,
        )
        val probed = probeWith(control, r)
        if (control.adaptive) {
            if (control.limit != learnedConcurrency) AppLog.debug("Scanner", "parallel probes: ${control.limit} (${control.changes})")
            learnedConcurrency = control.limit
        }
        return probed
    }

    /** The server in use and the next [CAREFUL_NEXT] of the plan (see [start]). */
    private fun carefulTargets(): List<Server> {
        val active = Repo.activeServers.associateBy { it.id }
        val cur = TunnelState.status.value.serverId?.takeIf { it in active }
        return (listOfNotNull(cur) + _plan.value.filter { it in active && it != cur }.take(CAREFUL_NEXT)).mapNotNull(active::get)
    }

    private suspend fun probeWith(control: ConcurrencyControl, r: Run): Int {
        val taken = AtomicInteger(0)
        // Background scans start one probe every few seconds (random gap) instead of a burst, so the network sees
        // no fan-out to the whole list at once (SECURITY.md S9). Anything urgent joining the scan ends the spacing.
        val pace = kotlinx.coroutines.sync.Mutex()
        var lastStart = 0L
        val base = Repo.settings.value.probeTimeoutSec * 1000L
        coroutineScope {
            val found = java.util.concurrent.ConcurrentLinkedQueue<Int>()
            var done = 0
            // Probes still waiting for an answer after their slot was handed on (see below).
            val waiting = AtomicInteger(0)
            fun finished(ms: Int?) {
                ms?.let { found += it }
                val d = synchronized(this@Scanner) { ++done }
                _progress.value = Progress(d, r.size(), _progress.value?.startedAt ?: 0)
                r.ended()
            }
            // A fixed pool of workers takes servers strictly in queue order, so probes start in plan order
            // (one coroutine per server racing for a semaphore started them in random order). How many of
            // them probe at once is adjusted on the fly (ConcurrencyControl). As many workers as the limit can
            // reach: the queue may grow while the scan runs.
            repeat(control.max) {
                launch {
                    while (true) {
                        control.acquire()
                        if (r.spaced() && r.hasNext()) pace.withLock {
                            val wait = lastStart + SPACING_MS + kotlin.random.Random.nextLong(SPACING_JITTER_MS) - System.currentTimeMillis()
                            if (lastStart > 0 && wait > 0) withTimeoutOrNull(wait) { r.signal.first { !r.spaced() } }
                            lastStart = System.currentTimeMillis()
                        }
                        val seen = r.signal.value
                        val next = r.take(stopRequested)
                        if (next == null) { control.release(); break }
                        if (next === Run.WAIT) {
                            control.release()
                            r.signal.first { it != seen }
                            continue
                        }
                        val server = next as Server
                        taken.incrementAndGet()
                        val typical = Repo.pings.value[server.id]?.typical
                        // Every probe waits the full timeout (longer if the network looks slow), so each server
                        // gets a real result. Once fast servers have answered, a slow probe stops holding its slot
                        // after the adaptive wait: it keeps waiting for its answer on its own, and the queue moves on.
                        val wait = adaptiveTimeout(found.toList(), synchronized(this@Scanner) { done }, r.size())
                        val probe = async { probeOne(server, maxOf(wait, base)) }
                        val early = if (wait < base && waiting.get() < control.limit) withTimeoutOrNull(wait) { probe.await() } else null
                        if (early != null || probe.isCompleted) {
                            val ms = early ?: probe.await()
                            control.release()
                            control.onResult(ms, typical)
                            finished(ms)
                        } else if (wait < base && waiting.get() < control.limit) {
                            control.release()
                            control.onResult(null, typical)
                            waiting.incrementAndGet()
                            launch { val ms = try { probe.await() } finally { waiting.decrementAndGet() }; finished(ms) }
                        } else {
                            val ms = try { probe.await() } finally { control.release() }
                            control.onResult(ms, typical)
                            finished(ms)
                        }
                    }
                }
            }
        }
        // Every server handed out was probed.
        return taken.get()
    }

    /**
     * How long the next probe of a scan holds its slot. Once several fast servers answered, slow ones are not
     * worth holding the queue for (they still get their answer, see [probeWith]); if nothing answered after half
     * the list, the network itself is probably slow, so the probe waits longer than usual.
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

    /** Scans spread out in time: only automatic background rescans (the user waits for the others). */
    private val SPACED = setOf(Activity.Kind.SCAN_BACKGROUND)
    private const val SPACING_MS = 2_000L
    private const val SPACING_JITTER_MS = 4_000L

    /** Scans narrowed by careful checks: the automatic ones that only look for something faster or fresher. */
    private val CAREFUL = setOf(Activity.Kind.SCAN_BACKGROUND, Activity.Kind.SCAN_OPEN, Activity.Kind.SCAN_STRATEGY)
    private const val CAREFUL_NEXT = 3

    private const val RANKING_WINDOW_MS = 3 * 86_400_000L
    private const val FRESH_MS = 10 * 60_000L
    private const val UNKNOWN_SCORE = 0.3
    /** Lets the caller of a subscription update start its own scan first (see [probeNew]). */
    private const val NEW_SERVERS_DELAY_MS = 2_000L
}
