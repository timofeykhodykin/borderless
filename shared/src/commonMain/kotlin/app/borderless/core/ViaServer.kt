package app.borderless.core

import app.borderless.config.LinkParser
import app.borderless.config.XrayConfig
import app.borderless.data.AppLog
import app.borderless.data.Repo
import app.borderless.data.Server
import app.borderless.net.Net
import app.borderless.net.NetError
import app.borderless.net.Socks
import app.borderless.platform.base64UrlEncode
import app.borderless.platform.nowMs
import app.borderless.platform.secureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Lets the app itself go online through one of the user's servers, for what it can't reach directly
 * (our app always works outside the tunnel, so it reaches what the phone reaches without it). A throwaway
 * Xray instance with only that server and a local SOCKS5 proxy (127.0.0.1, random port, random
 * credentials for this one use) runs for the duration of [use] and is stopped right after.
 * SOCKS, not HTTP: Android's HTTP client sends a proxy CONNECT without credentials first and retries on
 * the connection Xray has already closed after its 407; SOCKS5 authenticates within the handshake. The platform's
 * [Net] host speaks it ([Socks]).
 */
object ViaServer {
    private const val TAG = "ViaServer"

    /**
     * Servers worth trying, best first: the one in use now, then the ones that answered recently
     * (fastest first); with [prefer] (a subscription id) that subscription's good servers before all. At most [limit].
     */
    fun candidates(limit: Int = 3, prefer: String? = null): List<Server> {
        val pings = Repo.pings.value
        val now = nowMs()
        val current = TunnelState.status.value.takeIf { it.phase == Phase.CONNECTED }?.serverId?.let(Repo::server)
        val good = Repo.activeServers
            .mapNotNull { s -> pings[s.id]?.takeIf { it.ok && now - it.lastOkAt < 6 * 3_600_000L }?.let { s to it.ms!! } }
            .sortedBy { it.second }.map { it.first }
        // A subscription's own servers first: its provider already knows the phone fetches it (SECURITY.md S10).
        val own = good.filter { prefer != null && it.subscriptionId == prefer }
        return (own + listOfNotNull(current) + good).distinctBy { it.id }.take(limit)
    }

    /** Runs [use] with a local SOCKS5 proxy through [server] ([Socks]: pass it to [Net] requests). */
    suspend fun <T> withProxy(server: Server, use: suspend (proxy: Socks) -> T): T = withContext(Dispatchers.IO) {
        val port = Net.host.freePort()
        val socks = Socks(port, token(), token())
        val s = Repo.settings.value
        val config = XrayConfig.localProxy(LinkParser.parse(server.link).outbounds, s, port, socks.user, socks.pass, xrayLogLevel(s), xrayAccessLog(s))
        val controller = CoreEnv.xray.controller { }
        CoreEnv.ensure()
        // No TUN for this instance: it only listens on localhost.
        CoreEnv.startLock.withLock { controller.start(config, 0) }
        AppLog.debug(TAG, "local proxy through ${server.name} on 127.0.0.1:$port")
        try {
            // The port was free a moment ago; make sure it is our instance that took it, not another app.
            if (!ours(socks)) throw NetError(NetError.Kind.OTHER, "local port $port is not our proxy")
            use(socks)
        } finally {
            runCatching { controller.stop() }
        }
    }

    /**
     * Whether 127.0.0.1:[port] is our instance (SECURITY.md S17): it refuses a wrong password and accepts ours, asked
     * in a random order — a stranger that took the port can't pass both without knowing the password.
     */
    private fun ours(socks: Socks): Boolean {
        val right = { Net.host.socksAccepts(socks.port, socks.user, socks.pass) }
        val wrong = { !Net.host.socksAccepts(socks.port, socks.user, token()) }
        return if (secureRandom(1)[0] >= 0) right() && wrong() else wrong() && right()
    }

    private fun token(): String = base64UrlEncode(secureRandom(18))
}
