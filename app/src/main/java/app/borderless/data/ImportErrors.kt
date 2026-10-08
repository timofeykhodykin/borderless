package app.borderless.data

import app.borderless.R
import app.borderless.Res
import kotlinx.serialization.SerializationException

/**
 * Plain-language explanations of what went wrong with a link or a subscription, for the add sheet and
 * the subscription rows. The parser's own messages are short technical English; they stay as the
 * [Issue.detail] under the explanation.
 */
object ImportErrors {
    /** A problem with one piece of input: [item] a short excerpt to recognise it, [text] the explanation. */
    data class Issue(val item: String, val text: String, val detail: String? = null)

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
    fun forLink(link: String, e: Throwable): Issue {
        val msg = e.message.orEmpty()
        val scheme = link.trim().substringBefore("://", "").lowercase()
        val text = when {
            scheme == "happ" -> Res.s(R.string.imp_happ)
            scheme in unsupported -> Res.s(R.string.imp_unsupported, unsupported.getValue(scheme))
            msg == "not a link" || (scheme.isEmpty() && !link.trimStart().startsWith("{")) -> Res.s(R.string.imp_not_link)
            e is SerializationException || msg.startsWith("Unexpected JSON") -> Res.s(R.string.imp_json_bad)
            "no proxy outbound" in msg -> Res.s(R.string.imp_json_no_proxy)
            "plugins" in msg -> Res.s(R.string.imp_ss_plugin)
            "v2rayN format" in msg -> Res.s(R.string.imp_vmess_format)
            "bad base64" in msg -> Res.s(R.string.imp_base64)
            "no port" in msg -> Res.s(R.string.imp_incomplete, Res.s(R.string.imp_part_port))
            "no address" in msg || "bad IPv6" in msg -> Res.s(R.string.imp_incomplete, Res.s(R.string.imp_part_address))
            "no method" in msg -> Res.s(R.string.imp_incomplete, Res.s(R.string.imp_part_method))
            "is not supported" in msg -> Res.s(R.string.imp_unsupported, scheme.ifEmpty { "?" })
            else -> Res.s(R.string.imp_damaged)
        }
        return Issue(excerpt(link), text, msg.takeIf { it.isNotBlank() })
    }

    /** What the provider sent instead of servers (see [SubscriptionFormatException]). */
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

    /**
     * The provider could not be reached at all (no address, no connection, a timeout, the connection cut
     * during TLS): most likely there is no internet right now, nothing is wrong with the subscription.
     * An HTTP answer, a body too big or one without servers are real problems.
     */
    fun unreachable(e: Throwable): Boolean {
        val msg = e.message.orEmpty()
        return e is java.io.IOException && !msg.startsWith("HTTP ") && "larger than" !in msg
    }

    /** A subscription that could not be added or updated. */
    fun forSubscription(url: String, e: Throwable): Issue {
        val msg = e.message.orEmpty()
        val code = Regex("""HTTP (\d{3})""").find(msg)?.groupValues?.get(1)?.toIntOrNull()
        val text = when {
            e is SubscriptionLinksException -> msg
            e is SubscriptionFormatException -> Res.s(
                when (e.kind) {
                    BodyKind.EMPTY -> R.string.imp_sub_empty
                    BodyKind.HTML -> R.string.imp_sub_html
                    BodyKind.CLASH -> R.string.imp_sub_clash
                    BodyKind.SING_BOX -> R.string.imp_sub_singbox
                    BodyKind.HAPP -> R.string.imp_happ
                    BodyKind.UNKNOWN -> R.string.imp_sub_unknown
                }
            )
            e is java.net.UnknownHostException -> Res.s(R.string.imp_sub_dns)
            e is javax.net.ssl.SSLException -> Res.s(R.string.imp_sub_tls)
            e is java.net.SocketTimeoutException || e is java.net.ConnectException || "Failed to connect" in msg -> Res.s(R.string.imp_sub_unreachable)
            code == 401 || code == 403 -> Res.s(R.string.imp_sub_denied)
            code == 404 || code == 410 -> Res.s(R.string.imp_sub_not_found)
            code == 429 -> Res.s(R.string.imp_sub_rate)
            code != null && code >= 500 -> Res.s(R.string.imp_sub_server)
            "larger than" in msg -> Res.s(R.string.imp_sub_too_big)
            else -> Res.s(R.string.imp_sub_failed)
        }
        return Issue(Importer.safeUrl(url), text, msg.takeIf { it.isNotBlank() })
    }
}

/** Every link of a subscription was unusable; [message] is the explanation of the first one. */
class SubscriptionLinksException(message: String) : Exception(message)

/** A subscription answered, but with something that has no servers we can use. */
class SubscriptionFormatException(val kind: ImportErrors.BodyKind) : Exception("no usable servers in the response (${kind.name.lowercase()})")
