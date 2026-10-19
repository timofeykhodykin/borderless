package app.borderless.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A limited-network server: the app starts the native core, which carries UDP from a local port through the calls
 * service (TURN, DTLS inside) to the server; the server hands out a WireGuard config, and Xray's WireGuard outbound
 * talks to the local port. Such servers keep several connections and keepalives going, so they are used only when no
 * ordinary server works (see `core/Limited.kt`, `local/selection/DESIGN.md` §12).
 *
 * Link forms (pure, unit-tested):
 * - `wdtt://host:dtlsPort:wgPort:localPort:password[:call1,call2…][#name]` (wgPort and localPort are the server's
 *   and the upstream app's business: the local port is chosen by the app);
 * - `wdtt://<base64 JSON>[#name]` with `peer`, `pass`, `hashes`, `workers`, `deviceId`, `name`;
 * - `qwdtt://config?peer=host:port&pass=…&hashes=…&workers=…&deviceId=…&name=…`;
 * - `wdtt://connect?v=1&host=…&dtls=…&wg=…&local=…&password=…&hashes=…[&name=…][&max_workers=…]` (the same server
 *   with management on top; `wg` / `local` are not ours to use, `max_workers` is the server's own limit, which it
 *   enforces itself).
 */
data class LimitedLink(
    val host: String,
    val port: Int,
    val password: String,
    /** Identifiers of the calls whose streams carry the traffic (the part after `/call/join/`). */
    val calls: List<String>,
    /** Streams the link suggests (qwdtt `workers`); the app decides itself unless the user sets a number. */
    val streams: Int? = null,
    /** The server's own stream limit (WDTT Plus `max_workers`), if the link carries one. */
    val maxWorkers: Int? = null,
    /** The device id the server bound the password to, if the link carries one. */
    val deviceId: String? = null,
    val name: String? = null,
) {
    /** `host:port` as the core expects it (IPv6 in brackets). */
    val peer: String get() = if (':' in host) "[$host]:$port" else "$host:$port"

    companion object {
        val SCHEMES = listOf("wdtt", "qwdtt")
        const val PROTOCOL = "WDTT · WireGuard"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun matches(link: String): Boolean = link.trimStart().substringBefore("://", "").lowercase() in SCHEMES

        fun parse(link: String): LimitedLink {
            val s = link.trim()
            val scheme = s.substringBefore("://", "").lowercase()
            val body = s.substringAfter("://")
            val fragment = body.substringAfter('#', "").let { ShareUrl.percentDecode(it).trim() }.takeIf { it.isNotEmpty() }
            val payload = body.substringBefore('#').trim()
            val r = when (scheme) {
                "qwdtt" -> query(payload)
                "wdtt" -> when {
                    looksColon(payload) -> colon(payload)
                    '?' in payload -> query(payload)
                    else -> jsonForm(payload) ?: colon(payload)
                }
                else -> throw UnsupportedLinkException("not a limited-network link")
            }
            return if (fragment != null) r.copy(name = fragment) else r
        }

        private fun looksColon(p: String): Boolean {
            val parts = p.split(':')
            return parts.size >= 5 && '=' !in p && parts[1].isNotEmpty() && parts[1].all(Char::isDigit)
        }

        private fun colon(p: String): LimitedLink {
            val parts = p.split(':')
            if (parts.size < 5) throw UnsupportedLinkException("wdtt: needs host:port:wgPort:localPort:password")
            val port = parts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: throw UnsupportedLinkException("wdtt: bad port")
            val password = parts[4].trim().ifEmpty { throw UnsupportedLinkException("wdtt: no password") }
            return LimitedLink(
                host = parts[0].trim().ifEmpty { throw UnsupportedLinkException("wdtt: no address") },
                port = port, password = password,
                calls = calls(parts.getOrNull(5).orEmpty()).ifEmpty { throw UnsupportedLinkException("wdtt: no call identifiers") },
            )
        }

        private fun jsonForm(p: String): LimitedLink? {
            val text = ShareUrl.decodeBase64(p) ?: return null
            val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            fun f(vararg keys: String) = keys.firstNotNullOfOrNull { k -> (o[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } }
            val (host, port) = peer(f("peer", "server", "host") ?: return null)
            return LimitedLink(
                host = host, port = port,
                password = f("pass", "password", "pwd") ?: throw UnsupportedLinkException("wdtt: no password"),
                calls = calls(f("hashes", "vkHashes", "hash", "vk").orEmpty()).ifEmpty { throw UnsupportedLinkException("wdtt: no call identifiers") },
                streams = f("workers", "workersPerHash", "n")?.toIntOrNull()?.takeIf { it > 0 },
                maxWorkers = f("max_workers", "maxWorkers", "max-workers")?.toIntOrNull()?.takeIf { it > 0 },
                deviceId = f("deviceId", "device_id", "device", "did"),
                name = f("name", "ps", "remark", "title"),
            )
        }

        private fun query(p: String): LimitedLink {
            val q = p.substringAfter('?', "").split('&').filter { '=' in it }
                .associate { ShareUrl.percentDecode(it.substringBefore('=')) to ShareUrl.percentDecode(it.substringAfter('=').replace('+', ' ')).trim() }
            fun f(vararg keys: String) = keys.firstNotNullOfOrNull { q[it]?.takeIf(String::isNotEmpty) }
            val (host, port) = f("peer")?.let(::peer)
                ?: f("host")?.let { h ->
                    val port = f("dtls", "port")?.let { p -> p.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw UnsupportedLinkException("wdtt: bad port") }
                    if (port == null) peer(h) else h.trim().removePrefix("[").removeSuffix("]") to port
                }
                ?: throw UnsupportedLinkException("wdtt: no address")
            return LimitedLink(
                host = host, port = port,
                password = f("pass", "password") ?: throw UnsupportedLinkException("qwdtt: no password"),
                calls = calls(f("hashes", "vk", "vkHashes", "hash").orEmpty()).ifEmpty { throw UnsupportedLinkException("qwdtt: no call identifiers") },
                streams = f("workers", "workersPerHash")?.toIntOrNull()?.takeIf { it > 0 },
                maxWorkers = f("max_workers", "maxWorkers", "max-workers")?.toIntOrNull()?.takeIf { it > 0 },
                deviceId = f("deviceId", "device-id", "device", "did"),
                name = f("name"),
            )
        }

        /** `host:port`, `[v6]:port` or a bare host (the upstream default port 56000). */
        private fun peer(s: String): Pair<String, Int> {
            val t = s.trim()
            if (t.startsWith("[")) {
                val host = t.substringAfter('[').substringBefore(']')
                val port = t.substringAfter("]:", "").toIntOrNull() ?: DEFAULT_PORT
                return host to port
            }
            if (t.count { it == ':' } != 1) return t to DEFAULT_PORT
            val port = t.substringAfter(':').toIntOrNull()?.takeIf { it in 1..65535 } ?: throw UnsupportedLinkException("wdtt: bad port")
            return t.substringBefore(':') to port
        }

        /** Call identifiers from a list (commas, spaces, new lines), full join links reduced to the identifier. */
        fun calls(raw: String): List<String> = raw.split(',', ';', ' ', '\n', '\r', '\t')
            .map { it.trim().trim('<', '>', '"', '\'') }
            .mapNotNull { s ->
                val i = s.lowercase().indexOf("/call/join/")
                val id = if (i >= 0) s.substring(i + "/call/join/".length) else if ("://" in s) return@mapNotNull null else s
                id.substringBefore('?').substringBefore('#').substringBefore('/').trim().takeIf { it.isNotEmpty() }
            }
            .distinct()

        private const val DEFAULT_PORT = 56000

        /**
         * [link] carrying [device] as its device id (pure, unit-tested), for sharing a server with one's own other phone:
         * query forms get `device=` (`deviceId=` for qwdtt; other clients ignore keys they don't know), the JSON form a
         * `deviceId` field, and the colon form, which has no place for it, becomes the query form. Blank [device]:
         * the link unchanged.
         */
        fun withDevice(link: String, device: String?): String {
            val id = device?.trim().orEmpty()
            if (id.isEmpty() || !matches(link)) return link
            val s = link.trim()
            val scheme = s.substringBefore("://").lowercase()
            val body = s.substringAfter("://")
            val fragment = body.substringAfter('#', "").takeIf { '#' in body }
            val payload = body.substringBefore('#').trim()
            fun tail() = fragment?.let { "#$it" }.orEmpty()
            fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")
            if ('?' in payload) {
                val key = if (scheme == "qwdtt") "deviceId" else "device"
                val kept = payload.substringAfter('?').split('&').filter { it.isNotEmpty() }
                    .filterNot { it.substringBefore('=').let { k -> ShareUrl.percentDecode(k) in DEVICE_KEYS } }
                return "$scheme://${payload.substringBefore('?')}?" + (kept + "$key=${enc(id)}").joinToString("&") + tail()
            }
            if (scheme == "wdtt" && looksColon(payload)) {
                val parts = payload.split(':')
                val q = listOfNotNull(
                    "v=1", "host=${enc(parts[0])}", "dtls=${parts[1]}",
                    parts[2].takeIf { it.isNotEmpty() }?.let { "wg=$it" }, parts[3].takeIf { it.isNotEmpty() }?.let { "local=$it" },
                    "password=${enc(parts[4])}", parts.getOrNull(5)?.takeIf { it.isNotEmpty() }?.let { "hashes=${enc(it)}" },
                    "device=${enc(id)}",
                )
                return "wdtt://connect?" + q.joinToString("&") + tail()
            }
            val text = ShareUrl.decodeBase64(payload) ?: return link
            val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return link
            val updated = JsonObject(o.filterKeys { it !in DEVICE_KEYS } + ("deviceId" to JsonPrimitive(id)))
            val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(updated.toString().toByteArray())
            return "$scheme://$encoded" + tail()
        }

        private val DEVICE_KEYS = setOf("deviceId", "device-id", "device_id", "device", "did")

        /**
         * The outbound a limited-network server is described by before its core ran (analysis of the protocol, statistics):
         * WireGuard to the local core with no keys. Never started — [outbound] builds the real one.
         */
        fun placeholder(): JsonObject = outbound(WgConfig("", listOf("10.0.0.2/32"), "", null, 1280, 25), 0)

        /** Xray's WireGuard outbound for [wg], sending to the core on 127.0.0.1:[port]. */
        fun outbound(wg: WgConfig, port: Int): JsonObject = buildJsonObject {
            put("tag", "proxy")
            put("protocol", "wireguard")
            putJsonObject("settings") {
                put("secretKey", wg.privateKey)
                putJsonArray("address") { wg.addresses.forEach { add(it) } }
                putJsonArray("peers") {
                    add(buildJsonObject {
                        put("publicKey", wg.publicKey)
                        wg.presharedKey?.let { put("preSharedKey", it) }
                        put("endpoint", "127.0.0.1:$port")
                        put("keepAlive", wg.keepAlive)
                    })
                }
                put("mtu", wg.mtu)
                put("domainStrategy", "ForceIPv4")
            }
        }
    }
}

/** The parts of a WireGuard config ([Interface] + first [Peer]) the outbound needs; the endpoint is the core's. */
data class WgConfig(
    val privateKey: String,
    val addresses: List<String>,
    val publicKey: String,
    val presharedKey: String?,
    val mtu: Int,
    val keepAlive: Int,
) {
    companion object {
        /** Parses a wg-quick style config (pure, unit-tested); null if a key is missing. */
        fun parse(text: String): WgConfig? {
            var section = ""
            val iface = HashMap<String, String>()
            val peer = HashMap<String, String>()
            for (raw in text.lines()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                if (line.startsWith("[")) {
                    // Only the first peer counts.
                    section = if (line.equals("[Peer]", true) && peer.isNotEmpty()) "skip" else line.lowercase()
                    continue
                }
                val k = line.substringBefore('=').trim().lowercase()
                val v = line.substringAfter('=', "").trim()
                when (section) {
                    "[interface]" -> iface[k] = v
                    "[peer]" -> peer[k] = v
                }
            }
            val priv = iface["privatekey"]?.takeIf { it.isNotEmpty() } ?: return null
            val pub = peer["publickey"]?.takeIf { it.isNotEmpty() } ?: return null
            val addresses = iface["address"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .map { if ('/' in it) it else if (':' in it) "$it/128" else "$it/32" }
            return WgConfig(
                privateKey = priv,
                addresses = addresses.ifEmpty { return null },
                publicKey = pub,
                presharedKey = peer["presharedkey"]?.takeIf { it.isNotEmpty() },
                // The core path wraps packets once more: never above 1280 (the server's own value).
                mtu = iface["mtu"]?.toIntOrNull()?.coerceIn(1200, 1280) ?: 1280,
                keepAlive = peer["persistentkeepalive"]?.toIntOrNull()?.coerceIn(0, 120) ?: 25,
            )
        }
    }
}
