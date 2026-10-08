package app.borderless.data

import app.borderless.config.LinkParser
import app.borderless.config.Stream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * How much energy the core spends on a server's traffic, by its protocol stack ("class": protocol +
 * Vision + transport + chain, e.g. "VLESS Vision · TCP"). Two sources:
 *
 * - a starting estimate ([prior]): relative CPU work per byte. Cheapest is VLESS with XTLS Vision
 *   (inner TLS passes without a second encryption); every extra layer costs: VMess's own encryption,
 *   HTTP framing of WS / gRPC / XHTTP (gRPC and QUIC also send keepalive pings), userspace QUIC
 *   (Hysteria2) and WireGuard work per packet, mKCP retransmits aggressively, each chain hop encrypts again;
 * - this phone's own measurements: CPU time of the core per MB of proxied traffic, per class, taken in
 *   5-minute windows with one server and the app's screens closed ([StatsDb.coreCost]). With enough
 *   traffic of a class, its measured cost replaces the estimate (see [factors]).
 *
 * The battery saving mode prefers cheaper classes among working servers ([adjusted]).
 */
object ProtocolCost {
    /** [key] is stored in the stats DB (`core_cost.class`): keep existing keys unchanged (data compatibility). */
    data class Proto(val key: String, val prior: Double)

    /** Pure (unit-tested): the class of a server's outbounds (first = the proxy, the rest = its chain). */
    fun classify(outbounds: List<JsonObject>): Proto {
        val main = outbounds.firstOrNull() ?: return Proto("?", 1.3)
        val protocol = (main["protocol"] as? JsonPrimitive)?.contentOrNull ?: "?"
        val stream = main["streamSettings"] as? JsonObject
        val net = (stream?.get("network") as? JsonPrimitive)?.contentOrNull ?: "tcp"
        val text = main.toString()
        val vision = "xtls-rprx-vision" in text
        var cost = when (protocol) {
            "vless" -> if (vision) 1.0 else 1.05
            "trojan" -> 1.05
            "shadowsocks" -> if ("chacha" in text) 1.15 else 1.05
            "vmess" -> 1.25
            "socks", "http" -> 1.0
            "hysteria" -> 1.5
            "wireguard" -> 1.35
            else -> 1.3
        }
        cost += when (net) {
            "tcp", "raw", "hysteria" -> 0.0
            "httpupgrade" -> 0.08
            "ws" -> 0.1
            "h2", "http" -> 0.2
            "grpc", "xhttp", "splithttp" -> 0.25
            "kcp" -> 0.6
            else -> 0.15
        }
        // Each hop of a chain encrypts the traffic once more.
        cost += 0.4 * (outbounds.size - 1)
        val name = when (protocol) {
            "vless" -> if (vision) "VLESS Vision" else "VLESS"
            "shadowsocks" -> "Shadowsocks"
            "trojan" -> "Trojan"
            "vmess" -> "VMess"
            "socks" -> "SOCKS"
            "hysteria" -> "Hysteria2"
            "wireguard" -> "WireGuard"
            else -> protocol.uppercase()
        }
        val transport = if (protocol == "hysteria" || protocol == "wireguard") null else Stream.describe(net, "")
        val key = listOfNotNull(name, transport, "chain".takeIf { outbounds.size > 1 }).joinToString(" · ")
        return Proto(key, cost)
    }

    /** Measured traffic of one class: bytes and the core's CPU time for them. */
    data class Measured(val bytes: Long, val cpuMs: Long) {
        val mb get() = bytes / 1_048_576.0
        val cpuMsPerMb get() = if (mb > 0) cpuMs / mb else 0.0
    }

    /** Traffic of a class needed before its measurement counts fully. */
    const val TRUST_MB = 100.0

    /** Below this a class's measurement is ignored (too noisy). */
    const val MIN_MB = 10.0

    /**
     * Pure (unit-tested): the cost factor per class. The estimates are put on the measured scale by
     * one common factor (fit over all measured classes); a measured class then moves from its estimate
     * towards its measurement as its traffic grows to [TRUST_MB].
     */
    fun factors(priors: Map<String, Double>, measured: Map<String, Measured>): Map<String, Double> {
        val usable = measured.filter { (k, m) -> k in priors && m.mb >= MIN_MB }
        if (usable.isEmpty()) return priors
        // CPU ms per MB that a class with estimate 1.0 would cost on this phone.
        val scale = usable.values.sumOf { it.cpuMs.toDouble() } / usable.entries.sumOf { (k, m) -> priors.getValue(k) * m.mb }
        if (scale <= 0) return priors
        return priors.mapValues { (k, prior) ->
            val m = usable[k] ?: return@mapValues prior
            val trust = (m.mb / TRUST_MB).coerceIn(0.0, 1.0)
            prior * (1 - trust) + (m.cpuMsPerMb / scale) * trust
        }
    }

    /**
     * Latency weighted by the energy cost, for choosing among working servers in the battery saving
     * mode: [ms] × factor relative to the cheapest class. A working, reasonably fast connection comes
     * first: a server more than 60 % (and 150 ms) slower than the fastest answer is never preferred.
     */
    fun adjusted(ms: Int, factor: Double, cheapest: Double, fastestMs: Int): Double {
        val limit = maxOf(fastestMs * 1.6, fastestMs + 150.0)
        if (ms > limit) return Double.MAX_VALUE / 2 + ms
        return ms * (factor / cheapest.coerceAtLeast(0.01))
    }

    // ------------------------------------------------------------------ runtime

    private val classes = ConcurrentHashMap<String, Proto>()

    @Volatile
    private var learned: Map<String, Double> = emptyMap()

    @Volatile
    var measured: Map<String, Measured> = emptyMap()
        private set

    /** The class of [server] (parsed once per link). */
    fun of(server: Server): Proto = classes.getOrPut(server.id + "#" + server.link.hashCode()) {
        runCatching { classify(LinkParser.parse(server.link).outbounds) }.getOrDefault(Proto(server.protocol, 1.3))
    }

    /** The cost factor of [server]: measured on this phone if its class has enough traffic, else the estimate. */
    fun factor(server: Server): Double = of(server).let { p -> learned[p.key] ?: p.prior }

    /** Reloads the measurements (last 30 days) and recomputes the factors. */
    suspend fun refresh() {
        val now = System.currentTimeMillis()
        measured = StatsDb.coreCostByClass(now - 30 * 86_400_000L, now)
        val priors = Repo.servers.value.map(::of).associate { it.key to it.prior }
        learned = factors(priors, measured)
    }

    /** Readable factor for logs. */
    fun describe(server: Server): String = of(server).let { "${it.key}, cost ×%.2f".format(factor(server)) }
}
