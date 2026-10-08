package app.borderless.core

import app.borderless.data.Energy
import app.borderless.data.Activity
import app.borderless.data.LText
import app.borderless.R
import android.util.Log
import app.borderless.Res
import app.borderless.config.LinkParser
import app.borderless.config.XrayConfig
import app.borderless.data.AppLog
import app.borderless.data.AppSettings
import app.borderless.data.Countries
import app.borderless.data.EcoMath
import app.borderless.data.ProtocolCost
import app.borderless.data.Importer
import app.borderless.data.NoServerAction
import app.borderless.data.Errors
import app.borderless.data.Repo
import app.borderless.data.Strategy
import app.borderless.data.Server
import app.borderless.data.StatsDb
import app.borderless.data.lastProbe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the tunnel on a working, reasonably fast server. How eagerly it moves is set by the
 * [app.borderless.data.Strategy] (see [AppSettings.policy]):
 *
 * - While the current server answers health checks, stay on it. Every `rescanMin` minutes probe
 *   the others in the background and switch only if one is clearly faster (both `gainPercent` and
 *   `gainMs`) and we have been on the current one for at least `minStayMin`.
 * - After [AppSettings.failThreshold] failed checks in a row, probe everything (recently good servers
 *   first) and switch to the first one that answers, after a short grace period to prefer a faster one.
 * - If nothing answers, pass traffic directly (or pause it), and retry every [AppSettings.retryIntervalSec].
 *
 * All decisions run on one coroutine that handles timer ticks and [Event]s in order.
 */
class Engine(private val host: Host) {
    interface Host {
        val tunFd: Int
        fun hasNetwork(): Boolean
        fun isScreenOn(): Boolean
    }

    sealed interface Event {
        data object NetworkChanged : Event
        data class Select(val serverId: String) : Event
        /** Probe everything now and move to the best server (user asked for it). */
        data object FindBest : Event
        /** Settings that affect the generated config changed. */
        data object Reconfigure : Event
        data object ScanFinished : Event
        /** A server was disabled; leave it if it is the current one. */
        data class Disabled(val serverId: String) : Event
        /** Servers appeared or all disappeared (added, deleted, hidden). */
        data object ServersChanged : Event
        /** New routing lists were downloaded: restart the core so it loads them. */
        data object RestartCore : Event
        /** The strategy in effect changed (by the user, the battery saving mode or the default): choose by it now. */
        data object StrategyChanged : Event
    }

