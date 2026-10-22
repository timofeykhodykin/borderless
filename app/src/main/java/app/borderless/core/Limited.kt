package app.borderless.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.borderless.R
import app.borderless.Res
import app.borderless.config.LimitedLink
import app.borderless.config.WgConfig
import app.borderless.data.Activity
import app.borderless.data.AppLog
import app.borderless.data.Energy
import app.borderless.data.Errors
import app.borderless.data.LimitedPower
import app.borderless.data.Repo
import app.borderless.data.Server
import app.borderless.data.ShareFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Runs the native limited-network core (`native/limited`) for limited-network servers. The core is our own Go program
 * built from `native/limited/cmd/limitedd` and packaged as `lib/<abi>/liblimited.so` (see `app/build.gradle.kts`): the
 * app starts one, feeds it commands on stdin and reads its events on stdout. One core carries UDP from a local port
 * through the calls service to the server; the server returns a WireGuard config, and Xray's WireGuard outbound talks
 * to the local port.
 *
 * It is a process rather than a gomobile library because an app can hold only one of those and Xray already is one
 * (`native/limited/cmd/limitedd` explains it). The core is still first-party and GPL-free, and now measured: its CPU
 * comes from `/proc/<pid>/stat` and its radio from the keepalive cadence and carried bytes ([LimitedPower.radioMs],
 * [Energy.limited]), so it no longer hides from the battery model.
 *
 * Two can run at once: the one for the server in use ([connected]) and a throwaway one checking a limited-network
 * server ([withOutbounds] with `probe = true`). How many streams each keeps and how often they wake the radio is
 * decided by [LimitedPower] from the strategy, the screen and the battery saving mode, or set by hand per server.
 */
