package app.borderless.core

import app.borderless.config.LinkParser
import app.borderless.config.XrayConfig
import app.borderless.data.AppLog
import app.borderless.data.Repo
import app.borderless.data.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.Libv2ray
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Lets the app itself go online through one of the user's servers, for what it can't reach directly
 * (our app always works outside the tunnel, so it reaches what the phone reaches without it). A throwaway
 * Xray instance with only that server and a local SOCKS5 proxy (127.0.0.1, random port, random
 * credentials for this one use) runs for the duration of [use] and is stopped right after.
 * SOCKS, not HTTP: Android's HTTP client sends a proxy CONNECT without credentials first and retries on
 * the connection Xray has already closed after its 407; SOCKS5 authenticates within the handshake.
 */
object ViaServer {
    private const val TAG = "ViaServer"

    /** Credentials of the proxies running now, by port (answered only for 127.0.0.1). */
    private val credentials = ConcurrentHashMap<Int, PasswordAuthentication>()

    init {
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? =
                // SOCKS asks without a requestor type: match by our local address and port only.
                if (requestingHost in setOf("127.0.0.1", "localhost")) credentials[requestingPort] else null
        })
    }

    /**
     * Servers worth trying, best first: the one in use now, then the ones that answered recently
     * (fastest first). At most [limit].
     */
    fun candidates(limit: Int = 3): List<Server> {
        val pings = Repo.pings.value
        val now = System.currentTimeMillis()
        val current = TunnelState.status.value.takeIf { it.phase == Phase.CONNECTED }?.serverId?.let(Repo::server)
        val good = Repo.activeServers
            .mapNotNull { s -> pings[s.id]?.takeIf { it.ok && now - it.lastOkAt < 6 * 3_600_000L }?.let { s to it.ms!! } }
            .sortedBy { it.second }.map { it.first }
        return (listOfNotNull(current) + good).distinctBy { it.id }.take(limit)
    }

    /** Runs [use] with a [Proxy] through [server] (its credentials are answered by the default Authenticator). */
    suspend fun <T> withProxy(server: Server, use: suspend (proxy: Proxy) -> T): T = withContext(Dispatchers.IO) {
        val port = ServerSocket(0).use { it.localPort }
        val user = token()
        val pass = token()
        val s = Repo.settings.value
        val config = XrayConfig.localProxy(LinkParser.parse(server.link).outbounds, s, port, user, pass, xrayLogLevel(s), xrayAccessLog(s))
        val controller = Libv2ray.newCoreController(object : CoreCallbackHandler {
            override fun startup(): Long = 0
            override fun shutdown(): Long = 0
            override fun onEmitStatus(p0: Long, p1: String?): Long = 0
        })
        CoreEnv.ensure()
        // No TUN for this instance: it only listens on localhost.
        CoreEnv.startLock.withLock { controller.startLoop(config, 0) }
        credentials[port] = PasswordAuthentication(user, pass.toCharArray())
        AppLog.debug(TAG, "local proxy through ${server.name} on 127.0.0.1:$port")
        try {
            use(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        } finally {
            credentials.remove(port)
            runCatching { controller.stopLoop() }
        }
    }

    private fun token(): String {
        val b = ByteArray(18).also { SecureRandom().nextBytes(it) }
        return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
    }
}
