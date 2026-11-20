package app.borderless.bridge

import app.borderless.config.LimitedLink
import app.borderless.config.WgConfig
import app.borderless.config.XrayConfig
import app.borderless.core.DirectFirstCore
import app.borderless.core.LimitedCore
import app.borderless.core.Power
import app.borderless.data.AppLog
import app.borderless.data.Errors
import app.borderless.data.LimitedPower
import app.borderless.data.Repo
import app.borderless.data.Server
import app.borderless.net.Net
import app.borderless.platform.Host
import app.borderless.platform.Lock
import app.borderless.platform.fileExists
import app.borderless.platform.hex
import app.borderless.platform.hmacSha256
import app.borderless.platform.sha256
import app.borderless.platform.text
import app.borderless.platform.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.uuid.Uuid

/**
 * The native limited-network core on the Mac (`native/limited`, the same `limitedd` Android runs): the service
 * starts one sandboxed as the core's own account ([MacProcesses.limitedSpawn]) and talks to it over its stdin and
 * stdout — the line protocol of `cmd/limitedd`. Every socket it opens is bound to the physical interface
 * (`-iface`): the tunnel's routes take everything, so an unbound socket would loop into it. The resolver of the
 * network underneath goes as `-dns` (the system's points into the tunnel). Android's `/proc` CPU read is
 * `cpu_ms` in the STATS events instead; the stream / keepalive bookkeeping is the shared [LimitedPower], same as
 * on Android.
 */
