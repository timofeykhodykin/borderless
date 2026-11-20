@file:OptIn(ExperimentalForeignApi::class)

package app.borderless.bridge

import app.borderless.config.XrayConfig
import app.borderless.core.CoreEnv
import app.borderless.core.AutoPool
import app.borderless.core.CountryFilter
import app.borderless.core.Eco
import app.borderless.core.Engine
import app.borderless.core.GeoLists
import app.borderless.core.LimitedCore
import app.borderless.core.NetCheck
import app.borderless.core.Phase
import app.borderless.core.Power
import app.borderless.core.Scanner
import app.borderless.core.TunnelState
import app.borderless.core.TunnelStatus
import app.borderless.core.XrayHost
import app.borderless.data.AppLog
import app.borderless.data.AppSettings
import app.borderless.data.Countries
import app.borderless.data.CountryStats
import app.borderless.data.CustomZone
import app.borderless.data.EcoMath
import app.borderless.data.Errors
import app.borderless.data.GeoFile
import app.borderless.data.Group
import app.borderless.data.Importer
import app.borderless.data.Insecurity
import app.borderless.data.LText
import app.borderless.data.PingRecord
import app.borderless.data.Repo
import app.borderless.data.Sample
import app.borderless.data.Server
import app.borderless.data.ServerBuild
import app.borderless.data.SettingsLog
import app.borderless.data.Strategy
import app.borderless.data.StatsDb
import app.borderless.data.StatsMath
import app.borderless.data.Subscription
import app.borderless.data.Work
import app.borderless.data.WorkKind
import app.borderless.data.lastProbe
import app.borderless.net.HttpRequest
import app.borderless.net.HttpResponse
import app.borderless.net.Net
import app.borderless.net.NetError
import app.borderless.net.NetHost
import app.borderless.net.TooBig
import app.borderless.platform.Files
import app.borderless.platform.Host
import app.borderless.platform.Lock
import app.borderless.platform.MacSql
import app.borderless.platform.base64Decode
import app.borderless.platform.debugBinary
import app.borderless.platform.fixed
import app.borderless.platform.hex
import app.borderless.platform.nowMs
import app.borderless.platform.readText
import app.borderless.platform.sha256
import app.borderless.platform.text
import app.borderless.platform.withLock
import app.borderless.platform.writeText
import app.borderless.S
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import platform.Foundation.NSProcessInfo
import platform.darwin.sysctlbyname
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.math.roundToInt

/**
 * What the Mac's service gives the shared engine: its processes and the network underneath. Implemented in Swift by
 * the service (it spawns them sandboxed as the core's own account, see Tunnel.swift / Helper.swift). Every call blocks.
 */
interface MacProcesses {
    /** One JSON request to the helper (`borderless-core -helper`), its JSON answer. Many may run at once. */
    fun helper(request: String): String

    /** Starts the main core with [config] on the utun [tunFd] (a running one stops first); an error text, or null. */
    fun startCore(config: String, tunFd: Int): String?
    fun stopCore()
    fun coreRunning(): Boolean

    /** A command to the running main core ("DELAY <url>", "STATS"), its answer; "" when there is none. */
    fun coreCommand(command: String): String

    /** "<interface> <resolver> <kind>" of the network underneath now (kind: wifi, ethernet, other); "" without one. */
    fun network(): String

    /** The utun's descriptor while the tunnel is up, else 0. */
    fun tunFd(): Int

    /**
     * "screenOn powerSave charging percent capMah voltageMv" of the Mac now (−1 for a field the Mac
     * can't read — a desktop's battery or `AppleSmartBattery` keys); "" when nothing is known.
     */
    fun power(): String

    /**
     * The process counters [MacEnergy] measures (ENERGY-IMPL): one space-separated line
     * "cpuMs nj fbKb" × four roles — the service itself, its helper, the main core, all live limited
     * cores summed — then the wall-clock ms of the last heavy client request (the app's stats/log
     * screens; the ambient status poll doesn't stamp it). Counters are monotonic per role (dead or
     * replaced pids are folded into the role's sum on the service side); fbKb is the live physical
     * footprint. nj is nanojoules of `ri_energy_nj` — 0 forever on Intel, real on Apple Silicon.
     * "" when the service can't answer (the meter then shows nothing new, never a made-up line).
     */
    fun resources(): String = ""

    /** Whether the limited-network core (`borderless-limited`) is installed. */
    fun limitedAvailable(): Boolean

    /** Starts the limited core with [argv] (the service adds the account, the lifeline and the binary) plus the
     *  extra environment [env] — secrets go there, never into argv: any local user reads another process's
     *  command line (`ps auxww`), another account's environment not. >0 = its handle, 0 = it couldn't start. */
    fun limitedSpawn(argv: List<String>, env: Map<String, String>): Int

    /** Whether the core with [handle] still runs (false once it ended or was stopped). */
    fun limitedAlive(handle: Int): Boolean

    /** A command line to the core's stdin ("STREAMS n", "KEEPALIVE k b", "STOP"). */
    fun limitedSend(handle: Int, line: String)

    /** The next line of the core's output (its events and, with -verbose, its log); "" when it ended. Blocks. */
    fun limitedRead(handle: Int): String

    /** Ends the core (stdin closed, TERM, KILL if it stays). */
    fun limitedStop(handle: Int)
}

/** The network underneath, as the service reports it. */
internal data class MacNetwork(val iface: String, val resolver: String, val kind: String) {
    companion object {
        fun of(text: String): MacNetwork? {
            val f = text.trim().split(' ')
            return if (f.size >= 2 && f[0].isNotEmpty()) MacNetwork(f[0], f[1], f.getOrElse(2) { "other" }) else null
        }
    }
}

@Serializable
private class HelperReply(
    val id: Long = 0,
    val error: String? = null,
    val kind: String? = null,
    val code: Int = 0,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val url: String? = null,
    val ms: Long = 0,
    val handle: Long = 0,
    val port: Int = 0,
    val ok: Boolean = false,
    val version: String? = null,
)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val ids = AtomicLong(0)

private fun MacProcesses.call(op: String, fill: JsonObjectBuilder.() -> Unit = {}): HelperReply {
    val request = buildJsonObject {
        put("id", ids.incrementAndFetch())
        put("op", op)
        fill()
    }
    return json.decodeFromString(HelperReply.serializer(), helper(request.toString()))
}

/** [NetHost] on the Mac: the helper makes the requests, bound to the physical interface. */
private class MacNet(private val p: MacProcesses) : NetHost {
    override fun request(r: HttpRequest): HttpResponse {
        val a = p.call("http") {
            put("url", r.url)
            put("method", r.method)
            putJsonObject("headers") { r.headers.forEach { (k, v) -> put(k, v) } }
            put("connectMs", r.connectTimeoutMs)
            put("readMs", r.readTimeoutMs)
            put("follow", r.followRedirects)
            put("max", r.maxBytes)
            put("statusOnly", r.statusOnly)
            r.proxy?.let { s -> putJsonObject("socks") { put("port", s.port); put("user", s.user); put("pass", s.pass) } }
        }
        a.error?.let { e ->
            throw when (a.kind) {
                "toobig" -> TooBig(r.maxBytes)
                "dns" -> NetError(NetError.Kind.DNS, e)
                "tls" -> NetError(NetError.Kind.TLS, e)
                "unreachable" -> NetError(NetError.Kind.UNREACHABLE, e)
                else -> NetError(NetError.Kind.OTHER, e)
            }
        }
        return HttpResponse(a.code, a.headers, a.body?.let(::base64Decode) ?: ByteArray(0), a.url.orEmpty())
    }

    override fun freePort(): Int = p.call("free-port").port.takeIf { it > 0 } ?: throw NetError(NetError.Kind.OTHER, "no free port")

    override fun socksAccepts(port: Int, user: String, pass: String): Boolean =
        p.call("socks-check") { put("port", port); put("user", user); put("pass", pass) }.ok
}

/**
 * [XrayHost] on the Mac: the main instance is the service's core process on the utun; probes and local proxies run
 * inside the helper. Every config is bound to the physical interface first ([XrayConfig.bound]).
 */
private class MacXray(private val p: MacProcesses, private val listsDir: String) : XrayHost {
    private val lock = Lock()
    private var ready = false

    override fun version(): String = p.call("version").version ?: "?"

    override fun ensure() = lock.withLock {
        if (ready) return
        // The lists are downloaded (GeoLists); until then the files are there but empty.
        GeoLists.install(listsDir)
        GeoFile.dir = listsDir
        ready = true
    }

    /** The config bound to the network underneath; as it is when there is none (it can't connect anyway). */
    private fun bound(config: String): String {
        val n = MacNetwork.of(p.network()) ?: return config
        return XrayConfig.bound(config, n.iface, listOf(n.resolver, "https://1.0.0.1/dns-query"))
    }

    override fun measureOutbound(config: String, url: String): Long {
        val a = p.call("probe") { put("config", bound(config)); put("url", url) }
        a.error?.let { throw IllegalStateException(it) }
        return a.ms
    }

    override fun controller(log: (String) -> Unit): XrayHost.Controller = object : XrayHost.Controller {
        private var main = false
        private var handle = 0L

        /**
         * Traffic counters and the in-core delay are commands to the service's main core — a non-main
         * instance (the helper's local proxy, [start] with tunFd 0) has nowhere to ask, so [queryTraffic]
         * and [measureDelay] silently answer "" / −1 and a caller can't tell "no data" from "not the main
         * instance". Said once through [log], so the degrade at least shows in the detailed log.
         */
        private var silentLogged = false
        private fun notMain(): Unit = if (silentLogged) Unit else {
            silentLogged = true
            log("not the main instance: queryTraffic/measureDelay have no main core to ask and always answer empty")
        }

        override val isRunning: Boolean get() = if (main) p.coreRunning() else handle != 0L

        override fun start(config: String, tunFd: Int) {
            stop()
            if (tunFd > 0) {
                p.startCore(bound(config), tunFd)?.let { throw IllegalStateException(it) }
                main = true
            } else {
                val a = p.call("proxy-start") { put("config", bound(config)) }
                a.error?.let { throw IllegalStateException(it) }
                handle = a.handle
            }
        }

        override fun stop() {
            if (main) p.stopCore()
            main = false
            if (handle != 0L) p.call("proxy-stop") { put("handle", handle) }
            handle = 0
        }

        override fun queryTraffic(): String = if (main) p.coreCommand("STATS") else "".also { notMain() }

        override fun measureDelay(url: String): Long = if (main) p.coreCommand("DELAY $url").trim().toLongOrNull() ?: -1 else (-1L).also { notMain() }
    }
}

/**
 * The repository's store and the log files are the sealed [MacStore]/[MacLogFiles] in `MacStore.kt`
 * (encryption at rest, F-SYSTEM-0016-1 / F-DATA-0038-1): the private plaintext classes that lived here were
 * removed — the call sites below resolve to the public same-named replacements.
 */

private class MacErrors(private val dir: String, private val version: String) : Errors.Platform {
    override fun header(): String = "Border(less) for Mac $version\nmacOS ${NSProcessInfo.processInfo.operatingSystemVersionString}"

    /**
     * The platform's failure shapes (Android's exception classes don't exist here): the statistics' SQLite
     * fails with "sqlite: …" [IllegalStateException]s ([MacSql]), file access with the "can't …" / "short …" /
     * "cannot replace …" ones ([Files], [MacStore]). Everything else is a bug of ours — network failures
     * arrive as [NetError]/[TooBig] and are sorted out before this ([Errors.isBug]).
     */
    override fun reason(root: Throwable): String? = when {
        root is OutOfMemoryError -> "err_memory"
        root.message?.startsWith("sqlite:") == true -> "err_database"
        isIo(root) -> "err_io"
        else -> null
    }

    /** The device's doing, not a bug — where Android counts an IOException: storage trouble ([Files]). */
    override fun expected(root: Throwable): Boolean = isIo(root)

    private fun isIo(root: Throwable): Boolean =
        root is IllegalStateException && root.message?.let { m -> IO_PREFIXES.any(m::startsWith) } == true
    override fun frame(root: Throwable): String? =
        root.stackTraceToString().lines().drop(1).firstOrNull { "app.borderless" in it }?.trim()?.take(160)

    override var reportedAt: Long
        get() = Files.readText("$dir/errors_reported")?.trim()?.toLongOrNull() ?: 0
        set(value) = Files.writeText("$dir/errors_reported", value.toString())

