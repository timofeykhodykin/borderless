package app.borderless.core

import app.borderless.BuildConfig
import app.borderless.config.XrayConfig
import app.borderless.data.AppLog
import app.borderless.data.Errors
import app.borderless.data.Repo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.ServerSocket
import java.security.SecureRandom

/**
 * "Directly first" on a limited network ([app.borderless.data.AppSettings.directFirst]). On such a network the zones'
 * own sites usually open directly but not all of them, and some only open directly, not through a server. So while
 * the network is limited (and routing is on), the TCP connections the zones' lists would let go without the tunnel
 * are handed to a local dialer — `liblimited.so direct-first`, package `native/limited/directfirst` — that opens
 * each one directly from this app's process (outside the tunnel) and, when it doesn't connect or a TLS handshake gets
 * no answer in time, replays it through a SOCKS5 inbound of the running core that goes to the server
 * ([XrayConfig.DirectFirst]). A host that needed the server goes there straight away for 10 minutes.
 *
 * Both local ports take random credentials per run, so other apps can't use either way (SECURITY.md). The zones'
 * apps are put into the tunnel meanwhile (TunnelService), so their connections take the same way.
 */
object DirectFirst {
    private const val TAG = "DirectFirst"
    private const val EVENT_PREFIX = "__LIMITED_EVENT__|"
    private const val START_TIMEOUT_MS = 5_000L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + Errors.handler)
    private val lock = Mutex()
    private val random = SecureRandom()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var endpoint: XrayConfig.DirectFirst? = null

    /**
     * Called when a dialer that ran for a while ends by itself: the core's config still points at its port, so the
     * engine restarts the core (which starts a new dialer). One that dies at once isn't restarted in a loop.
     */
    @Volatile
    var onEnded: (() -> Unit)? = null
    private const val RESTART_AFTER_MS = 30_000L

    /** The running dialer's ports and credentials (started if needed), or null if it can't run. */
    suspend fun ensure(): XrayConfig.DirectFirst? = lock.withLock {
        endpoint?.takeIf { process?.isAlive == true }?.let { return@withLock it }
        stopLocked()
        val binary = Limited.binaryPath?.takeIf { File(it).exists() } ?: return@withLock null
        val backPort = freePort() ?: return@withLock null
        val user = token(); val pass = token(); val backUser = token(); val backPass = token()
        val verbose = Repo.settings.value.verboseLog
        val cmd = listOf(
            binary, "direct-first", "-listen", "127.0.0.1:0", "-user", user, "-pass", pass,
            "-back", "127.0.0.1:$backPort", "-back-user", backUser, "-back-pass", backPass, "-verbose=$verbose",
        )
        val p = try {
            ProcessBuilder(cmd).redirectErrorStream(true).start()
        } catch (e: Exception) {
            AppLog.debug(TAG, "dialer failed to start", e)
            return@withLock null
        }
        val port = CompletableDeferred<Int?>()
        val startedAt = System.currentTimeMillis()
        scope.launch { read(p, port, startedAt) }
        val got = withTimeoutOrNull(START_TIMEOUT_MS) { port.await() }
        if (got == null) {
            AppLog.debug(TAG, "dialer gave no port")
            p.destroy()
            return@withLock null
        }
        process = p
        AppLog.debug(TAG, "dialer on 127.0.0.1:$got, way back through the server on 127.0.0.1:$backPort")
        XrayConfig.DirectFirst(got, user, pass, backPort, backUser, backPass).also { endpoint = it }
    }

    /**
     * Debug builds only: `files/limited.test` makes "directly first" treat the network as limited. On the emulator a
     * limited network can't be staged reliably (its DNS hands out changing fake addresses), so the fallback through
     * the server is tested this way with only one zone site's addresses cut off by iptables.
     */
    fun forcedForTest(): Boolean = BuildConfig.DEBUG && Limited.filesDir?.let { File(it, "limited.test").exists() } == true

    /** Stops the dialer (the network is no longer limited, or the tunnel stops). */
    suspend fun stop() = lock.withLock { stopLocked() }

    private fun stopLocked() {
        val p = process ?: return
        process = null
        endpoint = null
        runCatching { p.outputStream.close() }
        runCatching { p.destroy() }
        AppLog.debug(TAG, "dialer stopped")
    }

    private fun read(p: Process, port: CompletableDeferred<Int?>, startedAt: Long) {
        try {
            p.inputStream.bufferedReader().forEachLine { line ->
                if (!line.startsWith(EVENT_PREFIX)) {
                    // Only with the detailed ("verbose") log: a line per host moved to the server.
                    AppLog.debug(TAG, line)
                    return@forEachLine
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
        } catch (_: java.io.IOException) {
            // The stream closes under the reader when the dialer is stopped: an ordinary end, not a failure.
        } finally {
            port.complete(null)
            if (process === p) {
                process = null
                endpoint = null
                AppLog.debug(TAG, "dialer ended (code ${runCatching { p.waitFor() }.getOrNull()})")
                if (System.currentTimeMillis() - startedAt > RESTART_AFTER_MS) onEnded?.invoke()
            }
        }
    }

    /** A port free right now for the core's inbound (taken a moment later by the core). */
    private fun freePort(): Int? = runCatching { ServerSocket(0).use { it.localPort } }.getOrNull()

    private fun token(): String = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }
}
