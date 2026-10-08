package app.borderless.core

import android.content.Context
import android.util.Base64
import android.util.Log
import app.borderless.config.LinkParser
import app.borderless.config.XrayConfig
import app.borderless.data.AppLog
import app.borderless.data.AppSettings
import app.borderless.data.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

private const val TAG = "Core"

/**
 * Native calls into Xray block their thread and cannot be interrupted, so they run on a cached
 * pool and callers wait with a timeout. A timed-out probe keeps its thread until Go gives up.
 */
private val nativePool = Executors.newCachedThreadPool { r -> Thread(r, "xray-native").apply { isDaemon = true } }

private suspend fun <T> native(timeoutMs: Long, block: () -> T): T? {
    val f = CompletableFuture.supplyAsync({ block() }, nativePool)
    return withTimeoutOrNull(timeoutMs) { f.await() }
}

object CoreEnv {
    @Volatile
    private var ready = false
    private lateinit var appContext: Context

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /** Initialises the core on first use (loads the native library, unpacks geo files). */
    fun ensure() {
        if (!ready) init(appContext)
    }

    /** Copies geo databases out of the APK and points Xray at them. Safe to call repeatedly. */
    @Synchronized
    fun init(context: Context) {
        if (ready) return
        appContext = context.applicationContext
        val dir = File(context.filesDir, "geo").apply { mkdirs() }
        val stamp = File(dir, ".version")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        // The APK's lists after an install or update, unless newer ones were downloaded meanwhile (GeoLists).
        val changed = stamp.takeIf { it.exists() }?.readText() != version
        GeoLists.install(context, dir, changed)
        if (changed) stamp.writeText(version)
        val prefs = context.getSharedPreferences("core", Context.MODE_PRIVATE)
        val key = prefs.getString("xudp", null) ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
            .let { Base64.encodeToString(it, Base64.NO_PADDING or Base64.URL_SAFE or Base64.NO_WRAP) }
            .also { prefs.edit().putString("xudp", it).apply() }
        Libv2ray.initCoreEnv(dir.absolutePath, key)
        AppLog.captureStdout()
        ready = true
        AppLog.debug("core", "Xray ${Libv2ray.checkVersionX()}")
    }

    fun version(): String = runCatching { Libv2ray.checkVersionX() }.getOrDefault("?")

    /**
     * Held while any core instance starts: libv2ray hands the TUN descriptor to the starting instance
     * through process-wide state, so two instances must not start at the same moment.
     */
    val startLock = Mutex()
}

/** One running Xray instance bound to the tunnel's TUN file descriptor. */
class CoreRunner {
    private val lock = Mutex()
    private val controller: CoreController = Libv2ray.newCoreController(object : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(p0: Long, p1: String?): Long {
            if (!p1.isNullOrBlank()) AppLog.debug("core", p1)
            return 0
        }
    })

    val isRunning: Boolean get() = runCatching { controller.isRunning }.getOrDefault(false)

    suspend fun start(config: String, tunFd: Int) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (controller.isRunning) runCatching { controller.stopLoop() }
            CoreEnv.startLock.withLock { controller.startLoop(config, tunFd) }
            check(controller.isRunning) { "core did not start" }
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (controller.isRunning) runCatching { controller.stopLoop() }.onFailure { Log.w(TAG, "stop failed", it) }
        }
    }

    /** Bytes per outbound tag since the last call: tag -> (uplink, downlink). Counters reset on read. */
    fun takeTraffic(): Map<String, Pair<Long, Long>> {
        if (!isRunning) return emptyMap()
        val raw = runCatching { controller.queryAllOutboundTrafficStats() }.getOrNull().orEmpty()
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

    suspend fun probeDetailed(server: Server, s: AppSettings, timeoutMs: Long): Outcome {
        val config = try {
            XrayConfig.probe(LinkParser.parse(server.link).outbounds, s, xrayLogLevel(s), xrayAccessLog(s))
        } catch (e: Exception) {
            AppLog.debug("probe", "${server.name}: bad config: ${e.message}")
            return Outcome(null, false)
        }
        val started = System.currentTimeMillis()
        // The timeout is for the network: starting the throwaway core gets its own allowance.
        // (The measured delay itself never includes the start: libv2ray times only the requests.)
        val r = native(timeoutMs + CORE_START_ALLOWANCE_MS) {
            CoreEnv.ensure()
            try {
                Libv2ray.measureOutboundDelay(config, s.testUrl).let { if (it > 0) Result.success(it) else Result.failure(Exception("delay $it")) }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        val ms = r?.getOrNull()?.toInt()
        val outcome = when {
            r == null -> "timeout ${timeoutMs} ms"
            ms != null -> "$ms ms (whole probe ${System.currentTimeMillis() - started} ms)"
            else -> "failed after ${System.currentTimeMillis() - started} ms: ${r.exceptionOrNull()?.message?.lines()?.firstOrNull()}"
        }
        AppLog.debug("probe", "${server.name} [${server.protocol}]: $outcome")
        return Outcome(ms, r == null)
    }
}