    private companion object {
        /** The [Files]/[MacStore] failure messages ([MacErrors.isIo]). */
        val IO_PREFIXES = listOf("can't read ", "can't write ", "short read ", "short write ", "cannot replace ")
    }
}

private class MacEngineHost(private val p: MacProcesses, private val renewed: () -> Unit) : Engine.Host {
    override val tunFd: Int get() = p.tunFd()
    override fun hasNetwork(): Boolean = MacNetwork.of(p.network()) != null
    override fun isScreenOn(): Boolean = Power.screenOn
    override fun networkKind(): String? = MacNetwork.of(p.network())?.kind

    /**
     * The zones' apps inside the tunnel or outside it: the Mac has no per-app TUN, so every app is "inside" and a
     * change only means the "directly first" dialer comes or goes — the core restarts with the config of the moment.
     */
    override fun zoneAppsInside(inside: Boolean) { renewed() }

    /** The engine's hourly tick (its `maybeUpdateSubscriptions` ran the due subscription/geo work itself):
     *  Android's `Updater.checkIfDue` counterpart — the updater's hook plus stamping the off-state timer. */
    override fun hourly() = MacEngine.hourly()
}

/** The Mac's power readings, asked of the service on demand ([MacProcesses.power]); a second's cache, since the
 *  engine reads several fields at one decision point and every field is otherwise its own round trip. */
private class MacPower(private val p: MacProcesses) : Power.Source {
    private val lock = Lock()
    private var readAt = 0L
    private var reading: List<String> = emptyList()

    private fun of(): List<String> {
        lock.withLock {
            val now = nowMs()
            if (now - readAt > 1_000) {
                reading = p.power().trim().split(' ').filter { it.isNotEmpty() }
                readAt = now
            }
            return reading
        }
    }

    override val screenOn: Boolean get() = of().firstOrNull() != "0"
    override val powerSave: Boolean get() = of().getOrNull(1) == "1"
    /** The primary path is metered (expensive or constrained): the service's network watcher pushes it
     *  ([MacEngine.meteredChanged] — NWPathMonitor's `isExpensive || isConstrained`), as Android reads
     *  `TRANSPORT_CELLULAR`. False until the service reports. */
    @Volatile
    override var metered: Boolean = false
    override val charging: Boolean get() = of().getOrNull(2) == "1"
    /** `AppleSmartBattery`'s capacity and voltage (the power row's cap_mah / mv) — null on a desktop
     *  or when the service's power line doesn't carry them. */
    override val capacityMah: Int? get() = of().getOrNull(4)?.toIntOrNull()?.takeIf { it > 0 }
    override val voltageMv: Int? get() = of().getOrNull(5)?.toIntOrNull()?.takeIf { it > 0 }
    override val batteryPercent: Int? get() = of().getOrNull(3)?.toIntOrNull()?.takeIf { it in 0..100 }
}

/**
 * The shared engine inside the Mac's service: set up once, turned on and off by the user (through the app or the menu
 * bar), told about network changes; its state goes to the app as JSON ([status]). Android's `TunnelService` in short.
 */
