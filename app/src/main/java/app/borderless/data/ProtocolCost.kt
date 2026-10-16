package app.borderless.data

import app.borderless.config.LinkParser
import app.borderless.config.Stream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log2

/**
 * How much energy the core spends on a server's traffic, by its protocol stack ("class": protocol +
 * Vision + transport + chain, e.g. "VLESS Vision · TCP"). Two sources:
 *
 * - a starting estimate ([prior]): relative CPU work per byte. Cheapest is VLESS with XTLS Vision
 *   (inner TLS passes without a second encryption); every extra layer costs: VMess's own encryption,
 *   HTTP framing of WS / gRPC / XHTTP (gRPC and QUIC also send keepalive pings), userspace QUIC
 *   (Hysteria2) and WireGuard work per packet, mKCP retransmits aggressively, each chain hop encrypts again;
 * - this phone's own measurements: CPU time of the core in 5-minute windows with one server and the app's
 *   screens closed ([StatsDb.coreCost]), compared between classes at the same traffic ([relative]). With
 *   enough windows of a class, its measured cost replaces the estimate (see [factors]).
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

    /** Measured traffic of one class: bytes, the core's CPU time for them, and in how many windows. */
    data class Measured(val bytes: Long, val cpuMs: Long, val windows: Int = 0) {
        val mb get() = bytes / 1_048_576.0
    }

    /** One measured window (about 5 minutes with one server, screens closed): class, traffic, core CPU time. */
    data class Window(val cls: String, val bytes: Long, val cpuMs: Long)

    /** Windows of a class needed before its measurement counts fully. */
    const val TRUST_WINDOWS = 100

    /** Below this a class's measurement is ignored (too noisy). */
    const val MIN_WINDOWS = 12

    /** Traffic band of a window: under ¼ MB, then powers of two up to 64 MB and more. */
    fun band(bytes: Long): Int {
        val mb = bytes / 1_048_576.0
        return if (mb < 0.25) 0 else (log2(mb / 0.25).toInt() + 1).coerceAtMost(9)
    }

    /**
     * Pure (unit-tested): each class's core CPU relative to all classes **at the same traffic** (1.0 = average),
     * with the number of windows behind it. The core's CPU is far from proportional to bytes (phone export
     * 2026-10-14: ~3 s per window under ½ MB, 25–35 s for anything over 5 MB, 100 MB no more than 7 MB), so CPU per
     * GB mostly showed how much a class's windows happened to carry. Each window is compared with the mean of all
     * windows in its traffic band; bands where only one class was measured tell nothing and are skipped.
     */
    fun relative(windows: List<Window>): Map<String, Pair<Double, Int>> {
        val observed = HashMap<String, Double>()
        val expected = HashMap<String, Double>()
        val count = HashMap<String, Int>()
        windows.groupBy { band(it.bytes) }.values.filter { w -> w.distinctBy { it.cls }.size >= 2 }.forEach { w ->
            val mean = w.sumOf { it.cpuMs.toDouble() } / w.size
            w.forEach {
                observed.merge(it.cls, it.cpuMs.toDouble(), Double::plus)
                expected.merge(it.cls, mean, Double::plus)
                count.merge(it.cls, 1, Int::plus)
            }
        }
        return observed.keys.filter { expected.getValue(it) > 0 }.associateWith { observed.getValue(it) / expected.getValue(it) to count.getValue(it) }
    }

    /**
     * Pure (unit-tested): the cost factor per class. A measured class ([relative]) is put on the estimates' scale
     * (1.0 = the window-weighted mean estimate of the measured classes) and moves from its estimate towards its
     * measurement as its windows grow to [TRUST_WINDOWS].
     */
    fun factors(priors: Map<String, Double>, windows: List<Window>): Map<String, Double> {
        val rel = relative(windows).filter { (k, v) -> k in priors && v.second >= MIN_WINDOWS }
        if (rel.isEmpty()) return priors
        val scale = rel.entries.sumOf { (k, v) -> priors.getValue(k) * v.second } / rel.values.sumOf { it.second }
        return priors.mapValues { (k, prior) ->
            val (r, n) = rel[k] ?: return@mapValues prior
            val trust = (n.toDouble() / TRUST_WINDOWS).coerceIn(0.0, 1.0)
            prior * (1 - trust) + r * scale * trust
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

    /** The class of [server] (parsed once per link). */
    fun of(server: Server): Proto = classes.getOrPut(server.id + "#" + server.link.hashCode()) {
        runCatching { classify(LinkParser.parse(server.link).outbounds) }.getOrDefault(Proto(server.protocol, 1.3))
    }

    /**
     * The cost factor of [server]: its class's CPU factor (measured on this phone if its class has enough
     * windows, else the estimate) × what its configuration adds in radio wake-ups ([ProtocolWeights.energy]).
     */
    fun factor(server: Server): Double = of(server).let { p -> (learned[p.key] ?: p.prior) * ProtocolWeights.energy(ProtocolWeights.of(server)) }

    /** Reloads the measurements (last 30 days) and recomputes the factors. */
    suspend fun refresh() {
        val now = System.currentTimeMillis()
        val priors = Repo.servers.value.map(::of).associate { it.key to it.prior }
        learned = factors(priors, StatsDb.coreCostWindows(now - 30 * 86_400_000L, now))
    }

    /** Readable factor for logs. */
    fun describe(server: Server): String = of(server).let { "${it.key}, cost ×%.2f".format(factor(server)) }
}
