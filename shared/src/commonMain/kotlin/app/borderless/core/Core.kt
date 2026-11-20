package app.borderless.core

import app.borderless.config.LinkParser
import app.borderless.config.XrayConfig
import app.borderless.data.AppLog
import app.borderless.data.AppSettings
import app.borderless.data.Server
import app.borderless.platform.nowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlin.concurrent.Volatile

/**
 * The platform's Xray. Android runs libv2ray inside the app's process, the Mac's service runs `borderless-core`
 * processes. Every call blocks its thread and may hang (the network): callers wait with a timeout ([native]).
 */
interface XrayHost {
    /** The core's version line ("Xray 26.10.21 (…)"). */
    fun version(): String

    /** Makes the core ready on first use (Android: loads the library, points it at the routing lists). */
    fun ensure()

    /** A throwaway instance with [config]: the delay to [url] in ms (> 0; the start is not timed), else throws. */
    fun measureOutbound(config: String, url: String): Long

    /** A controller for one long-lived instance; [log] gets the lines it emits. */
    fun controller(log: (String) -> Unit): Controller

    interface Controller {
        val isRunning: Boolean

        /** Starts with [config] on the tunnel's [tunFd] (0: no tunnel, e.g. a local proxy); stops a running one first. */
        fun start(config: String, tunFd: Int)
        fun stop()

        /** Traffic per outbound since the last call: "tag,uplink|downlink,bytes;…". */
        fun queryTraffic(): String

        /** The delay to [url] through the running instance in ms, ≤ 0 on failure. */
        fun measureDelay(url: String): Long
    }
}

/**
 * Native calls into Xray block their thread and cannot be interrupted, so they run on their own threads and callers
 * wait with a timeout. A timed-out call keeps its thread until the core gives up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private val nativeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(256))

suspend fun <T> native(timeoutMs: Long, block: () -> T): T? {
    val call = nativeScope.async { block() }
    return withTimeoutOrNull(timeoutMs) { call.await() }
}

object CoreEnv {
    /** Set by the platform at start. */
    lateinit var xray: XrayHost

    /** Initialises the core on first use. */
    fun ensure() = xray.ensure()

    fun version(): String = runCatching { xray.version() }.getOrDefault("?")

    /**
     * Held while any core instance starts: libv2ray hands the TUN descriptor to the starting instance
     * through process-wide state, so two instances must not start at the same moment.
     */
    val startLock = Mutex()
}

/** One running Xray instance bound to the tunnel's TUN file descriptor. */
class CoreRunner {
    private val lock = Mutex()
    private val controller: XrayHost.Controller = CoreEnv.xray.controller { line -> if (line.isNotBlank()) AppLog.debug("core", line) }

    val isRunning: Boolean get() = runCatching { controller.isRunning }.getOrDefault(false)

    suspend fun start(config: String, tunFd: Int) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (controller.isRunning) runCatching { controller.stop() }
            CoreEnv.startLock.withLock { controller.start(config, tunFd) }
            check(controller.isRunning) { "core did not start" }
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (controller.isRunning) runCatching { controller.stop() }.onFailure { AppLog.debug("core", "stop failed", it) }
        }
    }

    /** Bytes per outbound tag since the last call: tag -> (uplink, downlink). Counters reset on read. */
    fun takeTraffic(): Map<String, Pair<Long, Long>> {
        if (!isRunning) return emptyMap()
        val raw = runCatching { controller.queryTraffic() }.getOrNull().orEmpty()
        val out = HashMap<String, Pair<Long, Long>>()
        raw.split(';').filter { it.isNotBlank() }.forEach { part ->
            val (tag, dir, value) = part.split(',').takeIf { it.size == 3 } ?: return@forEach
            val v = value.toLongOrNull() ?: return@forEach
            val cur = out[tag] ?: (0L to 0L)
            out[tag] = if (dir == "uplink") cur.copy(first = cur.first + v) else cur.copy(second = cur.second + v)
        }
        return out
    }

    /** Latency through the running core; `null` when the request fails or times out. */
    suspend fun measure(url: String, timeoutMs: Long): Int? {
        if (!isRunning) return null
        val r = native(timeoutMs) { runCatching { controller.measureDelay(url) }.getOrDefault(-1L) }
        return r?.takeIf { it > 0 }?.toInt()
    }
}

/**
 * Xray's log settings are global to the process: every instance (including throwaway probe
 * instances) re-applies them on start. All instances therefore use the same values.
 */
/** Battery saving mode: errors only (every log line is captured, parsed and written by the app). */
fun xrayLogLevel(s: AppSettings) = if (s.verboseLog) "info" else if (s.ecoOn) "error" else "warning"
fun xrayAccessLog(s: AppSettings) = if (s.verboseLog) "" else "none"

object Prober {
    private const val CORE_START_ALLOWANCE_MS = 500L

    /** Result of one probe. [timedOut]: no answer within the timeout that was used. */
    data class Outcome(val ms: Int?, val timedOut: Boolean)

