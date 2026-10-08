package app.borderless.data

import app.borderless.config.LinkParser
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What the app supports, and how many of the user's configs use each thing (the "Supported" list in
 * Settings). Feature keys: `p:` protocol, `t:` transport, `s:` security, `f:` format.
 */
object SupportStats {
    val PROTOCOLS = listOf("vless" to "VLESS", "vmess" to "VMess", "trojan" to "Trojan", "shadowsocks" to "Shadowsocks",
        "hysteria" to "Hysteria2", "wireguard" to "WireGuard", "socks" to "SOCKS")
    val TRANSPORTS = listOf("tcp" to "TCP", "ws" to "WebSocket", "grpc" to "gRPC", "xhttp" to "XHTTP",
        "httpupgrade" to "HTTPUpgrade", "h2" to "HTTP/2", "kcp" to "mKCP")
    val SECURITY = listOf("reality" to "Reality", "tls" to "TLS", "vision" to "XTLS Vision")

    /** Pure (unit-tested): the features of one config ([link] as stored, [outbounds] parsed from it). */
    fun features(link: String, outbounds: List<JsonObject>): Set<String> {
        val main = outbounds.firstOrNull() ?: return emptySet()
        val protocol = (main["protocol"] as? JsonPrimitive)?.contentOrNull ?: return emptySet()
        val stream = main["streamSettings"] as? JsonObject
        val out = HashSet<String>()
        out += "p:$protocol"
        out += if (link.trimStart().startsWith("{")) "f:json" else "f:link"
        if (outbounds.size > 1) out += "f:chain"
        if (protocol != "hysteria" && protocol != "wireguard") {
            val net = when (val n = (stream?.get("network") as? JsonPrimitive)?.contentOrNull ?: "tcp") {
                "raw" -> "tcp"
                "splithttp" -> "xhttp"
                "http" -> "h2"
                else -> n
            }
            out += "t:$net"
        }
        when ((stream?.get("security") as? JsonPrimitive)?.contentOrNull) {
            "reality" -> out += "s:reality"
            "tls" -> out += "s:tls"
        }
        if ("xtls-rprx-vision" in main.toString()) out += "s:vision"
        return out
    }

    /** Feature → number of configs, over [servers] (all of them, hidden ones too). */
    fun count(servers: List<Server>): Map<String, Int> {
        val counts = HashMap<String, Int>()
        servers.forEach { s ->
            val f = runCatching { features(s.link, LinkParser.parse(s.link).outbounds) }.getOrDefault(emptySet())
            f.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        return counts
    }
}
