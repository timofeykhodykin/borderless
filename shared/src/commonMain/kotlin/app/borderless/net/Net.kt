package app.borderless.net

/**
 * The app's own network requests (checks, subscriptions, lists, country lookups), never through the tunnel: Android
 * excludes the app from it, the Mac's service binds them to the physical interface. Every call blocks its thread.
 */
interface NetHost {
    /** One HTTP(S) exchange. Throws [NetError] when there is no answer, [TooBig] past [HttpRequest.maxBytes]. */
    fun request(r: HttpRequest): HttpResponse

    /** A TCP port free on 127.0.0.1 right now (for a local proxy). */
    fun freePort(): Int

    /** Whether the SOCKS5 server on 127.0.0.1:[port] accepts [user] / [pass] (a stranger on the port must not pass). */
    fun socksAccepts(port: Int, user: String, pass: String): Boolean
}

/** A local SOCKS5 proxy with its credentials (a throwaway core through one of the user's servers, `ViaServer`). */
data class Socks(val port: Int, val user: String, val pass: String)

class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 20_000,
    val followRedirects: Boolean = true,
    /** The body read at most; a longer one throws [TooBig]. */
    val maxBytes: Long = 1L shl 20,
    val proxy: Socks? = null,
    /** HEAD-like checks: the status line is enough, no body is read. */
    val statusOnly: Boolean = false,
)

class HttpResponse(
    val code: Int,
    /** Header names in lower case, the first value of each. */
    val headers: Map<String, String>,
    val body: ByteArray,
    /** Where the answer came from after redirects. */
    val url: String = "",
) {
    fun header(name: String): String? = headers[name.lowercase()]
    val text: String get() = body.decodeToString()
}

/** No answer: what kind of failure, as far as the platform can tell. */
class NetError(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind {
        /** The name has no address (or the resolver can't be reached). */
        DNS,
        /** No connection, refused, or the time ran out. */
        UNREACHABLE,
        /** The connection broke during the TLS handshake or the certificate was refused. */
        TLS,
        /** Anything else on the way (a cut connection mid-body…). */
        OTHER,
    }
}

/** The body was larger than allowed. */
class TooBig(val limit: Long) : Exception("response larger than $limit bytes")

object Net {
    /** Set by the platform at start. */
    lateinit var host: NetHost

    fun request(r: HttpRequest): HttpResponse = host.request(r)

    /** GET [url]; an answer other than 2xx throws `HTTP <code>`. */
    fun get(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Long = 1L shl 20, proxy: Socks? = null,
            connectTimeoutMs: Int = 15_000, readTimeoutMs: Int = 20_000): HttpResponse {
        val r = host.request(HttpRequest(url, "GET", headers, connectTimeoutMs, readTimeoutMs, maxBytes = maxBytes, proxy = proxy))
        if (r.code !in 200..299) throw HttpStatus(r.code)
        return r
    }
}

/** An HTTP answer that isn't a success; the message is "HTTP <code>" as before. */
class HttpStatus(val code: Int) : Exception("HTTP $code")
