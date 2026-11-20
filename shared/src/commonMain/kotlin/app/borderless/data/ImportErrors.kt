package app.borderless.data

import app.borderless.S
import app.borderless.net.HttpStatus
import app.borderless.net.NetError
import app.borderless.net.TooBig
import app.borderless.platform.text

/**
 * Plain-language explanations of what went wrong with a link or a subscription, for the add sheet and
 * the subscription rows (links: [LinkErrors], shared with the Mac). The parser's own messages are short technical English; they stay as the
 * [Issue.detail] under the explanation.
 */
object ImportErrors {
    /** A problem with one piece of input (shared with the Mac: [ImportIssue]). */
    typealias Issue = ImportIssue

    typealias BodyKind = LinkErrors.BodyKind

    fun excerpt(link: String): String = LinkErrors.excerpt(link)

    /** A share link (or Xray JSON) that could not be turned into a server. */
    fun forLink(link: String, e: Throwable): Issue = LinkErrors.forLink(link, e)

    /** Guesses what a subscription body that gave no servers is. */
    fun kindOf(body: String): BodyKind = LinkErrors.kindOf(body)

    /**
     * The provider could not be reached at all (no address, no connection, a timeout, the connection cut
     * during TLS): most likely there is no internet right now, nothing is wrong with the subscription.
     * An HTTP answer, a body too big or one without servers are real problems.
     */
    fun unreachable(e: Throwable): Boolean = e is NetError

    /** A subscription that could not be added or updated. */
    fun forSubscription(url: String, e: Throwable): Issue {
        val msg = e.message.orEmpty()
        val code = (e as? HttpStatus)?.code
        val text = when {
            e is SubscriptionLinksException -> msg
            e is SubscriptionFormatException -> text(
                when (e.kind) {
                    BodyKind.EMPTY -> S.imp_sub_empty
                    BodyKind.HTML -> S.imp_sub_html
                    BodyKind.CLASH -> S.imp_sub_clash
                    BodyKind.SING_BOX -> S.imp_sub_singbox
                    BodyKind.HAPP -> S.imp_happ
                    BodyKind.UNKNOWN -> S.imp_sub_unknown
                }
            )
            e is NetError && e.kind == NetError.Kind.DNS -> text(S.imp_sub_dns)
            e is NetError && e.kind == NetError.Kind.TLS -> text(S.imp_sub_tls)
            e is NetError && e.kind == NetError.Kind.UNREACHABLE -> text(S.imp_sub_unreachable)
            code == 401 || code == 403 -> text(S.imp_sub_denied)
            code == 404 || code == 410 -> text(S.imp_sub_not_found)
            code == 429 -> text(S.imp_sub_rate)
            code != null && code >= 500 -> text(S.imp_sub_server)
            e is TooBig -> text(S.imp_sub_too_big)
            else -> text(S.imp_sub_failed)
        }
        return Issue(Importer.safeUrl(url), text, msg.takeIf { it.isNotBlank() })
    }
}

/** Every link of a subscription was unusable; [message] is the explanation of the first one. */
class SubscriptionLinksException(message: String) : Exception(message)

/** A subscription answered, but with something that has no servers we can use. */
class SubscriptionFormatException(val kind: ImportErrors.BodyKind) : Exception("no usable servers in the response (${kind.name.lowercase()})")