object MacEngine {
    private lateinit var processes: MacProcesses
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + Errors.handler)
    private val lock = Lock()

    /** Read under [lock] where it's replaced and bare on the engine's own threads: volatile, so those see it. */
    @Volatile
    private var engine: Engine? = null
    private var version = "?"
    private lateinit var powerSource: MacPower
    /** The sealed store [Repo] uses; [PENDING_HIDDEN_FILE] lives in it too — its keys are full share links
     *  (credentials), so nothing in `repo/` may stay plaintext (R1) and a write must be atomic. */
    private lateinit var repoStore: MacStore

    /** The wave-2 updater plugs in here (a `bridge/MacUpdater.kt`): the hourly / manual check, the user's
     *  install/dismiss answers (the `update_install` / `update_dismiss` actions) and the state shown as
     *  [Status.update]. null: the `update_*` actions answer "not implemented" and the status field stays null. */
    @Volatile
    internal var updateHooks: UpdateHooks? = null

    /** Subscription updates in flight (manual or scheduled): [Status.subsUpdating] — the rows' busy state. */
    private val subUpdates = AtomicLong(0)

    /**
     * Once, as the service starts. [dataDir] holds the user's data (`repo/`, `stats.db`, `logs/`), [listsDir] the routing
     * lists the core reads.
     */
    fun setUp(processes: MacProcesses, dataDir: String, listsDir: String, version: String) {
        this.processes = processes
        this.version = version
        Net.host = MacNet(processes)
        CoreEnv.xray = MacXray(processes, listsDir)
        Errors.guard("starting the service", Unit, show = false) {
            // For the subscription panels' x-device-* headers ([AppSettings.sendDeviceModel]): the real
            // system, not the defaults ("Android", empty). Inside the guard like everything below: the
            // sysctl allocates, and a failure of it must not escape the service's start.
            Host.system = "macOS"
            // "Version 26.0.1 (Build 25A354)" → "26.0.1": the release, as Android sends it.
            Host.systemVersion = NSProcessInfo.processInfo.operatingSystemVersionString
                .removePrefix("Version ").substringBefore(" (")
            Host.deviceModel = sysctl("hw.model")
            // The service can't read the app's strings table (generated into the app bundle a root daemon
            // never sees): its own English one for the keys it resolves to plain text — see [ServiceTexts].
            Host.text = { name, args -> ServiceTexts.of(name, args) }
            // What Android reads as BuildConfig.DEBUG: the test switches it gates ([GeoLists]'s own
            // manifest.test.json, [MacDirectFirst.forcedForTest]'s limited.test) get the same gate here.
            Host.debug = debugBinary()
            // Failures of the bundled files (regions.json, countries.json) are kept failures like any other:
            // logged and held for reporting, never a dialog — Android wires the same with `show = false`.
            Host.report = { e, where -> Errors.report(e, where, show = false) }
            // Read old keys only to recover encrypted files. New writes never depend on Keychain.
            MacCrypto.loadLegacy(dataDir)
            AppLog.init(MacLogFiles("$dataDir/logs"))
            Errors.init(MacErrors("$dataDir/logs", version))
            repoStore = MacStore("$dataDir/repo").also { Repo.init(it) }
            // A locked store keeps its sealed statistics as they are — Android's `Crypto.locked` gate.
            Errors.guard("statistics", Unit, show = false) {
                if (!repoStore.locked) StatsDb.init(MacSql.openPlain("$dataDir/stats.db", listOfNotNull(MacCrypto.dbKey()) + MacCrypto.dbOldKeys()))
            }
            // The platform hooks Android wires in AndroidStats (F-DATA-0026-1): battery saving's longer write
            // batches apply here too; `cellPowerW` only feeds the v5 activity migration, which a Mac-created
            // DB never runs (there is no cell radio either way).
            StatsDb.ecoOn = { Repo.settings.value.ecoOn }
            powerSource = MacPower(processes)
            Power.source = powerSource
            MacLimited.setUp(processes, dataDir)
            // The energy meter (ENERGY-IMPL): Android's Activity+Energy — tracked spans' CPU share,
            // power samples, the off-engine tick — fed by the service's [MacProcesses.resources].
            // setUp before the meter is installed: a Work.track between them would reach resources()
            // with no processes yet. Its counters flush with every statistics read, as Android's do.
            MacEnergy.setUp(processes)
            Work.meter = MacEnergy.meter
            StatsDb.beforeFlush = { MacEnergy.flush() }
            applyNetwork()
            Errors.guard("routing lists", Unit, show = false) { CoreEnv.ensure() }
            SettingsLog.start()
            Eco.init()
            AppLog.debug("service", "Border(less) for Mac $version, ${CoreEnv.version()}")
            // New routing lists: the core loads them when it starts.
            scope.launch { GeoLists.version.drop(1).collect { engine?.send(Engine.Event.RestartCore) } }
            // A bundle import's hidden-server marks (F-SUBS-0039-1): persisted now ([recordPendingHidden]),
            // applied once each subscription's first download makes its servers ([Repo.hideByKeys]).
            loadPendingHidden()
            scope.launch {
                val applied = HashSet<String>()
                Repo.subscriptions.collect { subs ->
                    var dirty = false
                    for (sub in subs) {
                        if (sub.updatedAt > 0 && applied.add(sub.id)) {
                            pendingHiddenLock.withLock { pendingHidden.remove(sub.id) }?.let { keys ->
                                Repo.hideByKeys(sub.id, keys)
                                dirty = true
                            }
                        }
                    }
                    // Marks of a subscription deleted before it downloaded are dropped.
                    dirty = pendingHiddenLock.withLock {
                        val stale = pendingHidden.keys.filter { k -> subs.none { it.id == k } }
                        stale.forEach(pendingHidden::remove)
                        stale.isNotEmpty()
                    } || dirty
                    if (dirty) writePendingHidden()
                }
            }
            // Due work once at start — then the engine's hourly while it runs, the timer below while it's off.
            dueWork()
            // The off-state counterpart of the engine's `maybeUpdateSubscriptions` (which stamps the same
            // [lastDueCheckAt] through [hourly], so they never double-run): a daemon that runs for months
            // with the tunnel down still refreshes due subscriptions and routing lists, expires the verbose
            // log and resolves server countries — the work Android's app does on its hourly alarm.
            scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(DUE_TICK_MS)
                    Errors.guard("due work", Unit, show = false) {
                        if (nowMs() - lastDueCheckAt >= DUE_EVERY_MS) dueWork()
                    }
                }
            }
            // Statistics past the retention go daily (the phone prunes on the statsDays change; a daemon that runs for
            // months would keep everything forever otherwise).
            scope.launch {
                while (true) {
                    Errors.guard("statistics", Unit, show = false) { StatsDb.prune(Repo.settings.value.statsDays) }
                    kotlinx.coroutines.delay(24 * 60 * 60_000L)
                }
            }
            // The tunnel is made from these fields (Android `AppSettings.tunnelKey`): a change rebuilds it. The engine
            // reacts to the strategy and countries itself; the rest is ours.
            scope.launch {
                Repo.settings.map { listOf(
                    it.routingEnabled, it.routingMode, it.regions, it.appsDirect, it.appsTunnel, it.directDomains,
                    it.proxyDomains, it.adFilter, it.remoteDns, it.directDns, it.ipv6, it.mtu, it.fragment, it.mux,
                    it.noServerAction, it.verboseLog,
                ) }.distinctUntilChanged().drop(1).collect { engine?.send(Engine.Event.Reconfigure) }
            }
            // A zone's contents changed (sites/apps/lists, a rename): the settings watcher sees only the
            // regions set, so a running core would keep routing by the old zone — compare the enabled zones'
            // contents and rebuild when they differ (F-ROUTING-0032-1; a disabled zone's edits don't touch
            // the running config and deliberately don't restart it).
            scope.launch {
                Repo.zones.map { zones -> zones.filter { it.id in Repo.settings.value.regions } }
                    .distinctUntilChanged().drop(1).collect { engine?.send(Engine.Event.Reconfigure) }
            }
        }
    }

    /** A sysctl string ("hw.model" → "MacBookPro18,3"); "" when it can't be read. */
    private fun sysctl(name: String): String = memScoped {
        val size = alloc<ULongVar>()
        if (sysctlbyname(name, null, size.ptr, null, 0u) != 0 || size.value.toInt() <= 0) return@memScoped ""
        val buf = allocArray<ByteVar>(size.value.toInt())
        if (sysctlbyname(name, buf, size.ptr, null, 0u) != 0) "" else buf.toKString()
    }

    /** The helper's connections follow the network underneath. */
    private fun applyNetwork() {
        val n = MacNetwork.of(processes.network())
        Errors.guard("network", Unit, show = false) {
            processes.call("network") { put("iface", n?.iface.orEmpty()); put("dns", n?.resolver.orEmpty()) }
        }
    }

    /**
     * The engine's hourly tick ran (`Engine.Host.hourly` — its `maybeUpdateSubscriptions` did the subscription /
     * geo-list due work itself): what is left of Android's platform hourly here is the updater's due check —
     * and stamping the off-state timer so [dueWork] doesn't run again inside the same hour.
     */
    internal fun hourly() {
        lastDueCheckAt = nowMs()
        Errors.guard("the update check", Unit, show = false) { updateHooks?.check?.invoke(false) }
    }

    /**
     * The due-work set Android does on its hourly alarm (and partly on app start): the verbose log's own
     * expiry, due subscription updates, due geo-list updates, server-country resolution and the updater's
     * check. Runs at [setUp] and then either by the engine's hourly or by the off-state timer.
     */
    private fun dueWork() {
        if (storageLocked()) return
        lastDueCheckAt = nowMs()
        Repo.expireVerbose()
        scope.launch { Errors.guard("updating subscriptions", Unit) { Importer.updateDue() } }
        scope.launch { Errors.guard("routing lists", Unit) { GeoLists.updateIfDue() } }
        scope.launch { Errors.guard("server countries", Unit) { Importer.resolveCountries() } }
        Errors.guard("the update check", Unit, show = false) { updateHooks?.check?.invoke(false) }
    }

    /** Last due-work run (either path); guarded reads/writes by the hour, not by the lock. */
    @Volatile
    private var lastDueCheckAt = 0L

    private const val DUE_EVERY_MS = 3_600_000L
    /** The off-state timer wakes this often and runs [dueWork] when [DUE_EVERY_MS] passed since the last run. */
    private const val DUE_TICK_MS = 15 * 60_000L

    /**
     * The network underneath is metered (expensive or constrained — the service's network watcher pushes it,
     * `EngineBridge.meteredChanged` from the NWPathMonitor read): [Power.stretch] then spreads probe and scan
     * intervals on it, and `Status.metered` lets the app show the hint — Android's `TRANSPORT_CELLULAR` reading.
     */
    fun meteredChanged(metered: Boolean) {
        if (::powerSource.isInitialized) powerSource.metered = metered
    }

    /**
     * A service-side event for the app log, language-neutral like every event ([name] is an `ev_*` string key
     * the app renders, [args] its arguments; [kind] an `AppLog.Kind` name). For the daemon's own happenings a
     * bridge can't emit — a tunnel start failure, a foreign tunnel, an autostart, a crash notice.
     */
    fun event(name: String, args: List<String> = emptyList(), kind: String = "INFO") =
        Errors.guard("the event", Unit, show = false) {
            AppLog.event(LText.of(name, *args.toTypedArray()),
                runCatching { AppLog.Kind.valueOf(kind) }.getOrDefault(AppLog.Kind.INFO))
        }

    /**
     * A line into the detailed log from the service's own parts ([tag] like "core" / "tunnel" / "helper"), so
     * core output and daemon decisions land where the app's detailed-log screen and export read them.
     */
    fun debug(tag: String, text: String) = Errors.guard("the debug line", Unit, show = false) { AppLog.debug(tag, text) }

    /** The user turned the connection on, or picked [serverId] (null: the last one, or as the strategy chooses). */
    fun turnOn(serverId: String?) = Errors.guard("turning on", Unit, show = false) {
        lock.withLock {
            engine?.let { e ->
                serverId?.let { e.send(Engine.Event.Select(it)) }
                return@withLock
            }
            if (serverId == null) Repo.updateSettings { EcoMath.startStrategy(it) }
            // Picking a server while the engine is off starts on it under the Manual strategy — the write
            // Android's MainActivity.connectTo makes before its service starts; otherwise the pick was only
            // the app's own flag and the engine never learned it was chosen by hand.
            else Repo.updateSettings { it.copy(strategy = Strategy.MANUAL, manualServerId = serverId) }
            AppLog.event(LText.of(S.ev_tunnel_on))
            TunnelState.set(TunnelStatus(phase = Phase.SEARCHING))
            engine = Engine(MacEngineHost(processes) { engine?.send(Engine.Event.TunRenewed) })
                .also { it.start(serverId ?: Repo.settings.value.lastServerId) }
        }
    }

    fun turnOff() = Errors.guard("turning off", Unit, show = false) {
        val e = lock.withLock { engine.also { engine = null } } ?: return@guard
        // The exports below are sync functions on the service's socket workers, so the suspend parts run
        // under runBlocking. That blocking is bounded: the service accepts at most 8 control connections
        // (one worker each) and times out their fds, so a stalled wait can't pile up unboundedly — it only
        // pins that one request's slot until the engine's own timeouts (or the client's) end it.
        runBlocking { Errors.guard("stopping the engine", Unit) { e.stop() } }
        TunnelState.set(TunnelStatus())
        AppLog.event(LText.of(S.ev_tunnel_off))
    }

    val isOn: Boolean get() = Errors.guard("the tunnel state", false, show = false) { lock.withLock { engine != null } }

    /** The network underneath changed — another Wi-Fi, a cable, or a fall to none (the service's
     *  `checkNetwork` sends the down transition here too, as Android's `onLost` does; see
     *  [networkValidated]). */
    fun networkChanged() = Errors.guard("a network change", Unit, show = false) {
        applyNetwork()
        NetCheck.forget()
        engine?.send(Engine.Event.NetworkChanged)
    }

    /**
     * The service's own check of the network underneath passed again ([Engine.Event.Validated]) — the service
     * sends it on an up transition when the network that came back looks like the one that went away. A loss
     * is deliberately not reported as `Validated(false)`: `Engine.onValidated` would only schedule a check of
     * the server in use, whose failure would be booked against the server rather than the network (and the
     * fallback modes ignore it entirely). A down transition reaches the engine the way Android's `onLost`
     * does: `checkNetwork` calls [networkChanged] on the empty reading, so the engine's checks pause rather
     * than book failures against the current server.
     */
    fun networkValidated(yes: Boolean) = Errors.guard("a network change", Unit, show = false) {
        engine?.send(Engine.Event.Validated(yes))
    }

    /** The display slept or woke (the service polls it): [Engine.Event.ScreenOn] / [Engine.Event.ScreenOff]. */
    fun screenChanged(on: Boolean) = Errors.guard("a screen change", Unit, show = false) {
        engine?.send(if (on) Engine.Event.ScreenOn else Engine.Event.ScreenOff)
    }

    /**
     * "Ping all" (Android's onPingAll): a scan in progress first starts over at full pace, then — the
     * connection on and the strategy not Manual — the engine scans and decides as it says; a plain scan
     * otherwise (off, or Manual: a picked server isn't left by a scan).
     */
    fun pingAll() = Errors.guard("ping all", Unit, show = false) {
        Scanner.restart()
        val e = engine
        if (e != null && Repo.settings.value.strategy != Strategy.MANUAL) {
            e.send(Engine.Event.PingAll(Repo.settings.value.pingAllSwitches))
        } else Scanner.start(reason = WorkKind.SCAN_MANUAL)
    }

    /**
     * Opening the app's window (`serviceAction` "scan_open", Android's `MainActivity.onStart`): the due-work
     * set the app's open always runs (`updateDue`/`updateIfDue` gate themselves inside, so a rapid reopen
     * costs nothing), then a rescan when the shown pings are stale by the engine's own schedule — the
     * policy's rescan interval while the tunnel is on, five minutes (×3 in battery saving) while off —
     * stretched further on a metered network or under power saving (`Power.stretch`), never a blind scan.
     * The data's age is its last check of servers (`probedAt`; the server in use is pinged on its own and
     * doesn't refresh the map).
     */
    private fun scanOpen() {
        dueWork()
        val newest = Repo.pings.value.values.lastProbe() ?: 0
        val s = Repo.settings.value
        val maxAge = if (TunnelState.status.value.active) s.policy.rescanMin * 60_000L
            else 5 * 60_000L * (if (s.ecoOn) EcoMath.OPEN_SCAN_FACTOR else 1)
        if (Repo.activeServers.isNotEmpty() && nowMs() - newest > maxAge * Power.stretch()) {
            Scanner.start(reason = WorkKind.SCAN_OPEN)
        }
        // Android's app-open also refreshes the battery-saving estimate (its own half-hour gate applies).
        Eco.refreshEstimate()
    }

    /** The MTU the utun is made with ([AppSettings.mtu]; the service asks for it when it brings the tunnel up). */
    fun currentMtu(): Int = Errors.guard("the settings", 1500, show = false) { Repo.settings.value.mtu }

    @Serializable
    private class Ping(val ms: Int? = null, val at: Long = 0)

    /** A failure queued for a user-visible dialog ([Errors.Report]): the app shows it, then dismisses it
     *  (`error_dismiss:<id>` through [action]). */
    @Serializable
    private class PendingError(val id: Long, val message: String, val details: String, val crash: Boolean = false, val bug: Boolean = false)

    /** What the update checker drives and reports (the wave-2 updater package implements it; [updateHooks]).
     *  [check] takes `force`: the settings' button asks now, the due-work path keeps the day's pace. */
    internal class UpdateHooks(
        val check: (Boolean) -> Unit,
        val install: () -> Unit,
        val dismiss: () -> Unit,
        val status: () -> UpdateStatus?,
    )

    /**
     * The update checker's state on the wire (`EngineStatus.update`; the names are the wire contract's):
     * `state`: "idle" | "checking" | "available" | "downloading" | "ready" | "installing" | "error".
     */
    @Serializable
    internal class UpdateStatus(
        val state: String,
        val version: String? = null,
        val checkedAt: Long? = null,
        val readyAt: Long? = null,
        val error: String? = null,
    )

    @Serializable
    private class Status(
        val phase: String,
        val serverId: String? = null,
        val selectedId: String? = null,
        val error: String? = null,
        val unanswered: Boolean = false,
        val noServers: Boolean = false,
        val nextRetryAt: Long = 0,
        val scanDone: Int = 0,
        val scanTotal: Int = 0,
        val scanStartedAt: Long = 0,
        val probing: List<String> = emptyList(),
        val pending: List<String> = emptyList(),
        /** Failures queued for a user-visible dialog ([Errors.pending]; `pending` is the scanner's field). */
        val pendingErrors: List<PendingError> = emptyList(),
        val pings: Map<String, Ping> = emptyMap(),
        val strategy: String = "",
        val routing: Boolean = true,
        val eco: Boolean = false,
        val ecoEstimate: Int? = null,
        val listsUpdated: Long = 0,
        val listsChecked: Long = 0,
        val listsError: String? = null,
        /** A routing-list update runs and what it does ("DOWNLOADING"/"VIA_SERVER"), and the next retry of a missing one. */
        val listsBusy: Boolean = false,
        val listsWork: String? = null,
        val listsRetryAt: Long? = null,
        /** Failures kept for reporting since the last statistics export (`Errors.unreported`). */
        val unreported: Int = 0,
        /** The device id a limited-network server sends when neither the user nor its link names one. */
        val deviceId: String = "",
        /** Server ids of the active subset (Android [Repo.isActive]/[Repo.visible]: the countries filter, the
         *  auto-visibility pool, hidden and insecure servers applied) — the app's `!disabled` stand-in. */
        val activeIds: List<String> = emptyList(),
        /** The network underneath is metered ([Power.metered], pushed by the service's network watcher). */
        val metered: Boolean = false,
        /** Live limited-session counters of the server in use ([MacLimited.live]); null when none runs. */
        val limitedActive: Int? = null,
        val limitedWanted: Int? = null,
        val limitedLimit: Int? = null,
        val limitedKnown: Int? = null,
        /** The update checker's state ([updateHooks]); null while no updater exists in the service. */
        val update: UpdateStatus? = null,
        /** The countries filter in effect (the globe button's state). */
        val countries: List<String> = emptyList(),
        /** `TunnelStatus.paused`/`ping`: traffic held while no server works, and the current server's delay. */
        val paused: Boolean = false,
        val ping: Int? = null,
        /** A subscription update runs ([subUpdates]): the settings' "Update" rows' busy state. */
        val subsUpdating: Boolean = false,
        /** When the tunnel last carried traffic (the "Last connected" subtitle); null: never connected. */
        val lastConnected: Long? = null,
    )

    /** The state the app shows, as JSON. */
    fun status(): String = Errors.guard("the status", "{}", show = false) {
        val s = TunnelState.status.value
        val scan = Scanner.progress.value
        val lists = GeoLists.state.value
        val live = MacLimited.live
        json.encodeToString(Status.serializer(), Status(
            phase = s.phase.name,
            serverId = s.serverId,
            selectedId = s.selectedId,
            error = s.error,
            unanswered = s.unanswered,
            noServers = s.noServers,
            nextRetryAt = s.nextRetryAt,
            scanDone = scan?.done ?: 0,
            scanTotal = scan?.total ?: 0,
            scanStartedAt = scan?.startedAt ?: 0,
            probing = Scanner.probing.value.toList(),
            pending = Scanner.pending.value.toList(),
            pendingErrors = Errors.pending.value.map { PendingError(it.id, it.message, it.details, it.crash, it.bug) },
            pings = pingsOut(),
            strategy = Repo.settings.value.strategy.name,
            routing = Repo.settings.value.routingEnabled,
            eco = Repo.settings.value.ecoOn,
            ecoEstimate = Eco.estimate.value?.percent,
            listsUpdated = lists.updatedAt,
            listsChecked = lists.checkedAt,
            listsError = lists.lastError,
            listsBusy = GeoLists.busy.value,
            listsWork = GeoLists.work.value?.name,
            listsRetryAt = lists.retryAt.takeIf { it > 0 },
            // runBlocking: a first() on a StateFlow returns at once (see turnOff for why blocking is bounded).
            unreported = runBlocking { Errors.unreported.first() },
            deviceId = defaultDeviceId,
            activeIds = Repo.activeServers.map { it.id },
            metered = Power.metered,
            limitedActive = live?.active,
            limitedWanted = live?.wanted,
            limitedLimit = live?.limit,
            limitedKnown = live?.serverLimit,
            update = updateHooks?.status?.invoke(),
            countries = Repo.settings.value.countries.sorted(),
            paused = s.paused,
            ping = s.ping,
            subsUpdating = subUpdates.load() > 0,
            lastConnected = lastConnected(),
        ))
    }

    /**
     * When the tunnel last reached CONNECTED, ms since 1970 (0/none: never in the kept statistics). Queried at
     * most once a minute — the status endpoint is polled every few seconds and a stale "Last connected" by a
     * minute is unnoticeable.
     */
    @Volatile
    private var lastConnectedQueryAt = 0L

    @Volatile
    private var lastConnectedValue = 0L

    private fun lastConnected(): Long? {
        val now = nowMs()
        if (now - lastConnectedQueryAt < 60_000L) return lastConnectedValue.takeIf { it > 0 }
        lastConnectedQueryAt = now
        // A month back: "last connected" is what the kept statistics remember; older is pruned anyway.
        lastConnectedValue = runBlocking {
            Errors.guard("statistics", 0L, show = false) {
                StatsDb.phases(now - 31 * 86_400_000L, now).lastOrNull { it.phase == Phase.CONNECTED && it.ts <= now }?.ts ?: 0L
            }
        }
        return lastConnectedValue.takeIf { it > 0 }
    }

    /** The [Status.pings] map for one [Repo.pings] value: the transform allocates per server on every call
     *  (every `hello`/`status`), while the source map itself only changes on a new measurement — so it is
     *  keyed by identity. The JSON still carries every ping: cutting that needs a protocol change (a
     *  snapshot the app diffs against), not a cache. */
    private class PingsSnapshot(val source: Map<String, PingRecord>, val out: Map<String, Ping>)

    @Volatile
    private var pingsSnapshot: PingsSnapshot? = null

    private fun pingsOut(): Map<String, Ping> {
        val raw = Repo.pings.value
        return pingsSnapshot?.takeIf { it.source === raw }?.out
            ?: raw.filterValues { it.at > 0 }.mapValues { (_, p) -> Ping(p.ms, p.at) }
                .also { pingsSnapshot = PingsSnapshot(raw, it) }
    }

    /**
     * The device id a limited-network server gets when neither the user nor the link names one — Android's
     * `Limited.defaultDeviceId` ("borderless-" + 6 hex of a hash); the Mac's seed is the install id, it has no
     * ANDROID_ID.
     */
    private val defaultDeviceId: String get() = MacLimited.defaultDeviceId

    // ------------------------------------------------------------------ the app's lists (Shared/Servers.swift)

    @Serializable
    private class StoredServer(
        val id: String,
        val name: String = "",
        val link: String,
        val protocol: String = "",
        val groupId: String = Group.DEFAULT_ID,
        val subscriptionId: String? = null,
        val country: String? = null,
        val countryManual: Boolean = false,
        val disabled: Boolean = false,
        val customName: String = "",
        val limitedStreams: Int = 0,
        val limitedDevice: String = "",
        val limitedIgnoreLimit: Boolean = false,
        /** What the link leaves unprotected, evaluated service-side by [Insecurity.of] (Android's own call —
         *  the app's "not encrypted" mark reads the built outbound, never the raw link). Wire names the app
         *  maps to its `InsecureIssue`: "no_encryption" / "no_cert_check". Written by the service only — a
         *  save's value is ignored (the issues are recomputed), and an older app simply doesn't send it. */
        val insecure: List<String> = emptyList(),
    )

    @Serializable
    private class StoredGroup(val id: String, val name: String? = null)

    @Serializable
    private class StoredSubscription(
        val id: String,
        val name: String = "",
        val url: String,
        val groupId: String = Group.DEFAULT_ID,
        val customName: String? = null,
        val updatedAt: Long? = null,
        val lastError: String? = null,
        val uploadBytes: Long? = null,
        val downloadBytes: Long? = null,
        val totalBytes: Long? = null,
        val expire: Long? = null,
        val providerHours: Int? = null,
        val viaServer: Boolean? = null,
        /** A bundle import's hidden-server marks for this subscription ([recordPendingHidden]; absent = none). */
        val hiddenKeys: List<String>? = null,
    )

    @Serializable
    private class ServerList(
        val groups: List<StoredGroup> = emptyList(),
        val servers: List<StoredServer> = emptyList(),
        val subscriptions: List<StoredSubscription> = emptyList(),
    )

    /** The lists as the app knows them. */
    fun serverList(): String = Errors.guard("the server list", "{}", show = false) {
        json.encodeToString(ServerList.serializer(), ServerList(
        groups = Repo.groups.value.map { StoredGroup(it.id, it.name) },
        servers = Repo.servers.value.map {
            StoredServer(it.id, it.name, it.link, it.protocol, Repo.groupOf(it), it.subscriptionId, it.country, it.countryManual,
                it.disabled, it.customName.orEmpty(), it.limitedStreams, it.limitedDevice, it.limitedIgnoreLimit,
                insecure = Insecurity.of(it).sorted().map { issue ->
                    when (issue) {
                        Insecurity.Issue.NO_ENCRYPTION -> "no_encryption"
                        Insecurity.Issue.NO_CERT_CHECK -> "no_cert_check"
                    }
                })
        },
        subscriptions = Repo.subscriptions.value.map {
            // updatedAt 0 = never downloaded: sent as nil so the app can show "never updated" rather than 1970.
            StoredSubscription(it.id, it.name, it.url, it.groupId ?: Group.DEFAULT_ID, it.customName,
                it.updatedAt.takeIf { u -> u > 0 },
                it.lastError, it.uploadBytes, it.downloadBytes, it.totalBytes, it.expire, it.providerHours, it.viaServer)
        },
    ))
    }

    /**
     * The app's edited lists, merged into what the service keeps (everything the app doesn't know — statistics' ids,
     * device ids, update times — stays); new subscriptions are downloaded at once. The refusal, or null.
     */
    fun saveServerList(text: String): String? = Errors.guard("saving servers", "can't read the list", show = false) {
        if (storageLocked()) return@guard "Stored data is locked; servers and settings are preserved."
        val list = json.decodeFromString(ServerList.serializer(), text)
        if (list.servers.size > 5000 || list.servers.map { it.id }.toSet().size != list.servers.size) return@guard "bad list"
        // Plain http can be changed on the way (servers swapped for someone else's): https only, as the
        // import path's check ([Importer.isSecureUrl]) — the app's save stores URLs verbatim otherwise (S4).
        if (list.subscriptions.any { !Importer.isSecureUrl(it.url) }) return@guard app.borderless.platform.text(S.imp_sub_http)
        val oldServers = Repo.servers.value.associateBy { it.id }
        val oldSubs = Repo.subscriptions.value.associateBy { it.id }
        val oldGroups = Repo.groups.value.associateBy { it.id }
        // A server whose link can't be parsed any more is dropped from the save — counted, so a save that
        // silently shrinks the list is at least visible in the detailed log (the link itself is secret).
        val dropped = ArrayList<String>()
        val servers = list.servers.mapNotNull { s ->
            val base = oldServers[s.id] ?: ServerBuild.build(listOf(s.link), s.subscriptionId, s.groupId).first.firstOrNull()?.copy(id = s.id)
            if (base == null) { dropped += s.id; return@mapNotNull null }
            base.copy(
                name = s.name.ifEmpty { base.name }, link = s.link, groupId = s.groupId, subscriptionId = s.subscriptionId,
                country = if (s.countryManual || base.countryManual || s.country == null) s.country else base.country,
                countryManual = s.countryManual,
                countryByIp = !s.countryManual && !base.countryManual && s.country != null && base.countryByIp,
                countryLookupVersion = if (!s.countryManual && !base.countryManual && s.country != null) base.countryLookupVersion else 0,
                disabled = s.disabled, customName = s.customName.ifEmpty { null },
                limitedStreams = s.limitedStreams, limitedDevice = s.limitedDevice, limitedIgnoreLimit = s.limitedIgnoreLimit,
            )
        }
        if (dropped.isNotEmpty()) {
            AppLog.debug("Repo", "the app's save dropped ${dropped.size} server(s) whose links can't be read " +
                "(${dropped.take(10).joinToString()}${if (dropped.size > 10) ", …" else ""})")
        }
        val subs = list.subscriptions.map { s ->
            (oldSubs[s.id] ?: Subscription(s.id, s.name, s.url, hwid = Repo.deviceIdFor(s.url)))
                .copy(
                    // The provider's title is the service's own data (the app can't edit it — renames are
                    // customName): a save between refetches must not regress it to the app's stale copy.
                    name = oldSubs[s.id]?.name ?: s.name.ifEmpty { s.url },
                    url = s.url, groupId = s.groupId, customName = s.customName,
                )
        }
        // What the app doesn't know (traffic, expiry, update times) stays as the service keeps it.
        val groups = list.groups.map { g ->
            val stored = Repo.group(g.id) ?: Group(g.id)
            // A nameless group's title resolves itself (the first subscription's name — Android's
            // Repo.groupName); the app's rows carry that resolved name, and a save echoing it back
            // would freeze it into a custom name (F-GROUP-0058-1). A name equal to the resolved one —
            // or empty — therefore stays null; the default group is never named.
            val auto = subs.firstOrNull { it.groupId == g.id }?.let { it.customName ?: it.name }
            stored.copy(name = if (g.id == Group.DEFAULT_ID) null else g.name?.takeIf { it.isNotEmpty() && it != auto })
        }
        // A subscription's servers can't leave the list one by one on the app (delete is per-subscription
        // only — "a subscription's would come back with its update"): a server of a surviving subscription
        // that the app's copy lacks was added by a refresh the app hadn't seen yet, so it stays
        // (F-SUBS-0017-1 — the app's snapshot can be up to a poll stale).
        val keepSubIds = subs.mapTo(HashSet()) { it.id }
        val appIds = servers.mapTo(HashSet()) { it.id }
        val merged = servers + oldServers.values.filter { it.subscriptionId != null && it.subscriptionId in keepSubIds && it.id !in appIds }
        // A deleted subscription's automatic group goes with it, as Android's Repo.removeSubscription does:
        // a nameless non-default group the delete left empty (servers and subscriptions all gone) is dropped —
        // the whole-list save is the only delete path and would otherwise keep it forever.
        val nowGroups = groups.toMutableList()
        for (id in oldSubs.keys - keepSubIds) {
            val gid = oldSubs.getValue(id).groupId ?: continue
            if (gid == Group.DEFAULT_ID) continue
            val g = nowGroups.firstOrNull { it.id == gid } ?: continue
            if (g.name != null) continue // renamed by hand: a user group now, kept
            if (merged.none { it.groupId == gid } && subs.none { it.groupId == gid }) nowGroups.remove(g)
        }
        // Bundle imports' hidden-server marks ride the save (StoredSubscription.hiddenKeys): kept root-only
        // (F-SUBS-0039-1) and applied when each subscription's first download makes its servers.
        list.subscriptions.forEach { s -> s.hiddenKeys?.takeIf { it.isNotEmpty() }?.let { recordPendingHidden(s.id, it) } }
        Repo.replaceAll(merged, nowGroups, subs)
        // A changed limited-network option (streams, ignored limit, device id) re-paces a running core at once —
        // Android's per-item save calls LimitedCore.repace; the whole-list save learns it here (F-GROUP-0052-1).
        if (logListChanges(oldServers, oldGroups, oldSubs, merged, nowGroups, subs)) LimitedCore.repace()
        val fresh = subs.filter { it.id !in oldSubs }
        if (fresh.isNotEmpty()) scope.launch { fresh.forEach { Importer.update(it) } }
        // The "stored" reply only after the queued file writes ran — otherwise it could precede disk by
        // the whole write queue, and a crash in between would lose a save the app believes it made.
        runBlocking { Repo.awaitWrites() }
        null
    }

    /**
     * What the app's save changed, logged and announced as Android's `Repo` does on its per-item calls — on the
     * Mac the whole-list save is the only mutation path (there are no per-item calls), so the events Android's
     * UI sends must be sent here: a removed or newly hidden server in use gets [Engine.Event.Disabled], a
     * country set by hand gets [Engine.Event.ServerCountryChanged], and the AppLog lines match `Repo`'s.
     * Deliberate moves ([ev_server_moved]/[ev_sub_moved]) can't be told apart from a group deletion's fallout
     * in a whole-list save, so they aren't logged — as on Android, where a removed group moves quietly.
     * Returns true when limited-network options changed ([LimitedCore.repace] applies them to a running core).
     */
    private fun logListChanges(
        oldServers: Map<String, Server>, oldGroups: Map<String, Group>, oldSubs: Map<String, Subscription>,
        servers: List<Server>, groups: List<Group>, subs: List<Subscription>,
    ): Boolean {
        val e = engine
        val newIds = servers.mapTo(HashSet()) { it.id }
        var countryEdited = false
        var repace = false
        for (id in oldServers.keys - newIds) {
            val gone = oldServers.getValue(id)
            AppLog.event(LText.of(S.ev_server_removed, Repo.realName(gone), gone.protocol), AppLog.Kind.WARN)
            // Removing the server in use: the engine moves to another one (Android's UI sends the same).
            e?.send(Engine.Event.Disabled(id))
        }
        val added = servers.filter { it.id !in oldServers }
        if (added.isNotEmpty()) {
            AppLog.debug("Repo", "added ${added.size} server(s): " +
                added.take(10).joinToString { "${it.protocol} · ${it.name}" } + if (added.size > 10) " …" else "")
        }
        for (after in servers) {
            val before = oldServers[after.id] ?: continue
            if (before.disabled != after.disabled) {
                AppLog.event(LText.of(if (after.disabled) S.ev_server_hidden else S.ev_server_shown, Repo.realName(after)))
                if (after.disabled) e?.send(Engine.Event.Disabled(after.id))
            }
            if (before.country != after.country || before.countryManual != after.countryManual) {
                val label = if (after.country == null) LText.of(S.country_auto) else LText.raw(Countries.name(after.country))
                AppLog.event(LText.of(S.ev_server_country, Repo.realName(after), label))
                countryEdited = true
            }
            val was = Repo.realName(before)
            val now = Repo.realName(after)
            if (was != now) AppLog.event(LText.of(S.ev_server_renamed, was, now))
            if (before.limitedStreams != after.limitedStreams || before.limitedIgnoreLimit != after.limitedIgnoreLimit) {
                repace = true
                AppLog.event(LText.concat(
                    LText.of(S.ev_server_power, Repo.realName(after),
                        if (after.limitedStreams == 0) LText.of(S.limited_auto) else LText.of(S.limited_n_streams, after.limitedStreams)),
                    if (after.limitedIgnoreLimit) LText.of(S.ev_server_power_ignore) else "",
                ))
            }
            if (before.limitedDevice != after.limitedDevice) {
                repace = true
                // The id itself is a credential-like value: only whether it is set, not what it is (as Android's).
                AppLog.debug("Repo", "server power: ${Repo.realName(after)}, device id ${if (after.limitedDevice.isEmpty()) "cleared" else "set"}")
            }
        }
        if (countryEdited) e?.send(Engine.Event.ServerCountryChanged)

        val newSubIds = subs.mapTo(HashSet()) { it.id }
        for (id in oldSubs.keys - newSubIds) {
            AppLog.event(LText.of(S.ev_sub_removed, oldSubs.getValue(id).let { it.customName ?: it.name }), AppLog.Kind.WARN)
        }
        for (after in subs) {
            val before = oldSubs[after.id]
            if (before == null) {
                AppLog.event(LText.of(S.ev_sub_added, after.customName ?: after.name))
                continue
            }
            val was = before.customName ?: before.name
            val now = after.customName ?: after.name
            if (was != now) AppLog.event(LText.of(S.ev_sub_renamed, was, now))
        }

        // A group's display name as Repo.groupName resolves it: its own name, its first subscription's, "Default".
        fun gname(g: Group?, of: List<Subscription>): String = when {
            g == null || g.id == Group.DEFAULT_ID -> text(S.own_group)
            else -> g.name ?: of.firstOrNull { it.groupId == g.id }?.let { it.customName ?: it.name } ?: text(S.own_group)
        }
        val newGroupIds = groups.mapTo(HashSet()) { it.id }
        for (id in oldGroups.keys - newGroupIds) {
            if (id == Group.DEFAULT_ID) continue
            AppLog.event(LText.of(S.ev_group_removed, gname(oldGroups[id], oldSubs.values.toList()), text(S.own_group)), AppLog.Kind.WARN)
        }
        for (after in groups) {
            val before = oldGroups[after.id]
            if (before == null) {
                AppLog.event(LText.of(S.ev_group_added, gname(after, subs)))
                continue
            }
            val was = gname(before, oldSubs.values.toList())
            val now = gname(after, subs)
            if (was != now) AppLog.event(LText.of(S.ev_group_renamed, was, now))
        }
        return repace
    }

    // ------------------------------------------------- bundle imports' hidden servers (F-SUBS-0039-1)

    /**
     * The keys ([ShareFormat] link keys) of servers a bundle import hid, by subscription id. They arrive with
     * the app's save (`StoredSubscription.hiddenKeys`) before the subscription's download makes the servers —
     * Android keeps them in `pendingHidden` until then, applying them through [Repo.hideByKeys]. The app's own
     * copy dies with it; here they live in `repo/pending-hidden.json` through the same sealed store as the
     * repo's files (R1 — the keys are full share links, i.e. credentials): a file written before the seal
     * reads through legacy key recovery and is migrated to plain JSON with its encrypted original preserved.
     */
    @Serializable
    private class PendingHiddenFile(val bySub: Map<String, List<String>> = emptyMap())

    private const val PENDING_HIDDEN_FILE = "pending-hidden.json"
    private val pendingHidden = HashMap<String, Set<String>>()
    private val pendingHiddenLock = Lock()

    private fun loadPendingHidden() {
        val stored = runCatching {
            // MacStore.read returns null for "no file" and for "sealed and the key is away" — both mean
            // "nothing to load this run", the sealed file kept as it is for the next start.
            repoStore.read(PENDING_HIDDEN_FILE)?.let { json.decodeFromString(PendingHiddenFile.serializer(), it.decodeToString()) }
        }.getOrElse {
            // Damaged or another key's work: kept aside like the store's own rule, so a fresh write can
            // replace it while the broken copy stays recoverable by hand.
            runCatching { repoStore.keepAside(PENDING_HIDDEN_FILE) }
            AppLog.debug("Repo", "$PENDING_HIDDEN_FILE can't be read (${it.message}); kept aside")
            return
        } ?: return
        pendingHiddenLock.withLock { pendingHidden.putAll(stored.bySub.mapValues { it.value.toSet() }) }
    }

    private fun writePendingHidden() {
        val snap = pendingHiddenLock.withLock { pendingHidden.mapValues { it.value.sorted() } }
        // Plain JSON, .tmp + fsync + verified rename; unreadable legacy originals remain protected.
        runCatching { repoStore.write(PENDING_HIDDEN_FILE, json.encodeToString(PendingHiddenFile.serializer(), PendingHiddenFile(snap)).encodeToByteArray()) }
            .onFailure { AppLog.debug("Repo", "can't write $PENDING_HIDDEN_FILE: ${it.message}") }
    }

    /**
     * The marks a save carried ([StoredSubscription.hiddenKeys]), recorded for when the subscription's servers
     * exist. Marks for a subscription that already downloaded are dropped: re-applying them would re-hide
     * servers the user showed again by hand (the app's own map is one-shot, this is its stale echo).
     */
    private fun recordPendingHidden(subId: String, keys: Collection<String>) {
        if (keys.isEmpty()) return
        if ((Repo.subscription(subId)?.updatedAt ?: 0) > 0) return
        pendingHiddenLock.withLock { pendingHidden[subId] = keys.toSet() }
        writePendingHidden()
    }

    // ------------------------------------------------------------------ the app's settings (Swift `SettingsState`)

    /**
     * The fields the app may not write itself: the battery saving mode (its switch goes through [Eco], which logs and
     * keeps the strategy bookkeeping), the countries ([CountryFilter] remembers what was in effect), the server picked
     * by hand and the engine's own bookkeeping. Everything else it may write ([AppSettings.palette] included):
     * the service just stores it (a locked real field refuses every later patch — settingsSent never advances).
     */
    private val lockedSettings = setOf(
        "ecoOn", "countries", "manualServerId", "lastServerId", "strategyBeforeEco", "strategyBeforeCountry",
        "serverBeforeCountry", "saverSeen", "ecoBySaver", "verboseSince", "deviceId", "limitedDevicesFrozen",
    )

    /** The settings as they are now ([AppSettings], every field written). */
    fun settings(): String = Errors.guard("the settings", "{}", show = false) {
        json.encodeToString(AppSettings.serializer(), Repo.settings.value)
    }

    /**
     * The app's edited fields, merged into what the service keeps (only the keys present are changed). Android's rules
     * for the two special ones: a new default strategy becomes the one in effect unless Manual is, and the detailed log
     * remembers when it was turned on (it turns itself off a day later).
     */
    fun saveSettings(patchText: String): String? = Errors.guard("saving settings", "can't read the settings", show = false) {
        if (storageLocked()) return@guard "Stored data is locked; servers and settings are preserved."
        val patch = json.parseToJsonElement(patchText).jsonObject
        lockedSettings.intersect(patch.keys).sorted().takeIf { it.isNotEmpty() }?.let { return@guard "locked: ${it.joinToString()}" }
        val before = Repo.settings.value
        // The merge runs inside the settings' own update, so the engine's writes of the same moment (lastServerId,
        // saverSeen) don't fall between the read and the write (found 2026-11-02).
        val after = json.decodeFromJsonElement(AppSettings.serializer(), JsonObject(
            json.encodeToJsonElement(AppSettings.serializer(), Repo.settings.value).jsonObject + patch))
        Repo.updateSettings { s ->
            var merged = JsonObject(json.encodeToJsonElement(AppSettings.serializer(), s).jsonObject + patch)
            if ("defaultStrategy" in patch && merged["strategy"]!!.jsonPrimitive.content != Strategy.MANUAL.name) {
                merged = json.encodeToJsonElement(AppSettings.serializer(),
                    EcoMath.startStrategy(json.decodeFromJsonElement(AppSettings.serializer(), merged))).jsonObject
            }
            if ("verboseLog" in patch) {
                val on = merged["verboseLog"]!!.jsonPrimitive.boolean
                merged = JsonObject(merged + ("verboseSince" to kotlinx.serialization.json.JsonPrimitive(
                    when { on && !s.verboseLog -> nowMs(); on -> s.verboseSince; else -> 0L })))
            }
            json.decodeFromJsonElement(AppSettings.serializer(), merged)
        }
        // The pool decides which servers are shown and checked; a switch asks it to take part at once.
        if (after.autoActive && !before.autoActive) AutoPool.refresh()
        after.statsDays.takeIf { it != before.statsDays }?.let { Errors.guard("statistics", Unit, show = false) { StatsDb.prune(it) } }
        runBlocking { Repo.awaitWrites() } // the "stored" reply only after the settings file is written
        null
    }

    /** The main screen's battery saving switch (the whole bookkeeping is [Eco]'s). */
    fun setEco(on: Boolean) = Errors.guard("the battery saving mode", Unit, show = false) { Eco.set(on) }

    /** The Mac's Low Power Mode may have changed (checked now and then by the service): follow it as the settings say. */
    fun saverChanged() = Errors.guard("the power saver", Unit, show = false) { Eco.saverChanged() }

    /** The countries button (the map's picker): [CountryFilter] keeps the strategy it stood in for. */
    fun setCountries(codes: List<String>) = Errors.guard("the countries", Unit, show = false) { CountryFilter.set(codes.toSet()) }

    // ------------------------------------------------------------------ the user's zones (Swift `CustomZone`)

    @Serializable
    private class ZoneOut(
        val id: String, val name: String, val on: Boolean, val direct: List<String> = emptyList(),
        val server: List<String> = emptyList(),
        /** As [CustomZone] keeps them: the app's packages outside the tunnel and the `.dat` categories' sizes. */
        val apps: List<String> = emptyList(), val listSizes: Map<String, Int> = emptyMap(),
    )

    @Serializable
    private class ZoneIn(val id: String? = null, val name: String, val on: Boolean = false,
                         val direct: List<String> = emptyList(), val server: List<String> = emptyList(),
                         val apps: List<String> = emptyList(), val listSizes: Map<String, Int> = emptyMap())

    fun zones(): String = Errors.guard("the zones", "[]", show = false) {
        json.encodeToString(
            ListSerializer(ZoneOut.serializer()),
            Repo.zones.value.map {
                ZoneOut(it.id, it.name, it.id in Repo.settings.value.regions, it.direct, it.server, it.apps, it.listSizes)
            },
        )
    }

    /** Adds or replaces a zone, switched on as [ZoneIn.on] says; bounded like the file path (`ZoneFile.MAX_ENTRIES`). */
    fun saveZone(zoneJson: String): String? = Errors.guard("saving the zone", "can't read the zone", show = false) {
        if (storageLocked()) return@guard "Stored data is locked; servers and settings are preserved."
        if (zoneJson.length > 2_000_000) return@guard "the zone is too big"
        val parsed = json.decodeFromString(ZoneIn.serializer(), zoneJson)
        if (parsed.name.length > 200) return@guard "the name is too long"
        // Android's ZoneFile leaves every entry past MAX_ENTRIES out rather than refusing the save
        // (F-ROUTING-0029-1) — the import screen shows a "truncated" notice on its side.
        val z = ZoneIn(parsed.id, parsed.name, parsed.on,
            direct = parsed.direct.take(CustomZoneListLimit),
            server = parsed.server.take(CustomZoneListLimit),
            apps = parsed.apps.take(CustomZoneListLimit),
            listSizes = parsed.listSizes)
        val entry = { list: List<String> -> list.all { it.length <= 512 } }
        if (!entry(z.direct) || !entry(z.server) || !entry(z.apps)) return@guard "an entry is too long"
        val id = z.id?.takeIf { it.startsWith(CustomZone.PREFIX) } ?: CustomZone.newId()
        // The app can't edit apps yet, but its save must not drop what an Android-made zone carries.
        Repo.saveZone(
            CustomZone(id = id, name = z.name, direct = z.direct, server = z.server, apps = z.apps, listSizes = z.listSizes),
            enable = z.on,
        )
        // Repo.saveZone's `enable` only ever adds the zone to the enabled set (a fresh zone starts off):
        // a save that switches an existing zone OFF removes it too — Android's toggle is setZoneEnabled
        // (F-ROUTING-0034-1); otherwise the zone kept routing and zones() reported it on.
        if (!z.on) Repo.setZoneEnabled(id, false)
        runBlocking { Repo.awaitWrites() } // the "stored" reply only after zones.json/settings.json are written
        null
    }

    fun deleteZone(id: String) = Errors.guard("deleting the zone", Unit, show = false) {
        if (id.startsWith(CustomZone.PREFIX)) {
            Repo.deleteZone(id)
            runBlocking { Repo.awaitWrites() } // as saveZone: the reply only once it is on disk
        }
    }

    // ------------------------------------------------------------------ the log screen

    @Serializable
    private class EventOut(val time: Long, val kind: String, val text: String)

    /** The events as the service stores them (language-neutral); the app renders the texts in its language. */
    fun events(): String = Errors.guard("the events", "[]", show = false) {
        json.encodeToString(
            ListSerializer(EventOut.serializer()),
            AppLog.events.value.map { EventOut(it.time, it.kind.name, it.text.toJson().toString()) },
        )
    }

    fun storageLocked(): Boolean = !::repoStore.isInitialized || repoStore.locked

    fun errorReports(): String = Errors.export()

    fun debugLog(): String = Errors.guard("the detailed log", "[]", show = false) {
        json.encodeToString(
            ListSerializer(kotlinx.serialization.serializer<String>()),
            AppLog.debugLines.value,
        )
    }

    fun clearLog() = Errors.guard("clearing the log", Unit, show = false) {
        AppLog.clear()
        // The delete is queued on the logs' scope: answer "cleared" only once it ran, not ahead of it.
        runBlocking { AppLog.awaitCleared() }
    }

    // ------------------------------------------------------------------ statistics (the screens read the service's DB)

    @Serializable
    private class StatsOut(
        val from: Long,
        val to: Long,
        val dataStart: Long,
        val phases: List<SpanOut> = emptyList(),
        val scans: List<ScanOut> = emptyList(),
        /** The best of all servers at each moment ([StatsDb.buckets] with no server). */
        val best: List<PointOut> = emptyList(),
        /** Per-server series for the ones the screen shows (the current one and the best ones). */
        val pings: Map<String, List<PointOut>> = emptyMap(),
        val servers: List<ServerStatOut> = emptyList(),
        val trafficProxy: Long = 0,
        val trafficDirect: Long = 0,
        /** Per-server traffic for the period (Android `StatsDb.trafficByServer`): the subscription tile sums it. */
        val trafficByServer: Map<String, Long> = emptyMap(),
        val losses: Int = 0,
        val failover: Int = 0,
        val faster: Int = 0,
        val manual: Int = 0,
        val foundPlace: Double? = null,
        val foundOf: Int? = null,
        val foundCount: Int = 0,
        val healthMedian: Int? = null,
        val healthP95: Int? = null,
        val healthJitter: Int? = null,
        /** The target's states over time ("in use / answering / no reply"); the overview needs none. */
        val states: List<StateOut> = emptyList(),
        /** Samples per band of [StatsMath.histogram] for the target's servers. */
        val histograms: Map<String, List<Int>> = emptyMap(),
        /** What the target subscription's provider reported at each update. */
        val usage: List<UsageOut> = emptyList(),
        /** The detail screen's tile figures over the raw samples (Android `StatsMath.summarize`), so the app
         *  doesn't compute them from the ≤ 96 chart buckets (F-STATS-0028-1); only for "s:" targets. */
        val answers: Int? = null,
        val checks: Int? = null,
        val availability: Double? = null,
        val median: Int? = null,
        val p95: Int? = null,
        val min: Int? = null,
        val max: Int? = null,
        val jitter: Int? = null,
    )

    @Serializable
    private class SpanOut(val start: Long, val end: Long, val phase: String, val server: String? = null)

    @Serializable
    private class ScanOut(val t: Long, val total: Int, val answered: Int)

    @Serializable
    private class PointOut(val t: Long, val ms: Int?)

    @Serializable
    private class ServerStatOut(
        val id: String, val availability: Double?, val avg: Int?, val samples: Int, val usage: Long, val chosen: Int,
    )

    @Serializable
    private class StateOut(val start: Long, val end: Long, val state: String)

    @Serializable
    private class UsageOut(val t: Long, val used: Long? = null, val total: Long? = null, val expire: Long? = null)

    /**
     * What the statistics screens draw over [from]…[to] (Android's loaders in `StatsScreen.kt`). [target] names one
     * server ("s:<id>") or one subscription ("sub:<id>") for the detail screens: its pings, states, histograms and the
     * provider's reports; null = the overview (pings of the current and the best servers only).
     */
    // runBlocking on the socket worker: bounded like turnOff's (one of ≤8 client slots; the queries are
    // local SQLite reads with a busy timeout, not network waits).
    fun stats(from: Long, to: Long, target: String?): String = runBlocking {
        Errors.guard("statistics", "{}", show = false) { statsOut(from, to, target) }
    }

    private suspend fun statsOut(from: Long, to: Long, target: String?): String {
        StatsDb.flush()
        if (target == "countries") return countryStats()
        // The activity tab's own target (ENERGY-IMPL): kept out of the overview's reply, asked for
        // only while the tab is on.
        if (target == "activity") return MacActivity.out(from, to)
        val segments = StatsMath.segments(StatsDb.phases(from, to), from, to, to)
        val events = StatsDb.events(from, to)
        val aggregates = StatsDb.aggregates(from, to)
        val traffic = StatsDb.traffic(from, to)
        val health = StatsMath.summarize(StatsDb.samplesLimited(from, to, null, StatsDb.Kind.HEALTH))
        val usageByServer = StatsMath.usage(segments)

        // The pings the screen shows: on the overview the current and the best five (Android `topServers`), on a
        // detail screen the target's.
        val targetIds: List<String> = when {
            target == null -> {
                val current = segments.lastOrNull { it.phase == Phase.CONNECTED }?.server
                val ok = aggregates.entries.filter { it.value.ok > 0 }
                // The app picks its lineup by availability while the service picked by score — ship both
                // top-5s so neither choice draws an empty series (F-STATS-0020-1; still a bounded ≤ 11).
                val byScore = ok.sortedByDescending { StatsMath.score(it.value.weight, it.value.answered, it.value.avg?.toDouble()) }
                    .map { it.key }.take(5)
                val byAvailability = ok.sortedByDescending { it.value.availability ?: 0.0 }.map { it.key }.take(5)
                (listOfNotNull(current?.takeIf { it in aggregates }) + byScore + byAvailability).distinct()
            }
            target.startsWith("s:") -> listOf(target.removePrefix("s:"))
            else -> Repo.servers.value.filter { it.subscriptionId == target.removePrefix("sub:") }.map { it.id }
        }
        val pings = targetIds.associateWith { id ->
            StatsDb.buckets(from, to, BUCKETS, id).map { PointOut(it.t, it.ms) }
        }
        // One query for all the target's servers (a subscription's was two per server over the whole range), then
        // each server's samples into its own histogram.
        val histogramSamples = if (targetIds.isEmpty()) emptyList() else StatsDb.samplesLimited(from, to, targetIds, null)
        val histograms = histogramSamples.groupBy(Sample::server).mapValues { (_, rows) -> StatsMath.histogram(rows) }
        // The one-server detail's tile figures over its raw samples, as Android's summarize — never from the
        // chart's ≤ 96 averaged buckets.
        val targetSummary = if (target?.startsWith("s:") == true) StatsMath.summarize(histogramSamples) else null
        val states = if (targetIds.isEmpty()) emptyList() else
            StatsMath.serverStates(segments, targetIds.toSet(), StatsDb.answerSlices(from, to, targetIds), from, to)
                .map { StateOut(it.start, it.end, it.state.name) }
        val found = events.filter { it.pos != null && it.type in FOUND_EVENTS }

        return json.encodeToString(StatsOut.serializer(), StatsOut(
            from = from,
            to = to,
            dataStart = StatsDb.firstTimestamp() ?: to,
            phases = segments.map { SpanOut(it.start, it.end, it.phase.name, it.server) },
            scans = StatsDb.scans(from, to).map { ScanOut(it.ts, it.total, it.available) },
            best = StatsDb.buckets(from, to, BUCKETS, null).map { PointOut(it.t, it.ms) },
            pings = pings,
            servers = aggregates.map { (id, a) ->
                ServerStatOut(id, a.availability, a.avg, a.count, usageByServer[id] ?: 0,
                    events.count { it.server == id && it.type != StatsDb.Event.LOST })
            },
            trafficProxy = traffic.proxy,
            trafficDirect = traffic.direct,
            trafficByServer = StatsDb.trafficByServer(from, to),
            losses = events.count { it.type == StatsDb.Event.LOST },
            failover = events.count { it.type == StatsDb.Event.FAILOVER },
            faster = events.count { it.type == StatsDb.Event.OPTIMIZE || it.type == StatsDb.Event.BEST },
            manual = events.count { it.type == StatsDb.Event.MANUAL },
            foundPlace = found.map { it.pos!! }.average().takeIf { found.isNotEmpty() },
            foundOf = found.map { it.posOf ?: 0 }.average().toInt().takeIf { found.isNotEmpty() },
            foundCount = found.size,
            healthMedian = health.median,
            healthP95 = health.p95,
            healthJitter = health.jitter,
            states = states,
            histograms = histograms,
            usage = if (target?.startsWith("sub:") == true) {
                StatsDb.subUsage(target.removePrefix("sub:"), from, to).map { UsageOut(it.ts, it.used, it.total, it.expire) }
            } else emptyList(),
            answers = targetSummary?.ok,
            checks = targetSummary?.count,
            availability = targetSummary?.availability,
            median = targetSummary?.median,
            p95 = targetSummary?.p95,
            min = targetSummary?.min,
            max = targetSummary?.max,
            jitter = targetSummary?.jitter,
        ))
    }

    /**
     * `stats` target "countries" (the wire contract): `{ "<cc>": {chosen, answered, checked} }` — the metrics
     * behind Android's "used" and "stable" country sorts, so the app doesn't rank on stand-ins
     * (F-MAIN-0063-1). `chosen` = how often each country joined the chosen set, all time (the settings log's
     * "country" values, Android `CountryStats.timesChosen`); `answered`/`checked` = the week's time-weighted
     * answering slices vs all slices of the country's servers (the sums `CountryStats.stability` smooths into
     * its answer rate — same 7-day window it uses).
     */
    private suspend fun countryStats(): String {
        val now = nowMs()
        val chosen = CountryStats.timesChosen(StatsDb.settingsHistory("country", 0, now).map { it.second })
        val aggregates = StatsDb.aggregates(now - COUNTRY_STATS_MS, now)
        val perCountry = HashMap<String, DoubleArray>()
        for (s in Repo.servers.value) {
            val a = aggregates[s.id] ?: continue
            val e = perCountry.getOrPut(Countries.of(s)) { DoubleArray(2) }
            e[0] += a.answered
            e[1] += a.weight
        }
        return buildJsonObject {
            (perCountry.keys + chosen.keys).sorted().forEach { code ->
                putJsonObject(code) {
                    put("chosen", chosen[code] ?: 0)
                    put("answered", (perCountry[code]?.get(0) ?: 0.0).roundToInt())
                    put("checked", (perCountry[code]?.get(1) ?: 0.0).roundToInt())
                }
            }
        }.toString()
    }

    /** The window the country usage/stability metrics cover (Android's 7-day aggregate window). */
    private const val COUNTRY_STATS_MS = 7 * 86_400_000L

    // ------------------------------------------------------------------ one-off actions of the settings screen

    /**
     * "update_subs" / "update_sub:<id>" / "update_geo" / "clear_stats" / "error_dismiss:<id>" / "scan_open" /
     * "probe_one:<id>" / "imported:<…>" / "update_check" / "update_install" / "update_dismiss": started here,
     * the answer says it began. The refusal, or null.
     *
     * The answers come back at once — the socket's per-connection timeouts (seconds) are far shorter than a
     * download's; [Status.subsUpdating] carries the busy state the immediate answer can't.
     */
    fun action(kind: String): String? = Errors.guard("the action", "the action failed", show = false) {
        when {
            kind == "update_subs" -> {
                scope.launch {
                    subUpdates.incrementAndFetch()
                    try { Errors.guard("updating subscriptions", Unit) { Importer.updateAll() } }
                    finally { subUpdates.decrementAndFetch() }
                }
                null
            }
            kind.startsWith("update_sub:") -> {
                val sub = Repo.subscriptions.value.firstOrNull { it.id == kind.removePrefix("update_sub:") }
                    ?: return@guard "no such subscription"
                scope.launch {
                    subUpdates.incrementAndFetch()
                    try {
                        Errors.guard("updating a subscription", Unit) {
                            Importer.update(sub)
                            // New unnamed servers get their country looked up at once — what Android's
                            // manual refresh does after every update (GroupsScreen), not only the
                            // scheduled paths (F-SUBS-0030-2).
                            Importer.resolveCountries()
                        }
                    } finally { subUpdates.decrementAndFetch() }
                }
                null
            }
            kind == "update_geo" -> {
                scope.launch { GeoLists.updateIfDue(force = true) }
                null
            }
            kind == "clear_stats" -> {
                Errors.guard("statistics", Unit, show = false) { StatsDb.clear() }
                null
            }
            kind == "scan_open" -> {
                // The app's window came up ([scanOpen] rescans only what is stale by the engine's schedule).
                scanOpen()
                null
            }
            kind.startsWith("probe_one:") -> {
                // A single server's check (Android's ServerSheet → Scanner.probeOne) — the app's "Test ping".
                val srv = Repo.server(kind.removePrefix("probe_one:")) ?: return@guard "no such server"
                scope.launch { Errors.guard("checking a server", Unit) { Scanner.probeOne(srv) } }
                null
            }
            kind == "update_check" || kind == "update_install" || kind == "update_dismiss" -> {
                // The wave-2 updater ([updateHooks]); until it exists the actions are answered honestly.
                val hooks = updateHooks ?: return@guard "not implemented"
                Errors.guard("the update action", Unit, show = false) {
                    when (kind) {
                        "update_check" -> hooks.check(true)
                        "update_install" -> hooks.install()
                        else -> hooks.dismiss()
                    }
                }
                null
            }
            kind.startsWith("error_dismiss:") -> {
                // A queued error dialog the app showed and the user closed ([Status.pendingErrors]).
                val id = kind.removePrefix("error_dismiss:").toLongOrNull() ?: return@guard "bad error id"
                Errors.dismiss(id)
                null
            }
            kind.startsWith("imported:") -> {
                // The app's import finished: the summary event Android's Importer writes ("Servers added: N /
                // subscriptions: M / not downloaded yet: P"). A whole-list save can't tell an import from a
                // manual add, so the app reports the counts itself — numbers only: problem details would sit in
                // the daemon's own service.log (the action is logged there) and stay in the dialog instead.
                val p = kind.removePrefix("imported:").split(':').map { it.toIntOrNull() ?: 0 }
                val result = Importer.Result(p.getOrElse(0) { 0 }, p.getOrElse(1) { 0 }, emptyList(), p.getOrElse(2) { 0 })
                AppLog.event(if (result.pending > 0)
                    LText.concat(result.text, " (", LText.of(S.import_pending, result.pending), ")") else result.text)
                p.getOrElse(3) { 0 }.takeIf { it > 0 }
                    ?.let { AppLog.debug("import", "$it item(s) failed the import; the app showed the details") }
                null
            }
            else -> "unknown action"
        }
    }

    /** Every statistics table as CSV (Android's `StatsExport`, one JSON {table: csv}); the biggest ones capped to
     *  their newest rows, so the answer stays well under the protocol's 16 MB line and the memory stays bounded.
     *  Like Android it also carries the settings info, the server list anonymized to ids/groups and the kept
     *  failures — and exporting marks them reported. */
    fun exportStats(): String = Errors.guard("statistics", "{}", show = false) {
        runBlocking { // bounded as in turnOff: one client slot, local reads only
            StatsDb.flush()
            val exportedAt = nowMs()
            val out = buildJsonObject {
                val s = Repo.settings.value
                put("info", buildString {
                    appendLine("Border(less) for Mac $version")
                    appendLine("macOS ${NSProcessInfo.processInfo.operatingSystemVersionString} (${Host.deviceModel})")
                    appendLine("Exported: ${app.borderless.platform.dateTime(exportedAt)}")
                    appendLine()
                    appendLine("strategy=${s.strategy} default=${s.defaultStrategy} autoActive=${s.autoActive} " +
                        "countries=${s.countries.sorted().joinToString(",").ifEmpty { "any" }} " +
                        "routing=${s.routingEnabled}/${s.routingMode} adFilter=${s.adFilter}")
                    append("healthIntervalSec=${s.healthIntervalSec} latencyCheckMin=${s.latencyCheckMin} " +
                        "rescanMin=${s.rescanIntervalMin} pauseScansScreenOff=${s.pauseScansScreenOff} " +
                        "statsDays=${s.statsDays}")
                })
                // Android's servers.csv: ids only (no names, hosts or links — the file is meant for sending).
                put("servers", buildString {
                    appendLine("id,group,protocol,country,active,auto_pool")
                    val pool = Repo.auto.value.pool
                    Repo.servers.value.forEach { srv ->
                        val group = Repo.groupName(Repo.group(Repo.groupOf(srv))).replace(",", " ")
                        appendLine("${srv.id},$group,${srv.protocol.replace(",", " ")},${srv.country.orEmpty()},${Repo.isActive(srv)},${srv.id in pool}")
                    }
                })
                StatsDb.TABLES.forEach { table ->
                    val csv = StringBuilder()
                    // dumpCsv's lines end in "\n" already (as Android's StatsExport writes them verbatim).
                    runCatching { StatsDb.dumpCsv(table, newest = 60_000) { line -> csv.append(line) } }
                    put(table, csv.toString())
                }
                put("errors", Errors.export())
            }
            // What an export carried counts as reported (Android `Errors.markReported`): the settings' counter starts again.
            Errors.markReported(exportedAt)
            out.toString()
        }
    }

    private const val BUCKETS = 96
    private const val CustomZoneListLimit = 50_000
    private val FOUND_EVENTS = setOf(StatsDb.Event.INITIAL, StatsDb.Event.FAILOVER, StatsDb.Event.RETRY)
}

