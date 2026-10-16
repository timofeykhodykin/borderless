package app.borderless.data

import app.borderless.config.LinkParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * What a server's configuration leaves unprotected on the way to it (SECURITY.md S7), read from the proxy outbound:
 * traffic that isn't encrypted at all (VLESS / Trojan / SOCKS / HTTP without TLS or Reality, VMess with no cipher,
 * Shadowsocks "none") — anyone on the path reads the credentials and the traffic; or TLS whose certificate isn't
 * checked (`allowInsecure` without a pinned certificate) — anyone on the path can stand in for the server. Shown on
 * the server, announced when a subscription refresh brings it, and such servers can be left out entirely.
 */
object Insecurity {
    enum class Issue { NO_ENCRYPTION, NO_CERT_CHECK }

    /** Pure (unit-tested): the issues of a server's outbounds (first = the proxy). */
    fun issues(outbounds: List<JsonObject>): Set<Issue> {
        val main = outbounds.firstOrNull() ?: return emptySet()
        val protocol = main.str("protocol")
        val stream = main["streamSettings"] as? JsonObject
        val security = stream?.str("security") ?: "none"
        val transportEncrypted = security == "tls" || security == "reality"
        val settings = main["settings"] as? JsonObject
        // Simplified (address / id in settings) and full (vnext / servers with users) forms.
        val server = ((settings?.get("vnext") ?: settings?.get("servers")) as? JsonArray)?.firstOrNull() as? JsonObject ?: settings
        val user = (server?.get("users") as? JsonArray)?.firstOrNull() as? JsonObject ?: server
        val out = HashSet<Issue>()
        val ownCipher = when (protocol) {
            "vless" -> (user?.str("encryption") ?: "none") != "none"
            "vmess" -> (user?.str("security") ?: "auto").lowercase() !in setOf("none", "zero")
            "shadowsocks" -> (server?.str("method") ?: "").lowercase() !in setOf("none", "plain", "")
            "hysteria", "hysteria2", "wireguard" -> true
            else -> false
        }
        if (!transportEncrypted && !ownCipher && protocol != "hysteria") out += Issue.NO_ENCRYPTION
        val tls = stream?.get("tlsSettings") as? JsonObject
        if (security == "tls" && tls?.get("allowInsecure")?.let { (it as? JsonPrimitive)?.booleanOrNull } == true &&
            tls["pinnedPeerCertSha256"] == null
        ) out += Issue.NO_CERT_CHECK
        return out
    }

    private val cache = ConcurrentHashMap<String, Set<Issue>>()

    fun of(server: Server): Set<Issue> = cache.getOrPut(server.id + "#" + server.link.hashCode()) {
        runCatching { issues(LinkParser.parse(server.link).outbounds) }.getOrDefault(emptySet())
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
