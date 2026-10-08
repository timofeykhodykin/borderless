package app.borderless.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A server decoded from a share link or an Xray JSON config. */
data class ParsedServer(
    val name: String,
    /** Human readable protocol summary, e.g. "VLESS · Reality". */
    val protocol: String,
    val host: String,
    val port: Int,
    /** Xray outbounds; the first one is tagged "proxy", the rest are its dependencies (dialerProxy chains). */
    val outbounds: List<JsonObject>,
)

class UnsupportedLinkException(message: String) : Exception(message)

object LinkParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val LEVEL = 8

    val supportedSchemes = listOf("vless", "vmess", "trojan", "ss", "hysteria2", "hy2", "socks", "socks5", "wireguard", "wg")

    fun parse(link: String): ParsedServer {
        val s = link.trim()
        if (s.startsWith("{")) return parseJsonConfig(json.parseToJsonElement(s).jsonObject)
        val scheme = s.substringBefore("://", "").lowercase()
        return when (scheme) {
            "vless" -> parseVless(s)
            "vmess" -> parseVmess(s)
            "trojan" -> parseTrojan(s)
            "ss" -> parseShadowsocks(s)
            "hysteria2", "hy2" -> parseHysteria2(s)
            "socks", "socks5" -> parseSocks(s)
            "wireguard", "wg" -> parseWireguard(s)
            "" -> throw UnsupportedLinkException("not a link")
            else -> throw UnsupportedLinkException("$scheme is not supported by Xray")
        }
    }

    /**
     * Splits arbitrary user input or a subscription body into individual server links.
     * Handles plain lists, base64 blobs and Xray JSON (single config or an array of configs).
     */
    fun extractLinks(text: String): List<String> {
        val t = text.trim().removePrefix("﻿")
        if (t.isEmpty()) return emptyList()
        if (t.startsWith("[") || t.startsWith("{")) {
            val el = runCatching { json.parseToJsonElement(t) }.getOrNull()
            if (el != null) {
                val configs = if (el is JsonArray) el.filterIsInstance<JsonObject>() else listOf(el.jsonObject)
                return configs.filter { "outbounds" in it }.map { it.toString() }
            }
        }
        if ("://" !in t) {
            val decoded = ShareUrl.decodeBase64(t)
            if (decoded != null && ("://" in decoded || decoded.trimStart().startsWith("{") || decoded.trimStart().startsWith("["))) {
                return extractLinks(decoded)
            }
        }
        return t.split('\n', '\r', ' ', '\t')
            .map { it.trim() }
            .filter { "://" in it }
    }

    // ---------------------------------------------------------------- protocols

    private fun parseVless(link: String): ParsedServer {
        val u = ShareUrl.parse(link)
        val stream = Stream.fromQuery(u, defaultSecurity = "none")
        val settings = buildJsonObject {
            put("address", u.host)
            put("port", u.requirePort())
            put("id", u.userInfo.orEmpty())
            put("encryption", u.q("encryption") ?: "none")
            u.q("flow")?.let { put("flow", it) }
            put("level", LEVEL)
        }
        val outbound = outbound("vless", settings, stream.toJson(u.host))
        return ParsedServer(u.nameOr("VLESS"), "VLESS · ${stream.label()}", u.host, u.requirePort(), listOf(outbound))
    }

    private fun parseTrojan(link: String): ParsedServer {
        val u = ShareUrl.parse(link)
        val stream = Stream.fromQuery(u, defaultSecurity = "tls")
        val settings = buildJsonObject {
            put("address", u.host)
            put("port", u.requirePort())
            put("password", u.userInfo.orEmpty())
            u.q("flow")?.let { put("flow", it) }
            put("level", LEVEL)
        }
        val outbound = outbound("trojan", settings, stream.toJson(u.host))
        return ParsedServer(u.nameOr("Trojan"), "Trojan · ${stream.label()}", u.host, u.requirePort(), listOf(outbound))
    }

    private fun parseVmess(link: String): ParsedServer {
        val body = link.substringAfter("://").substringBefore('#')
        val decoded = ShareUrl.decodeBase64(body)
            ?: throw UnsupportedLinkException("vmess: bad base64")
        val o = runCatching { json.parseToJsonElement(decoded).jsonObject }.getOrNull()
            ?: throw UnsupportedLinkException("vmess: only the v2rayN format (base64 JSON) is supported")
        fun f(k: String) = o[k]?.let { (it as? JsonPrimitive)?.contentOrNull }?.trim()?.takeIf { it.isNotEmpty() }
        val host = f("add") ?: throw UnsupportedLinkException("vmess: no address")
        val port = f("port")?.toIntOrNull() ?: throw UnsupportedLinkException("vmess: no port")
        val net = f("net") ?: "tcp"
        val stream = Stream(
            network = net,
            security = if (f("tls") == "tls") "tls" else if (f("tls") == "reality") "reality" else "none",
            sni = f("sni"),
            fp = f("fp"),
            alpn = f("alpn"),
            host = f("host"),
            path = f("path"),
            insecure = f("allowInsecure") == "1" || f("allowInsecure") == "true",
            pbk = f("pbk"),
            sid = f("sid"),
            spx = f("spx"),
        ).apply {
            when (normalizedNetwork()) {
                "grpc" -> {
                    serviceName = path
                    mode = f("type")
                    authority = host
                }
                "kcp" -> {
                    seed = path
                    headerType = f("type")
                }
                "xhttp" -> mode = f("type")
                else -> headerType = f("type")
            }
        }
        val settings = buildJsonObject {
            put("address", host)
            put("port", port)
            put("id", f("id").orEmpty())
            put("security", f("scy") ?: "auto")
            put("level", LEVEL)
        }
        val outbound = outbound("vmess", settings, stream.toJson(host))
        val name = f("ps") ?: "$host:$port"
        return ParsedServer(name, "VMess · ${stream.label()}", host, port, listOf(outbound))
    }

    private fun parseShadowsocks(link: String): ParsedServer {
        val noFragment = link.substringBefore('#')
        val name = link.substringAfter('#', "").let { if (it.isEmpty()) "" else ShareUrl.parse("ss://x@h:1#$it").fragment }
        var u = ShareUrl.parse(link)
        val method: String
        val password: String
        val info = u.userInfo
        if (info != null) {
            val decoded = ShareUrl.decodeBase64(info)?.takeIf { ':' in it && it.all { c -> !c.isISOControl() } }
            val pair = decoded ?: info
            require(':' in pair) { "ss: no method and password" }
            method = pair.substringBefore(':')
            password = pair.substringAfter(':')
        } else {
            // legacy: ss://BASE64(method:password@host:port)#name
            val body = noFragment.substringAfter("://").substringBefore('?').substringBefore('/')
            val decoded = ShareUrl.decodeBase64(body) ?: throw UnsupportedLinkException("ss: bad base64")
            val at = decoded.lastIndexOf('@')
            require(at > 0) { "ss: bad format" }
            method = decoded.substring(0, at).substringBefore(':')
            password = decoded.substring(0, at).substringAfter(':')
            u = ShareUrl.parse("ss://x@" + decoded.substring(at + 1))
        }
        val plugin = ShareUrl.parse(link).q("plugin")
        if (plugin != null && !plugin.startsWith("none")) throw UnsupportedLinkException("ss: plugins ($plugin) are not supported by Xray")
        val settings = buildJsonObject {
            put("address", u.host)
            put("port", u.requirePort())
            put("method", method.lowercase())
            put("password", password)
            put("level", LEVEL)
        }
        val q = ShareUrl.parse(link)
        val stream = Stream.fromQuery(q, defaultSecurity = "none")
        val outbound = outbound("shadowsocks", settings, stream.toJson(u.host))
        val label = if (stream.normalizedNetwork() == "tcp" && stream.security == "none") method.lowercase() else stream.label()
        return ParsedServer(name.ifEmpty { "${u.host}:${u.requirePort()}" }, "Shadowsocks · $label", u.host, u.requirePort(), listOf(outbound))
    }

    private fun parseHysteria2(link: String): ParsedServer {
        // port may be a list/range used for port hopping: host:443,20000-30000
        val portSpec = link.substringAfter("@", link.substringAfter("://")).substringBefore('/').substringBefore('?')
            .substringAfterLast(']').substringAfter(':', "")
        val firstPort = Regex("\\d+").find(portSpec)?.value?.toIntOrNull()
        val u = ShareUrl.parse(if (firstPort != null && portSpec != firstPort.toString()) link.replace(":$portSpec", ":$firstPort") else link)
        val port = u.port ?: 443
        val hop = u.q("mport") ?: portSpec.takeIf { it != firstPort?.toString() && it.isNotEmpty() }
        val sni = u.q("sni", "peer") ?: u.host.takeIf { isDomain(it) }
        val stream = buildJsonObject {
            put("network", "hysteria")
            put("security", "tls")
            putJsonObject("tlsSettings") {
                sni?.let { put("serverName", it) }
                putJsonArray("alpn") { add("h3") }
                if (u.q("insecure", "allowInsecure") == "1") put("allowInsecure", true)
                u.q("pinSHA256")?.let { put("pinnedPeerCertSha256", it) }
            }
            putJsonObject("hysteriaSettings") {
                put("version", 2)
                put("auth", u.userInfo.orEmpty())
            }
            val obfs = u.q("obfs-password")
            if (obfs != null || hop != null) {
                putJsonObject("finalmask") {
                    if (obfs != null) putJsonArray("udp") {
                        add(buildJsonObject {
                            put("type", "salamander")
                            putJsonObject("settings") { put("password", obfs) }
                        })
                    }
                    if (hop != null) putJsonObject("quicParams") {
                        putJsonObject("udpHop") {
                            put("ports", hop)
                            put("interval", u.q("mportHopInt", "hop-interval") ?: "30")
                        }
                    }
                }
            }
        }
        val settings = buildJsonObject {
            put("address", u.host)
            put("port", port)
            put("version", 2)
        }
        val outbound = outbound("hysteria", settings, stream)
        return ParsedServer(u.nameOr("Hysteria2"), "Hysteria2", u.host, port, listOf(outbound))
    }

    private fun parseSocks(link: String): ParsedServer {
        val u = ShareUrl.parse(link)
        var user: String? = null
        var pass: String? = null
        u.userInfo?.let { info ->
            val pair = if (':' in info) info else ShareUrl.decodeBase64(info) ?: info
            user = pair.substringBefore(':')
            pass = pair.substringAfter(':', "")
        }
        val settings = buildJsonObject {
            put("address", u.host)
            put("port", u.requirePort())
            if (!user.isNullOrEmpty()) {
                put("user", user)
                put("pass", pass.orEmpty())
            }
            put("level", LEVEL)
        }
        val outbound = outbound("socks", settings, null)
        return ParsedServer(u.nameOr("SOCKS"), "SOCKS5", u.host, u.requirePort(), listOf(outbound))
    }

    private fun parseWireguard(link: String): ParsedServer {
        val u = ShareUrl.parse(link)
        val endpointHost = if (':' in u.host) "[${u.host}]" else u.host
        val settings = buildJsonObject {
            put("secretKey", u.userInfo ?: u.q("privatekey", "privateKey") ?: "")
            putJsonArray("address") {
                (u.q("address", "ip") ?: "172.16.0.2/32").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    .forEach { add(if ('/' in it) it else if (':' in it) "$it/128" else "$it/32") }
            }
            putJsonArray("peers") {
                add(buildJsonObject {
                    put("publicKey", u.q("publickey", "publicKey", "peer_public_key") ?: "")
                    u.q("presharedkey", "preSharedKey", "psk")?.let { put("preSharedKey", it) }
                    put("endpoint", "$endpointHost:${u.requirePort()}")
                    put("keepAlive", u.q("keepalive")?.toIntOrNull() ?: 25)
                })
            }
            put("mtu", u.q("mtu")?.toIntOrNull() ?: 1280)
            u.q("reserved")?.let { r ->
                val parts = r.split(',').mapNotNull { it.trim().toIntOrNull() }
                if (parts.size == 3) putJsonArray("reserved") { parts.forEach { add(it) } }
            }
            put("domainStrategy", "ForceIPv4")
        }
        val outbound = outbound("wireguard", settings, null)
        return ParsedServer(u.nameOr("WireGuard"), "WireGuard", u.host, u.requirePort(), listOf(outbound))
    }

    private fun parseJsonConfig(config: JsonObject): ParsedServer {
        val outbounds = config["outbounds"]?.jsonArray?.filterIsInstance<JsonObject>().orEmpty()
        val system = setOf("freedom", "blackhole", "dns", "loopback")
        val main = outbounds.firstOrNull { it.str("tag") == "proxy" }
            ?: outbounds.firstOrNull { it.str("protocol") !in system }
            ?: throw UnsupportedLinkException("no proxy outbound in JSON")
        val byTag = outbounds.associateBy { it.str("tag") }
        val deps = LinkedHashMap<String, JsonObject>()
        fun collect(o: JsonObject) {
            val stream = o["streamSettings"] as? JsonObject
            val refs = listOfNotNull(
                (stream?.get("sockopt") as? JsonObject)?.str("dialerProxy"),
                (o["proxySettings"] as? JsonObject)?.str("tag"),
            )
            refs.forEach { tag ->
                val dep = byTag[tag] ?: return@forEach
                if (tag !in deps && dep !== main) {
                    deps[tag] = dep
                    collect(dep)
                }
            }
        }
        collect(main)
        // Dependencies get tags of our own: a chain outbound called "direct" or "dns-out" would
        // otherwise take over the app's routing (e.g. send all "direct" traffic through it).
        val tags = deps.keys.withIndex().associate { (i, t) -> t to "chain-$i" }
        val renamed = retag(JsonObject(main.toMutableMap().apply { put("tag", JsonPrimitive("proxy")) }), tags)
        val chain = deps.map { (t, o) -> retag(JsonObject(o.toMutableMap().apply { put("tag", JsonPrimitive(tags.getValue(t))) }), tags) }
        val settings = main["settings"] as? JsonObject
        val vnext = (settings?.get("vnext") ?: settings?.get("servers")) as? JsonArray
        val first = vnext?.firstOrNull() as? JsonObject ?: settings
        val host = first?.str("address") ?: (settings?.get("peers") as? JsonArray)?.firstOrNull()?.jsonObject?.str("endpoint")?.substringBeforeLast(':') ?: "?"
        val port = (first?.get("port") as? JsonPrimitive)?.intOrNull ?: 0
        val protocol = main.str("protocol") ?: "?"
        val stream = main["streamSettings"] as? JsonObject
        val net = stream?.str("network") ?: "tcp"
        val sec = stream?.str("security") ?: "none"
        val label = buildString {
            append(protocol.replaceFirstChar { it.uppercase() })
            append(" · ")
            append(Stream.describe(net, sec))
            if (deps.isNotEmpty()) append(" · chain")
        }
        val name = config.str("remarks") ?: main.str("tag")?.takeIf { it != "proxy" } ?: "$host:$port"
        return ParsedServer(name, label, host, port, listOf(renamed) + chain)
    }

    /** Rewrites references to other outbounds (dialerProxy, proxySettings.tag) through [tags]. */
    private fun retag(o: JsonObject, tags: Map<String, String>): JsonObject {
        val m = o.toMutableMap()
        (m["streamSettings"] as? JsonObject)?.let { stream ->
            (stream["sockopt"] as? JsonObject)?.let { sock ->
                sock.str("dialerProxy")?.let { ref ->
                    val s2 = sock.toMutableMap().apply { put("dialerProxy", JsonPrimitive(tags[ref] ?: ref)) }
                    m["streamSettings"] = JsonObject(stream.toMutableMap().apply { put("sockopt", JsonObject(s2)) })
                }
            }
        }
        (m["proxySettings"] as? JsonObject)?.let { ps ->
            ps.str("tag")?.let { ref -> m["proxySettings"] = JsonObject(ps.toMutableMap().apply { put("tag", JsonPrimitive(tags[ref] ?: ref)) }) }
        }
        return JsonObject(m)
    }

    // ---------------------------------------------------------------- helpers

    private fun outbound(protocol: String, settings: JsonObject, stream: JsonObject?) = buildJsonObject {
        put("tag", "proxy")
        put("protocol", protocol)
        put("settings", settings)
        if (stream != null) put("streamSettings", stream)
    }

    private fun ShareUrl.requirePort(): Int = port ?: throw UnsupportedLinkException("no port in link")
    private fun ShareUrl.nameOr(default: String) = fragment.ifEmpty { "$default $host" }

    internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    internal fun isDomain(s: String): Boolean =
        s.isNotEmpty() && ':' !in s && !s.all { it.isDigit() || it == '.' } && '.' in s
}

