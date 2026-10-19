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
import app.borderless.data.ProtocolWeights
import app.borderless.data.Importer
import app.borderless.data.NoServerAction
import app.borderless.data.Errors
import app.borderless.data.Repo
import app.borderless.data.Strategy
import app.borderless.data.Server
import app.borderless.data.StatsDb
import app.borderless.data.lastProbe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
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
import kotlinx.coroutines.flow.runningReduce
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
 * - If nothing answers, pass traffic directly (or pause it), and retry every [AppSettings.retryIntervalSec] (twice as
 *   often with the screen on). Without internet even directly, no servers are scanned: a quick direct check every few
 *   seconds ([pulse]) notices it coming back, and so do the system (network validated, a new network) and the screen
 *   turning on — then the search starts at once.
 *
 * All decisions run on one coroutine that handles timer ticks and [Event]s in order.
 */
class Engine(private val host: Host) {
    interface Host {
        val tunFd: Int
        fun hasNetwork(): Boolean
        fun isScreenOn(): Boolean
        /** The network underneath: "wifi", "cell", "ethernet", "other" (null: none). */
        fun networkKind(): String?
    }

    sealed interface Event {
        data object NetworkChanged : Event
        /** The system's own check of the network underneath passed ([yes]) or stopped passing. */
        data class Validated(val yes: Boolean) : Event
        /** The screen turned on: someone may be waiting for the internet right now. */
        data object ScreenOn : Event
        /** The screen turned off: work that cuts connections can be done now. */
        data object ScreenOff : Event
        data class Select(val serverId: String) : Event
        /** Probe everything now and move to the best server (user asked for it). */
        data object FindBest : Event
        /**
         * "Ping all" with the connection on: probe everything, then move to the best server ([toBest], the default:
         * people press it when something is slow) or let the strategy decide as after any rescan.
         */
        data class PingAll(val toBest: Boolean) : Event
        /** Settings that affect the generated config changed. */
        data object Reconfigure : Event
        data object ScanFinished : Event
        /** A scan the engine started for [what] (a decision waiting for it, see scanThen) has ended. */
        data class ScanDone(val what: String) : Event
        /** A server was disabled; leave it if it is the current one. */
        data class Disabled(val serverId: String) : Event
        /** Servers appeared or all disappeared (added, deleted, hidden). */
        data object ServersChanged : Event
        /** New routing lists were downloaded: restart the core so it loads them. */
        data object RestartCore : Event
        /** The strategy in effect changed (by the user, the battery saving mode or the default): choose by it now. */
        data object StrategyChanged : Event
        /** Servers of chosen countries only, or any again (see [AppSettings.countries], CountryFilter). */
        data object CountryChanged : Event
        /** A server's country was set by hand (or reset to automatic): a country filter may no longer cover it. */
        data object ServerCountryChanged : Event
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

    /** The network reaches only the zones' own sites ([NetCheck.Reach.LIMITED]): retries are thinned out. */
    private var limited = false

    /**
     * A network is there but nothing answers even directly ([NetCheck.Reach.NONE]): servers aren't scanned (only now
     * and then, [OFFLINE_BACKSTOP_MS]); [pulse] watches for the internet instead.
     */
    private var offline = false

    /** Next quick direct check while offline or limited ([pulse]; MAX: none), and the growing gap between them. */
    private var pulseAt = Long.MAX_VALUE
    private var pulseStep = PULSE_FIRST_MS

    /** When the last [retry] started (the screen turning on doesn't repeat one that just ran). */
    private var lastRetryAt = 0L

    /** Servers that answered while the network was limited: the only ones checked between full checks there. */
    private val limitedOk = HashSet<String>()

    /**
     * Limited network, servers that answer there briefly (on a train in 1.0.0: ~25 cycles of connect → lost after
     * 1–9 min → direct, each one cutting every connection). [connectedLimited]: the connection in use was made there.
     * A server lost there is left out for 2, 5, then 15 min ([limitedBan]) and must then answer twice ≥ 1 min apart
     * ([limitedFirstOk]) before it is used again; once two losses happened and no connection lasted 5 min
     * ([limitedSurvived]), only the scheduled full checks run ([limitedGiveUp]). A new network or the limit ending
     * forgets all of it ([resetLimited]).
     */
    private var connectedLimited = false
    private val limitedBan = HashMap<String, Long>()
    private val limitedLosses = HashMap<String, Int>()
    private val limitedFirstOk = HashMap<String, Long>()
    private var limitedSurvived = false
    private var limitedGiveUp = false

    private fun resetLimited() {
        limitedBan.clear()
        limitedLosses.clear()
        limitedFirstOk.clear()
        limitedSurvived = false
        limitedGiveUp = false
    }

    /** Whether [id] may be connected to now in a limited network (see [connectedLimited]); not limited: always. */
    private fun limitedUsable(id: String, now: Long): Boolean {
        if (!limited) return true
        if ((limitedBan[id] ?: 0L) > now) return false
        if ((limitedLosses[id] ?: 0) == 0) return true
        val first = limitedFirstOk[id] ?: run {
            limitedFirstOk[id] = now
            AppLog.debug(TAG, "limited network: ${Repo.server(id)?.name} answers again, waiting for a second answer a minute later")
            return false
        }
        return now - first >= LIMITED_CONFIRM_MS
    }

    /** Limited network: when all servers are checked again, and how many such checks in a row found nothing. */
    private var limitedFullAt = 0L
    private var limitedMisses = 0

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

    /** Last time the current server answered a check (a ping, or traffic coming back during one). */
    private var lastOkCheckAt = 0L

    /** Manual strategy: the server in use stopped answering (shown, not acted on — see [TunnelStatus.unanswered]). */
    private var unanswered = false

    /** Last time bytes came back through the proxy ([watch], [healthCheck]). */
    private var lastDownAt = 0L

    /** Last full health tick ([healthCheck]); between them, with the screen on, [watch] reads the counters. */
    private var lastHealthAt = 0L

    /** Proxy bytes up / down read by [watch] and not yet seen by [healthCheck]. */
    private val unseen = LongArray(2)

    /**
     * Traffic going out with nothing coming back ([watch]): since when (0 = not now) and how much went out. Any byte
     * coming back ends it — with the server out of reach exactly nothing comes back through the proxy.
     */
    private var stallStart = 0L
    private var stallUp = 0L

    /** Rescans skipped in a row because no server could beat the current one by enough. */
    private var skippedRescans = 0

    /**
     * A switch for speed waiting for nobody to look ([runDeferred]): restarting the core cuts every open connection
     * (messenger channels reconnect visibly, calls and downloads break), so it waits for the screen to turn off. A
     * [large] gain (owner: a strong speed difference is worth it even while the phone is used) switches with the screen
     * on too: at a moment of [LARGE_QUIET_MS] without traffic, or after [LARGE_WAIT_MS] from [largeAt] anyway.
     * Dropped after [PENDING_MAX_MS].
     */
    private class Pending(val server: Server, val announce: LText, val from: String, val since: Long, val large: Boolean, val largeAt: Long)
    private var pending: Pending? = null

    /** New routing lists wait to be loaded (a core restart) like [pending]: since when (0 = nothing waits). */
    private var restartWanted = 0L

    /** The last counter read that saw traffic (≥ [QUIET_RATE] bytes a second through the tunnel), and the last read. */
    private var lastBusyAt = 0L
    private var lastReadAt = 0L


    /**
     * A window measuring the core's own CPU per byte for the current server's protocol class
     * (ProtocolCost): process CPU minus the app's tracked actions, while one server carries the
     * traffic, the app's screens stay closed and no scan runs.
     */
    private var costStart = 0L
    private var costCpu = 0L
    private var costAttributed = 0L
    private var costLimited = 0L
    private var costBytes = 0L
    private var costServer: String? = null

    /** Traffic since the last write to the stats DB: proxy up/down, direct up/down. */
    private val trafficPending = LongArray(4)
    private var trafficFlushedAt = System.currentTimeMillis()

    /**
     * Servers that recently failed while in use, until the given time: left out of optimisation, and a failover or
     * retry takes one only if nothing else answered. Repeated losses on the same network make it longer
     * ([PENALTY_STEPS_MS] by [losses]); a new network forgets both.
     */
    private val penalty = HashMap<String, Long>()
    private val losses = HashMap<String, Int>()

    private val settings: AppSettings get() = Repo.settings.value

    fun start(preferredId: String?) {
        // Whoever changes the strategy (main screen, battery saving mode, settings), the server is chosen by it.
        // Countries come with their strategy change in one settings update (CountryFilter): one event for both.
        scope.launch {
            Repo.settings.map { it.strategy to it.countries }.distinctUntilChanged().runningReduce { old, new ->
                send(if (new.second != old.second) Event.CountryChanged else Event.StrategyChanged)
                new
            }.collect {}
        }
        // Unprotected servers switched off (SECURITY.md S7): leave the one in use if it is such.
        scope.launch {
            Repo.settings.map { it.avoidInsecure }.distinctUntilChanged().drop(1).collect { current?.id?.let { send(Event.Disabled(it)) } }
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
                if (app.borderless.BuildConfig.DEBUG) Log.e(TAG, "engine crashed", e)
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
        Errors.guard("statistics", Unit, show = false) { endCut(null) }
        Errors.guard("traffic stats", Unit, show = false) { readTraffic(); flushTraffic() }
        Limited.disconnect()
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
        val now = System.currentTimeMillis()
        val bytes = proxy.first + proxy.second + direct.first + direct.second
        val span = (now - lastReadAt).coerceAtLeast(1000)
        if (lastReadAt == 0L || bytes * 1000 >= QUIET_RATE * span) lastBusyAt = now
        // A limited-network server's core opens more streams when traffic rises, fewer when it stays low (LimitedPower).
        if (current?.limited == true && mode == Mode.PROXY && lastReadAt != 0L) Limited.setRate((proxy.first + proxy.second) * 1000 / span)
        lastReadAt = now
        window.addLast(longArrayOf(now, proxy.first, proxy.second))
        while (window.isNotEmpty() && window.first()[0] < now - 60_000L) window.removeFirst()
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
            costLimited = Limited.cpuTotalMs
            costBytes = 0
            costServer = cur?.id
        }
        if (cur == null || costStart == 0L || costServer != cur.id) return restart()
        costBytes += bytes
        if (now - costStart < COST_WINDOW_MS) return
        val quiet = !Activity.uiVisible && Activity.uiChangedAt < costStart && !Scanner.isRunning && Scanner.lastActiveAt < costStart
        // A limited-network server's core works in its own process: its CPU time belongs to the class as well.
        val cpu = android.os.Process.getElapsedCpuTime() - costCpu - (Energy.attributedTotalMs - costAttributed) +
            (Limited.cpuTotalMs - costLimited)
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
        val eco = settings.ecoOn
        val stable = settings.strategy == Strategy.STABLE
        if (!eco && !stable) return fastest.key
        val servers = ok.keys.mapNotNull(Repo::server)
        if (servers.isEmpty()) return fastest.key
        val cheapest = servers.minOf(ProtocolCost::factor)
        val pick = servers.minBy { weigh(it, ok.getValue(it.id), cheapest, fastest.value) }
        if (pick.id != fastest.key) {
            AppLog.debug(
                TAG, "${if (eco) "battery saving" else "stable"}: ${pick.name} (${ok[pick.id]} ms, ${ProtocolCost.describe(pick)}) instead of the fastest " +
                    "${Repo.server(fastest.key)?.let { "${it.name} (${fastest.value} ms, ${ProtocolCost.describe(it)}${ProtocolWeights.describe(it).takeIf(String::isNotEmpty)?.let { t -> ", $t" } ?: ""})" }}",
            )
        }
        return pick.id
    }

