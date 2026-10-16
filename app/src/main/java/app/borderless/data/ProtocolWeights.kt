package app.borderless.data

import app.borderless.config.LinkParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Weights a server's latency by how its configuration behaves, not by how its servers happen to answer (that is
 * each server's own statistics). Two sets, each read from the proxy's outbound:
 *
 * - **stability** (the Stable strategy): latency × factor + ms for traits that tend to break a working
 *   connection later — one long upload stream ([SingleStream]), UDP transports (often slowed or cut on the way),
 *   everything in one TCP connection (gRPC, mux: one lost packet holds up every connection), unwrapped
 *   Shadowsocks, TLS without a browser fingerprint;
 * - **energy** (the battery saving mode, on top of [ProtocolCost]'s CPU factor): traits that wake the radio while
 *   the phone is idle or multiply traffic — QUIC keepalives (Hysteria2), gRPC health checks, WireGuard
 *   keepalives, mKCP, XHTTP's many short upload requests.
 *
 * Factors multiply, capped at [MAX_FACTOR]; added milliseconds sum up to [MAX_EXTRA_MS].
 */
object ProtocolWeights {
    enum class Trait(val factor: Double, val extraMs: Int = 0) {
        // stability
        SINGLE_STREAM(SingleStream.FACTOR, SingleStream.EXTRA_MS),
        UDP(1.3, 80),
        ONE_CONNECTION(1.2, 50),
        PLAIN_SHADOWSOCKS(1.2),
        NO_FINGERPRINT(1.1, 30),

        // energy
        QUIC_KEEPALIVE(1.4),
        GRPC_HEALTH_CHECK(1.3),
        WIREGUARD_KEEPALIVE(1.3),
        KCP(1.6),
        MANY_REQUESTS(1.15),
    }

    private val STABILITY = setOf(Trait.SINGLE_STREAM, Trait.UDP, Trait.ONE_CONNECTION, Trait.PLAIN_SHADOWSOCKS, Trait.NO_FINGERPRINT)

    const val MAX_FACTOR = 2.0
    const val MAX_EXTRA_MS = 200

    data class Weight(val factor: Double, val extraMs: Int, val traits: Set<Trait>) {
        fun apply(ms: Double) = ms * factor + extraMs
    }

    private fun weight(traits: Set<Trait>) = Weight(
        traits.fold(1.0) { f, t -> f * t.factor }.coerceAtMost(MAX_FACTOR),
        traits.sumOf { it.extraMs }.coerceAtMost(MAX_EXTRA_MS),
        traits,
    )

    /** Pure (unit-tested): the traits of a server's outbounds (first = the proxy). */
    fun traits(outbounds: List<JsonObject>): Set<Trait> {
        val main = outbounds.firstOrNull() ?: return emptySet()
        val protocol = main.str("protocol")?.lowercase()
        val stream = main["streamSettings"] as? JsonObject
        val net = stream?.str("network")?.lowercase() ?: "tcp"
        val security = stream?.str("security")?.lowercase()
        val tls = (stream?.get("tlsSettings") ?: stream?.get("realitySettings")) as? JsonObject
        val alpn = (tls?.get("alpn") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }.orEmpty()
        val out = HashSet<Trait>()
        val xhttp = net == "xhttp" || net == "splithttp"
        if (SingleStream.detect(outbounds)) out += Trait.SINGLE_STREAM
        else if (xhttp) out += Trait.MANY_REQUESTS
        if (protocol == "hysteria" || protocol == "wireguard" || net == "kcp" || net == "hysteria" || (xhttp && alpn == listOf("h3"))) out += Trait.UDP
        val mux = (main["mux"] as? JsonObject)?.let { (it["enabled"] as? JsonPrimitive)?.booleanOrNull } == true
        if (net == "grpc" || mux) out += Trait.ONE_CONNECTION
        if (protocol == "shadowsocks" && (security == null || security == "none")) out += Trait.PLAIN_SHADOWSOCKS
        if ((security == "tls" || security == "reality") && protocol != "hysteria" && tls?.str("fingerprint").isNullOrEmpty()) out += Trait.NO_FINGERPRINT
        if (protocol == "hysteria" || net == "hysteria") out += Trait.QUIC_KEEPALIVE
        if (net == "grpc") {
            val grpc = stream?.get("grpcSettings") as? JsonObject
            if (((grpc?.get("idle_timeout") as? JsonPrimitive)?.intOrNull ?: 0) > 0) out += Trait.GRPC_HEALTH_CHECK
        }
        if (protocol == "wireguard") {
            val peers = (main["settings"] as? JsonObject)?.get("peers") as? JsonArray
            if (peers.orEmpty().any { (((it as? JsonObject)?.get("keepAlive") as? JsonPrimitive)?.intOrNull ?: 0) > 0 }) out += Trait.WIREGUARD_KEEPALIVE
        }
        if (net == "kcp") out += Trait.KCP
        return out
    }

    /** Pure: the Stable strategy's weight for these traits. */
    fun stability(traits: Set<Trait>): Weight = weight(traits intersect STABILITY)

    /** Pure: the battery saving mode's extra energy factor for these traits (radio wake-ups, extra traffic). */
    fun energy(traits: Set<Trait>): Double = weight(traits - STABILITY).factor

    private val cache = ConcurrentHashMap<String, Set<Trait>>()

    /** [traits] of [server] (parsed once per link). */
    fun of(server: Server): Set<Trait> = cache.getOrPut(server.id + "#" + server.link.hashCode()) {
        runCatching { traits(LinkParser.parse(server.link).outbounds) }.getOrDefault(emptySet())
    }

    /** Readable traits for logs. */
    fun describe(server: Server): String = of(server).joinToString { it.name.lowercase().replace('_', ' ') }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