/** Transport + security parameters shared by VLESS/VMess/Trojan/Shadowsocks links. */
internal data class Stream(
    var network: String = "tcp",
    var security: String = "none",
    var sni: String? = null,
    var fp: String? = null,
    var alpn: String? = null,
    var insecure: Boolean = false,
    var pbk: String? = null,
    var sid: String? = null,
    var spx: String? = null,
    var pqv: String? = null,
    var ech: String? = null,
    var pinned: String? = null,
    var vcn: String? = null,
    var host: String? = null,
    var path: String? = null,
    var headerType: String? = null,
    var mode: String? = null,
    var serviceName: String? = null,
    var authority: String? = null,
    var extra: String? = null,
    var seed: String? = null,
    var finalMask: String? = null,
) {
    fun normalizedNetwork(): String = when (network.lowercase()) {
        "", "raw", "tcp" -> "tcp"
        "splithttp", "xhttp" -> "xhttp"
        "gun", "grpc" -> "grpc"
        "http", "h2" -> "h2"
        "mkcp", "kcp" -> "kcp"
        else -> network.lowercase()
    }

    fun label(): String = describe(normalizedNetwork(), security)

    fun toJson(serverAddress: String): JsonObject = buildJsonObject {
        val net = normalizedNetwork()
        put("network", net)
        var sniFallback: String? = null
        when (net) {
            "tcp" -> if (headerType == "http") {
                putJsonObject("tcpSettings") {
                    putJsonObject("header") {
                        put("type", "http")
                        putJsonObject("request") {
                            putJsonArray("path") { (path ?: "/").split(',').forEach { add(it.trim()) } }
                            putJsonObject("headers") {
                                host?.let { h -> putJsonArray("Host") { h.split(',').forEach { add(it.trim()) } } }
                            }
                        }
                    }
                }
                sniFallback = host?.split(',')?.first()?.trim()
            } else sniFallback = host

            "ws" -> {
                putJsonObject("wsSettings") {
                    put("path", path ?: "/")
                    host?.let { put("host", it) }
                }
                sniFallback = host
            }

            "httpupgrade" -> {
                putJsonObject("httpupgradeSettings") {
                    put("path", path ?: "/")
                    host?.let { put("host", it) }
                }
                sniFallback = host
            }

            "xhttp" -> {
                putJsonObject("xhttpSettings") {
                    put("path", path ?: "/")
                    host?.let { put("host", it) }
                    mode?.let { put("mode", it) }
                    extra?.let { e -> runCatching { Json.parseToJsonElement(e) }.getOrNull()?.let { put("extra", it) } }
                }
                sniFallback = host
            }

            "grpc" -> {
                putJsonObject("grpcSettings") {
                    put("serviceName", serviceName ?: path ?: "")
                    authority?.let { put("authority", it) }
                    put("multiMode", mode == "multi")
                    put("idle_timeout", 60)
                    put("health_check_timeout", 20)
                }
                sniFallback = authority
            }

            "h2" -> {
                putJsonObject("httpSettings") {
                    putJsonArray("host") { host?.split(',')?.forEach { add(it.trim()) } }
                    put("path", path ?: "/")
                }
                sniFallback = host?.split(',')?.first()?.trim()
            }

            "kcp" -> {
                putJsonObject("kcpSettings") {
                    put("mtu", 1350); put("tti", 50)
                    put("uplinkCapacity", 12); put("downlinkCapacity", 100)
                    put("congestion", false)
                    put("readBufferSize", 1); put("writeBufferSize", 1)
                }
                val masks = mutableListOf<JsonObject>()
                if (!headerType.isNullOrEmpty() && headerType != "none") {
                    masks += buildJsonObject {
                        put("type", "mkcp-legacy")
                        putJsonObject("settings") {
                            put("header", if (headerType == "wechat-video") "wechat" else headerType)
                            if (headerType == "dns" && !host.isNullOrEmpty()) put("value", host)
                        }
                    }
                }
                masks += buildJsonObject {
                    put("type", "mkcp-legacy")
                    if (!seed.isNullOrEmpty()) putJsonObject("settings") { put("value", seed) }
                }
                putJsonObject("finalmask") { put("udp", JsonArray(masks.reversed())) }
            }
        }

        if (security == "tls" || security == "reality") {
            put("security", security)
            val serverName = sni
                ?: sniFallback?.takeIf { LinkParser.isDomain(it) }
                ?: serverAddress.takeIf { LinkParser.isDomain(it) }
            val key = if (security == "tls") "tlsSettings" else "realitySettings"
            putJsonObject(key) {
                serverName?.let { put("serverName", it) }
                put("fingerprint", fp ?: "chrome")
                if (security == "tls") {
                    alpn?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
                        ?.let { list -> putJsonArray("alpn") { list.forEach { add(it) } } }
                    if (insecure && pinned == null) put("allowInsecure", true)
                    ech?.let { put("echConfigList", it) }
                    pinned?.let { put("pinnedPeerCertSha256", it) }
                    vcn?.let { put("verifyPeerCertByName", it) }
                } else {
                    put("publicKey", pbk.orEmpty())
                    sid?.let { put("shortId", it) }
                    spx?.let { put("spiderX", it) }
                    pqv?.let { put("mldsa65Verify", it) }
                }
            }
        } else {
            put("security", "none")
        }

        finalMask?.let { fm -> runCatching { Json.parseToJsonElement(fm) }.getOrNull()?.let { put("finalmask", it) } }
    }

    companion object {
        fun fromQuery(u: ShareUrl, defaultSecurity: String): Stream {
            val sec = (u.q("security") ?: defaultSecurity).lowercase()
            return Stream(
                network = u.q("type", "network", "net") ?: "tcp",
                security = if (sec == "tls" || sec == "reality" || sec == "xtls") (if (sec == "xtls") "tls" else sec) else "none",
                sni = u.q("sni", "peer", "serverName"),
                fp = u.q("fp", "fingerprint"),
                alpn = u.q("alpn"),
                insecure = u.q("insecure", "allowInsecure", "allow_insecure") == "1" || u.q("allowInsecure") == "true",
                pbk = u.q("pbk", "publicKey"),
                sid = u.q("sid", "shortId"),
                spx = u.q("spx", "spiderX"),
                pqv = u.q("pqv"),
                ech = u.q("ech"),
                pinned = u.q("pcs"),
                vcn = u.q("vcn"),
                host = u.q("host"),
                path = u.q("path"),
                headerType = u.q("headerType"),
                mode = u.q("mode"),
                serviceName = u.q("serviceName"),
                authority = u.q("authority"),
                extra = u.q("extra"),
                seed = u.q("seed"),
                finalMask = u.q("fm"),
            )
        }

        fun describe(network: String, security: String): String {
            val net = when (network) {
                "tcp", "raw" -> ""
                "ws" -> "WS"
                "grpc" -> "gRPC"
                "httpupgrade" -> "HTTPUpgrade"
                "xhttp", "splithttp" -> "XHTTP"
                "h2", "http" -> "H2"
                "kcp" -> "mKCP"
                else -> network
            }
            val sec = when (security) {
                "reality" -> "Reality"
                "tls" -> "TLS"
                else -> ""
            }
            return listOf(net, sec).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "TCP" }
        }
    }
}
