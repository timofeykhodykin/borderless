package app.borderless.data

import app.borderless.platform.Host
import kotlinx.serialization.SerializationException

/** A problem with one piece of input: [item] a short excerpt to recognise it, [text] the explanation. */
data class ImportIssue(val item: String, val text: String, val detail: String? = null)

/**
 * Plain-language explanations of a link that could not be turned into a server (the add sheet / dialog). The parser's
 * own messages are short technical English; they stay as the [ImportIssue.detail] under the explanation. Subscription
 * failures are explained by the platform (`ImportErrors` on Android: they depend on its network errors).
 */
object LinkErrors {
    /** Protocols people meet in share links that Xray can't run (scheme → readable name). */
    private val unsupported = mapOf(
        "tuic" to "TUIC", "hysteria" to "Hysteria 1", "hy" to "Hysteria 1", "ssr" to "ShadowsocksR",
        "naive+https" to "NaïveProxy", "naive+quic" to "NaïveProxy", "naive" to "NaïveProxy", "juicity" to "Juicity",
        "anytls" to "AnyTLS", "mieru" to "Mieru", "brook" to "Brook", "ssh" to "SSH", "snell" to "Snell",
        "shadowtls" to "ShadowTLS", "http" to "HTTP proxy", "https" to "HTTPS proxy",
    )

    /** What a link looks like in a list of problems: scheme and host, or the start of it. */
    fun excerpt(link: String): String {
        val t = link.trim()
        if (t.startsWith("{")) return "Xray JSON"
        val scheme = t.substringBefore("://", "")
        val host = t.substringAfter("://", "").substringAfter('@').substringBefore('?').substringBefore('#').substringBefore('/')
        return if (scheme.isNotEmpty() && host.isNotEmpty() && host.length < 60) "$scheme://$host" else t.take(40) + if (t.length > 40) "…" else ""
    }

    /** A share link (or Xray JSON) that could not be turned into a server. */
    fun forLink(link: String, e: Throwable): ImportIssue {
        val msg = e.message.orEmpty()
        val scheme = link.trim().substringBefore("://", "").lowercase()
        val t = Host.text
        val text = when {
            scheme == "happ" -> t("imp_happ", emptyList())
            scheme in unsupported -> t("imp_unsupported", listOf(unsupported.getValue(scheme)))
            msg == "not a link" || (scheme.isEmpty() && !link.trimStart().startsWith("{")) -> t("imp_not_link", emptyList())
            e is SerializationException || msg.startsWith("Unexpected JSON") -> t("imp_json_bad", emptyList())
            "no proxy outbound" in msg -> t("imp_json_no_proxy", emptyList())
            "plugins" in msg -> t("imp_ss_plugin", emptyList())
            "v2rayN format" in msg -> t("imp_vmess_format", emptyList())
            "bad base64" in msg -> t("imp_base64", emptyList())
            "no port" in msg -> t("imp_incomplete", listOf(t("imp_part_port", emptyList())))
            "no address" in msg || "bad IPv6" in msg -> t("imp_incomplete", listOf(t("imp_part_address", emptyList())))
            "no method" in msg -> t("imp_incomplete", listOf(t("imp_part_method", emptyList())))
            "is not supported" in msg -> t("imp_unsupported", listOf(scheme.ifEmpty { "?" }))
            else -> t("imp_damaged", emptyList())
        }
        return ImportIssue(excerpt(link), text, msg.takeIf { it.isNotBlank() })
    }

    /** What the provider sent instead of servers. */
    enum class BodyKind { EMPTY, HTML, CLASH, SING_BOX, HAPP, UNKNOWN }

    /** Guesses what a subscription body that gave no servers is. */
    fun kindOf(body: String): BodyKind {
        val t = body.trim()
        return when {
            t.isEmpty() -> BodyKind.EMPTY
            t.startsWith("<") || t.contains("<html", ignoreCase = true) -> BodyKind.HTML
            t.contains("happ://crypt") -> BodyKind.HAPP
            Regex("""(?m)^proxies:\s*$""").containsMatchIn(t) || t.contains("\nproxy-groups:") -> BodyKind.CLASH
            t.startsWith("{") && t.contains("\"outbounds\"") && t.contains("\"type\"") && !t.contains("\"protocol\"") -> BodyKind.SING_BOX
            else -> BodyKind.UNKNOWN
        }
    }
}

/** Servers from share links, as every platform stores them. */
object ServerBuild {
    /**
     * Servers for [links] (ids stable per link and subscription; country waits for IP lookup) and the problems of the links
     * that gave none.
     */
    fun build(links: List<String>, subscriptionId: String?, groupId: String): Pair<List<Server>, List<ImportIssue>> {
        val ok = ArrayList<Server>()
        val errors = ArrayList<ImportIssue>()
        val seen = HashSet<String>()
        for (link in links) {
            try {
                val p = app.borderless.config.LinkParser.parse(link)
                var id = idFor(link, subscriptionId)
                var n = 1
                while (!seen.add(id)) id = idFor(link, subscriptionId) + "-" + n++
                ok += Server(
                    id = id,
                    name = p.name,
                    link = link.trim(),
                    protocol = p.protocol,
                    host = p.host,
                    port = p.port,
                    subscriptionId = subscriptionId,
                    groupId = groupId,
                )
            } catch (e: Exception) {
                errors += LinkErrors.forLink(link, e)
            }
        }
        return ok to errors
    }

    /** First 8 bytes of SHA-1(subscription | link without its name), hex: the same link keeps its id (and statistics). */
    fun idFor(link: String, subscriptionId: String?): String {
        val core = if (link.trimStart().startsWith("{")) link else link.substringBefore('#')
        val digest = app.borderless.platform.sha1(((subscriptionId ?: "") + "|" + core.trim()).encodeToByteArray())
        return digest.copyOf(8).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}
