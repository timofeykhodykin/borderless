package app.borderless.data

/**
 * Keeps secrets out of what can leave the phone (SECURITY.md S12): error messages, kept error records, "copy
 * details", the detailed log when shared. Pure (unit-tested).
 */
object Scrub {
    private val PROXY_SCHEMES = "vless|vmess|trojan|ss|hysteria2|hy2|hysteria|socks5?|wireguard|wg|tuic|ssr|borderless"
    private val PROXY_LINK = Regex("\\b($PROXY_SCHEMES)://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
    private val WEB_LINK = Regex("\\b(https?)://([^/\\s\"'<>?#]+)[^\\s\"'<>]*", RegexOption.IGNORE_CASE)

    /** kotlinx.serialization quotes the input it failed on ("JSON input: …") — links with keys, passwords. */
    private val JSON_INPUT = Regex("JSON input:[^\\n]*")

    /**
     * [text] without credentials: share links reduced to their scheme, web addresses to their host (a subscription's
     * token is in the path), quoted JSON input dropped.
     */
    fun secrets(text: String): String = text
        .replace(JSON_INPUT, "JSON input: (hidden)")
        .replace(PROXY_LINK) { "${it.groupValues[1]}://…" }
        .replace(WEB_LINK) { m -> if (m.value.length > m.groupValues[1].length + 3 + m.groupValues[2].length) "${m.groupValues[1]}://${m.groupValues[2]}/…" else m.value }

    private val IPV4 = Regex("\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b")
    private val PRIVATE_V4 = Regex("^(10\\.|127\\.|192\\.168\\.|172\\.(1[6-9]|2\\d|3[01])\\.|169\\.254\\.|0\\.)")

    /**
     * [text] with the given server [hosts] (names and addresses) and every public IPv4 address replaced by stable
     * placeholders ("server-1", "ip-1"): the log stays readable — the same server is the same placeholder — without
     * handing out the server list. Longer hosts first, so "a.b.c" isn't half-replaced by "b.c".
     */
    fun addresses(text: String, hosts: Collection<String>): String {
        var out = text
        hosts.filter { it.length >= 4 && it != "?" }.distinct().sortedByDescending { it.length }.forEachIndexed { i, h ->
            out = out.replace(h, "server-${i + 1}", ignoreCase = true)
        }
        val ips = LinkedHashMap<String, String>()
        return IPV4.replace(out) { m ->
            if (PRIVATE_V4.containsMatchIn(m.value)) m.value else ips.getOrPut(m.value) { "ip-${ips.size + 1}" }
        }
    }
}