    /**
     * Real end-to-end test: spins up a throwaway Xray instance with this server and fetches the
     * test URL. [timeoutMs] defaults to the configured probe timeout.
     */
    suspend fun probe(server: Server, s: AppSettings, timeoutMs: Long = s.probeTimeoutSec * 1000L): Int? =
        probeDetailed(server, s, timeoutMs).ms

    /** Demo builds only (README screenshots): answers probes with made-up results instead of the network. */
    @Volatile
    var standIn: (suspend (Server) -> Outcome)? = null

    suspend fun probeDetailed(server: Server, s: AppSettings, timeoutMs: Long): Outcome {
        standIn?.let { return it(server) }
        // A limited-network server is reached through its core: start a throwaway one, probe through it, tear it down.
        if (server.limited) return LimitedCore.withOutbounds(server) { outbounds -> probeWith(server, outbounds, s, timeoutMs) }
            ?: Outcome(null, false).also { AppLog.debug("probe", "${server.name}: core didn't come up") }
        val outbounds = try {
            LinkParser.parse(server.link).outbounds
        } catch (e: Exception) {
            AppLog.debug("probe", "${server.name}: bad config: ${e.message}")
            return Outcome(null, false)
        }
        return probeWith(server, outbounds, s, timeoutMs)
    }

    private suspend fun probeWith(server: Server, outbounds: List<JsonObject>, s: AppSettings, timeoutMs: Long): Outcome {
        val config = XrayConfig.probe(outbounds, s, xrayLogLevel(s), xrayAccessLog(s))
        val started = nowMs()
        // The timeout is for the network: starting the throwaway core gets its own allowance.
        // (The measured delay itself never includes the start: the core times only the requests.)
        val r = native(timeoutMs + CORE_START_ALLOWANCE_MS) {
            CoreEnv.ensure()
            try {
                CoreEnv.xray.measureOutbound(config, s.testUrl).let { if (it > 0) Result.success(it) else Result.failure(Exception("delay $it")) }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        val ms = r?.getOrNull()?.toInt()
        val outcome = when {
            r == null -> "timeout ${timeoutMs} ms"
            ms != null -> "$ms ms (whole probe ${nowMs() - started} ms)"
            else -> "failed after ${nowMs() - started} ms: ${r.exceptionOrNull()?.message?.lines()?.firstOrNull()}"
        }
        AppLog.debug("probe", "${server.name} [${server.protocol}]: $outcome")
        return Outcome(ms, r == null)
    }
}

/**
 * Limited-network servers' cores, as the engine and the probes use them. Android runs the native core as a
 * subprocess (`Limited`), the Mac's service starts it sandboxed (`bridge/MacLimited`); without a [host]
 * no such server is ever reached.
 */
object LimitedCore {
    interface Host {
        val available: Boolean

        /** CPU of every running core process so far (they are outside the app's own CPU count). */
        val cpuTotalMs: Long
        suspend fun connect(server: Server): List<JsonObject>?
        fun disconnect()
        fun setRate(rateBps: Long)
        fun repace()
        suspend fun <T> withOutbounds(server: Server, use: suspend (List<JsonObject>) -> T): T?

        /** Why the last start failed, in the UI language. */
        fun failureText(): String
    }

    var host: Host? = null

    val available: Boolean get() = host?.available == true
    val cpuTotalMs: Long get() = host?.cpuTotalMs ?: 0
    suspend fun connect(server: Server): List<JsonObject>? = host?.connect(server)
    fun disconnect() { host?.disconnect() }
    fun setRate(rateBps: Long) { host?.setRate(rateBps) }
    fun repace() { host?.repace() }
    suspend fun <T> withOutbounds(server: Server, use: suspend (List<JsonObject>) -> T): T? = host?.withOutbounds(server, use)
    /** Why the last start failed; without a host (the core isn't in this build), why it can't run at all. */
    fun failureText(): String = host?.failureText() ?: app.borderless.platform.text("limited_unsupported")
}

/**
 * "On a limited network, try directly first": the local dialer the core sends the zones' traffic to (Android runs
 * it as `liblimited.so direct-first`, `DirectFirst`; the Mac's as `borderless-limited direct-first`,
 * `bridge/MacDirectFirst`; without a [host] the feature stays off).
 */
object DirectFirstCore {
    interface Host {
        /** The running dialer's ports and credentials (started if needed), or null if it can't run. */
        suspend fun ensure(): XrayConfig.DirectFirst?
        suspend fun stop()

        /** Development builds: treat the network as limited (a test file is present). */
        fun forcedForTest(): Boolean

        /** Called when a dialer that ran for a while ends by itself. */
        var onEnded: (() -> Unit)?
    }

    var host: Host? = null

    suspend fun ensure(): XrayConfig.DirectFirst? = host?.ensure()
    suspend fun stop() { host?.stop() }
    fun forcedForTest(): Boolean = host?.forcedForTest() == true
    var onEnded: (() -> Unit)?
        get() = host?.onEnded
        set(value) { host?.onEnded = value }
}
