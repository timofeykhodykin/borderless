package app.borderless.config

import app.borderless.data.AppSettings
import app.borderless.data.GeoFile
import app.borderless.data.Regions
import app.borderless.data.RoutingMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds complete Xray configs.
 *
 * Routing rules that implement user routing are bound to `inboundTag: tun`. Connections the
 * app itself opens through the core (health checks via `CoreController.measureDelay`) have no
 * inbound tag, so they skip those rules and always use the proxy, regardless of routing mode.
 */
object XrayConfig {
    private const val TUN = "tun"
    private const val DNS_TAG = "dns-module"

    /** What the chosen regions ([AppSettings.regions]) contribute to the rules. */
    class RegionRules(s: AppSettings) {
        // Only zones whose lists are all here (a zone still downloading takes no part yet).
        private val regions = Regions.active(s.regions)
        // Only categories the routing lists have now (downloaded ones may still be on their way, see GeoFile.usable).
        val directDomains = (listOf("geosite:private") + regions.flatMap { it.directDomains }).distinct().filter(GeoFile::usable)
        val directIps = regions.flatMap { it.directIps }.distinct().filter(GeoFile::usable)
        val serverDomains = regions.flatMap { it.serverDomains }.distinct().filter(GeoFile::usable)
        val serverIps = regions.flatMap { it.serverIps }.distinct().filter(GeoFile::usable)

        /** Resolvers for direct names: the user's, else the regions' own, else public ones. */
        val dns: List<String> = s.directDns.trim().takeIf { it.isNotEmpty() }?.let(::listOf)
            ?: regions.flatMap { it.dns }.distinct().ifEmpty { PUBLIC_DNS }
    }

    /** Direct resolvers when neither the user nor a chosen region names one. */
    private val PUBLIC_DNS = listOf("1.1.1.1", "8.8.8.8")

    enum class Mode { PROXY, DIRECT, PAUSE }

    /** [logLevel] "info" logs every routing decision (`[tun >> direct]`), useful in debug builds. */
    fun build(mode: Mode, proxyOutbounds: List<JsonObject>, s: AppSettings, logLevel: String = "warning", accessLog: String? = null): String {
        val routing = s.routingEnabled
        val region = RegionRules(s)
        // Direct resolvers are reached directly (their addresses get a rule of their own).
        val directDnsIps = region.dns.mapNotNull(::hostOf)
        val config = buildJsonObject {
            putJsonObject("log") {
                put("loglevel", logLevel)
                accessLog?.let { put("access", it) }
            }
            put("dns", dns(mode, s, region))
            putJsonArray("inbounds") {
                addJsonObject {
                    put("tag", TUN)
                    put("protocol", "tun")
                    putJsonObject("settings") {
                        put("name", "xray0")
                        put("MTU", s.mtu)
                        put("userLevel", 8)
                    }
                    putJsonObject("sniffing") {
                        put("enabled", true)
                        putJsonArray("destOverride") { add("http"); add("tls"); add("quic") }
                        put("routeOnly", true)
                    }
                }
            }
            putJsonArray("outbounds") {
                when (mode) {
                    Mode.PROXY -> proxyOutbounds.forEachIndexed { i, o -> add(if (i == 0) withGlobalOptions(o, s) else o) }
                    Mode.DIRECT -> {}
                    Mode.PAUSE -> add(blackhole("proxy"))
                }
                addJsonObject {
                    put("tag", "direct")
                    put("protocol", "freedom")
                    putJsonObject("settings") { put("domainStrategy", if (s.ipv6) "UseIP" else "UseIPv4") }
                }
                add(blackhole("drop"))
                addJsonObject {
                    put("tag", "dns-out")
                    put("protocol", "dns")
                }
            }
            putJsonObject("routing") {
                put("domainStrategy", "IPIfNonMatch")
                putJsonArray("rules") {
                    // DNS queries from apps are answered by the core's DNS module.
                    rule(inbound = TUN, port = "53", out = "dns-out")
                    if (mode == Mode.PAUSE) {
                        rule(inbound = TUN, network = "tcp,udp", out = "drop")
                        return@putJsonArray
                    }
                    if (directDnsIps.isNotEmpty()) rule(ip = directDnsIps, out = "direct")
                    rule(inbound = DNS_TAG, out = if (mode == Mode.PROXY) "proxy" else "direct")
                    if (mode == Mode.DIRECT) {
                        rule(inbound = TUN, network = "tcp,udp", out = "direct")
                        return@putJsonArray
                    }
                    rule(inbound = TUN, ip = listOf("geoip:private"), out = "direct")
                    if (!routing) return@putJsonArray

                    if (s.adFilter && GeoFile.usable("geosite:category-ads-all")) rule(inbound = TUN, domain = listOf("geosite:category-ads-all"), out = "drop")
                    // The user's lists too: an unknown geosite / geoip category would stop the core from starting.
                    val proxyDomains = s.proxyDomains.map(::domainRule).filter(GeoFile::usable)
                    if (proxyDomains.isNotEmpty()) rule(inbound = TUN, domain = proxyDomains, out = "proxy")
                    val (directIps, directDomains) = s.directDomains.partition(::looksLikeIp)
                    val directSites = directDomains.map(::domainRule).filter(GeoFile::usable)
                    if (directSites.isNotEmpty()) rule(inbound = TUN, domain = directSites, out = "direct")
                    val directAddresses = directIps.filter(GeoFile::usable)
                    if (directAddresses.isNotEmpty()) rule(inbound = TUN, ip = directAddresses, out = "direct")

                    when (s.routingMode) {
                        RoutingMode.REGION_DIRECT -> {
                            if (region.serverDomains.isNotEmpty()) rule(inbound = TUN, domain = region.serverDomains, out = "proxy")
                            rule(inbound = TUN, domain = region.directDomains, out = "direct")
                            if (region.serverIps.isNotEmpty()) rule(inbound = TUN, ip = region.serverIps, out = "proxy")
                            if (region.directIps.isNotEmpty()) rule(inbound = TUN, ip = region.directIps, out = "direct")
                        }

                        RoutingMode.LISTED_ONLY -> {
                            if (region.serverDomains.isNotEmpty()) rule(inbound = TUN, domain = region.serverDomains, out = "proxy")
                            if (region.serverIps.isNotEmpty()) rule(inbound = TUN, ip = region.serverIps, out = "proxy")
                            rule(inbound = TUN, network = "tcp,udp", out = "direct")
                        }
                    }
                }
            }
            // Per-outbound byte counters, read once a minute for the statistics screen.
            putJsonObject("stats") {}
            putJsonObject("policy") {
                putJsonObject("system") {
                    put("statsOutboundUplink", true)
                    put("statsOutboundDownlink", true)
                }
                putJsonObject("levels") {
                    putJsonObject("8") {
                        put("handshake", 4)
                        put("connIdle", 300)
                        put("uplinkOnly", 1)
                        put("downlinkOnly", 1)
                    }
                }
            }
        }
        return config.toString()
    }