object MacLimited : LimitedCore.Host {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + Errors.handler)

    private lateinit var p: MacProcesses
    private lateinit var dataDir: String

    /** Once, from [MacEngine.setUp]; registers this and [MacDirectFirst] as the cores' hosts. */
    fun setUp(processes: MacProcesses, dataDir: String) {
        p = processes
        this.dataDir = dataDir
        LimitedCore.host = this
        DirectFirstCore.host = MacDirectFirst.also { it.setUp(processes, dataDir) }
    }

    /** Whether the core is installed (a service installed by an older app has none). */
    override val available: Boolean get() = p.limitedAvailable()

    /** The core held for the server in use (null when no limited-network server is connected). */
    @Volatile
    var connected: Session? = null
        private set

    /**
     * What the core for the server in use reports right now (Android's `Limited.live`): streams actually
     * connected, streams it is told to keep, the stream limit in effect (the link's `max_workers` lowered by
     * the server's `POLICY:max_workers`, or null when the user ignores limits / none is known) and the limit
     * known even when it is ignored. [serverId] names the server the figures belong to.
     */
    data class Live(val serverId: String, val active: Int, val wanted: Int, val limit: Int?, val serverLimit: Int?)

    /**
     * The connected core's live figures, or null when no limited-network server is running (or its process
     * ended): the service's status polls this for `EngineStatus.limitedActive` / `limitedWanted` /
     * `limitedLimit` / `limitedKnown` (from [Live.active] / [Live.wanted] / [Live.limit] / [Live.serverLimit]).
     * A throwaway probe core never appears here — [connected] only ever holds the session of the server in use.
     */
    val live: Live?
        get() = connLock.withLock { connected }?.takeIf { it.usable() }
            ?.let { Live(it.server.id, it.activeStreams, it.wantedStreams, it.effectiveLimit, it.knownLimit) }

    /** Serializes [connect] calls: two connects never overlap (disconnect does not take it — see [generation]). */
    private val connectMutex = Mutex()

    /**
     * Guards the [connected]/[generation] swaps, held only for pointer reads and writes, never across a wait:
     * [disconnect] is not a suspend function, so it can't join [connectMutex] — it wins by bumping [generation],
     * and a connect in flight then drops the session it was starting instead of overwriting the cleared pointer.
     */
    private val connLock = Lock()
    private var generation = 0

    /** The stream limit the link carries (`max_workers`), or null. */
    fun maxWorkers(server: Server): Int? =
        runCatching { LimitedLink.parse(server.link).maxWorkers }.getOrNull()

    /** The limit the app should keep within now: the link's, unless the user allows ignoring it. */
    fun effectiveLimit(server: Server): Int? = if (server.limitedIgnoreLimit) null else maxWorkers(server)

    /** The device id sent for [server]: the user's, else the link's, else this install's [defaultDeviceId]. */
    fun deviceId(server: Server): String {
        server.limitedDevice.takeIf { it.isNotEmpty() }?.let { return it }
        linkDevice(server)?.takeIf { it.isNotEmpty() }?.let { return it }
        return defaultDeviceId
    }

    private fun linkDevice(server: Server): String? = runCatching { LimitedLink.parse(server.link).deviceId }.getOrNull()

    /**
     * "borderless-" + 6 hex characters of a hash of the install id ([Repo.deviceId]), the same shape Android
     * derives from ANDROID_ID: readable in the server's admin panel, its own on every install (two devices with
     * one id would push each other off). A Mac has no ANDROID_ID; the raw install id never leaves the app.
     */
    val defaultDeviceId: String by lazy {
        "borderless-" + sha256("borderless-device|${Repo.deviceId()}".encodeToByteArray()).copyOf(3).hex()
    }

    /** A stable per-server id passed to the credential source; never the hardware id. */
    fun instanceId(server: Server): String = stable("limited-instance|" + server.id)

    /** HMAC of [seed] with the install id: stable across restarts, different on another install. */
    private fun stable(seed: String, bytes: Int = 8): String =
        hmacSha256(Repo.deviceId().encodeToByteArray(), seed.encodeToByteArray()).copyOf(bytes).hex()

    private fun conditions(): LimitedPower.Conditions {
        val s = Repo.settings.value
        return LimitedPower.Conditions(s.strategy, s.ecoOn, Power.screenOn)
    }

    /**
     * Starts (or reuses) the core for the server in use and returns its WireGuard outbounds, or null if it couldn't
     * get a config in time. The core stays running and is held as [connected]; [setRate] then paces it.
     */
    override suspend fun connect(server: Server): List<JsonObject>? {
        val link = runCatching { LimitedLink.parse(server.link) }.getOrNull() ?: return null
        connectMutex.withLock {
            val existing = connLock.withLock { connected }
            if (existing != null && existing.server.id == server.id && existing.usable()) {
                // A disconnect may still land while the start runs (it doesn't take connectMutex): it bumps
                // [generation] and clears [connected], and a core [ensureStarted] spawned meanwhile is an
                // orphan — stopped, not returned. The generation is the same check the fresh path makes.
                val g = connLock.withLock { generation }
                val wg = existing.ensureStarted(link)
                val stillCurrent = connLock.withLock { generation == g && connected === existing }
                // !usable() here also means a disconnect stopped it while the start was running.
                if (wg == null || !stillCurrent || !existing.usable()) {
                    existing.stop()
                    connLock.withLock { if (connected === existing) connected = null }
                    return null
                }
                return listOf(LimitedLink.outbound(wg, existing.port))
            }
            // A core that died, timed out or belongs to another server is not reused: its config points at a dead port.
            existing?.stop()
            connLock.withLock { if (connected === existing) connected = null }
            val g = connLock.withLock { generation }
            val session = Session(server, probe = false)
            val wg = session.ensureStarted(link)
            if (wg == null) {
                session.stop()
                return null
            }
            // A disconnect while the start was running took the want away: rather than overwrite the cleared
            // pointer (a spawned limitedd nobody holds would leak), this session is orphaned and stopped.
            val orphan = connLock.withLock {
                if (generation != g || connected != null) true else { connected = session; false }
            }
            if (orphan) {
                session.stop()
                return null
            }
            return listOf(LimitedLink.outbound(wg, session.port))
        }
    }

    /** Stops the core of the server in use, if any. */
    override fun disconnect() {
        val s = connLock.withLock {
            generation++
            connected.also { connected = null }
        }
        s?.stop()
    }

    /** The traffic rate (bytes / s) carried by the server in use, so the core can open or close streams. */
    override fun setRate(rateBps: Long) {
        connected?.pace(rateBps, conditions())
    }

    /** Re-applies the power plan (screen / strategy / battery-saving / server limit changed), without a rate change. */
    override fun repace() {
        connected?.refreshOptions()
        connected?.pace(null, conditions())
    }

    /**
     * Runs [use] with the WireGuard outbounds of a limited-network server, starting a throwaway core for it (probe
     * mode: one stream, the quickest start). Returns null if it couldn't get a config. The core is stopped afterwards.
     */
    override suspend fun <T> withOutbounds(server: Server, use: suspend (List<JsonObject>) -> T): T? {
        val link = runCatching { LimitedLink.parse(server.link) }.getOrNull() ?: return null
        val session = Session(server, probe = true)
        return try {
            val wg = session.ensureStarted(link) ?: return null
            use(listOf(LimitedLink.outbound(wg, session.port)))
        } finally {
            session.stop()
        }
    }

    /** Why the last start of a core gave no config ([Session.lastError] of a fatal error), null: no answer in time. */
    @Volatile
    var lastFailure: String? = null
        private set

    /** [lastFailure] in words, for the event "cannot read the config of …". */
    override fun failureText(): String = failureText(lastFailure)

    /**
     * The words for a stored failure code. [text] is asked first — in the app it resolves the real, localised
     * string; in the service it goes through ServiceTexts (MacEngine.kt), which lists none of these keys and
     * then answers with the key itself, so [FAILURE_TEXTS] carries the English of strings.xml for exactly
     * that case (a root daemon cannot read the app's generated strings table).
     */
    private fun failureText(code: String?): String {
        val key = when (code) {
            "denied:device_mismatch" -> "limited_err_device"
            "denied:wrong_password" -> "limited_err_password"
            "denied:expired" -> "limited_err_expired"
            "denied:deactivated" -> "limited_err_off"
            else -> "limited_no_config"
        }
        return text(key).takeIf { it != key } ?: (FAILURE_TEXTS[key] ?: key)
    }

    /** The English of `strings.xml` for the keys [failureText] emits (see there); kept verbatim from Android. */
    private val FAILURE_TEXTS = mapOf(
        "limited_err_device" to "the server ties this password to another device: if it is yours, " +
            "set that device's id in the server's menu → Server power; otherwise ask for a password of your own",
        "limited_err_password" to "the server doesn't accept the password",
        "limited_err_expired" to "the password has expired on the server",
        "limited_err_off" to "the password has been turned off on the server",
        "limited_no_config" to "the server didn't return a configuration",
        "limited_unsupported" to "this platform doesn't run limited-network servers yet",
    )

    /** How long to wait for a core to hand out a config (credentials + TURN + DTLS handshake + the server's answer). */
    private const val CONFIG_TIMEOUT_MS = 25_000L

    /** RTP obfuscation the server expects ("audio" or "video"; the link does not carry it). */
    private const val MEDIA_MODE = "audio"

    /** Stats every 3 s: the power plan and the cpu_ms deltas work off these. */
    private const val STATS_SEC = 3

    private val cpuTotal = AtomicLong(0)

    /**
     * CPU time (ms) all core processes used so far (read at their stats ticks). They run outside the service's own
     * process, so its own count misses them: the protocol cost adds this (Android reads the same from /proc).
     */
    override val cpuTotalMs: Long get() = cpuTotal.load()

    /** The interface and resolver the core's connections leave by ("-iface" / "-dns"), now as text for the log. */
    private fun network(): Pair<String, String> {
        val n = MacNetwork.of(p.network())
        return (n?.iface.orEmpty()) to (n?.resolver.orEmpty())
    }

    /** One core process. */
    class Session(val server: Server, private val probe: Boolean) {
        @Volatile
        private var handle = 0
        @Volatile
        private var reader: Job? = null
        private val starting = Lock()
        @Volatile
        private var startDeferred: CompletableDeferred<WgConfig?>? = null
        private val linkLimit = runCatching { LimitedLink.parse(server.link) }.getOrNull()?.maxWorkers
        @Volatile
        private var ignoreLimit = server.limitedIgnoreLimit
        @Volatile
        private var manual = server.limitedStreams
        /** A lower limit learned from the server while it runs (`POLICY:max_workers`), null: none. */
        @Volatile
        private var runtimeLimit: Int? = null

        @Volatile
        var port: Int = 0
            private set

        @Volatile
        var activeStreams: Int = 0
            private set

        /** Streams the core has been told to keep (STATS `wanted`). */
        @Volatile
        var wantedStreams: Int = 0
            private set

        /** The limit in effect for this session: the link's or the server's, unless the user ignores it. */
        val effectiveLimit: Int? get() = if (ignoreLimit) null else minOfNotNull(linkLimit, runtimeLimit)

        /** The limit known from the link or the server, whether or not the user ignores it (for the log). */
        val knownLimit: Int? get() = minOfNotNull(linkLimit, runtimeLimit)

        @Volatile
        var lastError: String? = null
            private set

        /** The first stream carries traffic (READY). */
        @Volatile
        var ready: Boolean = false
            private set

        private val bytes = AtomicLong(0)

        /** All [control] calls run under this: the engine and the reader coroutines both reach it. */
        private val controlLock = Lock()
        @Volatile
        private var control = LimitedPower.Controller(manual, effectiveLimit)

        /** Whether this session can still be reused: its process is alive and it has a config. */
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        fun usable(): Boolean {
            val h = handle
            if (h == 0) return false
            val d = startDeferred ?: return false
            return d.isCompleted && runCatching { d.getCompleted() }.getOrNull() != null && p.limitedAlive(h)
        }

        private fun minOfNotNull(a: Int?, b: Int?): Int? = when {
            a == null -> b
            b == null -> a
            else -> minOf(a, b)
        }

        /** Re-reads the server's options (manual count, ignore-limit switch) after the user changed them. */
        fun refreshOptions() {
            val s = Repo.server(server.id) ?: return
            val manualChanged = s.limitedStreams != manual
            ignoreLimit = s.limitedIgnoreLimit
            manual = s.limitedStreams
            // Apply the new count at once: a limit or a fixed count change may not alter the controller's own plan.
            val plan = controlLock.withLock {
                if (manualChanged) control = LimitedPower.Controller(manual, effectiveLimit) else control.setLimit(effectiveLimit)
                control.planNow(conditions())
            }
            keepaliveSec = plan.keepaliveSec
            bindingSec = plan.bindingSec
            val streams = effectiveLimit?.let { plan.streams.coerceAtMost(it) } ?: plan.streams
            send("STREAMS $streams")
            send("KEEPALIVE ${plan.keepaliveSec} ${plan.bindingSec}")
            AppLog.debug(TAG, "core ${server.name}: options changed, $streams stream(s)")
        }

        /** Carried bytes since the core started (both ways), from its STATS events. */
        val totalBytes: Long get() = bytes.load()

        /** Keepalive / binding intervals in effect. */
        @Volatile
        private var keepaliveSec = 15
        @Volatile
        private var bindingSec = 10

        @Volatile
        private var lastCpuMs = 0L
        @Volatile
        private var lastStatsAt = 0L

        /** Starts the core if not started and waits for the WireGuard config (null: it didn't come in time). */
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        suspend fun ensureStarted(link: LimitedLink): WgConfig? {
            lastFailure = null
            val existing = starting.withLock {
                val d = startDeferred
                // A finished start is reusable only while its process is alive and gave a config; otherwise reset it.
                if (d != null && d.isCompleted &&
                    (runCatching { d.getCompleted() }.getOrNull() == null || handle == 0)
                ) {
                    startDeferred = null
                }
                startDeferred ?: CompletableDeferred<WgConfig?>().also { startDeferred = it; launchProcess(link, it) }
            }
            return withTimeoutOrNull(CONFIG_TIMEOUT_MS) { existing.await() }
        }

        private fun launchProcess(link: LimitedLink, result: CompletableDeferred<WgConfig?>) {
            val c = conditions()
            val limit = effectiveLimit
            val startPlan = if (probe) LimitedPower.probe(manual, limit) else LimitedPower.start(manual, c, limit)
            val ceiling = LimitedPower.ceilingAtStart(manual, limit)
            keepaliveSec = startPlan.keepaliveSec
            bindingSec = startPlan.bindingSec
            wantedStreams = startPlan.streams
            val verbose = Repo.settings.value.verboseLog
            val (iface, dns) = network()
            val argv = listOf(
                "-iface", iface,
                "-dns", dns,
                "-listen", "127.0.0.1:0",
                "-media", MEDIA_MODE,
                "-n", ceiling.toString(),
                "-active", startPlan.streams.toString(),
                "-keepalive", startPlan.keepaliveSec.toString(),
                "-binding", startPlan.bindingSec.toString(),
                "-stats", STATS_SEC.toString(),
                "-verbose=$verbose",
            )
            AppLog.debug(TAG, "starting core for ${server.name}: ${link.peer}, ${link.calls.size} call(s), " +
                (if (probe) "probe" else "${startPlan.streams}/$ceiling streams") +
                (if (iface.isEmpty()) "" else ", out by $iface"))
            // Credentials go in the environment, never on the command line: any local user reads another
            // process's argv (ps auxww), another account's environment not. limitedd takes each unset
            // flag from the variable of the same name (native/limited/cmd/limitedd/main.go).
            val env = mapOf(
                "LIMITED_PEER" to link.peer,
                "LIMITED_CALLS" to link.calls.joinToString(","),
                "LIMITED_PASSWORD" to link.password,
                "LIMITED_DEVICE_ID" to deviceId(server),
                "LIMITED_INSTANCE" to instanceId(server),
            )
            val h = p.limitedSpawn(argv, env)
            if (h <= 0) {
                AppLog.debug(TAG, "core ${server.name}: didn't start")
                result.complete(null)
                return
            }
            handle = h
            lastStatsAt = app.borderless.platform.nowMs()
            reader = scope.launch {
                try {
                    readEvents(h, result)
                    AppLog.debug(TAG, "core ${server.name}: ended")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    AppLog.debug(TAG, "core ${server.name} failed to start", e)
                    if (!result.isCompleted) result.complete(null)
                } finally {
                    if (!result.isCompleted) result.complete(null)
                    val ours = starting.withLock { if (handle == h) { handle = 0; true } else false }
                    // The core ended by itself: nobody calls [stop] for it, and the service drops its
                    // bookkeeping only on limitedStop — without this its entry and descriptors would stay
                    // until the service ends. A [stop] in flight took the handle already (ours is false).
                    if (ours) runCatching { p.limitedStop(h) }
                }
            }
        }

        private fun readEvents(h: Int, result: CompletableDeferred<WgConfig?>) {
            val verbose = Repo.settings.value.verboseLog
            while (true) {
                val line = p.limitedRead(h)
                if (line.isEmpty()) break
                if (line.startsWith(EVENT_PREFIX)) {
                    val rest = line.removePrefix(EVENT_PREFIX)
                    val payload = runCatching {
                        json.parseToJsonElement(rest.substringAfter('|', "").ifEmpty { "{}" }).jsonObject
                    }.getOrNull() ?: JsonObject(emptyMap())
                    handle(rest.substringBefore('|'), payload, result)
                } else if (verbose) {
                    // The config carries the private key: never written even in the detailed log.
                    AppLog.debug(TAG, "[core ${server.name}] " + redact(line))
                }
            }
        }

        private fun handle(type: String, p: JsonObject, result: CompletableDeferred<WgConfig?>) {
            when (type) {
                "LISTEN" -> p["port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.let { port = it }
                "CONFIG" -> {
                    val wg = p["config"]?.jsonPrimitive?.contentOrNull?.let { WgConfig.parse(it) }
                    if (wg != null) {
                        AppLog.debug(TAG, "core ${server.name}: config received, local port $port")
                        result.complete(wg)
                    } else {
                        lastError = "config"
                        result.complete(null)
                    }
                }
                "READY" -> {
                    if (!ready) AppLog.debug(TAG, "core ${server.name}: first stream carrying traffic")
                    ready = true
                }
                "STATS" -> {
                    activeStreams = p["active"]?.jsonPrimitive?.int ?: activeStreams
                    wantedStreams = p["wanted"]?.jsonPrimitive?.int ?: wantedStreams
                    bytes.store((p["bytes_up"]?.jsonPrimitive?.longOrNull ?: 0) +
                        (p["bytes_down"]?.jsonPrimitive?.longOrNull ?: 0))
                    // Its CPU time, counted since its start: only the delta belongs to us.
                    p["cpu_ms"]?.jsonPrimitive?.longOrNull?.let { cpu ->
                        cpuTotal.addAndFetch((cpu - lastCpuMs).coerceAtLeast(0))
                        lastCpuMs = cpu
                    }
                    lastStatsAt = app.borderless.platform.nowMs()
                }
                "POLICY" -> {
                    // The server's own stream limit (`POLICY:max_workers=N`): keep within it unless ignored.
                    val max = p["max_workers"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.takeIf { it > 0 } ?: return
                    runtimeLimit = runtimeLimit?.let { minOf(it, max) } ?: max
                    controlLock.withLock { control.setLimit(effectiveLimit) }
                    val cap = effectiveLimit
                    if (cap != null) {
                        val target = if (wantedStreams > 0) minOf(wantedStreams, cap) else cap
                        send("STREAMS $target")
                    }
                    AppLog.debug(TAG, "core ${server.name}: server limit $max stream(s)")
                }
                "ERROR" -> {
                    // The server's refusal travels as FATAL_AUTH[reason] (device_mismatch, wrong_password, expired…).
                    val denied = p["message"]?.jsonPrimitive?.contentOrNull
                        ?.let { Regex("FATAL_AUTH\\[([a-z_]+)]").find(it)?.groupValues?.get(1) }
                    val code = denied?.let { "denied:$it" } ?: p["code"]?.jsonPrimitive?.contentOrNull ?: "error"
                    val fatal = p["fatal"]?.jsonPrimitive?.contentOrNull == "true"
                    lastError = code
                    if (fatal) lastFailure = code
                    AppLog.debug(TAG, "core ${server.name}: ${if (fatal) "fatal " else ""}error $code")
                    if (fatal && !result.isCompleted) result.complete(null)
                }
            }
        }

        /** Updates the core's power plan from the rate and conditions (see [LimitedPower.Controller]). */
        fun pace(rateBps: Long?, c: LimitedPower.Conditions) {
            if (probe) return
            val plan = controlLock.withLock { control.update(rateBps ?: 0L, c, app.borderless.platform.nowMs()) } ?: return
            keepaliveSec = plan.keepaliveSec
            bindingSec = plan.bindingSec
            val streams = effectiveLimit?.let { plan.streams.coerceAtMost(it) } ?: plan.streams
            send("STREAMS $streams")
            send("KEEPALIVE ${plan.keepaliveSec} ${plan.bindingSec}")
            AppLog.debug(TAG, "core ${server.name}: $streams stream(s), keepalive ${plan.keepaliveSec}s")
        }

        private fun send(line: String) {
            val h = handle
            if (h != 0) runCatching { p.limitedSend(h, line) }
        }

        fun stop() {
            AppLog.debug(TAG, "stopping core ${server.name}")
            // Under `starting`, which launchProcess holds from the spawn to the handle assignment: the handle
            // taken here can't be a spawned-but-not-recorded process (that one would run on, never stopped).
            val h = starting.withLock {
                send("STOP")
                startDeferred?.let { if (!it.isCompleted) it.complete(null) }
                startDeferred = null
                val current = handle
                handle = 0
                current
            }
            scope.launch {
                withTimeoutOrNull(1_000L) { reader?.join() }
                if (h != 0) runCatching { p.limitedStop(h) }
            }
        }

        private fun redact(line: String) = line.replace(Regex("(PrivateKey|PublicKey|PreSharedKey)\\s*=\\s*\\S+"), "$1 = …")

        companion object {
            private const val EVENT_PREFIX = "__LIMITED_EVENT__|"
        }
    }

    private const val TAG = "MacLimited"
}

/**
 * "Directly first" on the Mac ([app.borderless.data.AppSettings.directFirst]): the local SOCKS5 dialer
 * (`limitedd direct-first`, package `native/limited/directfirst`) that opens each connection of the zones' lists
 * directly — bound to the physical interface — and replays it through the running core's SOCKS inbound when the
 * direct way doesn't work (Android's `DirectFirst`, minus the per-app TUN: on the Mac every app is "inside").
 * Ports and credentials are random per run, so other programs can't use either way.
 */
object MacDirectFirst : DirectFirstCore.Host {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + Errors.handler)
    private val lock = Mutex()

    private lateinit var p: MacProcesses
    private lateinit var dataDir: String

    fun setUp(processes: MacProcesses, dataDir: String) {
        p = processes
        this.dataDir = dataDir
    }

    @Volatile
    private var handle = 0

    @Volatile
    private var endpoint: XrayConfig.DirectFirst? = null

    /**
     * Called when a dialer that ran for a while ends by itself: the core's config still points at its port, so the
     * engine restarts the core (which starts a new dialer). One that dies at once isn't restarted in a loop.
     */
    @Volatile
    override var onEnded: (() -> Unit)? = null
    private const val RESTART_AFTER_MS = 30_000L
    private const val START_TIMEOUT_MS = 5_000L
    private const val EVENT_PREFIX = "__LIMITED_EVENT__|"
    private const val TAG = "DirectFirst"

    /** The running dialer's ports and credentials (started if needed), or null if it can't run. */
    override suspend fun ensure(): XrayConfig.DirectFirst? = lock.withLock {
        endpoint?.takeIf { handle != 0 && p.limitedAlive(handle) }?.let { return@withLock it }
        stopLocked()
        if (!p.limitedAvailable()) return@withLock null
        val backPort = runCatching { Net.host.freePort() }.getOrNull() ?: return@withLock null
        val user = token(); val pass = token(); val backUser = token(); val backPass = token()
        val verbose = Repo.settings.value.verboseLog
        val n = MacNetwork.of(p.network())
        // The SOCKS credentials go in the environment, never on the command line (see MacLimited's spawn):
        // they exist to keep other local programs off these proxies, and argv is readable by all of them.
        val env = mapOf(
            "LIMITED_DF_USER" to user,
            "LIMITED_DF_PASS" to pass,
            "LIMITED_DF_BACK_USER" to backUser,
            "LIMITED_DF_BACK_PASS" to backPass,
        )
        val argv = listOf(
            "direct-first", "-listen", "127.0.0.1:0",
            "-back", "127.0.0.1:$backPort",
            "-iface", n?.iface.orEmpty(), "-dns", n?.resolver.orEmpty(), "-verbose=$verbose",
        )
        val h = p.limitedSpawn(argv, env)
        if (h <= 0) {
            AppLog.debug(TAG, "dialer failed to start")
            return@withLock null
        }
        // The handle is ours before the reader launches: a dialer dying between LISTEN and the assignment
        // must still be cleared by the reader's finally — an unknown one would leave a dead endpoint cached.
        handle = h
        val port = CompletableDeferred<Int?>()
        val startedAt = app.borderless.platform.nowMs()
        scope.launch { read(h, port, startedAt) }
        val got = withTimeoutOrNull(START_TIMEOUT_MS) { port.await() }
        // A port of a process that died meanwhile is not an endpoint: re-check it is still running.
        if (got == null || !p.limitedAlive(h)) {
            AppLog.debug(TAG, "dialer gave no port")
            runCatching { p.limitedStop(h) }
            if (handle == h) { handle = 0; endpoint = null }
            return@withLock null
        }
        AppLog.debug(TAG, "dialer on 127.0.0.1:$got, way back through the server on 127.0.0.1:$backPort")
        XrayConfig.DirectFirst(got, user, pass, backPort, backUser, backPass).also { endpoint = it }
    }

    /**
     * Debug builds only ([Host.debug], Android's `BuildConfig.DEBUG` gate): `$dataDir/limited.test` makes
     * "directly first" treat the network as limited — a release service never honours the file.
     */
    override fun forcedForTest(): Boolean = Host.debug && fileExists("$dataDir/limited.test")

    /** Stops the dialer (the network is no longer limited, or the tunnel stops). */
    override suspend fun stop() = lock.withLock { stopLocked() }

    private fun stopLocked() {
        val h = handle
        handle = 0
        endpoint = null
        if (h != 0) runCatching { p.limitedStop(h) }
        AppLog.debug(TAG, "dialer stopped")
    }

    private fun read(h: Int, port: CompletableDeferred<Int?>, startedAt: Long) {
        try {
            while (true) {
                val line = p.limitedRead(h)
                if (line.isEmpty()) break
                if (!line.startsWith(EVENT_PREFIX)) {
                    // Only with the detailed ("verbose") log: a line per host moved to the server.
                    AppLog.debug(TAG, line)
                    continue
                }
                val rest = line.removePrefix(EVENT_PREFIX)
                val payload = runCatching { json.parseToJsonElement(rest.substringAfter('|', "").ifEmpty { "{}" }).jsonObject }.getOrNull()
                when (rest.substringBefore('|')) {
                    "LISTEN" -> port.complete(payload?.get("port")?.jsonPrimitive?.contentOrNull?.toIntOrNull())
                    "STATS" -> payload?.let {
                        fun n(k: String) = it[k]?.jsonPrimitive?.contentOrNull ?: "0"
                        AppLog.debug(TAG, "so far: ${n("direct")} directly, ${n("server")} through the server " +
                            "(${n("fallbacks")} after a direct try), ${n("failed")} neither way")
                    }
                    "ERROR" -> AppLog.debug(TAG, "dialer: " + (payload?.get("message")?.jsonPrimitive?.contentOrNull ?: rest))
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        } finally {
            port.complete(null)
            if (handle == h) {
                handle = 0
                endpoint = null
                AppLog.debug(TAG, "dialer ended")
                if (app.borderless.platform.nowMs() - startedAt > RESTART_AFTER_MS) onEnded?.invoke()
            }
        }
    }

    private fun token(): String = Uuid.random().toHexString().take(24)
}