/**
 * The service's own texts, English only: the app's `Shared.strings` is generated into the app bundle, which a
 * root-only daemon never sees (finding A8 — `MacBridge.setUp`'s `text` stays nil for the service). Log events
 * are language-neutral [LText]s rendered by the app; what needs a table here is what the service resolves to a
 * plain string itself — a subscription's `lastError`, [Errors.describe] results, [Repo]'s file failures, the
 * debug lines' event/announce texts and the units inside them. The English of
 * `android/src/main/res/values/strings.xml` (the one source of texts) for every key the shared code can
 * resolve in the service; a key not found falls back to the key itself, as the default [Host.text] does.
 */
private object ServiceTexts {
    fun of(name: String, args: List<Any>): String = EN[name]?.let { format(it, args) } ?: name

    /**
     * Android's placeholders in these texts: `%s`, `%d` and `%.Nf` take the next argument, `%N$…` the N-th
     * (1-based), `%%` is a literal percent. The arguments arrive already rendered (counts as "5", decimals
     * as "0.4"); `%.Nf` rounds to the asked digits, the rest is verbatim.
     */
    private fun format(pattern: String, args: List<Any>): String {
        val out = StringBuilder()
        var next = 0
        var i = 0
        while (i < pattern.length) {
            if (pattern[i] != '%' || i + 1 >= pattern.length) { out.append(pattern[i]); i++; continue }
            var j = i + 1
            var positional = -1
            val at = j
            while (j < pattern.length && pattern[j].isDigit()) j++
            if (j < pattern.length && pattern[j] == '$' && j > at) {
                positional = pattern.substring(at, j).toInt() - 1
                j++
            }
            var precision = -1
            if (j < pattern.length && pattern[j] == '.') {
                val p = ++j
                while (j < pattern.length && pattern[j].isDigit()) j++
                precision = pattern.substring(p, j).toIntOrNull() ?: -1
            }
            if (j >= pattern.length) { out.append(pattern, i, j); break }
            when (pattern[j]) {
                '%' -> out.append('%')
                's', 'd', 'f' -> {
                    val arg = args.getOrNull(if (positional >= 0) positional else next++)
                    out.append(
                        when {
                            arg == null -> ""
                            pattern[j] == 'f' && precision >= 0 ->
                                (arg as? Double ?: arg.toString().toDoubleOrNull())?.let { fixed(it, precision) } ?: arg.toString()
                            else -> arg.toString()
                        },
                    )
                }
                else -> out.append(pattern, i, j + 1) // not a placeholder: the whole span goes out as it is
            }
            i = j + 1
        }
        return out.toString()
    }