    /** Minimal config for `Libv2ray.measureOutboundDelay`: it only uses outbounds. */
    fun probe(proxyOutbounds: List<JsonObject>, s: AppSettings, logLevel: String = "warning", accessLog: String = "none"): String = buildJsonObject {
        putJsonObject("log") {
            put("loglevel", logLevel)
            put("access", accessLog)
        }
        putJsonArray("outbounds") {
            proxyOutbounds.forEachIndexed { i, o -> add(if (i == 0) withGlobalOptions(o, s) else o) }
            addJsonObject { put("tag", "direct"); put("protocol", "freedom") }
        }
    }.toString()

    /**
     * A throwaway instance that lets the app itself reach the internet through [proxyOutbounds] (e.g. a
     * subscription whose provider can't be reached directly): a SOCKS5 proxy on 127.0.0.1:[port] that
     * requires [user]/[pass] (random, for one download), so other apps can't use it.
     */
    fun localProxy(
        proxyOutbounds: List<JsonObject>, s: AppSettings, port: Int, user: String, pass: String,
        logLevel: String = "warning", accessLog: String = "none",
    ): String = buildJsonObject {
        putJsonObject("log") {
            put("loglevel", logLevel)
            put("access", accessLog)
        }
        putJsonArray("inbounds") {
            addJsonObject {
                put("tag", "fetch")
                put("listen", "127.0.0.1")
                put("port", port)
                put("protocol", "socks")
                putJsonObject("settings") {
                    put("auth", "password")
                    putJsonArray("accounts") { addJsonObject { put("user", user); put("pass", pass) } }
                    put("udp", false)
                }
            }
        }
        putJsonArray("outbounds") {
            proxyOutbounds.forEachIndexed { i, o -> add(if (i == 0) withGlobalOptions(o, s) else o) }
        }
    }.toString()