    /**
     * How a server's latency [ms] counts when choosing among working servers: in the battery saving mode
     * weighted by its protocol's energy cost; under the Stable strategy weighted by how steady its kind of
     * connection tends to be ([ProtocolWeights.stability]). Plain latency otherwise.
     */
    private fun weigh(srv: Server, ms: Int, cheapest: Double, fastest: Int): Double {
        val base = if (settings.ecoOn) ProtocolCost.adjusted(ms, ProtocolCost.factor(srv), cheapest, fastest) else ms.toDouble()
        val weighted = if (settings.strategy == Strategy.STABLE) ProtocolWeights.stability(ProtocolWeights.of(srv)).apply(base) else base
        // Limited-network servers carry traffic but cost much more battery, so any ordinary server that
        // answers wins over them: they are only ever chosen when nothing ordinary does (see Limited).
        return if (srv.limited) weighted * LIMITED_PENALTY + LIMITED_PENALTY_MS else weighted
    }

    /** Writes the pending traffic, the proxied part counted for the server in use (see [switchTo]). */
    private fun flushTraffic() {
        if (trafficPending.any { it > 0 }) {
            val u = app.borderless.data.Units::bytes
            AppLog.debug(
                TAG, "traffic over ${(System.currentTimeMillis() - trafficFlushedAt) / 1000} s: " +
                    "${current?.let { "via ${it.name}" } ?: "through the tunnel"} ↑${u(trafficPending[0])} ↓${u(trafficPending[1])}, " +
                    "direct ↑${u(trafficPending[2])} ↓${u(trafficPending[3])}",
            )
        }
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
                    is Event.Validated -> onValidated(event.yes)
                    Event.ScreenOn -> onScreenOn()
                    Event.ScreenOff -> { if (current?.limited == true) Limited.repace(); runDeferred() }
                    is Event.Select -> select(event.serverId)
                    Event.FindBest -> findBest()
                    is Event.PingAll -> pingAll(event.toBest)
                    Event.Reconfigure -> reconfigure()
                    Event.ScanFinished -> considerSwitch()
                    is Event.ScanDone -> when (event.what) {
                        AFTER_STRATEGY -> afterStrategyScan()
                        AFTER_BEST -> afterBest()
                        AFTER_PING_ALL -> afterPingAll()
                    }
                    // Hiding doesn't matter while a country is chosen: the server may still take part.
                    is Event.Disabled -> if (event.serverId == current?.id && mode == Mode.PROXY && !Repo.isActive(event.serverId)) failover()
                    Event.StrategyChanged -> onStrategyChanged()
                    Event.CountryChanged -> onCountryChanged()
                    Event.ServerCountryChanged -> onServerCountryChanged()
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
        if (mode == null) try {
            enterFallback()
        } catch (e2: Throwable) {
            if (e2 is kotlinx.coroutines.CancellationException) throw e2
            Errors.report(e2, "engine: falling back to direct", show = false)
        }
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
            else (lastHealthAt + healthIntervalMs() - System.currentTimeMillis()).let { untilHealth ->
                watchMs()?.let { minOf(it, untilHealth) } ?: untilHealth
            }
            Mode.DIRECT, Mode.PAUSE -> if (nothingToUse()) NO_SERVERS_TICK_MS else minOf(nextRetryAt, pulseAt) - System.currentTimeMillis()
            null -> 1000L
        }
    }

    private suspend fun tick() {
        when (mode) {
            Mode.PROXY -> {
                when {
                    checkSoon || failStreak > 0 || System.currentTimeMillis() >= lastHealthAt + healthIntervalMs() -> healthCheck()
                    else -> watch()
                }
                if (mode == Mode.PROXY && failStreak == 0) runDeferred()
                if (mode == Mode.PROXY) widerPulse()
            }
            Mode.DIRECT, Mode.PAUSE -> if (System.currentTimeMillis() >= nextRetryAt) retry() else pulse()
            null -> initialConnect(null)
        }
    }

    /** Health ticks: only local counters are read; with the screen off they can be sparser. */
    private fun healthIntervalMs(): Long {
        val s = settings
        return (if (Power.screenOn) s.healthIntervalSec * 1000L else maxOf(60_000L, s.healthIntervalSec * 3000L)) *
            (if (s.ecoOn) EcoMath.TICK_FACTOR else 1)
    }

    /** How often [watch] reads the counters between health ticks: with the screen on only (null: not at all). */
    private fun watchMs(): Long? = if (!Power.screenOn) null else if (settings.ecoOn) WATCH_ECO_MS else WATCH_MS

    /**
     * Between health ticks, with the screen on: reads the core's counters (local, no network) to notice at once
     * when requests go out through the server and nothing comes back — someone is waiting for a page that won't
     * load. [WATCH_STALL_UP_BYTES] out and [STALL_MIN_MS] without a single byte back → check the server now.
     */
    private suspend fun watch() {
        val (up, down) = readTraffic()
        unseen[0] += up
        unseen[1] += down
        val now = System.currentTimeMillis()
        if (down > 0) {
            lastDownAt = now
            stallStart = 0
            stallUp = 0
            return
        }
        if (up == 0L) return
        if (stallStart == 0L) stallStart = now
        stallUp += up
        // Just checked and it answered: give the requests time (a slow site isn't a lost server).
        if (stallUp < WATCH_STALL_UP_BYTES || now - stallStart < STALL_MIN_MS || now - lastActiveCheck < STALL_RECHECK_MS) return
        AppLog.debug(TAG, "nothing came back for ${(now - stallStart) / 1000} s (${stallUp} B sent): checking")
        healthCheck(stalledNow = true)
    }

    /** How long the last successful [ping] took in all (for the statistics). */
    private var lastCheckTook: Int? = null

    /**
     * Statistics of interruptions (§5 of the review): a working connection is cut — lost, reconnected, replaced for
     * speed, for new lists, by a network change, by the user — from its first sign ([troubleSince]: stall, suspicion
     * or failed check; else the restart) to the next answered check. The proxy bytes of the last minute
     * ([lastMinute]) say whether anyone was using it.
     */
    private class Cut(val start: Long, val cause: String, val screen: Boolean, val net: String?, val server: String?, val up: Long, val down: Long)
    private var cut: Cut? = null
    private var troubleSince = 0L
    private val window = ArrayDeque<LongArray>()

    private fun beginCut(cause: String, start: Long = System.currentTimeMillis()) {
        if (cut != null) return
        val (u, d) = lastMinute()
        cut = Cut(start, cause, Power.screenOn, host.networkKind(), current?.id, u, d)
    }

    private fun endCut(end: Long?) {
        val c = cut ?: return
        cut = null
        AppLog.debug(TAG, "cut (${c.cause}) ${if (end != null) "${end - c.start} ms" else "until the tunnel stopped"}")
        StatsDb.cut(c.start, end, c.cause, c.screen, c.net, c.server, c.up, c.down)
    }

    /** Proxy bytes up / down in the last minute (from the counter reads). */
    private fun lastMinute(): Pair<Long, Long> = window.sumOf { it[1] } to window.sumOf { it[2] }

    /** What a health ping decided before its own answer came (see [ping]). */
    private enum class Decision { RECONNECT, FAILOVER }

    /** The whole health ping may take this long (the core opens connections, TLS to the test site, two requests). */
    private fun fullCheckMs(): Long = settings.probeTimeoutSec * 1000L + 2000

    /**
     * Past this a health ping is only a *suspicion* ([ping]): a few of the server's usual round trips, or half again
     * its longest recent check (a whole check takes longer than the round trip it reports), never more than the full
     * timeout. 1.0.0's data: a latency-only limit would have failed 17 % of the checks after a switch.
     */
    private fun suspicionMs(cur: Server): Long {
        val full = fullCheckMs()
        val p = Repo.pings.value[cur.id] ?: return full
        val byLatency = p.usual?.let { it * 4L + 1_500 } ?: full
        val byDuration = p.checks.maxOrNull()?.let { it * 3L / 2 } ?: 0L
        return maxOf(byLatency, byDuration).coerceIn(CHECK_MIN_MS, full)
    }