    private val EN = mapOf(
        "act_core_base" to "Core: processing your traffic",
        "act_core_start" to "Core start",
        "act_current_meter" to "Current measurement (diagnostics)",
        "act_db_commit" to "Statistics write",
        "act_file_write" to "Data file save",
        "act_j" to "%s J",
        "act_kj" to "%s kJ",
        "act_limited" to "Limited-network server",
        "act_log_write" to "Log write",
        "act_mj" to "%d mJ",
        "act_net_check" to "Internet check",
        "act_ping_failed" to "Recheck after a failure",
        "act_ping_periodic" to "Scheduled ping",
        "act_ping_ride" to "Ping along with traffic",
        "act_ping_stall" to "Ping: traffic looked stuck",
        "act_ping_verify" to "Check after a switch",
        "act_ping_wake" to "Check when the screen turns on",
        "act_probe" to "Server check",
        "act_probe_explore" to "Check of a hidden server",
        "act_probe_rescue" to "Emergency check of a hidden server",
        "act_rescan_skipped" to "Rescan skipped (saved)",
        "act_scan_background" to "Background scan",
        "act_scan_engine" to "Search for a server",
        "act_scan_manual" to "Scan by you",
        "act_scan_new" to "Check of new servers",
        "act_scan_open" to "Scan on opening the app",
        "act_scan_strategy" to "Scan after a strategy change",
        "act_seconds" to "%d s",
        "act_seconds_f" to "%s s",
        "act_stats_sample" to "Statistics: power sample",
        "act_sub_update" to "Subscription update",
        "act_ui_base" to "App screens (while open)",
        "ann_back" to "Servers reachable again, connected to",
        "ann_best" to "Fastest server:",
        "ann_cheaper" to "More economical protocol (%1${"$"}s → %2${"$"}s), switched to",
        "ann_connected" to "Connected to",
        "ann_country" to "Country changed, now:",
        "ann_faster" to "Found a faster server (%1${"$"}d → %2${"$"}d ms), switched to",
        "ann_limited" to "On a limited-network server:",
        "ann_limited_off" to "Back to an ordinary server:",
        "ann_manual" to "Picked manually:",
        "ann_steadier" to "Moved to a steadier kind of connection (%1${"$"}d → %2${"$"}d ms), switched to",
        "ann_switched" to "Switched to",
        "bytes_gb" to "%.1f GB",
        "bytes_kb" to "%.0f KB",
        "bytes_mb" to "%.0f MB",
        "bytes_tb" to "%.1f TB",
        "count_millions" to "%s M",
        "country_auto" to "automatic",
        "dur_d" to "%d d",
        "dur_dh" to "%1${"$"}d d %2${"$"}d h",
        "dur_h_short" to "%d h",
        "dur_hm" to "%1${"$"}d h %2${"$"}d min",
        "dur_m" to "%d min",
        "dur_ms" to "%1${"$"}d min %2${"$"}d s",
        // The Mac's wrap is a non-exportable Keychain key sealing the data key (MacCrypto) — Android's
        // Keystore wording adapted (strings.xml:433-434), not the raw key names left to surface.
        "err_crypto_locked" to "The Mac's secure storage (the Keychain) isn't answering, so the service's saved servers and settings can't be opened right now. Nothing is changed or lost, but changes made now won't be kept. Restart the app or the Mac to try again.",
        "err_crypto" to "Secure storage (the Keychain) is unavailable, so data is kept unencrypted on this Mac.",
        "err_crypto_wrap" to "The Mac's Keychain isn't answering, so the service's stored data is sealed with a key bound to this Mac instead — still encrypted at rest, and it moves back to the Keychain's wrap once that answers.",
        "err_data_file" to "The file %1${"$"}s was damaged and could not be read. A copy was kept as %2${"$"}s; the app continues without it.",
        "err_database" to "The statistics database failed.",
        "err_event" to "Error: %s",
        "err_format" to "The data has an unexpected format.",
        "err_io" to "Reading or writing data failed.",
        "err_memory" to "Not enough memory.",
        "err_network" to "Could not connect. Check the internet connection.",
        "err_reason" to "Reason: %s",
        "err_save" to "Could not save %s. Changes may be lost after a restart.",
        "err_stats" to "Statistics could not be read or saved. The rest of the app keeps working.",
        "err_sub_no_server" to "no working server to download through",
        "err_timeout" to "The server did not answer in time.",
        "err_tls" to "A secure (TLS) connection could not be established.",
        "err_unexpected" to "An unexpected error occurred.",
        "ev_already_best" to "The current server is already the fastest",
        "ev_auto_offline" to "Nothing answered and the internet is down even directly — keeping the server list as it is",
        "ev_auto_pool" to "Auto-visibility: %1${"$"}d shown, %2${"$"}d hidden, %3${"$"}d active",
        "ev_auto_rescue" to "Nothing in the active list answered — checking hidden servers (%d)",
        "ev_auto_rescue_none" to "No hidden server answered either",
        "ev_auto_rescued" to "Auto-visibility: %d hidden servers answered and were added",
        "ev_back" to "%1${"$"}s responds again · %2${"$"}d ms",
        "ev_bad_config" to "Cannot read the config of %1${"$"}s: %2${"$"}s",
        "ev_check_failed" to "%1${"$"}s failed a check (%2${"$"}d of %3${"$"}d)",
        "ev_core_failed" to "Core failed to start: %s",
        "ev_core_failed_server" to "Core failed to start with %1${"$"}s: %2${"$"}s",
        "ev_country_off" to "Servers of any country again",
        "ev_country_on" to "Only servers from: %1${"$"}s (%2${"$"}d)",
        "ev_data_kept_aside" to "The file %1${"$"}s cannot be opened any more — a copy was kept aside; the app continues without it.",
        "ev_direct_first_off" to "The zones’ sites and apps follow their lists again",
        "ev_direct_first_on" to "Limited network: the zones’ sites and apps are tried directly first, through the server if that doesn’t work",
        "ev_down_direct" to "No server responds — traffic goes direct, retrying every %s",
        "ev_down_paused" to "No server responds — traffic is paused, retrying every %s",
        "ev_eco_off" to "Battery saving off",
        "ev_eco_off_saver" to "Battery saving off: the phone's battery saver turned off",
        "ev_eco_on" to "Battery saving on",
        "ev_eco_on_saver" to "Battery saving on: the phone's battery saver turned on",
        "ev_eco_strategy" to "Strategy: %1${"$"}s → %2${"$"}s",
        "ev_finding_best" to "Looking for the fastest server…",
        "ev_geo_check_failed" to "Couldn't check the routing lists for updates (%s); the current ones stay",
        "ev_geo_checked" to "Routing lists checked: no changes",
        "ev_geo_dropped" to "Routing list %1${"$"}s: entries reaching far beyond its zone were left out (%2${"$"}s)",
        "ev_geo_failed" to "Couldn't download the zones' lists — next try in %s, and as soon as a server connects",
        "ev_geo_refused" to "Routing list %1${"$"}s: the downloaded version wasn't taken (%2${"$"}s)",
        "ev_geo_unsigned" to "Routing list %s doesn't match the signed version; it wasn't taken",
        "ev_geo_updated" to "Routing lists updated (%1${"$"}d categories, %2${"$"}s)",
        "ev_group_added" to "Group created: %s",
        "ev_group_removed" to "Group deleted: %s, its servers moved to %s",
        "ev_group_renamed" to "Group renamed: %1${"$"}s → %2${"$"}s",
        "ev_less_secure" to "%1${"$"}s from the subscription is now less protected: %2${"$"}s",
        "ev_limited" to "Limited network: only some sites open directly and no server answers — servers will be checked less often",
        "ev_limited_over" to "The network is no longer limited",
        "ev_limited_trying" to "No ordinary server works — trying the limited-network servers…",
        "ev_lost" to "Lost connection to %s — looking for another server",
        "ev_lost_manual" to "%s is not responding — strategy “Manual”, staying on it",
        "ev_net_changed" to "Network changed — reconnecting",
        "ev_net_up" to "Network is back",
        "ev_no_network" to "No network — waiting",
        "ev_no_servers" to "No servers to connect to — traffic goes direct until one is added",
        "ev_no_servers_country" to "No servers from %s — traffic goes direct",
        "ev_no_servers_country_paused" to "No servers from %s — traffic is paused",
        "ev_no_servers_paused" to "No servers to connect to — traffic is paused until one is added",
        "ev_reconfig_off" to "Applying new settings (routing off)",
        "ev_reconfig_on" to "Applying new settings (routing on)",
        "ev_reconnected" to "%s answers, but the connection to it stopped — reconnecting",
        "ev_scan" to "Ping done in %1${"$"}d s: %2${"$"}d of %3${"$"}d available, best %4${"$"}s — %5${"$"}d ms",
        "ev_scan_hidden" to "%d hidden servers not checked",
        "ev_scan_none" to "Ping done in %1${"$"}d s: none of %2${"$"}d servers answered",
        "ev_scan_stopped" to "stopped once a server was chosen, %d not checked",
        "ev_scan_targeted" to "battery saving: only servers that could be faster, %d not checked",
        "ev_searching" to "Looking for a working server…",
        "ev_server_country" to "Server country: %1${"$"}s → %2${"$"}s",
        "ev_server_country_out" to "%1${"$"}s is no longer from the chosen countries (%2${"$"}s) — leaving it",
        "ev_server_hidden" to "Server hidden: %s",
        "ev_server_moved" to "Server %1${"$"}s moved to group %2${"$"}s",
        "ev_server_power" to "Server %1${"$"}s: %2${"$"}s",
        "ev_server_power_ignore" to " · server limit ignored",
        "ev_server_removed" to "Server deleted: %1${"$"}s (%2${"$"}s)",
        "ev_server_renamed" to "Server renamed: %1${"$"}s → %2${"$"}s",
        "ev_server_shown" to "Server shown: %s",
        "ev_strategy_stay" to "%1${"$"}s: staying on %2${"$"}s",
        "ev_sub_added" to "Subscription added: %s",
        "ev_sub_changes" to ", %1${"$"}d new, %2${"$"}d removed",
        "ev_sub_default_ua" to "“%s”: the answer for your User-Agent had no usable servers, the default one was used",
        "ev_sub_failed" to "Could not update subscription “%1${"$"}s”: %2${"$"}s",
        "ev_sub_moved" to "Subscription %1${"$"}s moved to group %2${"$"}s",
        "ev_sub_removed" to "Subscription deleted: %s, with its servers",
        "ev_sub_renamed" to "Subscription renamed: %1${"$"}s → %2${"$"}s",
        "ev_sub_skipped" to ", %d skipped",
        "ev_sub_updated" to "Subscription “%1${"$"}s” updated: %2${"$"}d servers",
        "ev_sub_via_server" to " (through a server: the provider is not reachable directly)",
        "ev_through_server" to " (through a server)",
        "ev_tunnel_off" to "Connection off",
        "ev_tunnel_on" to "Connection on",
        "ev_verbose_off" to "The detailed core log turned itself off after a day",
        "ev_wider_back" to "The internet answers fully again — checking the ordinary servers",
        "imp_happ" to "Encrypted Happ link: only the Happ app can open it. Ask the provider for an ordinary link or subscription.",
        "imp_not_link" to "Not a server link. Expected vless://, vmess://, trojan://, ss://, hysteria2://, wireguard://, socks://, an Xray JSON config or a subscription https:// address.",
        "imp_sub_clash" to "The provider sends its list only in the Clash format, which is not supported. Ask for a link for v2rayNG / Xray clients.",
        "imp_sub_denied" to "Access denied: the link is outdated or the subscription has ended.",
        "imp_sub_dns" to "The subscription address is not found: check the link and the internet.",
        "imp_sub_empty" to "The provider returned an empty list.",
        "imp_sub_exists" to "This subscription is already added.",
        "imp_sub_failed" to "Couldn't download the subscription.",
        "imp_sub_html" to "A web page came instead of a subscription: the link may be wrong, or the page needs a login.",
        "imp_sub_http" to "Only https:// subscriptions: over plain http the server list can be swapped on the way.",
        "imp_sub_not_found" to "No such subscription: check the link (the provider may have changed it).",
        "imp_sub_rate" to "Too many requests: the provider asks to wait. It will be retried later.",
        "imp_sub_server" to "An error on the provider's side; it will be retried later.",
        "imp_sub_singbox" to "The provider sends its list only in the sing-box format, which is not supported. Ask for a link for v2rayNG / Xray clients.",
        "imp_sub_tls" to "Couldn't set up a secure connection to the provider (directly or through your servers).",
        "imp_sub_too_big" to "The provider's answer is too large (over 10 MB).",
        "imp_sub_unknown" to "The provider's answer has no server links that can be read.",
        "imp_sub_unreachable" to "The provider doesn't answer, neither directly nor through your servers.",
        "import_added" to "Servers added: %d",
        "import_nothing" to "Nothing added",
        "import_pending" to "not downloaded yet: %d, will retry later",
        "import_subs" to "subscriptions: %d",
        "ins_no_cert_check" to "certificate not checked",
        "ins_no_encryption" to "not encrypted",
        "limited_auto" to "Automatic",
        "limited_err_device" to "the server ties this password to another device: if it is yours, set that " +
            "device's id in the server's menu → Server power; otherwise ask for a password of your own",
        "limited_err_expired" to "the password has expired on the server",
        "limited_err_off" to "the password has been turned off on the server",
        "limited_err_password" to "the server doesn't accept the password",
        "limited_no_config" to "the server didn't return a configuration",
        "limited_n_streams" to "%1${"$"}d streams",
        "limited_unsupported" to "this platform doesn't run limited-network servers yet",
        "ms" to "%d ms",
        "own_group" to "Default",
        "strategy_failover" to "On failure",
        "strategy_fastest" to "Fastest",
        "strategy_manual" to "Manual",
        "strategy_stable" to "Stable",
    )
}