object Limited {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + Errors.handler)

    private lateinit var appContext: Context
    private lateinit var binary: String

    fun attach(context: Context) {
        appContext = context.applicationContext
        binary = context.applicationInfo.nativeLibraryDir + "/" + BINARY
        freezeOldDeviceIds()
    }

    /**
     * Once, on the first start of 3.1.0 or later: limited-network servers that sent the per-server derived id
     * (no id of the user's, none in the link) keep it as their own — a server may have tied a password to it.
     */
    private fun freezeOldDeviceIds() {
        if (Repo.settings.value.limitedDevicesFrozen) return
        val old = Repo.servers.value
            .filter { it.limited && it.limitedDevice.isEmpty() && linkDevice(it).isNullOrEmpty() }
            .associate { it.id to stable("limited|" + it.id) }
        Repo.setLimitedDevicesQuietly(old)
        Repo.updateSettings { it.copy(limitedDevicesFrozen = true) }
        if (old.isNotEmpty()) AppLog.debug(TAG, "kept the device id of ${old.size} limited-network server(s) added before")
    }

    /** The packaged native program (also runs the direct-first dialer, see [DirectFirst]). */
    val binaryPath: String? get() = if (this::binary.isInitialized) binary else null

    /** The app's files directory (for debug-only test switches, see [DirectFirst.forcedForTest]). */
    val filesDir: File? get() = if (this::appContext.isInitialized) appContext.filesDir else null

    /** Whether the core is packaged (per ABI; a build without it has no limited-network support). */
    val available: Boolean get() = this::binary.isInitialized && File(binary).exists()

    /** The core held for the server in use (null when no limited-network server is connected). */
    @Volatile
    var connected: Session? = null
        private set

    /**
     * What the core for the server in use reports: streams actually connected, streams it wants, and the server's
     * limit in effect (null when the app ignores it). The server sheet and the power dialog show it while it runs.
     */
    data class Live(val serverId: String, val active: Int, val wanted: Int, val limit: Int?, val serverLimit: Int?)

    private val _live = MutableStateFlow<Live?>(null)
    val live: StateFlow<Live?> = _live.asStateFlow()

    private fun publishLive(s: Session?) {
        // A throwaway probe core must not overwrite what the connected core reports.
        if (s != null && connected !== s) return
        _live.value = s?.let { Live(it.server.id, it.activeStreams, it.wantedStreams, it.effectiveLimit, it.knownLimit) }
    }

    /** The stream limit the link carries (`max_workers`), or null. */
    fun maxWorkers(server: Server): Int? =
        runCatching { LimitedLink.parse(server.link).maxWorkers }.getOrNull()

    /** The limit the app should keep within now: the link's, unless the user allows ignoring it. */
    fun effectiveLimit(server: Server): Int? = if (server.limitedIgnoreLimit) null else maxWorkers(server)

    /** The device id sent for [server]: the user's, else the link's, else this phone's [defaultDeviceId]. */
    fun deviceId(server: Server): String {
        server.limitedDevice.takeIf { it.isNotEmpty() }?.let { return it }
        linkDevice(server)?.takeIf { it.isNotEmpty() }?.let { return it }
        return defaultDeviceId()
    }

    private fun linkDevice(server: Server): String? = runCatching { LimitedLink.parse(server.link).deviceId }.getOrNull()

    /**
     * "borderless-" + 6 hex characters of a hash of this app's ANDROID_ID (owner, 2026-10-21): readable in the server's
     * admin panel, its own on every phone (one id per device, as WDTT does; two devices with one id would push each
     * other off) and the same after a reinstall (ANDROID_ID is per signing key and phone). WDTT's own id can't be
     * reproduced: Android gives every signing key a different ANDROID_ID. The raw id never leaves the app. Without a
     * usable ANDROID_ID: the install id instead.
     */
    @Volatile
    private var defaultId: String? = null

    @android.annotation.SuppressLint("HardwareIds")
    fun defaultDeviceId(): String = defaultId ?: run {
        val platform = runCatching {
            android.provider.Settings.Secure.getString(appContext.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        }.getOrNull()?.trim()?.takeIf { it.length >= 8 && it.lowercase() !in BAD_ANDROID_IDS }
        val seed = platform ?: Repo.deviceId()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(("borderless-device|$seed").toByteArray())
        ("borderless-" + hash.take(3).joinToString("") { "%02x".format(it) }).also { defaultId = it }
    }

    /** ANDROID_ID values some old phones share (and WDTT refuses too). */
    private val BAD_ANDROID_IDS = setOf("9774d56d682e549c", "unknown")

    /** Why the last start of a core gave no config ([Session.lastError] of a fatal error), null: no answer in time. */
    @Volatile
    var lastFailure: String? = null
        private set

    /** [lastFailure] in words, for the event "cannot read the config of …". */
    fun failureText(): String = Res.s(
        when (lastFailure) {
            "denied:device_mismatch" -> R.string.limited_err_device
            "denied:wrong_password" -> R.string.limited_err_password
            "denied:expired" -> R.string.limited_err_expired
            "denied:deactivated" -> R.string.limited_err_off
            else -> R.string.limited_no_config
        }
    )

    /** Whether [b] holds a limited-network server's link (the share sheet then offers to pass the device id along). */
    fun hasLimited(b: ShareFormat.Bundle): Boolean = b.groups.any { g -> g.servers.any { LimitedLink.matches(it.l) } }

    /**
     * [b] with each limited-network server's link carrying the id this install sends for it ([deviceId]): the server
     * ties a password to one device id, so this moves the server to one's own other phone. Two devices using one id at
     * once get the same WireGuard key from the server and push each other off.
     */
    fun withDeviceIds(b: ShareFormat.Bundle): ShareFormat.Bundle {
        val byLink = Repo.servers.value.associateBy { it.link }
        return b.copy(groups = b.groups.map { g ->
            g.copy(servers = g.servers.map { s ->
                val server = byLink[s.l]?.takeIf { it.limited } ?: return@map s
                s.copy(l = LimitedLink.withDevice(s.l, deviceId(server)))
            })
        })
    }

    /** A stable per-server id passed to the credential source; never the hardware id. */
    fun instanceId(server: Server): String = stable("limited-instance|" + server.id)

    /** HMAC of [seed] with the install id: stable across restarts, different on another install. */
    private fun stable(seed: String, bytes: Int = 8): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(Repo.deviceId().toByteArray(), "HmacSHA256")) }
        return mac.doFinal(seed.toByteArray()).take(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun conditions(): LimitedPower.Conditions {
        val s = Repo.settings.value
        return LimitedPower.Conditions(s.strategy, s.ecoOn, Power.screenOn)
    }

    /**
     * Starts (or reuses) the core for the server in use and returns its WireGuard outbounds, or null if it couldn't
     * get a config in time. The core stays running and is held as [connected]; [setRate] then paces it.
     */
    suspend fun connect(server: Server): List<JsonObject>? {
        val link = runCatching { LimitedLink.parse(server.link) }.getOrNull() ?: return null
        val existing = connected
        if (existing != null && existing.server.id == server.id && existing.usable()) {
            val wg = existing.ensureStarted(link) ?: run {
                existing.stop()
                connected = null
                publishLive(null)
                return null
            }
            publishLive(existing)
            return listOf(LimitedLink.outbound(wg, existing.port))
        }
        // A core that died, timed out or belongs to another server is not reused: its config points at a dead port.
        if (existing != null) {
            existing.stop()
            connected = null
            publishLive(null)
        }
        val session = Session(server, probe = false)
        connected = session
        val wg = session.ensureStarted(link)
        if (wg == null) {
            session.stop()
            connected = null
            publishLive(null)
            return null
        }
        publishLive(session)
        return listOf(LimitedLink.outbound(wg, session.port))
    }

    /** Stops the core of the server in use, if any. */
    fun disconnect() {
        connected?.stop()
        connected = null
        publishLive(null)
    }

    /** The traffic rate (bytes / s) carried by the server in use, so the core can open or close streams. */
    fun setRate(rateBps: Long) {
        connected?.pace(rateBps, conditions())
        publishLive(connected)
    }

    /** Re-applies the power plan (screen / strategy / battery-saving / server limit changed), without a rate change. */
    fun repace() {
        connected?.refreshOptions()
        connected?.pace(null, conditions())
        publishLive(connected)
    }

    /**
     * Runs [use] with the WireGuard outbounds of a limited-network server, starting a throwaway core for it (probe
     * mode: one stream, the quickest start). Returns null if it couldn't get a config. The core is stopped afterwards.
     */
    suspend fun <T> withOutbounds(server: Server, use: suspend (List<JsonObject>) -> T): T? {
        val link = runCatching { LimitedLink.parse(server.link) }.getOrNull() ?: return null
        val session = Session(server, probe = true)
        return try {
            val wg = session.ensureStarted(link) ?: return null
            use(listOf(LimitedLink.outbound(wg, session.port)))
        } finally {
            session.stop()
        }
    }

    /** How long to wait for a core to hand out a config (credentials + TURN + DTLS handshake + the server's answer). */
    private const val CONFIG_TIMEOUT_MS = 25_000L

    private const val BINARY = "liblimited.so"

    /** RTP obfuscation the server expects ("audio" or "video"; the link does not carry it). */
    private const val MEDIA_MODE = "audio"

    /** Stats every 3 s: the power plan and the battery model work off these. */
    private const val STATS_SEC = 3

    private val cpuTotal = AtomicLong(0)

    /**
     * CPU time (ms) all core processes used since this process started (read at their stats ticks). They run outside
     * the app's process, so `Process.getElapsedCpuTime()` misses them: the power samples and the protocol cost add it.
     */
    val cpuTotalMs: Long get() = cpuTotal.get()

    /** One core process. */
    class Session(val server: Server, private val probe: Boolean) {
        private var process: Process? = null
        private var reader: Job? = null
        private val starting = Any()
        private var startDeferred: CompletableDeferred<WgConfig?>? = null
        private val link = runCatching { LimitedLink.parse(server.link) }.getOrNull()
        private val linkLimit = link?.maxWorkers
        private var ignoreLimit = server.limitedIgnoreLimit
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

        /** The limit known from the link or the server, whether or not the user ignores it (for the UI). */
        val knownLimit: Int? get() = minOfNotNull(linkLimit, runtimeLimit)

        @Volatile
        var lastError: String? = null
            private set

        /** The first stream carries traffic (READY). */
        @Volatile
        var ready: Boolean = false
            private set

        private val bytes = AtomicLong(0)
        private var control = LimitedPower.Controller(manual, effectiveLimit)

        /** Whether this session can still be reused: its process is alive and it has a config. */
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        fun usable(): Boolean {
            val p = process ?: return false
            if (!p.isAlive) return false
            val d = startDeferred ?: return false
            return d.isCompleted && runCatching { d.getCompleted() }.getOrNull() != null
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
            if (manualChanged) control = LimitedPower.Controller(manual, effectiveLimit) else control.setLimit(effectiveLimit)
            // Apply the new count at once: a limit or a fixed count change may not alter the controller's own plan.
            val plan = control.planNow(conditions())
            keepaliveSec = plan.keepaliveSec
            bindingSec = plan.bindingSec
            val streams = effectiveLimit?.let { plan.streams.coerceAtMost(it) } ?: plan.streams
            send("STREAMS $streams")
            send("KEEPALIVE ${plan.keepaliveSec} ${plan.bindingSec}")
            AppLog.debug(TAG, "core ${server.name}: options changed, $streams stream(s)")
        }

        /** Carried bytes since the core started (both ways), from its STATS events. */
        val totalBytes: Long get() = bytes.get()

        /** Keepalive / binding intervals in effect, for the battery model. */
        @Volatile
        private var keepaliveSec = 15
        @Volatile
        private var bindingSec = 10

        private val clockTicks: Long by lazy {
            runCatching { android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK) }.getOrDefault(100L)
        }
        private var lastCpuMs = 0L
        private var lastBytes = 0L
        private var lastStatsAt = 0L
        private var lastStatsUp = 0L

        /** Starts the core if not started and waits for the WireGuard config (null: it didn't come in time). */
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        suspend fun ensureStarted(link: LimitedLink): WgConfig? {
            lastFailure = null
            val existing = synchronized(starting) {
                val d = startDeferred
                // A finished start is reusable only while its process is alive and gave a config; otherwise reset it.
                if (d != null && d.isCompleted &&
                    (runCatching { d.getCompleted() }.getOrNull() == null || process?.isAlive != true)
                ) {
                    process?.destroy()
                    process = null
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
            val dns = networkDns()
            val cmd = listOf(
                binary,
                "-dns", dns,
                "-peer", link.peer,
                "-calls", link.calls.joinToString(","),
                "-password", link.password,
                "-device-id", deviceId(server),
                "-instance", instanceId(server),
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
                ", " + (if (dns.isEmpty()) "fallback resolvers" else "${dns.count { it == ',' } + 1} resolver(s) of the network"))
            reader = scope.launch {
                var p: Process? = null
                try {
                    val dir = File(appContext.cacheDir, "limited").apply { mkdirs() }
                    val pb = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true)
                    // Every cost of these cores is one line of the battery breakdown; a probe still counts as a probe.
                    val started = Energy.track(Activity.Kind.LIMITED_START) { pb.start() }
                    p = started
                    Activity.count(if (probe) Activity.Kind.PROBE else Activity.Kind.LIMITED_START)
                    process = started
                    lastStatsAt = System.currentTimeMillis()
                    lastStatsUp = android.os.SystemClock.uptimeMillis()
                    readEvents(started, result)
                    // The process ending on its own is worth a line: a fatal error or a crash explains a dropped server.
                    val code = runCatching { started.exitValue() }.getOrNull()
                    if (code != null && code != 0) AppLog.debug(TAG, "core ${server.name}: exited with code $code")
                } catch (e: Exception) {
                    AppLog.debug(TAG, "core for ${server.name} failed to start", e)
                    if (!result.isCompleted) result.complete(null)
                } finally {
                    if (!result.isCompleted) result.complete(null)
                    synchronized(starting) { if (p != null && process === p) process = null }
                }
            }
        }

        private fun readEvents(p: Process, result: CompletableDeferred<WgConfig?>) {
            val verbose = Repo.settings.value.verboseLog
            p.inputStream.bufferedReader().forEachLine { line ->
                if (line.startsWith(EVENT_PREFIX)) {
                    val rest = line.removePrefix(EVENT_PREFIX)
                    val type = rest.substringBefore('|')
                    val payload = runCatching {
                        json.parseToJsonElement(rest.substringAfter('|', "").ifEmpty { "{}" }).jsonObject
                    }.getOrNull() ?: JsonObject(emptyMap())
                    handle(type, payload, result)
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
                    publishLive(this)
                }
                "STATS" -> {
                    activeStreams = p["active"]?.jsonPrimitive?.int ?: activeStreams
                    wantedStreams = p["wanted"]?.jsonPrimitive?.int ?: wantedStreams
                    val total = (p["bytes_up"]?.jsonPrimitive?.longOrNull ?: 0) +
                        (p["bytes_down"]?.jsonPrimitive?.longOrNull ?: 0)
                    bytes.set(total)
                    account(total)
                    publishLive(this)
                }
                "POLICY" -> {
                    // The server's own stream limit (`POLICY:max_workers=N`): show it and keep within it unless ignored.
                    val max = p["max_workers"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.takeIf { it > 0 } ?: return
                    runtimeLimit = runtimeLimit?.let { minOf(it, max) } ?: max
                    control.setLimit(effectiveLimit)
                    val cap = effectiveLimit
                    if (cap != null) {
                        val target = if (wantedStreams > 0) minOf(wantedStreams, cap) else cap
                        send("STREAMS $target")
                    }
                    AppLog.debug(TAG, "core ${server.name}: server limit $max stream(s)")
                    publishLive(this)
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

        /**
         * Puts the core's cost into the battery model: its own CPU time (it runs outside this process, so it is read
         * from `/proc/<pid>/stat`) and the radio time of its keepalives and carried bytes, all charged to
         * [Activity.Kind.LIMITED_START] (the server in use and throwaway probes alike). The CPU time is also added to
         * [cpuTotalMs] for the app's CPU chart and the protocol cost. [total] is its cumulative carried bytes.
         */
        private fun account(total: Long) {
            val now = System.currentTimeMillis()
            val cpuMs = processCpuMs()
            val carried = (total - lastBytes).coerceAtLeast(0)
            lastBytes = total
            val since = lastStatsAt
            val up = android.os.SystemClock.uptimeMillis()
            val sinceUp = lastStatsUp
            lastStatsAt = now
            lastStatsUp = up
            if (since == 0L) {
                lastCpuMs = cpuMs
                return
            }
            // Awake time only: while the phone sleeps the core's timers (keepalives) stop too.
            val window = minOf(now - since, up - sinceUp).coerceAtLeast(0)
            val cpu = (cpuMs - lastCpuMs).coerceAtLeast(0)
            lastCpuMs = cpuMs
            cpuTotal.addAndGet(cpu)
            val radio = LimitedPower.radioMs(activeStreams, keepaliveSec, bindingSec, carried, window, Power.metered)
            Energy.limited(cpu, radio, since, now)
        }

        /** The core process's CPU time (ms), from /proc; 0 when it is gone. */
        private fun processCpuMs(): Long {
            val pid = process?.let(::pidOf) ?: 0L
            if (pid <= 0) return 0
            val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return 0
            // Fields after the executable name in parentheses: state is field 3, utime 14, stime 15.
            val f = stat.substringAfterLast(')').trim().split(' ')
            val utime = f.getOrNull(11)?.toLongOrNull() ?: return 0
            val stime = f.getOrNull(12)?.toLongOrNull() ?: return 0
            return (utime + stime) * 1000 / clockTicks.coerceAtLeast(1)
        }

        /** `java.lang.Process.pid()` (API 26+) is missing from the compile stubs, so it is asked for at runtime. */
        private fun pidOf(p: Process): Long =
            runCatching { Process::class.java.getMethod("pid").invoke(p) as Long }.getOrDefault(0L)

        /** Updates the core's power plan from the rate and conditions (see [LimitedPower.Controller]). */
        fun pace(rateBps: Long?, c: LimitedPower.Conditions) {
            if (probe) return
            val plan = control.update(rateBps ?: 0L, c, System.currentTimeMillis()) ?: return
            keepaliveSec = plan.keepaliveSec
            bindingSec = plan.bindingSec
            val streams = effectiveLimit?.let { plan.streams.coerceAtMost(it) } ?: plan.streams
            send("STREAMS $streams")
            send("KEEPALIVE ${plan.keepaliveSec} ${plan.bindingSec}")
            AppLog.debug(TAG, "core ${server.name}: $streams stream(s), keepalive ${plan.keepaliveSec}s")
            publishLive(this)
        }

        private fun send(line: String) {
            runCatching {
                process?.outputStream?.let { out ->
                    out.write((line + "\n").toByteArray())
                    out.flush()
                }
            }
        }

        fun stop() {
            AppLog.debug(TAG, "stopping core ${server.name}")
            send("STOP")
            startDeferred?.let { if (!it.isCompleted) it.complete(null) }
            startDeferred = null
            val p = process
            process = null
            scope.launch {
                withTimeoutOrNull(1_000L) { reader?.join() }
                runCatching { p?.destroy() }
            }
        }

        private fun redact(line: String) = line.replace(Regex("(PrivateKey|PublicKey|PreSharedKey)\\s*=\\s*\\S+"), "$1 = …")

        companion object {
            private const val EVENT_PREFIX = "__LIMITED_EVENT__|"
        }
    }

    /**
     * The resolvers of the network the app's own traffic takes (the app is outside the tunnel), comma-separated. The core
     * is a plain Go program: Android gives it no resolv.conf, so without these it falls back to public resolvers,
     * which a limited network may not let through.
     */
    @Suppress("DEPRECATION")
    private fun networkDns(): String = Errors.guard("limited dns", "", show = false) {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return@guard ""
        (listOfNotNull(cm.activeNetwork) + cm.allNetworks).distinct().asSequence()
            .filter { n ->
                cm.getNetworkCapabilities(n)?.let {
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                } == true
            }
            .mapNotNull { n -> cm.getLinkProperties(n)?.dnsServers?.mapNotNull { it.hostAddress?.substringBefore('%') }?.takeIf { it.isNotEmpty() } }
            .firstOrNull()?.joinToString(",").orEmpty()
    }

    private const val TAG = "Session"
}