    /**
     * One health ping of [cur] through the running core, in two stages. It always may run to the full timeout; only
     * that (or an error from the core) is a failure. Past [suspicionMs] it is a suspicion: what a failure would start
     * starts now — the search (automatic strategies) and a fresh probe of [cur] with a throwaway instance, the second
     * independent opinion. The fresh probe answering while the core's own request hangs means the server works and
     * only the core's connection to it broke (half of 1.0.0's losses on Wi-Fi): [Decision.RECONNECT]. It failing while
     * another server already answered: [Decision.FAILOVER] at once, without waiting for the ping or a recheck. Not for
     * the check right after a switch (the first call is always slow). Returns the latency (null: failed) or the
     * decision; a successful ping's duration is kept ([PingRecord.checks]).
     */
    private suspend fun ping(cur: Server, kind: Activity.Kind): Pair<Int?, Decision?> = coroutineScope {
        val s = settings
        lastCheckTook = null
        val started = System.currentTimeMillis()
        val ping = async { core.measure(s.testUrl, fullCheckMs()) }
        val canSuspect = kind != Activity.Kind.PING_VERIFY
        val limit = suspicionMs(cur)
        var fresh: Deferred<Int?>? = null
        var decision: Decision? = null
        while (ping.isActive) {
            if (canSuspect && fresh == null && System.currentTimeMillis() - started >= limit) {
                if (troubleSince == 0L) troubleSince = stallStart.takeIf { it > 0 } ?: started
                AppLog.debug(TAG, "health ${cur.name}: no answer within $limit ms — probing it afresh${if (s.policy.failover) " and looking for others" else ""}")
                if (s.policy.failover) Scanner.start(reason = Activity.Kind.SCAN_ENGINE)
                fresh = async { Scanner.probeOne(cur) }
            }
            val f = fresh
            if (f != null && f.isCompleted) {
                val answered = f.getCompleted() != null
                if (answered) decision = Decision.RECONNECT
                else if (s.policy.failover && othersAnsweredSince(started, cur)) decision = Decision.FAILOVER
                if (decision != null) break
            }
            withTimeoutOrNull(POLL_MS) { ping.join() }
        }
        if (decision == null) {
            val ms = ping.await()
            if (ms != null) {
                fresh?.cancel()
                lastCheckTook = (System.currentTimeMillis() - started).toInt()
                Repo.recordCheck(cur.id, lastCheckTook!!)
                return@coroutineScope ms to null
            }
            // Failed: the fresh probe is the second opinion (started now if the ping failed before the suspicion).
            val f = fresh ?: run {
                if (s.policy.failover) Scanner.start(reason = Activity.Kind.SCAN_ENGINE)
                async { Scanner.probeOne(cur) }
            }
            decision = when {
                f.await() != null -> Decision.RECONNECT
                s.policy.failover && othersAnsweredSince(started, cur) -> Decision.FAILOVER
                else -> null
            }
        }
        ping.cancel()
        fresh?.cancel()
        if (decision != null) {
            AppLog.debug(TAG, "health ${cur.name}: ${if (decision == Decision.RECONNECT) "a fresh probe answers, the core's connection doesn't" else "a fresh probe fails too and others answer"} -> $decision")
        }
        null to decision
    }

    /** Another server taking part answered at or after [since]. */
    private fun othersAnsweredSince(since: Long, cur: Server): Boolean {
        val pings = Repo.pings.value
        return Repo.activeServers.any { it.id != cur.id && pings[it.id]?.let { p -> p.ok && p.at >= since } == true }
    }

    /** The last [reconnect]: server and time (a second one within [RECONNECT_AGAIN_MS] counts as a failure). */
    private var reconnected: Pair<String, Long>? = null

    /** [cur] answers a fresh probe but not through the core: a fresh core on the same server (no penalty, ~0.3 s). */
    private suspend fun reconnect(cur: Server) {
        reconnected = cur.id to System.currentTimeMillis()
        beginCut("RECONNECT", troubleSince.takeIf { it > 0 } ?: System.currentTimeMillis())
        AppLog.event(LText.of(R.string.ev_reconnected, label(cur)), AppLog.Kind.WARN)
        if (switchTo(cur, null)) {
            StatsDb.event(StatsDb.Event.RECONNECT, cur.id)
        } else {
            Repo.recordPing(cur.id, null)
            failover()
        }
    }

    /**
     * With the screen on someone may be waiting: retries come twice as often (never under [RETRY_MIN_MS]). With the
     * screen off and the servers down for a while (each retry is a scan of all of them, while traffic goes direct
     * anyway) the gap grows: the setting for the first 5 minutes, then 1, 2 and 5 minutes ([slowSince]: back to the
     * start on screen on, a network change or validation).
     */
    private fun retryMs(): Long {
        val base = maxOf(RETRY_MIN_MS, settings.retryIntervalSec * 1000L / (if (Power.screenOn) 2 else 1))
        if (Power.screenOn || slowSince == 0L) return base
        val down = System.currentTimeMillis() - slowSince
        return when {
            down < 5 * 60_000L -> base
            down < 10 * 60_000L -> maxOf(base, 60_000L)
            down < 20 * 60_000L -> maxOf(base, 120_000L)
            else -> maxOf(base, 300_000L)
        }
    }

    /** Since when servers have been down with nobody looking (0: not now) — see [retryMs]. */
    private var slowSince = 0L

    // ------------------------------------------------------------------ connecting