    private fun dns(mode: Mode, s: AppSettings, region: RegionRules) = buildJsonObject {
        put("tag", DNS_TAG)
        put("queryStrategy", if (s.ipv6) "UseIP" else "UseIPv4")
        putJsonObject("hosts") {
            put("dns.google", buildJsonArray { add("8.8.8.8"); add("8.8.4.4") })
            put("one.one.one.one", buildJsonArray { add("1.1.1.1"); add("1.0.0.1") })
        }
        putJsonArray("servers") {
            val direct = region.dns
            when {
                mode != Mode.PROXY -> direct.forEach { add(it) }

                !s.routingEnabled -> add(s.remoteDns)
                s.routingMode == RoutingMode.LISTED_ONLY -> {
                    add(direct.first())
                    val viaServer = region.serverDomains + s.proxyDomains.map(::domainRule).filter(GeoFile::usable)
                    // Listed names are resolved through the server (none known yet: everything resolves directly).
                    if (viaServer.isNotEmpty()) addJsonObject {
                        put("address", s.remoteDns)
                        putJsonArray("domains") { viaServer.forEach { add(it) } }
                        put("skipFallback", true)
                    }
                }

                else -> {
                    add(s.remoteDns)
                    addJsonObject {
                        put("address", direct.first())
                        putJsonArray("domains") {
                            (region.directDomains + s.directDomains.filterNot(::looksLikeIp).map(::domainRule).filter(GeoFile::usable)).forEach { add(it) }
                        }
                        put("skipFallback", true)
                    }
                }
            }
        }
    }

    /** Applies user-wide options (mux, TLS fragmentation) to the main proxy outbound. */
    private fun withGlobalOptions(o: JsonObject, s: AppSettings): JsonObject {
        val map = o.toMutableMap()
        val protocol = (o["protocol"] as? JsonPrimitive)?.content
        val stream = o["streamSettings"] as? JsonObject
        val network = (stream?.get("network") as? JsonPrimitive)?.content
        val security = (stream?.get("security") as? JsonPrimitive)?.content
        val flow = ((o["settings"] as? JsonObject)?.get("flow") as? JsonPrimitive)?.content
        val muxAllowed = protocol in setOf("vless", "vmess") && network != "xhttp" && flow.isNullOrEmpty()
        if (s.mux && muxAllowed && "mux" !in o) {
            map["mux"] = buildJsonObject {
                put("enabled", true)
                put("concurrency", 8)
                put("xudpConcurrency", 16)
                put("xudpProxyUDP443", "reject")
            }
        }
        if (s.fragment && stream != null && (security == "tls" || security == "reality") && "finalmask" !in stream) {
            val sm = stream.toMutableMap()
            sm["finalmask"] = buildJsonObject {
                putJsonArray("tcp") {
                    addJsonObject {
                        put("type", "fragment")
                        putJsonObject("settings") {
                            put("packets", if (security == "reality") "1-3" else "tlshello")
                            put("length", "50-100")
                            put("delay", "10-20")
                            put("maxSplit", "10")
                        }
                    }
                }
            }
            map["streamSettings"] = JsonObject(sm)
        }
        return JsonObject(map)
    }

    private fun blackhole(tag: String) = buildJsonObject {
        put("tag", tag)
        put("protocol", "blackhole")
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.rule(
        inbound: String? = null,
        domain: List<String>? = null,
        ip: List<String>? = null,
        port: String? = null,
        network: String? = null,
        out: String,
    ) = addJsonObject {
        put("type", "field")
        inbound?.let { putJsonArray("inboundTag") { add(it) } }
        domain?.let { d -> putJsonArray("domain") { d.forEach { add(it) } } }
        ip?.let { list -> putJsonArray("ip") { list.forEach { add(it) } } }
        port?.let { put("port", it) }
        network?.let { put("network", it) }
        put("outboundTag", out)
    }

    /** "example.com" -> "domain:example.com"; prefixed rules (geosite:, full:, regexp:, keyword:) are kept. */
    fun domainRule(d: String): String {
        val t = d.trim().lowercase()
        return if (':' in t) t else "domain:${t.removePrefix("*.").removePrefix(".")}"
    }

    private fun looksLikeIp(s: String): Boolean {
        val t = s.trim()
        return t.startsWith("geoip:") || Regex("^[0-9.]+(/\\d+)?$").matches(t) || (t.count { it == ':' } >= 2 && !t.contains("."))
    }

    private fun hostOf(dns: String): String? {
        val t = dns.trim()
        val host = t.substringAfter("://").substringBefore('/').substringBefore(':')
        return host.takeIf { Regex("^[0-9.]+$").matches(it) }
    }
}