    private enum class Mode { PROXY, DIRECT, PAUSE }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + Errors.handler)
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val core = CoreRunner()

    private var mode: Mode? = null
    private var current: Server? = null
    private var connectedAt = 0L
    private var failStreak = 0
    private var lastRescanAt = 0L
    private var nextRetryAt = 0L
    private var lastSubCheckAt = 0L
    private var lastPing: Int? = null
    private var noNetwork = false

    /** "No servers" was announced (once until there are servers again). */
    private var noServersLogged = false

    /** The TUN descriptor the running core reads; the service may hand over a fresh one (network change). */
    private var coreFd = 0

    private var recordedPhase: Phase? = null
    private var recordedServer: String? = null
    private var recordedAt = 0L

    /** Verify a server we have just switched to without waiting a full health interval. */
    private var checkSoon = false

    /** Last time the current server was pinged through the core (a real request over the network). */
    private var lastActiveCheck = 0L

    /** Rescans skipped in a row because no server could beat the current one by enough. */
    private var skippedRescans = 0


    /**
     * A window measuring the core's own CPU per byte for the current server's protocol class
     * (ProtocolCost): process CPU minus the app's tracked actions, while one server carries the
     * traffic, the app's screens stay closed and no scan runs.
     */
    private var costStart = 0L
    private var costCpu = 0L
    private var costAttributed = 0L
    private var costBytes = 0L
    private var costServer: String? = null

    /** Traffic since the last write to the stats DB: proxy up/down, direct up/down. */
    private val trafficPending = LongArray(4)
    private var trafficFlushedAt = System.currentTimeMillis()

    /** Servers that recently failed while in use, excluded from optimisation until the given time. */
    private val penalty = HashMap<String, Long>()

    private val settings: AppSettings get() = Repo.settings.value

    fun start(preferredId: String?) {
        // Whoever changes the strategy (main screen, battery saving mode, settings), the server is chosen by it.
        scope.launch {
            Repo.settings.map { it.strategy }.distinctUntilChanged().drop(1).collect { send(Event.StrategyChanged) }
        }
        // No servers at all: nothing to retry until one is added; then look at once.
        scope.launch {
            Repo.visible.map { it.isEmpty() }.distinctUntilChanged().drop(1).collect { send(Event.ServersChanged) }
        }
        scope.launch {
            try {
                run(preferredId)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "engine crashed", e)
                Errors.report(e, "engine")
                TunnelState.update { it.copy(error = e.message) }
            }
        }
    }

    fun send(e: Event) {
        events.trySend(e)
    }

    suspend fun stop() {
        scope.cancel()
        Errors.guard("traffic stats", Unit, show = false) { readTraffic(); flushTraffic() }
        core.stop()
    }

    /**
     * Reads Xray's per-outbound byte counters (they reset on read) and returns the proxy's
     * (uplink, downlink) since the last read. Totals go to the stats DB every few minutes.
     */
    private fun readTraffic(): Pair<Long, Long> {
        val t = Errors.guard("traffic stats", emptyMap(), show = false) { core.takeTraffic() }
        val proxy = t["proxy"] ?: (0L to 0L)
        val direct = t["direct"] ?: (0L to 0L)
        trafficPending[0] += proxy.first; trafficPending[1] += proxy.second
        trafficPending[2] += direct.first; trafficPending[3] += direct.second
        if (System.currentTimeMillis() - trafficFlushedAt >= TRAFFIC_FLUSH_MS) flushTraffic()
        Errors.guard("protocol cost", Unit, show = false) { measureCost(proxy.first + proxy.second) }
        return proxy
    }

    private fun measureCost(bytes: Long) {
        val now = System.currentTimeMillis()
        val cur = current?.takeIf { mode == Mode.PROXY }
        fun restart() {
            costStart = if (cur != null) now else 0L
            costCpu = android.os.Process.getElapsedCpuTime()
            costAttributed = Energy.attributedTotalMs
            costBytes = 0
            costServer = cur?.id
        }
        if (cur == null || costStart == 0L || costServer != cur.id) return restart()
        costBytes += bytes
        if (now - costStart < COST_WINDOW_MS) return
        val quiet = !Activity.uiVisible && Activity.uiChangedAt < costStart && !Scanner.isRunning && Scanner.lastActiveAt < costStart
        val cpu = android.os.Process.getElapsedCpuTime() - costCpu - (Energy.attributedTotalMs - costAttributed)
        if (quiet && costBytes >= COST_MIN_BYTES && cpu >= 0) {
            StatsDb.coreCost(now, ProtocolCost.of(cur).key, costBytes, cpu)
            if (settings.verboseLog) AppLog.debug(TAG, "core cost: ${ProtocolCost.of(cur).key}, ${costBytes / 1024} KB, $cpu ms CPU")
        }
        restart()
    }

    /**
     * The server to take among the ones that answered ([ok]: id → ms): the fastest; in the battery
     * saving mode the one with the best latency weighted by its protocol's energy cost (never one much
     * slower than the fastest, see [ProtocolCost.adjusted]).
     */
    private fun choose(ok: Map<String, Int>): String? {
        val fastest = ok.minByOrNull { it.value } ?: return null
        if (!settings.ecoOn) return fastest.key
        val servers = ok.keys.mapNotNull(Repo::server)
        if (servers.isEmpty()) return fastest.key
        val cheapest = servers.minOf(ProtocolCost::factor)
        val pick = servers.minBy { ProtocolCost.adjusted(ok.getValue(it.id), ProtocolCost.factor(it), cheapest, fastest.value) }
        if (pick.id != fastest.key) {
            AppLog.debug(
                TAG, "battery saving: ${pick.name} (${ok[pick.id]} ms, ${ProtocolCost.describe(pick)}) instead of the fastest " +
                    "${Repo.server(fastest.key)?.let { "${it.name} (${fastest.value} ms, ${ProtocolCost.describe(it)})" }}",
            )
        }
        return pick.id
    }

    /** Writes the pending traffic, the proxied part counted for the server in use (see [switchTo]). */
    private fun flushTraffic() {
        StatsDb.traffic(trafficPending[0], trafficPending[1], trafficPending[2], trafficPending[3], current?.id)
        trafficPending.fill(0)
        trafficFlushedAt = System.currentTimeMillis()
    }

    // ------------------------------------------------------------------ main loop

    private suspend fun run(preferredId: String?) {
        publish(Phase.SEARCHING)
        step("connect") { initialConnect(preferredId) }
        while (true) {
            val wait = step("schedule", 1_000L) { nextDelay() }
            val event = withTimeoutOrNull(wait.coerceAtLeast(1)) { events.receive() }
            step(event?.javaClass?.simpleName ?: "tick") {
                when (event) {
                    null -> tick()
                    Event.NetworkChanged -> onNetworkChanged()
                    is Event.Select -> select(event.serverId)
                    Event.FindBest -> findBest()
                    Event.Reconfigure -> reconfigure()
                    Event.ScanFinished -> considerSwitch()
                    is Event.Disabled -> if (event.serverId == current?.id && mode == Mode.PROXY) failover()
                    Event.StrategyChanged -> onStrategyChanged()
                    Event.RestartCore -> restartCore()
                    Event.ServersChanged -> if (mode == Mode.DIRECT || mode == Mode.PAUSE) { if (nothingToUse()) enterFallback(noNetwork) else retry() }
                }
            }
            step("subscriptions") { maybeUpdateSubscriptions() }
            step("heartbeat") { heartbeat() }
        }
    }

    /**
     * Runs one step of the loop. A failure is reported but does not stop the engine; if no core is
     * running yet, traffic falls back to direct (or paused) and the usual retry takes over.
     */
    private suspend fun <T> step(what: String, default: T, block: suspend () -> T): T = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Errors.report(e, "engine: $what")
        // Nothing running yet (failed before any core started): keep the phone online.
        if (mode == null) runCatching { enterFallback() }
        default
    }

    private suspend fun step(what: String, block: suspend () -> Unit) = step(what, Unit, block)

    /** Re-records the current phase now and then, so durations survive the process being killed. */
    private fun heartbeat() {
        val p = recordedPhase ?: return
        if (System.currentTimeMillis() - recordedAt >= StatsDb.HEARTBEAT_MS) {
            recordedAt = System.currentTimeMillis()
            StatsDb.phase(p, recordedServer, recordedAt)
            // Sample first, then write: the sample's costs reach the DB now, not 5 minutes later.
            Activity.samplePower()
            Activity.flush()
        }
    }

    private fun nextDelay(): Long {
        val s = settings
        return when (mode) {
            // Ticks only read local traffic counters; with the screen off they can be sparser.
            Mode.PROXY -> if (checkSoon) VERIFY_MS else if (failStreak > 0) QUICK_RECHECK_MS
            else (if (Power.screenOn) s.healthIntervalSec * 1000L else maxOf(60_000L, s.healthIntervalSec * 3000L)) *
                (if (s.ecoOn) EcoMath.TICK_FACTOR else 1)
            Mode.DIRECT, Mode.PAUSE -> if (nothingToUse()) NO_SERVERS_TICK_MS else nextRetryAt - System.currentTimeMillis()
            null -> 1000L
        }
    }

    private suspend fun tick() {
        when (mode) {
            Mode.PROXY -> healthCheck()
            Mode.DIRECT, Mode.PAUSE -> retry()
            null -> initialConnect(null)
        }
    }

    // ------------------------------------------------------------------ connecting

    private suspend fun initialConnect(preferredId: String?) {
        if (!host.hasNetwork()) {
            enterFallback(noNetwork = true)
            return
        }
        // Keep the phone online while we look for a server.
        enterFallbackMode()
        if (nothingToUse()) {
            enterFallback()
            return
        }
        publish(Phase.SEARCHING)
        // Manual strategy: go straight to the chosen server, whatever its last ping was.
        val manual = Repo.server(preferredId)?.takeIf { !settings.policy.failover && Repo.isActive(it) }
        if (manual != null) {
            if (!switchTo(manual, LText.of(R.string.ann_connected))) enterFallback()
            return
        }
        AppLog.event(LText.of(R.string.ev_searching))
        val server = findWorking(preferredId) ?: AutoPool.rescue(null)
        if (server != null && switchTo(server, LText.of(R.string.ann_connected))) StatsDb.event(StatsDb.Event.INITIAL, server.id)
        else enterFallback()
    }

    /** Starts the core with [server]. [announce] is the event text logged on success (null: silent). */
    private suspend fun switchTo(server: Server, announce: LText?): Boolean {
        val outbounds = try {
            LinkParser.parse(server.link).outbounds
        } catch (e: Exception) {
            AppLog.event(LText.of(R.string.ev_bad_config, label(server), e.message.orEmpty()), AppLog.Kind.ERROR)
            Repo.recordPing(server.id, null)
            return false
        }
        return try {
            // Counters restart with the core; what the previous server carried is written for it now.
            readTraffic()
            flushTraffic()
            AppLog.debug(TAG, "starting core: proxy via ${server.name} (${server.protocol}, ${server.host}:${server.port})")
            val fd = host.tunFd
            Energy.track(Activity.Kind.CORE_START) { core.start(xrayConfig(XrayConfig.Mode.PROXY, outbounds), fd) }
            coreFd = fd
            Activity.count(Activity.Kind.CORE_START)
            mode = Mode.PROXY
            current = server
            connectedAt = System.currentTimeMillis()
            failStreak = 0
            checkSoon = true
            lastPing = Repo.pings.value[server.id]?.ms
            if (lastRescanAt == 0L) lastRescanAt = connectedAt
            Repo.updateSettings { it.copy(lastServerId = server.id) }
            publish(Phase.CONNECTED)
            // Routing lists a zone still lacks (the source may only be reachable through a server): try now.
            if (GeoLists.missing().isNotEmpty()) scope.launch { GeoLists.updateIfDue(connected = true) }
            // Subscriptions added while their provider couldn't be reached: now there is a connection.
            scope.launch { app.borderless.data.Importer.pendingAfterConnect() }
            announce?.let { AppLog.event(LText.concat(it, " ", label(server), lastPing?.let { ms -> LText.concat(" · ", LText.of(R.string.ms, ms)) } ?: ""), AppLog.Kind.GOOD) }
            true
        } catch (e: Exception) {
            AppLog.event(LText.of(R.string.ev_core_failed_server, label(server), e.message.orEmpty()), AppLog.Kind.ERROR)
            AppLog.debug(TAG, "core start failed", e)
            Repo.recordPing(server.id, null)
            false
        }
    }

    private fun xrayConfig(mode: XrayConfig.Mode, outbounds: List<kotlinx.serialization.json.JsonObject>): String {
        val s = settings
        val cfg = XrayConfig.build(mode, outbounds, s, logLevel = xrayLogLevel(s), accessLog = xrayAccessLog(s))
        if (s.verboseLog) AppLog.debug(TAG, "config: " + redact(cfg))
        return cfg
    }

    /** Starts the core without a proxy so traffic keeps flowing (or is paused) while there is no server. */
    /**
     * No server to use at all: none added, or all hidden by hand (auto-visibility may still bring hidden ones back).
     * Then traffic simply goes direct — pausing it would cut the phone off with nothing to wait for.
     */
    private fun nothingToUse(): Boolean =
        Repo.activeServers.isEmpty() && (Repo.servers.value.isEmpty() || !settings.autoActive)

    private suspend fun enterFallbackMode() {
        val m = if (settings.noServerAction == NoServerAction.PAUSE && !nothingToUse()) Mode.PAUSE else Mode.DIRECT
        // The last bytes of the server we are leaving are written for it.
        Errors.guard("traffic stats", Unit, show = false) { readTraffic(); flushTraffic() }
        AppLog.debug(TAG, "starting core: ${if (m == Mode.PAUSE) "pause" else "direct"} mode")
        try {
            val fd = host.tunFd
            Energy.track(Activity.Kind.CORE_START) {
                core.start(xrayConfig(if (m == Mode.PAUSE) XrayConfig.Mode.PAUSE else XrayConfig.Mode.DIRECT, emptyList()), fd)
            }
            coreFd = fd
            Activity.count(Activity.Kind.CORE_START)
        } catch (e: Exception) {
            AppLog.event(LText.of(R.string.ev_core_failed, e.message.orEmpty()), AppLog.Kind.ERROR)
            AppLog.debug(TAG, "fallback core failed", e)
        }
        mode = m
        current = null
        lastPing = null
    }

    private suspend fun enterFallback(noNetwork: Boolean = false) {
        val wasFallback = mode == Mode.DIRECT || mode == Mode.PAUSE
        val hadNoNetwork = this.noNetwork
        val none = nothingToUse()
        // Pausing with no server at all would cut the phone off: switch to direct (and back once servers exist).
        val wantPause = settings.noServerAction == NoServerAction.PAUSE && !none
        if (!wasFallback || (mode == Mode.PAUSE) != wantPause) enterFallbackMode()
        this.noNetwork = noNetwork
        val retry = settings.retryIntervalSec
        when {
            noNetwork && !hadNoNetwork -> AppLog.event(LText.of(R.string.ev_no_network), AppLog.Kind.WARN)
            noNetwork -> {}
            none -> if (!noServersLogged) { noServersLogged = true; AppLog.event(LText.of(R.string.ev_no_servers), AppLog.Kind.WARN) }
            !wasFallback || hadNoNetwork -> AppLog.event(
                LText.of(if (mode == Mode.PAUSE) R.string.ev_down_paused else R.string.ev_down_direct, app.borderless.data.Units.durationL(retry.toLong())),
                AppLog.Kind.WARN,
            )
            else -> AppLog.debug(TAG, "retry: still no working server")
        }
        if (!none) noServersLogged = false
        // Not stretched in the battery saving mode either: a working connection comes first.
        nextRetryAt = System.currentTimeMillis() + retry * 1000L
        publish(
            when {
                noNetwork -> Phase.NO_NETWORK
                mode == Mode.PAUSE -> Phase.PAUSED
                else -> Phase.DIRECT
            }
        )
    }

    // ------------------------------------------------------------------ health & failover

    /**
     * Is the current server still working? Usually answered without touching the network: if data
     * keeps coming back through the proxy, it works. A real ping through the core is sent when
     * traffic looks stuck (sent but nothing received), right after a switch or a failure, every
     * 30 s while traffic flows anyway (the radio is awake, the ping is nearly free), and otherwise
     * every few minutes (sparser with the screen off, on mobile data, in battery saver).
     */
    private suspend fun healthCheck() {
        val cur = current ?: return enterFallback()
        val s = settings
        val now = System.currentTimeMillis()
        val (up, down) = readTraffic()
        val stalled = up >= STALL_UP_BYTES && down < STALL_DOWN_BYTES
        // While traffic flows the radio is awake anyway, so a ping on top costs next to nothing:
        // ride along often (dense latency data while the phone is used), stay quiet when idle.
        val busy = up + down >= BUSY_BYTES
        if (busy) Energy.userTraffic()
        val rideEvery = (if (Power.screenOn) RIDE_ALONG_MS else RIDE_ALONG_MS * 2) * (if (s.ecoOn) EcoMath.RIDE_FACTOR else 1)
        val rideAlong = busy && now - lastActiveCheck >= rideEvery
        val due = checkSoon || failStreak > 0 || rideAlong || now - lastActiveCheck >= activeInterval()
        if (!due && !stalled) {
            // Bytes flowing back (or nothing to do): no need to wake the radio for a ping.
            maybeStartRescan(busy)
            return
        }
        if (stalled) AppLog.debug(TAG, "traffic looks stuck (up $up B, down $down B): checking")
        val kind = when {
            checkSoon -> Activity.Kind.PING_VERIFY
            failStreak > 0 -> Activity.Kind.PING_FAILED
            stalled -> Activity.Kind.PING_STALL
            rideAlong -> Activity.Kind.PING_RIDE
            else -> Activity.Kind.PING_PERIODIC
        }
        Activity.count(kind)
        checkSoon = false
        lastActiveCheck = now
        val ms = Energy.track(kind) { core.measure(s.testUrl, s.probeTimeoutSec * 1000L + 2000) }
        if (ms != null) {
            if (failStreak > 0) AppLog.event(LText.of(R.string.ev_back, label(cur), ms), AppLog.Kind.GOOD)
            StatsDb.sample(cur.id, ms, StatsDb.Kind.HEALTH)
            if (s.verboseLog) AppLog.debug(TAG, "health ${cur.name}: $ms ms")
            failStreak = 0
            lastPing = ms
            Repo.recordPing(cur.id, ms)
            publish(Phase.CONNECTED)
            maybeStartRescan(busy)
            return
        }
        if (!host.hasNetwork()) {
            enterFallback(noNetwork = true)
            return
        }
        failStreak++
        StatsDb.sample(cur.id, null, StatsDb.Kind.HEALTH)
        val threshold = s.failThreshold.coerceAtLeast(1)
        AppLog.debug(TAG, "health ${cur.name}: failed ($failStreak/$threshold)")
        if (failStreak >= threshold && !s.policy.failover) {
            // Manual strategy: report, keep the server and keep checking at the normal pace.
            AppLog.event(LText.of(R.string.ev_lost_manual, label(cur)), AppLog.Kind.ERROR)
            Repo.recordPing(cur.id, null)
            lastPing = null
            failStreak = 0
            publish(Phase.CONNECTED)
        } else if (failStreak >= threshold) {
            AppLog.event(LText.of(R.string.ev_lost, label(cur)), AppLog.Kind.ERROR)
            Repo.recordPing(cur.id, null)
            failover()
        } else {
            AppLog.event(LText.of(R.string.ev_check_failed, label(cur), failStreak, threshold), AppLog.Kind.WARN)
        }
    }

    private suspend fun failover() {
        val lost = current
        lost?.let { penalty[it.id] = System.currentTimeMillis() + PENALTY_MS }
        StatsDb.event(StatsDb.Event.LOST, lost?.id)
        publish(Phase.SEARCHING)
        val server = findWorking(null, avoid = lost?.id) ?: AutoPool.rescue(lost?.id)
        if (server != null && switchTo(server, LText.of(R.string.ann_switched))) StatsDb.event(StatsDb.Event.FAILOVER, server.id, lost?.id)
        else enterFallback()
    }

    private suspend fun retry() {
        if (!host.hasNetwork()) {
            enterFallback(noNetwork = true)
            return
        }
        if (nothingToUse()) {
            enterFallback()
            return
        }
        TunnelState.update { it.copy(phase = Phase.SEARCHING) }
        AppLog.debug(TAG, "retry: probing servers")
        val last = Repo.server(settings.manualServerId ?: settings.lastServerId)?.takeIf { Repo.isActive(it) }
        if (!settings.policy.failover && last != null) {
            if (switchTo(last, LText.of(R.string.ann_back))) StatsDb.event(StatsDb.Event.RETRY, last.id) else enterFallback()
            return
        }
        val server = findWorking(settings.lastServerId) ?: AutoPool.rescue(null)
        if (server != null && switchTo(server, LText.of(R.string.ann_back))) StatsDb.event(StatsDb.Event.RETRY, server.id)
        else enterFallback()
    }

    /**
     * Probes servers (preferred and recently good ones first) and returns as soon as a good
     * choice is known: the preferred server if it answers, otherwise the fastest server that
     * answered within [GRACE_MS] after the first success. The scan itself continues in the
     * background so the heatmap fills in.
     */
    private suspend fun findWorking(preferredId: String?, avoid: String? = null): Server? = coroutineScope {
        if (Repo.activeServers.isEmpty()) return@coroutineScope null
        val startedAt = System.currentTimeMillis()
        val ok = ConcurrentHashMap<String, Int>()
        val decided = CompletableDeferred<Unit>()
        // A scan may already be running (e.g. started when the app was opened): use what it found.
        val active = Repo.activeServers.map { it.id }.toSet()
        Repo.pings.value.forEach { (id, p) ->
            if (p.ok && startedAt - p.at < FRESH_MS && id != avoid && id in active) ok[id] = p.ms!!
        }
        if (preferredId != null && ok.containsKey(preferredId)) decided.complete(Unit)
        else if (ok.isNotEmpty()) launch { delay(GRACE_MS); decided.complete(Unit) }
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            Scanner.results.collect { (id, ms) ->
                // Hidden servers probed along with the scan (auto-visibility) are not candidates.
                if (ms == null || id == avoid || !Repo.isActive(id)) return@collect
                ok[id] = ms
                if (id == preferredId) decided.complete(Unit)
                else if (ok.size == 1) launch { delay(GRACE_MS); decided.complete(Unit) }
            }
        }
        // Battery saving mode: if fresh results already settle it (the preferred server answered within
        // the last minute), no scan at all.
        if (settings.ecoOn && decided.isCompleted) {
            AppLog.debug(TAG, "battery saving: fresh results suffice, no scan")
        } else {
            val scan = Scanner.start(first = listOfNotNull(preferredId), reason = Activity.Kind.SCAN_ENGINE)
            launch { scan.join(); decided.complete(Unit) }
        }
        decided.await()
        collector.cancel()
        coroutineContext.cancelChildren()
        // Normally the scan goes on so the map fills in; the battery saving mode stops it here.
        if (settings.ecoOn) Scanner.finishEarly(Activity.Kind.SCAN_ENGINE)

        ok.keys.removeAll { !Repo.isActive(it) }
        val id = preferredId?.takeIf { ok.containsKey(it) } ?: choose(ok)
        AppLog.debug(TAG, "findWorking(preferred=${Repo.server(preferredId)?.name}, avoid=${Repo.server(avoid)?.name}): ${ok.size} answered so far -> ${Repo.server(id)?.name}")
        Repo.server(id)
    }

    // ------------------------------------------------------------------ optimisation

    /** How often to ping the current server while traffic says it works. */
    private fun activeInterval(): Long {
        val base = settings.latencyCheckMin * 60_000L * (if (Power.screenOn) 1 else 3) * (if (settings.ecoOn) EcoMath.LATENCY_FACTOR else 1)
        return (base * Power.stretch()).toLong()
    }

    /** [busy]: the user's traffic is flowing (the radio is awake anyway). */
    private fun maybeStartRescan(busy: Boolean) {
        val s = settings
        val p = s.policy
        if (!p.optimize) return
        if ((s.pauseScansScreenOff || s.ecoOn) && !Power.screenOn) return
        // Battery saving mode: a faster server only matters while traffic flows, and then the radio is
        // awake anyway; with the phone idle, wait.
        if (s.ecoOn && !busy) return
        val now = System.currentTimeMillis()
        if (now - lastRescanAt < p.rescanMin * 60_000L * Power.stretch()) return
        lastRescanAt = now
        if (s.ecoOn) {
            // Only the servers that could be faster by enough, judging by their best recent latency.
            val targets = candidates()
            if (targets.isEmpty()) {
                Activity.count(Activity.Kind.RESCAN_SKIPPED)
                AppLog.debug(TAG, "battery saving: rescan skipped, no server could beat the current one by enough")
                return
            }
            AppLog.debug(TAG, "battery saving: targeted rescan of ${targets.size} servers")
            scope.launch {
                Scanner.start(reason = Activity.Kind.SCAN_BACKGROUND, only = targets).join()
                send(Event.ScanFinished)
            }
            return
        }
        // A full scan costs a connection to every server: skip it (at most twice in a row) when
        // even the best latency any other server ever had would not justify a switch.
        if (!gainPossible() && skippedRescans < 2) {
            skippedRescans++
            Activity.count(Activity.Kind.RESCAN_SKIPPED)
            AppLog.debug(TAG, "rescan skipped: no server could beat the current one by enough")
            return
        }
        skippedRescans = 0
        AppLog.debug(TAG, "background rescan")
        scope.launch {
            Scanner.start(reason = Activity.Kind.SCAN_BACKGROUND).join()
            send(Event.ScanFinished)
        }
    }

    /**
     * Servers that, by their best latency of the last day, could beat the current one by enough
     * (most promising first, at most [EcoMath.TARGETED_MAX]).
     */
    private fun candidates(): List<Server> {
        val p = settings.policy
        val cur = current ?: return emptyList()
        val pings = Repo.pings.value
        val curMs = pings[cur.id]?.typical ?: lastPing ?: return emptyList()
        val dayAgo = System.currentTimeMillis() - 86_400_000L
        val now = System.currentTimeMillis()
        val known = Repo.activeServers
            .filter { it.id != cur.id && (penalty[it.id] ?: 0L) < now }
            .mapNotNull { srv -> pings[srv.id]?.takeIf { it.lastOkAt >= dayAgo }?.recent?.minOrNull()?.let { srv to it } }
        if (known.isEmpty()) return emptyList()
        // Judged like considerSwitch in this mode: latency weighted by the protocol's energy cost.
        val cheapest = (known.map { it.first } + cur).minOf(ProtocolCost::factor)
        val fastest = minOf(curMs, known.minOf { it.second })
        val curScore = ProtocolCost.adjusted(curMs, ProtocolCost.factor(cur), cheapest, fastest)
        return known
            .map { (srv, ms) -> srv to ProtocolCost.adjusted(ms, ProtocolCost.factor(srv), cheapest, fastest) }
            .filter { (_, score) -> score <= curScore * (100 - p.gainPercent) / 100 && curScore - score >= p.gainMs }
            .sortedBy { it.second }
            .map { it.first }
            .take(EcoMath.TARGETED_MAX)
    }

    /** Could some other server, judging by its best recent latency, be faster enough to switch to? */
    private fun gainPossible(): Boolean {
        val p = settings.policy
        val cur = current ?: return true
        val pings = Repo.pings.value
        val curMs = pings[cur.id]?.typical ?: lastPing ?: return true
        val dayAgo = System.currentTimeMillis() - 86_400_000L
        val best = Repo.activeServers.filter { it.id != cur.id }
            .mapNotNull { srv -> pings[srv.id]?.takeIf { it.lastOkAt >= dayAgo }?.recent?.minOrNull() }
            .minOrNull() ?: return true
        return best <= curMs * (100 - p.gainPercent) / 100 && curMs - best >= p.gainMs
    }

    /** [chosen]: the user just picked this strategy, so the minimum stay doesn't hold it back and the outcome is logged. */
    private suspend fun considerSwitch(chosen: Boolean = false) {
        val p = settings.policy
        val cur = current ?: return
        if (mode != Mode.PROXY || !p.optimize) return
        val now = System.currentTimeMillis()
        if (!chosen && now - connectedAt < p.minStayMin * 60_000L) return
        val pings = Repo.pings.value
        val curMs = pings[cur.id]?.typical ?: lastPing ?: return
        penalty.entries.removeAll { it.value < now }
        val others = Repo.activeServers
            .filter { it.id != cur.id && it.id !in penalty }
            .mapNotNull { srv -> pings[srv.id]?.takeIf { it.ok && it.at >= lastRescanAt }?.let { srv to it.ms!! } }
        if (others.isEmpty()) {
            if (chosen) AppLog.event(LText.of(R.string.ev_strategy_stay, LText.of(Eco.label(settings.strategy)), label(cur)), AppLog.Kind.GOOD)
            return
        }
        // Battery saving mode: latencies weighted by the protocols' energy cost (plain latency otherwise).
        val eco = settings.ecoOn
        val cheapest = if (eco) (others.map { it.first } + cur).minOf(ProtocolCost::factor) else 1.0
        val fastest = minOf(curMs, others.minOf { it.second })
        fun score(srv: Server, ms: Int) = if (eco) ProtocolCost.adjusted(ms, ProtocolCost.factor(srv), cheapest, fastest) else ms.toDouble()
        val best = others.minBy { score(it.first, it.second) }
        val curScore = score(cur, curMs)
        val bestScore = score(best.first, best.second)
        val gainOk = bestScore <= curScore * (100 - p.gainPercent) / 100 && curScore - bestScore >= p.gainMs
        AppLog.debug(
            TAG, "optimise: current ${cur.name} $curMs ms, best ${best.first.name} ${best.second} ms" +
                (if (eco) " (cost-weighted %.0f vs %.0f; ${ProtocolCost.describe(cur)} / ${ProtocolCost.describe(best.first)})".format(curScore, bestScore) else "") +
                " -> ${if (gainOk) "switch" else "stay"}",
        )
        if (gainOk) {
            val announce = if (best.second < curMs) LText.of(R.string.ann_faster, curMs, best.second)
            else LText.of(R.string.ann_cheaper, LText.raw(ProtocolCost.of(cur).key), LText.raw(ProtocolCost.of(best.first).key))
            if (switchTo(best.first, announce)) StatsDb.event(StatsDb.Event.OPTIMIZE, best.first.id, cur.id)
            else switchTo(cur, null)
        } else if (chosen) {
            AppLog.event(LText.of(R.string.ev_strategy_stay, LText.of(Eco.label(settings.strategy)), label(cur)), AppLog.Kind.GOOD)
        }
    }

    /**
     * The strategy changed: choose the server the way it would. Manual goes back to the server picked by
     * hand last (stays if there is none); "On failure" keeps the current server once a ping confirms it
     * works; Stable / Fastest look at fresh pings of all servers (a scan, unless one just finished) and
     * move if their rules say so — the minimum stay doesn't apply to a choice the user just made.
     */
    private suspend fun onStrategyChanged() {
        val s = settings
        AppLog.debug(TAG, "strategy: ${s.strategy}")
        if (mode == null) return
        if (mode != Mode.PROXY) {
            // No server at the moment: look again now, by the new rules.
            if (!noNetwork) retry()
            return
        }
        val cur = current ?: return
        when (s.strategy) {
            Strategy.MANUAL -> {
                val pick = Repo.server(s.manualServerId)?.takeIf { Repo.isActive(it) } ?: return
                if (pick.id == cur.id) return
                publish(Phase.SEARCHING, pick.id)
                if (switchTo(pick, LText.of(R.string.ann_manual))) StatsDb.event(StatsDb.Event.MANUAL, pick.id, cur.id)
                else if (!switchTo(cur, null)) failover()
            }
            Strategy.FAILOVER -> checkSoon = true
            Strategy.STABLE, Strategy.FASTEST -> {
                val now = System.currentTimeMillis()
                val lastScan = Repo.pings.value.values.lastProbe() ?: 0L
                // Results of a scan that ended moments ago are fresh enough.
                if (Scanner.isRunning || now - lastScan > STRATEGY_FRESH_MS) {
                    publish(Phase.SEARCHING)
                    lastRescanAt = now
                    Scanner.start(reason = Activity.Kind.SCAN_STRATEGY).join()
                } else {
                    lastRescanAt = now - STRATEGY_FRESH_MS
                }
                val mine = Repo.pings.value[cur.id]
                if (mine != null && !mine.ok && mine.at >= lastRescanAt) {
                    AppLog.event(LText.of(R.string.ev_lost, label(cur)), AppLog.Kind.ERROR)
                    failover()
                    return
                }
                publish(Phase.CONNECTED)
                considerSwitch(chosen = true)
                lastRescanAt = System.currentTimeMillis()
            }
        }
    }

    // ------------------------------------------------------------------ events

    private suspend fun select(id: String) {
        val server = Repo.server(id) ?: return
        // Picking a server by hand switches to the Manual strategy: the engine stays on it until the
        // user chooses an automatic strategy again. Manual goes back to this server later.
        if (settings.strategy != Strategy.MANUAL || settings.manualServerId != id) {
            Repo.updateSettings { it.copy(strategy = Strategy.MANUAL, manualServerId = id) }
        }
        // Already on it and it works (e.g. the strategy change got here first): nothing to restart.
        if (id == current?.id && mode == Mode.PROXY && (lastPing != null || System.currentTimeMillis() - connectedAt < 5_000)) {
            publish(Phase.CONNECTED)
            return
        }
        publish(Phase.SEARCHING, serverId = id)
        val from = current?.id
        if (switchTo(server, LText.of(R.string.ann_manual))) {
            StatsDb.event(StatsDb.Event.MANUAL, server.id, from)
            publish(Phase.CONNECTED)
        } else {
            Repo.updateSettings { it.copy(lastServerId = server.id) }
            enterFallback()
        }
    }

    private suspend fun findBest() {
        publish(Phase.SEARCHING, serverId = current?.id)
        AppLog.event(LText.of(R.string.ev_finding_best))
        Scanner.start().join()
        val pings = Repo.pings.value
        val best = Repo.activeServers.mapNotNull { srv -> pings[srv.id]?.takeIf { it.ok }?.let { srv to it.ms!! } }
            .minByOrNull { it.second }?.first
        if (best == null) {
            enterFallback()
        } else if (best.id != current?.id || mode != Mode.PROXY) {
            val from = current?.id
            if (switchTo(best, LText.of(R.string.ann_best))) StatsDb.event(StatsDb.Event.BEST, best.id, from) else failover()
        } else {
            AppLog.event(LText.of(R.string.ev_already_best), AppLog.Kind.GOOD)
            publish(Phase.CONNECTED)
        }
        lastRescanAt = System.currentTimeMillis()
    }

    private suspend fun reconfigure() {
        val cur = current
        AppLog.event(LText.of(if (settings.routingEnabled) R.string.ev_reconfig_on else R.string.ev_reconfig_off))
        if (mode == Mode.PROXY && cur != null) {
            if (!switchTo(cur, null)) failover()
        } else {
            mode = null
            enterFallback(noNetwork)
        }
    }

    /** Same server, same mode, a fresh core (it reads the routing lists only when it starts). */
    private suspend fun restartCore() {
        val cur = current
        if (mode == Mode.PROXY && cur != null) {
            if (!switchTo(cur, null)) failover()
        } else if (mode != null) {
            enterFallbackMode()
            publish(if (noNetwork) Phase.NO_NETWORK else if (mode == Mode.PAUSE) Phase.PAUSED else Phase.DIRECT)
        }
    }

    private suspend fun onNetworkChanged() {
        // Let the new network settle, and coalesce bursts of callbacks.
        delay(1500)
        while (true) {
            val more = events.tryReceive().getOrNull() ?: break
            if (more != Event.NetworkChanged) {
                events.trySend(more)
                break
            }
        }
        // The service may have replaced the TUN interface: a fallback core must move to the new one too.
        if ((mode == Mode.DIRECT || mode == Mode.PAUSE) && coreFd != host.tunFd) enterFallbackMode()
        if (!host.hasNetwork()) {
            enterFallback(noNetwork = true)
            return
        }
        if (noNetwork) AppLog.event(LText.of(R.string.ev_net_up), AppLog.Kind.GOOD) else AppLog.event(LText.of(R.string.ev_net_changed))
        noNetwork = false
        when (mode) {
            Mode.PROXY -> {
                // Connections bound to the old network are dead; restart the core in place.
                current?.let { if (!switchTo(it, null)) failover() }
                healthCheck()
            }

            else -> retry()
        }
    }

    private fun maybeUpdateSubscriptions() {
        val now = System.currentTimeMillis()
        if (now - lastSubCheckAt < 3_600_000L) return
        lastSubCheckAt = now
        scope.launch { Importer.updateDue() }
        scope.launch { GeoLists.updateIfDue() }
    }

    private fun publish(phase: Phase, serverId: String? = current?.id) {
        val server = if (phase == Phase.DIRECT || phase == Phase.PAUSED || phase == Phase.NO_NETWORK) null else serverId
        if (phase != recordedPhase || server != recordedServer) {
            recordedPhase = phase
            recordedServer = server
            recordedAt = System.currentTimeMillis()
            StatsDb.phase(phase, server, recordedAt)
        }
        TunnelState.set(
            TunnelStatus(
                phase = phase,
                serverId = if (phase == Phase.DIRECT || phase == Phase.PAUSED || phase == Phase.NO_NETWORK) null else serverId,
                ping = if (phase == Phase.CONNECTED) lastPing else null,
                nextRetryAt = nextRetryAt,
                noServers = phase == Phase.DIRECT && nothingToUse(),
            )
        )
    }

    private fun label(s: Server) = Repo.displayName(s)

    /** Hides credentials so a shared log does not leak access to the servers. */
    private fun redact(json: String) = json.replace(
        Regex("\"(id|password|pass|auth|secretKey|privateKey|preSharedKey|publicKey|shortId|path|serviceName|seed|mldsa65Verify|pinnedPeerCertSha256)\":\"[^\"]*\""), "\"$1\":\"***\"",
    )

    companion object {
        private const val TAG = "Engine"
        private const val QUICK_RECHECK_MS = 3_000L
        private const val VERIFY_MS = 2_000L
        private const val GRACE_MS = 1_200L
        /** Probe results younger than this count as current when looking for a server. */
        private const val FRESH_MS = 60_000L
        private const val TRAFFIC_FLUSH_MS = 5 * 60_000L
        /** Sent at least this much since the last tick while receiving almost nothing: probably stuck. */
        private const val STALL_UP_BYTES = 2_000L
        private const val STALL_DOWN_BYTES = 200L
        /** Traffic since the last tick above which the radio is surely awake. */
        private const val BUSY_BYTES = 4_000L
        private const val RIDE_ALONG_MS = 30_000L
        private const val PENALTY_MS = 5 * 60_000L
        /** With no server at all the engine only wakes now and then (adding one wakes it at once). */
        private const val NO_SERVERS_TICK_MS = 10 * 60_000L
        /** A strategy picked within this time after a scan uses its results instead of a new scan. */
        private const val STRATEGY_FRESH_MS = 60_000L
        /** Length of one window measuring the core's cost per byte, and the traffic it needs to count. */
        private const val COST_WINDOW_MS = 5 * 60_000L
        private const val COST_MIN_BYTES = 256 * 1024L
    }
}