    private suspend fun initialConnect(preferredId: String?) {
        if (!host.hasNetwork()) {
            enterFallback(noNetwork = true)
            return
        }
        // The last server answered minutes ago: start on it straight away (one core start instead of two) and let the
        // check right after the switch decide; if it fails, the failover takes over as usual.
        // Never a limited-network server: those are the fallback when nothing ordinary works, not a place to start.
        val recent = Repo.server(preferredId)?.takeIf { srv ->
            settings.policy.failover && Repo.isActive(srv) && !srv.limited &&
                Repo.pings.value[srv.id]?.let { it.ok && System.currentTimeMillis() - it.at < RECENT_START_MS } == true
        }
        if (recent != null) {
            AppLog.debug(TAG, "starting on ${recent.name}: it answered ${(System.currentTimeMillis() - Repo.pings.value[recent.id]!!.at) / 1000} s ago")
            if (switchTo(recent, LText.of(R.string.ann_connected))) {
                StatsDb.event(StatsDb.Event.INITIAL, recent.id)
                return
            }
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
        if (server != null && switchTo(server, LText.of(R.string.ann_connected))) StatsDb.event(StatsDb.Event.INITIAL, server.id, pos = picked(server))
        else if (!limitedFallback(StatsDb.Event.INITIAL)) enterFallback()
    }

    /**
     * Limited-network servers when nothing ordinary works. Tried after [findWorking] and [AutoPool.rescue],
     * so they are the last resort; on a limited network (where ordinary servers don't reach) it is reached quickly.
     * Starting a limited-network server's core is the probe: [switchTo] returns false if it can't get a config. Off when the
     * user turned automatic use off, or the core isn't in this build.
     */
    private suspend fun limitedFallback(event: StatsDb.Event, avoid: String? = null): Boolean {
        if (!Limited.available || !settings.limitedAuto) return false
        val pings = Repo.pings.value
        val found = Repo.limitedServers.filter { it.id != avoid }
            .sortedWith(compareByDescending<Server> { pings[it.id]?.lastOkAt ?: 0L }.thenBy { pings[it.id]?.failStreak ?: 0 })
        if (found.isEmpty()) return false
        AppLog.event(LText.of(R.string.ev_limited_trying))
        for (srv in found.take(LIMITED_TRY)) {
            if (switchTo(srv, LText.of(R.string.ann_limited))) {
                StatsDb.event(event, srv.id, avoid)
                return true
            }
        }
        return false
    }

    /**
     * Starts the core with [server]. [announce] is the event text logged on success (null: silent). [cause]: why a
     * working connection is being replaced (recorded as a cut, see [beginCut]); null when there was none to cut.
     */
    private suspend fun switchTo(server: Server, announce: LText?, cause: String? = null): Boolean {
        if (cause != null && mode == Mode.PROXY) beginCut(cause)
        // Limited-network servers get their outbound from a running core (its WireGuard config); ordinary ones from the link.
        // Moving to an ordinary server stops any core still up.
        if (!server.limited) Limited.disconnect()
        val outbounds = try {
            if (server.limited) Limited.connect(server) ?: throw Exception(Limited.failureText())
            else LinkParser.parse(server.link).outbounds
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
            offline = false
            unanswered = false
            connectedLimited = limited
            widerAt = if (server.limited && limited) System.currentTimeMillis() + WIDER_ON_MS else Long.MAX_VALUE
            lastDownAt = System.currentTimeMillis()
            lastBusyAt = lastDownAt
            // A fresh core has loaded the routing lists.
            restartWanted = 0
            pending = null
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

    /**
     * No server to use at all: none added, or all hidden by hand (auto-visibility may still bring hidden ones back).
     * Traffic then goes direct — or stays paused if the user chose that: "pause traffic" means nothing ever leaves
     * without the tunnel (SECURITY.md S4), also when the last servers vanished with a subscription refresh.
     */
    private fun nothingToUse(): Boolean =
        Repo.activeServers.isEmpty() && (Repo.servers.value.isEmpty() || !AutoPool.enabled)

    private suspend fun enterFallbackMode() {
        Limited.disconnect()
        val m = if (settings.noServerAction == NoServerAction.PAUSE) Mode.PAUSE else Mode.DIRECT
        // The last bytes of the server we are leaving are written for it.
        Errors.guard("traffic stats", Unit, show = false) { readTraffic(); flushTraffic() }
        AppLog.debug(TAG, "starting core: ${if (m == Mode.PAUSE) "pause" else "direct"} mode")
        try {
            val fd = host.tunFd
            Energy.track(Activity.Kind.CORE_START) {
                core.start(xrayConfig(if (m == Mode.PAUSE) XrayConfig.Mode.PAUSE else XrayConfig.Mode.DIRECT, emptyList()), fd)
            }
            coreFd = fd
            restartWanted = 0
            Activity.count(Activity.Kind.CORE_START)
        } catch (e: Exception) {
            AppLog.event(LText.of(R.string.ev_core_failed, e.message.orEmpty()), AppLog.Kind.ERROR)
            AppLog.debug(TAG, "fallback core failed", e)
        }
        mode = m
        current = null
        pending = null
        lastPing = null
    }

    private suspend fun enterFallback(noNetwork: Boolean = false) {
        val wasFallback = mode == Mode.DIRECT || mode == Mode.PAUSE
        val hadNoNetwork = this.noNetwork
        if (!wasFallback || slowSince == 0L) slowSince = System.currentTimeMillis()
        val none = nothingToUse()
        val wantPause = settings.noServerAction == NoServerAction.PAUSE
        if (!wasFallback || (mode == Mode.PAUSE) != wantPause) enterFallbackMode()
        this.noNetwork = noNetwork
        val retry = retryMs() / 1000
        // Nothing answered while the phone has a network: does the internet work directly, or only the zones' own
        // sites (a limited network)? A check of the last half minute is reused (a rescue may have just asked).
        val wasLimited = limited
        val wasOffline = offline
        val reach = if (noNetwork || none) null else NetCheck.reach(REACH_FRESH_MS)
        limited = reach == NetCheck.Reach.LIMITED
        offline = reach == NetCheck.Reach.NONE
        val down = noNetwork || offline
        when {
            down && !(hadNoNetwork || wasOffline) -> AppLog.event(LText.of(R.string.ev_no_network), AppLog.Kind.WARN)
            down -> {}
            limited && !wasLimited -> {
                AppLog.event(LText.of(R.string.ev_limited), AppLog.Kind.WARN)
                // The scan that just failed was a full one: the next full check after a minute.
                limitedFullAt = System.currentTimeMillis() + LIMITED_FULL_MS[0]
                limitedMisses = 1
            }
            limited -> {}
            none -> if (!noServersLogged) {
                noServersLogged = true
                val countries = settings.countries
                val paused = mode == Mode.PAUSE
                AppLog.event(
                    if (countries.isEmpty()) LText.of(if (paused) R.string.ev_no_servers_paused else R.string.ev_no_servers)
                    else LText.of(if (paused) R.string.ev_no_servers_country_paused else R.string.ev_no_servers_country, Countries.labels(countries)),
                    AppLog.Kind.WARN,
                )
            }
            !wasFallback || hadNoNetwork || wasOffline -> AppLog.event(
                LText.of(if (mode == Mode.PAUSE) R.string.ev_down_paused else R.string.ev_down_direct, app.borderless.data.Units.durationL(retry.toLong())),
                AppLog.Kind.WARN,
            )
            else -> AppLog.debug(TAG, "retry: still no working server")
        }
        if (!none) noServersLogged = false
        if (!limited && wasLimited && !down) AppLog.event(LText.of(R.string.ev_limited_over), AppLog.Kind.GOOD)
        if (!limited) { limitedFullAt = 0; limitedMisses = 0 }
        if (!limited && wasLimited) resetLimited()
        // Not stretched in the battery saving mode either: a working connection comes first. A limited network is
        // the exception: with no server that answered there, nothing is checked until the next full check. Offline,
        // servers are scanned only now and then: the pulse watches for the internet and retries the moment it is back.
        val now = System.currentTimeMillis()
        nextRetryAt = when {
            offline -> now + OFFLINE_BACKSTOP_MS
            limited && limitedTargets().isEmpty() -> maxOf(limitedFullAt, now + retryMs())
            else -> now + retryMs()
        }
        if (offline || limited) {
            if (offline != wasOffline || limited != wasLimited) pulseStep = PULSE_FIRST_MS
            schedulePulse()
        } else {
            pulseAt = Long.MAX_VALUE
        }
        publish(fallbackPhase())
    }

    /** The next [pulse]: the gap grows from [PULSE_FIRST_MS] to a few seconds (screen on) or longer (screen off). */
    private fun schedulePulse() {
        val on = Power.screenOn
        val cap = if (limited) (if (on) PULSE_LIMITED_ON_MS else PULSE_LIMITED_OFF_MS) else (if (on) PULSE_ON_MS else PULSE_OFF_MS)
        pulseStep = minOf(pulseStep, cap)
        pulseAt = System.currentTimeMillis() + pulseStep
        pulseStep = minOf((pulseStep * PULSE_GROWTH).toLong(), cap)
    }

    /**
     * Offline or limited: one quick direct check (offline every site, limited only the common ones). If the internet
     * reaches further than before, the search starts at once instead of at the next retry.
     */
    private suspend fun pulse() {
        pulseAt = Long.MAX_VALUE
        if (mode == Mode.PROXY || noNetwork || !(offline || limited)) return
        val before = if (offline) NetCheck.Reach.NONE else NetCheck.Reach.LIMITED
        val now = NetCheck.pulse(before)
        if (now.ordinal > before.ordinal) {
            AppLog.debug(TAG, "pulse: direct internet $before -> $now, looking for a server now")
            if (offline) AppLog.event(LText.of(R.string.ev_net_up), AppLog.Kind.GOOD)
            comeBack()
        } else {
            schedulePulse()
        }
    }

    /** Next look whether the wider internet answers again (on a limited-network server in a limited network). */
    private var widerAt = Long.MAX_VALUE

    /** On a limited-network server the fallback chose (not by hand). */
    private fun leavingLimited() = current?.limited == true && settings.strategy != Strategy.MANUAL

    /**
     * On a limited-network server reached in a limited network, the wider internet is looked at directly every
     * [WIDER_ON_MS] / [WIDER_OFF_MS] (common sites only; the core's keepalives keep the radio awake anyway). The moment
     * it answers, the ordinary servers are checked at once instead of at the next rescan.
     */
    private suspend fun widerPulse() {
        val now = System.currentTimeMillis()
        if (now < widerAt) return
        widerAt = Long.MAX_VALUE
        if (!leavingLimited()) return
        if (NetCheck.pulse(NetCheck.Reach.LIMITED) == NetCheck.Reach.FULL) {
            AppLog.event(LText.of(R.string.ev_wider_back), AppLog.Kind.GOOD)
            limited = false
            AutoPool.resetBackoff()
            lastRescanAt = 0
            maybeStartRescan(busy = true, now = now, urgent = true)
        } else {
            widerAt = now + if (Power.screenOn) WIDER_ON_MS else WIDER_OFF_MS
        }
    }

    /** The network got better: look for a server now, every back-off forgotten (also for hidden servers). */
    private suspend fun comeBack() {
        AutoPool.resetBackoff()
        slowSince = System.currentTimeMillis()
        limitedFullAt = 0
        limitedMisses = 0
        pulseStep = PULSE_FIRST_MS
        retry()
    }

    private fun fallbackPhase() = when {
        noNetwork || offline -> Phase.NO_NETWORK
        limited -> Phase.LIMITED
        mode == Mode.PAUSE -> Phase.PAUSED
        else -> Phase.DIRECT
    }

    /** Active servers that answered while the network was limited. */
    private fun limitedTargets(): List<Server> = if (limitedGiveUp) emptyList() else {
        val now = System.currentTimeMillis()
        limitedOk.mapNotNull(Repo::server).filter { Repo.isActive(it) && (limitedBan[it.id] ?: 0L) <= now }
    }

    // ------------------------------------------------------------------ health & failover

    /**
     * Is the current server still working? Usually answered without touching the network: if data
     * keeps coming back through the proxy, it works. A real ping through the core is sent when
     * traffic looks stuck (sent but nothing received), right after a switch or a failure, every
     * 30 s while traffic flows anyway (the radio is awake, the ping is nearly free), and otherwise
     * every few minutes (sparser with the screen off, on mobile data, in battery saver).
     */
    private suspend fun healthCheck(stalledNow: Boolean = false, wake: Boolean = false) {
        val cur = current ?: return enterFallback()
        val s = settings
        val now = System.currentTimeMillis()
        lastHealthAt = now
        val (up, down) = readTraffic().let { (u, d) -> (u + unseen[0]) to (d + unseen[1]) }
        unseen.fill(0)
        if (down > 0) lastDownAt = maxOf(lastDownAt, now - 1)
        val stalled = stalledNow || (up >= STALL_UP_BYTES && down < STALL_DOWN_BYTES)
        // While traffic flows the radio is awake anyway, so a ping on top costs next to nothing:
        // ride along often (dense latency data while the phone is used), stay quiet when idle.
        val busy = up + down >= BUSY_BYTES
        if (busy) Energy.userTraffic()
        // Riding along keeps the latency data dense, it doesn't find failures (watching and stall checks do): often
        // right after a switch, then every minute on Wi-Fi and every two on mobile data (12 % of the app's energy
        // went into these at a fixed 30 s).
        val rideBase = if (now - connectedAt < RIDE_FRESH_MS) RIDE_ALONG_MS else if (Power.metered) RIDE_MOBILE_MS else RIDE_WIFI_MS
        val rideEvery = (if (Power.screenOn) rideBase else rideBase * 2) * (if (s.ecoOn) EcoMath.RIDE_FACTOR else 1)
        val rideAlong = busy && now - lastActiveCheck >= rideEvery
        val due = wake || checkSoon || failStreak > 0 || rideAlong || now - lastActiveCheck >= activeInterval()
        if (!due && !stalled) {
            // Bytes flowing back (or nothing to do): no need to wake the radio for a ping.
            maybeStartRescan(busy)
            return
        }
        if (stalled) AppLog.debug(TAG, "traffic looks stuck (up $up B, down $down B): checking")
        val kind = when {
            checkSoon -> Activity.Kind.PING_VERIFY
            wake -> Activity.Kind.PING_WAKE
            failStreak > 0 -> Activity.Kind.PING_FAILED
            stalled -> Activity.Kind.PING_STALL
            rideAlong -> Activity.Kind.PING_RIDE
            else -> Activity.Kind.PING_PERIODIC
        }
        Activity.count(kind)
        checkSoon = false
        lastActiveCheck = now
        val (ms, decision) = Energy.track(kind) { ping(cur, kind) }
        if (ms == null) {
            // Traffic is evidence: if the user's data came back through the server meanwhile, it works, whatever
            // the ping says (it may only be the ping's own request that got stuck) — before any decision.
            val (u2, d2) = readTraffic()
            unseen[0] += u2
            unseen[1] += d2
            if (d2 < EVIDENCE_BYTES) when (decision) {
                // A server reconnected to a minute ago that hangs again isn't a stale connection: a failure.
                Decision.RECONNECT -> if (reconnected?.let { it.first == cur.id && System.currentTimeMillis() - it.second < RECONNECT_AGAIN_MS } != true) {
                    return reconnect(cur)
                }
                Decision.FAILOVER -> {
                    AppLog.event(LText.of(R.string.ev_lost, label(cur)), AppLog.Kind.ERROR)
                    Repo.recordPing(cur.id, null)
                    return failover()
                }
                null -> {}
            }
            if (d2 >= EVIDENCE_BYTES) {
                AppLog.debug(TAG, "health ${cur.name}: the check got no answer, but ${d2 / 1024} KB came back meanwhile: working")
                stallStart = 0
                stallUp = 0
                failStreak = 0
                lastOkCheckAt = System.currentTimeMillis()
                troubleSince = 0
                endCut(lastOkCheckAt)
                publish(Phase.CONNECTED)
                return
            }
        }
        if (ms != null) {
            stallStart = 0
            stallUp = 0
            lastOkCheckAt = System.currentTimeMillis()
            troubleSince = 0
            endCut(lastOkCheckAt)
            if (failStreak > 0) AppLog.event(LText.of(R.string.ev_back, label(cur), ms), AppLog.Kind.GOOD)
            StatsDb.sample(cur.id, ms, StatsDb.Kind.HEALTH, took = lastCheckTook)
            if (s.verboseLog) AppLog.debug(TAG, "health ${cur.name}: $ms ms")
            failStreak = 0
            lastPing = ms
            unanswered = false
            if (connectedLimited && System.currentTimeMillis() - connectedAt >= LIMITED_SURVIVE_MS) limitedSurvived = true
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
        if (troubleSince == 0L) troubleSince = stallStart.takeIf { it > 0 } ?: now
        StatsDb.sample(cur.id, null, StatsDb.Kind.HEALTH)
        val threshold = s.failThreshold.coerceAtLeast(1)
        AppLog.debug(TAG, "health ${cur.name}: failed ($failStreak/$threshold)")
        if (failStreak >= threshold && !s.policy.failover) {
            // Manual strategy: report, keep the server and keep checking at the normal pace — but say so where the user
            // looks (status, tile, notification with a way out), not "Connected" over a dead internet.
            if (!unanswered) AppLog.event(LText.of(R.string.ev_lost_manual, label(cur)), AppLog.Kind.ERROR)
            Repo.recordPing(cur.id, null)
            lastPing = null
            failStreak = 0
            unanswered = true
            publish(Phase.CONNECTED)
        } else if (failStreak >= threshold) {
            AppLog.event(LText.of(R.string.ev_lost, label(cur)), AppLog.Kind.ERROR)
            Repo.recordPing(cur.id, null)
            failover()
        } else {
            AppLog.event(LText.of(R.string.ev_check_failed, label(cur), failStreak, threshold), AppLog.Kind.WARN)
            // Look for a replacement already while confirming: if the next check fails too, the failover finds
            // the scan running or its fresh results, and the connection is down for less time.
            if (s.policy.failover) Scanner.start(reason = Activity.Kind.SCAN_ENGINE)
        }
    }

    private suspend fun failover() {
        val lost = current
        if (lost != null && connectedLimited) {
            val n = limitedLosses.merge(lost.id, 1, Int::plus)!!
            limitedBan[lost.id] = System.currentTimeMillis() + LIMITED_BAN_MS[minOf(n, LIMITED_BAN_MS.size) - 1]
            limitedFirstOk.remove(lost.id)
            if (!limitedSurvived && limitedLosses.values.sum() >= 2 && !limitedGiveUp) {
                limitedGiveUp = true
                AppLog.debug(TAG, "limited network: servers keep getting lost here, only the scheduled full checks from now on")
            }
        }
        lost?.let {
            val n = losses.merge(it.id, 1, Int::plus)!!
            penalty[it.id] = System.currentTimeMillis() + PENALTY_STEPS_MS[minOf(n, PENALTY_STEPS_MS.size) - 1]
        }
        val now = System.currentTimeMillis()
        if (mode == Mode.PROXY) beginCut("LOST", troubleSince.takeIf { it > 0 } ?: now)
        val (up60, down60) = lastMinute()
        StatsDb.event(
            StatsDb.Event.LOST, lost?.id,
            loss = StatsDb.Loss(now - connectedAt, host.networkKind(), Power.metered, connectedLimited, up60, down60),
        )
        publish(Phase.SEARCHING)
        val server = findWorking(null, avoid = lost?.id) ?: AutoPool.rescue(lost?.id)
        if (server != null && switchTo(server, LText.of(R.string.ann_switched))) StatsDb.event(StatsDb.Event.FAILOVER, server.id, lost?.id, pos = picked(server))
        else if (!limitedFallback(StatsDb.Event.FAILOVER, lost?.id)) enterFallback()
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
        // Limited network: between full checks (1, 2, then every 5 minutes) only the servers that answered there.
        val now = System.currentTimeMillis()
        lastRetryAt = now
        val thin = limited && now < limitedFullAt
        val only = if (thin) limitedTargets() else null
        if (thin && only.isNullOrEmpty()) {
            enterFallback()
            return
        }
        TunnelState.update { it.copy(phase = Phase.SEARCHING) }
        AppLog.debug(TAG, if (thin) "retry (limited network): probing ${only!!.size} server(s) that answered there" else "retry: probing servers")
        val last = Repo.server(settings.manualServerId ?: settings.lastServerId)?.takeIf { Repo.isActive(it) }
        if (!settings.policy.failover && last != null) {
            if (switchTo(last, LText.of(R.string.ann_back))) StatsDb.event(StatsDb.Event.RETRY, last.id) else enterFallback()
            return
        }
        if (limited && !thin) limitedFullAt = now + LIMITED_FULL_MS[minOf(limitedMisses++, LIMITED_FULL_MS.lastIndex)]
        val server = findWorking(settings.lastServerId, only = only) ?: if (thin) null else AutoPool.rescue(null)
        if (server != null && switchTo(server, LText.of(R.string.ann_back))) StatsDb.event(StatsDb.Event.RETRY, server.id, pos = picked(server))
        else if (!limitedFallback(StatsDb.Event.RETRY)) enterFallback()
    }

    /**
     * Probes servers (preferred and recently good ones first) and returns as soon as a good
     * choice is known: the preferred server if it answers, otherwise the fastest server that
     * answered within [GRACE_MS] after the first success. The scan itself continues in the
     * background so the heatmap fills in.
     */
    /** The last [findWorking] choice and its place in the check order (statistics: how far down a working server was). */
    private var lastPick: Pair<String, Pair<Int, Int>?>? = null

    /** [server]'s place in the check order if [findWorking] chose it (not for a hidden server found by the rescue). */
    private fun picked(server: Server): Pair<Int, Int>? = lastPick?.takeIf { it.first == server.id }?.second

    private suspend fun findWorking(preferredId: String?, avoid: String? = null, only: List<Server>? = null): Server? = coroutineScope {
        lastPick = null
        // Ordinary servers only: a limited-network server is reached through [limitedFallback] when none of them works
        // (its pings from health checks through its core would otherwise make it the preferred, instant pick).
        if (Repo.activeOrdinary.isEmpty()) return@coroutineScope null
        val startedAt = System.currentTimeMillis()
        val ok = ConcurrentHashMap<String, Int>()
        val decided = CompletableDeferred<Unit>()
        // A scan may already be running (e.g. started when the app was opened): use what it found.
        val active = Repo.activeOrdinary.map { it.id }.toSet()
        Repo.pings.value.forEach { (id, p) ->
            if (p.ok && startedAt - p.at < FRESH_MS && id != avoid && id in active) ok[id] = p.ms!!
        }
        // The grace counts from the first answer, not from now: results of a scan started at the first failure are
        // already seconds old when the failover comes.
        val firstAt = ok.keys.minOfOrNull { Repo.pings.value[it]?.at ?: startedAt } ?: startedAt
        if (preferredId != null && ok.containsKey(preferredId)) decided.complete(Unit)
        else if (ok.isNotEmpty()) launch { delay((firstAt + GRACE_MS - System.currentTimeMillis()).coerceAtLeast(0)); decided.complete(Unit) }
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            Scanner.results.collect { (id, ms) ->
                // Hidden servers probed along with the scan (auto-visibility) are not candidates.
                if (ms == null || id == avoid || id !in active) return@collect
                ok[id] = ms
                if (id == preferredId) decided.complete(Unit)
                else if (ok.size == 1) launch { delay(GRACE_MS); decided.complete(Unit) }
            }
        }
        // Battery saving mode: if fresh results already settle it (the preferred server answered within
        // the last minute), no scan at all.
        if ((settings.ecoOn || settings.carefulScans) && decided.isCompleted) {
            AppLog.debug(TAG, "battery saving / careful checks: fresh results suffice, no scan")
        } else {
            val scan = Scanner.start(first = listOfNotNull(preferredId), reason = Activity.Kind.SCAN_ENGINE, only = only)
            launch { scan.join(); decided.complete(Unit) }
        }
        decided.await()
        collector.cancel()
        coroutineContext.cancelChildren()
        // Normally the scan goes on so the map fills in; the battery saving mode and careful checks stop it here.
        if (settings.ecoOn || settings.carefulScans) Scanner.finishEarly(Activity.Kind.SCAN_ENGINE)

        ok.keys.removeAll { !Repo.isActive(it) || Repo.server(it)?.limited == true }
        if (limited) limitedOk += ok.keys
        // A server lost lately is taken only if nothing else answered (it came back again and again on bad networks).
        val now = System.currentTimeMillis()
        // Limited network: servers lost there come back only after their back-off and a second answer.
        ok.keys.removeAll { !limitedUsable(it, now) }
        val clean = ok.filterKeys { (penalty[it] ?: 0L) < now }
        val id = preferredId?.takeIf { clean.containsKey(it) } ?: choose(clean.ifEmpty { ok })
        val pos = id?.let { Scanner.positionOf(it, listOfNotNull(preferredId)) }
        if (id != null) lastPick = id to pos
        AppLog.debug(
            TAG, "findWorking(preferred=${Repo.server(preferredId)?.name}, avoid=${Repo.server(avoid)?.name}): ${ok.size} answered so far -> " +
                "${Repo.server(id)?.name}${pos?.let { " (no. ${it.first} of ${it.second} in the check order)" } ?: ""}",
        )
        Repo.server(id)
    }

    // ------------------------------------------------------------------ optimisation

    /** How often to ping the current server while traffic says it works. */
    private fun activeInterval(): Long {
        val base = settings.latencyCheckMin * 60_000L * (if (Power.screenOn) 1 else 3) * (if (settings.ecoOn) EcoMath.LATENCY_FACTOR else 1)
        return (base * Power.stretch()).toLong()
    }

    /** [busy]: the user's traffic is flowing (the radio is awake anyway). */
    private fun maybeStartRescan(busy: Boolean, now: Long = System.currentTimeMillis(), urgent: Boolean = false) {
        val s = settings
        val p = s.policy
        // On a limited-network server (the fallback chose it): keep looking for an ordinary one under every automatic
        // strategy — On failure and battery saving too — even with the screen off or the phone idle.
        val onLimited = leavingLimited()
        if (onLimited) {
            if (now - lastRescanAt < LIMITED_RESCAN_MS) return
            lastRescanAt = now
            AppLog.debug(TAG, "on a limited-network server: rescan of the ordinary servers" + if (urgent) " (the internet is back)" else "")
            // The internet just came back: all at once (a background scan spaces its probes 2–6 s apart).
            scope.launch {
                Scanner.start(reason = if (urgent) Activity.Kind.SCAN_ENGINE else Activity.Kind.SCAN_BACKGROUND).join()
                send(Event.ScanFinished)
            }
            return
        }
        if (!p.optimize) return
        if ((s.pauseScansScreenOff || s.ecoOn) && !Power.screenOn) return
        // Battery saving mode: a faster server only matters while traffic flows, and then the radio is
        // awake anyway; with the phone idle, wait.
        if (s.ecoOn && !busy) return
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
            .filter { it.id != cur.id && !it.limited && (penalty[it.id] ?: 0L) < now }
            .mapNotNull { srv -> pings[srv.id]?.takeIf { it.lastOkAt >= dayAgo && it.steady }?.usual?.let { srv to it } }
        if (known.isEmpty()) return emptyList()
        // Judged like considerSwitch in this mode: latency weighted by the protocol's energy cost.
        val cheapest = (known.map { it.first } + cur).minOf(ProtocolCost::factor)
        val fastest = minOf(curMs, known.minOf { it.second })
        val curScore = weigh(cur, curMs, cheapest, fastest)
        return known
            .map { (srv, ms) -> srv to weigh(srv, ms, cheapest, fastest) }
            .filter { (_, score) -> score <= curScore * (100 - p.gainPercent) / 100 && curScore - score >= p.gainMs }
            .sortedBy { it.second }
            .map { it.first }
            .take(EcoMath.TARGETED_MAX)
    }

    /** Could some other server, judging by its best recent latency, be faster enough to switch to? */
    private fun gainPossible(): Boolean {
        val p = settings.policy
        val cur = current ?: return true
        // On a limited-network server (expensive core): always look for an ordinary server to move back to.
        if (cur.limited) return true
        val pings = Repo.pings.value
        val curMs = pings[cur.id]?.typical ?: lastPing ?: return true
        // A server of a less steady kind may be left for a slower one (see weigh): don't skip the rescan.
        if (settings.strategy == Strategy.STABLE && ProtocolWeights.stability(ProtocolWeights.of(cur)).traits.isNotEmpty()) return true
        val dayAgo = System.currentTimeMillis() - 86_400_000L
        val best = Repo.activeServers.filter { it.id != cur.id && !it.limited }
            .mapNotNull { srv -> pings[srv.id]?.takeIf { it.lastOkAt >= dayAgo && it.steady }?.usual }
            .minOrNull() ?: return true
        return best <= curMs * (100 - p.gainPercent) / 100 && curMs - best >= p.gainMs
    }

    /** The server that won the last rescan for speed (a switch needs the same winner twice in a row). */
    private var winner: String? = null

    /** [chosen]: the user just picked this strategy, so the minimum stay doesn't hold it back and the outcome is logged. */
    private suspend fun considerSwitch(chosen: Boolean = false) {
        val p = settings.policy
        val cur = current ?: return
        // Leaving a limited-network server for any working ordinary one is always worth it (the core costs battery): no
        // minimum stay, no speed floor, no two-rescan confirmation, under every automatic strategy.
        val leavingLimited = leavingLimited()
        if (mode != Mode.PROXY || !(p.optimize || leavingLimited)) return
        val now = System.currentTimeMillis()
        val immediate = chosen || leavingLimited
        if (!immediate && now - connectedAt < p.minStayMin * 60_000L) return
        val pings = Repo.pings.value
        val curMs = pings[cur.id]?.usual ?: lastPing ?: if (leavingLimited) Int.MAX_VALUE else return
        penalty.entries.removeAll { it.value < now }
        // Stable values only (1.0.0 switched on one sample, once to a server with 4 of its last 5 probes failed):
        // the median of a candidate's last successes, this rescan's included, and no failure among its last results.
        // Never a limited-network server as the target (they are a fallback, not something to optimise towards).
        // Leaving a limited-network server, an answer in this rescan is enough: failures from the limited network
        // itself would otherwise keep every ordinary server "unsteady" for five more rescans (the target is probed
        // once more right before the move).
        val others = Repo.activeServers
            .filter { it.id != cur.id && !it.limited && it.id !in penalty }
            .mapNotNull { srv -> pings[srv.id]?.takeIf { it.at >= lastRescanAt && (if (leavingLimited) it.ok else it.steady) }?.let { srv to it.usual!! } }
        if (others.isEmpty()) {
            winner = null
            if (chosen) AppLog.event(LText.of(R.string.ev_strategy_stay, LText.of(Eco.label(settings.strategy)), label(cur)), AppLog.Kind.GOOD)
            return
        }
        // Battery saving mode: latencies weighted by the protocols' energy cost (plain latency otherwise).
        val eco = settings.ecoOn
        val cheapest = if (eco) (others.map { it.first } + cur).minOf(ProtocolCost::factor) else 1.0
        val fastest = minOf(curMs, others.minOf { it.second })
        fun score(srv: Server, ms: Int) = weigh(srv, ms, cheapest, fastest)
        val best = others.minBy { score(it.first, it.second) }
        val curScore = score(cur, curMs)
        val bestScore = score(best.first, best.second)
        var gainOk = bestScore <= curScore * (100 - p.gainPercent) / 100 && curScore - bestScore >= p.gainMs
        // Not asked for by the user, and not under Fastest (asked for speed): below SPEED_FLOOR_MS a faster server
        // isn't felt (chats, pages; streams aren't limited by the round trip) — only a steadier kind of connection
        // still counts; and the same winner must come out of two rescans in a row (servers' own pings jump).
        if (gainOk && !immediate && settings.strategy != Strategy.FASTEST) {
            val steadier = settings.strategy == Strategy.STABLE &&
                ProtocolWeights.stability(ProtocolWeights.of(cur)).traits.size > ProtocolWeights.stability(ProtocolWeights.of(best.first)).traits.size
            if (curMs < SPEED_FLOOR_MS && !steadier) {
                AppLog.debug(TAG, "optimise: ${cur.name} at $curMs ms is fast enough, not switching for speed")
                gainOk = false
            } else if (winner != best.first.id) {
                AppLog.debug(TAG, "optimise: ${best.first.name} wins once, the next rescan must confirm")
                winner = best.first.id
                return
            }
        }
        if (!gainOk) winner = null
        val weighted = eco || curScore != curMs.toDouble() || bestScore != best.second.toDouble()
        AppLog.debug(
            TAG, "optimise: current ${cur.name} $curMs ms, best ${best.first.name} ${best.second} ms" +
                (if (weighted) " (weighted %.0f vs %.0f; ${ProtocolCost.describe(cur)} / ${ProtocolCost.describe(best.first)})".format(curScore, bestScore) else "") +
                " -> ${if (gainOk) "switch" else "stay"}",
        )
        if (gainOk) {
            val announce = when {
                leavingLimited -> LText.of(R.string.ann_limited_off)
                best.second < curMs -> LText.of(R.string.ann_faster, curMs, best.second)
                eco -> LText.of(R.string.ann_cheaper, LText.raw(ProtocolCost.of(cur).key), LText.raw(ProtocolCost.of(best.first).key))
                // Stable left a server of a less steady kind for a slightly slower one.
                else -> LText.of(R.string.ann_steadier, curMs, best.second)
            }
            if (immediate) {
                val cause = if (leavingLimited && !chosen) "LIMITED_OFF" else "STRATEGY"
                if (leavingLimited && Scanner.probeOne(best.first) == null) {
                    AppLog.debug(TAG, "leaving the limited-network server: ${best.first.name} didn't answer again, staying")
                    return
                }
                if (switchTo(best.first, announce, cause)) StatsDb.event(StatsDb.Event.OPTIMIZE, best.first.id, cur.id)
                else if (!leavingLimited) switchTo(cur, null)
            } else {
                // Not asked for by the user: wait for a quiet moment so open connections aren't cut mid-way.
                // A later scan may pick another target; the waiting time keeps counting from the first decision.
                winner = null
                // A large gain may use a quiet moment with the screen on; otherwise it waits for the screen to go off.
                // A strong difference: 1.5 times the strategy's gain in both terms.
                val large = bestScore <= curScore * (100 - LARGE_GAIN * p.gainPercent) / 100 && curScore - bestScore >= LARGE_GAIN * p.gainMs
                val before = pending?.takeIf { it.from == cur.id }
                pending = Pending(best.first, announce, cur.id, before?.since ?: now, large, before?.takeIf { it.large }?.largeAt ?: now)
                AppLog.debug(TAG, "optimise: switching to ${best.first.name} when ${if (large) "traffic pauses (≤ 30 s) or the screen is off" else "the screen is off"}")
                runDeferred()
            }
        } else if (chosen) {
            AppLog.event(LText.of(R.string.ev_strategy_stay, LText.of(Eco.label(settings.strategy)), label(cur)), AppLog.Kind.GOOD)
        }
    }

    /**
     * What waits for nobody to look ([pending] switch for speed, [restartWanted] lists): done now if the screen is off;
     * with it on the restart after [QUIET_MS] without traffic, a switch with a large gain after [LARGE_QUIET_MS]
     * without traffic or [LARGE_WAIT_MS] at most, other switches not at all. Before a switch the target
     * is probed afresh (minutes may have passed). A switch not done within [PENDING_MAX_MS] is dropped (the next
     * rescan decides again); so is one whose target stopped answering or when the server changed meanwhile.
     */
    private suspend fun runDeferred() {
        val cur = current ?: return
        if (mode != Mode.PROXY) return
        val now = System.currentTimeMillis()
        val quiet = now - lastBusyAt >= QUIET_MS
        if (restartWanted > 0 && (!Power.screenOn || quiet)) {
            AppLog.debug(TAG, "routing lists: restarting the core now (${if (!Power.screenOn) "screen off" else "traffic paused"})")
            restartWanted = 0
            if (!switchTo(cur, null, "LISTS")) failover()
            return
        }
        val p = pending ?: return
        if (cur.id != p.from || !Repo.isActive(p.server) || Repo.pings.value[p.server.id]?.ok != true || now - p.since > PENDING_MAX_MS) {
            AppLog.debug(TAG, "optimise: the switch to ${p.server.name} is dropped")
            pending = null
            return
        }
        val largeNow = p.large && (now - lastBusyAt >= LARGE_QUIET_MS || now - p.largeAt >= LARGE_WAIT_MS)
        if (Power.screenOn && !largeNow) return
        pending = null
        val ms = Scanner.probeOne(p.server)
        if (ms == null) {
            AppLog.debug(TAG, "optimise: ${p.server.name} doesn't answer any more, staying")
            return
        }
        val why = when {
            !Power.screenOn -> "screen off"
            now - lastBusyAt >= LARGE_QUIET_MS -> "large gain, traffic paused"
            else -> "large gain, no pause in traffic, waited enough"
        }
        AppLog.debug(TAG, "optimise: switching after ${(now - p.since) / 1000} s ($why)")
        if (switchTo(p.server, p.announce, "OPTIMIZE")) StatsDb.event(StatsDb.Event.OPTIMIZE, p.server.id, p.from)
        else switchTo(cur, null)
    }

    /**
     * The strategy changed: choose the server the way it would. Manual goes back to the server picked by
     * hand last (stays if there is none); "On failure" keeps the current server once a ping confirms it
     * works; Stable / Fastest look at fresh pings of all servers (a scan, unless one just finished) and
     * move if their rules say so — the minimum stay doesn't apply to a choice the user just made.
     */
    private suspend fun onStrategyChanged() {
        if (current?.limited == true) Limited.repace()
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
                if (switchTo(pick, LText.of(R.string.ann_manual), "STRATEGY")) StatsDb.event(StatsDb.Event.MANUAL, pick.id, cur.id)
                else if (!switchTo(cur, null)) failover()
            }
            Strategy.FAILOVER -> checkSoon = true
            Strategy.STABLE, Strategy.FASTEST -> {
                val now = System.currentTimeMillis()
                val lastScan = Repo.pings.value.values.lastProbe() ?: 0L
                // Results of a scan that ended moments ago are fresh enough.
                if (Scanner.isRunning || now - lastScan > STRATEGY_FRESH_MS) {
                    // The connection keeps working (and being watched) meanwhile: decided when the scan ends.
                    lastRescanAt = now
                    return scanThen(AFTER_STRATEGY, Activity.Kind.SCAN_STRATEGY)
                }
                lastRescanAt = now - STRATEGY_FRESH_MS
                afterStrategyScan()
            }
        }
    }

    /** Stable / Fastest chosen: a scan gave fresh results — leave a server that failed in it, else choose by the rules. */
    private suspend fun afterStrategyScan() {
        val cur = current ?: return
        if (mode != Mode.PROXY || (settings.strategy != Strategy.STABLE && settings.strategy != Strategy.FASTEST)) return
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

    /**
     * Servers of chosen countries only, or any country again (see CountryFilter for what happens to the strategy).
     * Choosing: a server in use outside the countries is left at once (Manual's own server if it is among them, else
     * the best of them by a scan). Ending: Manual goes back to its server, On failure to the server used before the
     * filter. Otherwise Stable / Fastest scan the new set and may move; the others only refresh the map. No servers
     * in the countries: like no servers at all.
     */
    private suspend fun onCountryChanged() {
        val s = settings
        val on = s.countries.isNotEmpty()
        AppLog.event(if (!on) LText.of(R.string.ev_country_off) else LText.of(R.string.ev_country_on, Countries.labels(s.countries), Repo.activeServers.size))
        AppLog.debug(TAG, "countries: ${s.countries.ifEmpty { setOf("any") }.joinToString()}, strategy ${s.strategy}")
        noServersLogged = false
        if (mode == null) return
        if (mode != Mode.PROXY) {
            if (!noNetwork) retry()
            return
        }
        val cur = current ?: return
        // Where to go now, if it is decided without a scan.
        val back = when {
            s.strategy == Strategy.MANUAL -> Repo.server(s.manualServerId)?.takeIf { Repo.isActive(it) }
            !on && s.strategy == Strategy.FAILOVER -> Repo.server(s.serverBeforeCountry)?.takeIf { Repo.isActive(it) }
            else -> null
        }
        if (back != null && back.id != cur.id) {
            publish(Phase.SEARCHING, back.id)
            if (switchTo(back, LText.of(if (s.strategy == Strategy.MANUAL) R.string.ann_manual else R.string.ann_country), "COUNTRY")) {
                StatsDb.event(StatsDb.Event.MANUAL, back.id, cur.id)
                Scanner.start(reason = Activity.Kind.SCAN_STRATEGY)
                return
            }
        }
        if (!Repo.isActive(cur)) {
            publish(Phase.SEARCHING)
            val server = findWorking(null)
            if (server != null && switchTo(server, LText.of(R.string.ann_country), "COUNTRY")) StatsDb.event(StatsDb.Event.MANUAL, server.id, cur.id, pos = picked(server))
            else enterFallback()
            return
        }
        if (s.strategy == Strategy.STABLE || s.strategy == Strategy.FASTEST) {
            // Servers that took no part until now have no fresh results: check the whole new set first (in the
            // background — the connection works meanwhile), then choose as when the strategy is picked.
            lastRescanAt = System.currentTimeMillis()
            scanThen(AFTER_STRATEGY, Activity.Kind.SCAN_STRATEGY)
        } else {
            // Manual / On failure stay on a server of the set; the new set is still checked, so the map is fresh.
            Scanner.start(reason = Activity.Kind.SCAN_STRATEGY)
        }
    }

    /**
     * A server's country was set by hand ("Set country" in a server's menu) or reset to automatic. With a country
     * filter on, the server in use may have just fallen out of the chosen countries: leave it the way a change of the
     * filter would (see [onCountryChanged]) — the rest of the list is unaffected and needs no scan.
     */
    private suspend fun onServerCountryChanged() {
        val s = settings
        if (s.countries.isEmpty() || mode == null) return
        if (mode != Mode.PROXY) {
            if (!noNetwork) retry()
            return
        }
        val cur = current ?: return
        if (Repo.isActive(cur)) return
        AppLog.event(LText.of(R.string.ev_server_country_out, label(cur), Countries.labels(s.countries)), AppLog.Kind.WARN)
        publish(Phase.SEARCHING)
        val server = findWorking(null)
        if (server != null && switchTo(server, LText.of(R.string.ann_country), "COUNTRY")) {
            StatsDb.event(StatsDb.Event.MANUAL, server.id, cur.id, pos = picked(server))
        } else {
            enterFallback()
        }
    }

    // ------------------------------------------------------------------ events

    private suspend fun select(id: String) {
        val server = Repo.server(id) ?: return
        AppLog.debug(TAG, "picked by hand: ${server.name} (${server.protocol})")
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
        if (switchTo(server, LText.of(R.string.ann_manual), "MANUAL")) {
            StatsDb.event(StatsDb.Event.MANUAL, server.id, from)
            publish(Phase.CONNECTED)
        } else {
            Repo.updateSettings { it.copy(lastServerId = server.id) }
            enterFallback()
        }
    }

    /**
     * Probes every server and moves to the best one: by [choose] (the fastest, weighed as the strategy and battery
     * saving weigh it). [quiet]: the connection keeps showing as it is during the scan (the user's "Ping all").
     */
    private fun findBest() {
        // With a working connection it stays "connected" during the scan (the map shows the scan).
        if (mode != Mode.PROXY) publish(Phase.SEARCHING, serverId = current?.id)
        AppLog.event(LText.of(R.string.ev_finding_best))
        scanThen(AFTER_BEST, Activity.Kind.SCAN_MANUAL)
    }

    private suspend fun afterBest() {
        val pings = Repo.pings.value
        // Ordinary servers only (a limited-network server's ping can be old, and it is reached only by the fallback).
        val ok = Repo.activeOrdinary.mapNotNull { srv -> pings[srv.id]?.takeIf { it.ok }?.let { srv.id to it.ms!! } }.toMap()
        val best = Repo.server(choose(ok))
        if (best == null) {
            // Nothing ordinary answers: a limited-network server in use stays, otherwise the usual last resort.
            if (current?.limited == true && mode == Mode.PROXY) publish(Phase.CONNECTED)
            else if (!limitedFallback(StatsDb.Event.BEST)) enterFallback()
        } else if (best.id != current?.id || mode != Mode.PROXY) {
            val from = current?.id
            if (switchTo(best, LText.of(R.string.ann_best), "BEST")) StatsDb.event(StatsDb.Event.BEST, best.id, from) else failover()
        } else {
            AppLog.event(LText.of(R.string.ev_already_best), AppLog.Kind.GOOD)
            publish(Phase.CONNECTED)
        }
        lastRescanAt = System.currentTimeMillis()
    }

    private suspend fun pingAll(toBest: Boolean) {
        AppLog.debug(TAG, "ping all: " + if (toBest) "then the best server" else "then the strategy decides")
        if (toBest) return findBest()
        scanThen(AFTER_PING_ALL, Activity.Kind.SCAN_MANUAL)
    }

    private suspend fun afterPingAll() {
        lastRescanAt = System.currentTimeMillis()
        when (mode) {
            Mode.PROXY -> considerSwitch()
            Mode.DIRECT, Mode.PAUSE -> if (!noNetwork) retry()
            null -> {}
        }
    }

    /**
     * Starts (or joins) a scan and comes back with [Event.ScanDone] when it ends: the engine keeps watching the
     * connection, handling network changes and failures meanwhile instead of waiting for the scan.
     */
    private fun scanThen(what: String, reason: Activity.Kind) {
        val job = Scanner.start(reason = reason)
        scope.launch { job.join(); send(Event.ScanDone(what)) }
    }

    private suspend fun reconfigure() {
        val cur = current
        AppLog.event(LText.of(if (settings.routingEnabled) R.string.ev_reconfig_on else R.string.ev_reconfig_off))
        if (mode == Mode.PROXY && cur != null) {
            if (!switchTo(cur, null, "RECONFIGURE")) failover()
        } else {
            mode = null
            enterFallback(noNetwork)
        }
    }

    /** Same server, same mode, a fresh core (it reads the routing lists only when it starts). */
    private suspend fun restartCore() {
        val cur = current
        if (mode == Mode.PROXY && cur != null) {
            // In use the lists can wait (a restart cuts every connection): until the screen is off or traffic pauses.
            restartWanted = System.currentTimeMillis()
            runDeferred()
        } else if (mode != null) {
            enterFallbackMode()
            publish(fallbackPhase())
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
        if (noNetwork || offline) AppLog.event(LText.of(R.string.ev_net_up), AppLog.Kind.GOOD) else AppLog.event(LText.of(R.string.ev_net_changed))
        noNetwork = false
        // Another network may reach further: ask again, and check all servers (hidden ones too) at once.
        NetCheck.forget()
        AutoPool.resetBackoff()
        penalty.clear()
        losses.clear()
        resetLimited()
        // Not known for the new network yet (the next fallback asks again).
        limited = false
        limitedFullAt = 0
        limitedMisses = 0
        pulseStep = PULSE_FIRST_MS
        when (mode) {
            Mode.PROXY -> {
                // Connections bound to the old network are dead; restart the core in place.
                current?.let { if (!switchTo(it, null, "NETWORK")) failover() }
                healthCheck()
            }

            else -> retry()
        }
    }

    /**
     * The system's check of the network underneath: passing again while we have no server means the internet is
     * back (look now); in use, a change either way is worth a ping of the current server.
     */
    private suspend fun onValidated(yes: Boolean) {
        AppLog.debug(TAG, "network ${if (yes) "validated by the system" else "no longer validated by the system"}")
        when (mode) {
            Mode.PROXY -> if (!yes || failStreak > 0) checkSoon = true
            Mode.DIRECT, Mode.PAUSE -> if (yes && !noNetwork && !nothingToUse()) {
                NetCheck.forget()
                comeBack()
            }
            null -> {}
        }
    }

    /**
     * The screen turned on. In use: a server that died (or a core connection that went stale) while the phone slept
     * would otherwise be found only when the user's first requests hang — so if nothing came back through it for a
     * minute and its last answer is older than 2 minutes, check it now (the unlock wakes the radio anyway). Without a
     * server: check now instead of at the next timer.
     */
    private suspend fun onScreenOn() {
        if (current?.limited == true) Limited.repace()
        if (mode == Mode.PROXY) {
            val now = System.currentTimeMillis()
            if (pending == null && failStreak == 0 && !checkSoon && now - lastDownAt >= WAKE_QUIET_MS && now - lastOkCheckAt >= WAKE_STALE_MS) {
                AppLog.debug(TAG, "screen on: last answer ${(now - lastOkCheckAt) / 1000} s ago, checking")
                healthCheck(wake = true)
            }
            return
        }
        if ((mode != Mode.DIRECT && mode != Mode.PAUSE) || noNetwork || nothingToUse()) return
        val now = System.currentTimeMillis()
        slowSince = now
        if (offline || limited) {
            pulseStep = PULSE_FIRST_MS
            pulseAt = now
        }
        if (!offline) nextRetryAt = if (now - lastRetryAt >= SCREEN_RETRY_GAP_MS) now else minOf(nextRetryAt, lastRetryAt + retryMs())
    }

    private fun maybeUpdateSubscriptions() {
        val now = System.currentTimeMillis()
        if (now - lastSubCheckAt < 3_600_000L) return
        lastSubCheckAt = now
        Repo.expireVerbose()
        scope.launch { Importer.updateDue() }
        scope.launch { GeoLists.updateIfDue() }
    }

    private fun publish(phase: Phase, serverId: String? = current?.id) {
        val server = if (phase in WITHOUT_SERVER) null else serverId
        if (phase != recordedPhase || server != recordedServer) {
            AppLog.debug(TAG, "phase: $phase${server?.let(Repo::server)?.let { " via ${it.name}" }.orEmpty()}")
            recordedPhase = phase
            recordedServer = server
            recordedAt = System.currentTimeMillis()
            StatsDb.phase(phase, server, recordedAt)
        }
        TunnelState.set(
            TunnelStatus(
                phase = phase,
                serverId = if (phase in WITHOUT_SERVER) null else serverId,
                // The same value as the server list (median of the last pings), not one reading that may be an outlier.
                ping = if (phase == Phase.CONNECTED) (current?.let { Repo.pings.value[it.id]?.typical } ?: lastPing) else null,
                nextRetryAt = nextRetryAt,
                noServers = (phase == Phase.DIRECT || phase == Phase.PAUSED) && nothingToUse(),
                paused = mode == Mode.PAUSE,
                unanswered = phase == Phase.CONNECTED && unanswered,
                // A server picked by hand stays the visible choice while the engine is not connected to it
                // (a limited-network one whose core can't start, for instance).
                selectedId = settings.manualServerId?.takeIf { settings.strategy == Strategy.MANUAL && Repo.isActive(it) },
            )
        )
    }

    private fun label(s: Server) = Repo.displayName(s)

    /** Hides credentials so a shared log does not leak access to the servers. */
    private fun redact(json: String) = json.replace(
        Regex(
            "\"(id|password|pass|user|auth|secretKey|privateKey|preSharedKey|publicKey|shortId|path|serviceName|seed|mldsa65Seed|" +
                "mldsa65Verify|pinnedPeerCertSha256|encryption|address|serverName|host|endpoint)\":\"[^\"]*\"",
        ),
        "\"$1\":\"***\"",
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
        private const val RIDE_FRESH_MS = 2 * 60_000L
        private const val RIDE_WIFI_MS = 60_000L
        private const val RIDE_MOBILE_MS = 120_000L
        private val PENALTY_STEPS_MS = longArrayOf(5 * 60_000L, 15 * 60_000L, 60 * 60_000L)
        /** With no server at all the engine only wakes now and then (adding one wakes it at once). */
        private const val NO_SERVERS_TICK_MS = 10 * 60_000L
        /** A strategy picked within this time after a scan uses its results instead of a new scan. */
        private const val STRATEGY_FRESH_MS = 60_000L
        /** Length of one window measuring the core's cost per byte, and the traffic it needs to count. */
        private const val COST_WINDOW_MS = 5 * 60_000L
        private const val COST_MIN_BYTES = 256 * 1024L
        /** Under this many bytes a second through the tunnel counts as quiet; quiet this long lets deferred work run. */
        private const val QUIET_RATE = 1_024L
        private const val QUIET_MS = 5_000L
        /** A switch for speed this many times the strategy's gain is "large" ([Pending]). */
        private const val LARGE_GAIN = 1.5
        /** A large gain with the screen on: a pause in traffic this long, or this long at most, and it switches. */
        private const val LARGE_QUIET_MS = 2_000L
        private const val LARGE_WAIT_MS = 30_000L
        /** A switch for speed not done within this is dropped ([runDeferred]). */
        private const val PENDING_MAX_MS = 15 * 60_000L
        /** Limited network: a server lost there is left out this long (1st, 2nd, later losses), then needs two answers. */
        private val LIMITED_BAN_MS = longArrayOf(2 * 60_000L, 5 * 60_000L, 15 * 60_000L)
        private const val LIMITED_CONFIRM_MS = 60_000L
        /** A connection made in a limited network that lasts this long shows servers can live there. */
        private const val LIMITED_SURVIVE_MS = 5 * 60_000L
        /** Limited network: all servers are checked again after 1, 2, then every 5 minutes. */
        private val LIMITED_FULL_MS = longArrayOf(60_000L, 120_000L, 300_000L)
        /** [watch] reads the counters this often with the screen on (battery saving: [WATCH_ECO_MS]). */
        private const val WATCH_MS = 2_000L
        private const val WATCH_ECO_MS = 4_000L
        /** Out with nothing back this long → check; not again within [STALL_RECHECK_MS] of a check that answered. */
        private const val STALL_MIN_MS = 3_000L
        /** Less than a tick's [STALL_UP_BYTES]: not one byte back is a clear sign even for light traffic. */
        private const val WATCH_STALL_UP_BYTES = 1_024L
        private const val STALL_RECHECK_MS = 10_000L
        /** A server answering faster than this isn't left for speed alone ([considerSwitch]). */
        private const val SPEED_FLOOR_MS = 150
        /** Shortest suspicion limit of a health ping ([suspicionMs]). */
        private const val CHECK_MIN_MS = 3_000L
        /** A running health ping is looked at this often (fresh probe done? others answered?). */
        private const val POLL_MS = 250L
        /** A server reconnected to this recently that hangs again: a failure, not another reconnect. */
        private const val RECONNECT_AGAIN_MS = 60_000L
        /** Down through the proxy during a failed ping that proves the server works. */
        private const val EVIDENCE_BYTES = 4_096L
        /** Screen on in use: check if nothing came back for this long and the last answer is older than [WAKE_STALE_MS]. */
        private const val WAKE_QUIET_MS = 60_000L
        private const val WAKE_STALE_MS = 2 * 60_000L
        /** Shortest retry interval (the setting halved with the screen on). */
        private const val RETRY_MIN_MS = 5_000L
        /** Offline: servers are still scanned this often, in case only the direct checks are cut off. */
        private const val OFFLINE_BACKSTOP_MS = 2 * 60_000L
        /** A direct internet check this young is reused when nothing answered. */
        private const val REACH_FRESH_MS = 30_000L
        /**
         * Quick direct checks while offline: 1 s after the change, growing ×1.6 up to 5 s with the screen on, 20 s
         * with it off. Limited networks: up to 10 / 30 s (the wider internet comes back there rarely).
         */
        private const val PULSE_FIRST_MS = 1_000L
        private const val PULSE_GROWTH = 1.6
        private const val PULSE_ON_MS = 5_000L
        private const val PULSE_OFF_MS = 20_000L
        private const val PULSE_LIMITED_ON_MS = 10_000L
        private const val PULSE_LIMITED_OFF_MS = 30_000L
        /** On a limited-network server in a limited network: how often the wider internet is looked at (screen on / off). */
        private const val WIDER_ON_MS = 30_000L
        private const val WIDER_OFF_MS = 120_000L
        /** On a limited-network server: how often the ordinary servers are checked again (any strategy but Manual). */
        private const val LIMITED_RESCAN_MS = 5 * 60_000L
        /** The screen turning on starts a retry unless one started this recently. */
        private const val SCREEN_RETRY_GAP_MS = 5_000L
        /** A server that answered this recently is started on at once when the tunnel is turned on. */
        private const val RECENT_START_MS = 10 * 60_000L

        /** Limited-network servers are weighed far worse than any ordinary one, so they lose unless nothing
         * ordinary answered (see [weigh]). */
        private const val LIMITED_PENALTY = 100.0
        private const val LIMITED_PENALTY_MS = 100_000.0
        /** At most this many limited-network servers are tried in one fallback (each one starts its core). */
        private const val LIMITED_TRY = 2
        /** What waits for a scan ([scanThen]). */
        private const val AFTER_STRATEGY = "strategy"
        private const val AFTER_BEST = "best"
        private const val AFTER_PING_ALL = "ping all"
        /** Phases in which no server carries the traffic. */
        private val WITHOUT_SERVER = setOf(Phase.DIRECT, Phase.PAUSED, Phase.NO_NETWORK, Phase.LIMITED)
    }
}
