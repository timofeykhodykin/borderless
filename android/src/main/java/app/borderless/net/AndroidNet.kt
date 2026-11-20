package app.borderless.net

import java.io.IOException
import java.net.Authenticator
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException

/**
 * [NetHost] on Android: `HttpURLConnection`. The app is outside its own tunnel, so plain sockets go directly. A local
 * SOCKS5 proxy's credentials are answered by the default [Authenticator] (Android's HTTP client asks it for SOCKS).
 */
object AndroidNet : NetHost {
    private val credentials = ConcurrentHashMap<Int, PasswordAuthentication>()

    fun attach() {
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? =
                if (requestingHost == "127.0.0.1" || requestingHost == "localhost") credentials[requestingPort] else null
        })
        Net.host = this
    }

    /** A JVM [Proxy] for [socks] (its credentials registered for the default Authenticator); [Proxy.NO_PROXY] for null. */
    fun proxy(socks: Socks?): Proxy {
        if (socks == null) return Proxy.NO_PROXY
        credentials[socks.port] = PasswordAuthentication(socks.user, socks.pass.toCharArray())
        return Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socks.port))
    }

    /** What kind of failure a JVM exception is ([NetError.Kind]). */
    fun classify(e: Throwable): Throwable = when {
        e is NetError || e is TooBig || e !is IOException -> e
        e is UnknownHostException -> NetError(NetError.Kind.DNS, e.message ?: "unknown host", e)
        e is SSLException -> NetError(NetError.Kind.TLS, e.message ?: "TLS failed", e)
        e is SocketTimeoutException || e is ConnectException || e is NoRouteToHostException || "Failed to connect" in e.message.orEmpty() ->
            NetError(NetError.Kind.UNREACHABLE, e.message ?: "unreachable", e)
        else -> NetError(NetError.Kind.OTHER, e.message ?: e.javaClass.simpleName, e)
    }

    override fun request(r: HttpRequest): HttpResponse {
        val c = (URL(r.url).openConnection(proxy(r.proxy)) as HttpURLConnection).apply {
            connectTimeout = r.connectTimeoutMs
            readTimeout = r.readTimeoutMs
            instanceFollowRedirects = r.followRedirects
            requestMethod = r.method
            r.headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = c.responseCode
            val headers = c.headerFields.orEmpty().entries
                .filter { it.key != null && it.value.isNotEmpty() }
                .associate { it.key.lowercase() to it.value.first() }
            if (r.statusOnly || r.method == "HEAD") return HttpResponse(code, headers, ByteArray(0), c.url.toString())
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val body = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > r.maxBytes) throw TooBig(r.maxBytes)
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } ?: ByteArray(0)
            return HttpResponse(code, headers, body, c.url.toString())
        } catch (e: Exception) {
            throw classify(e)
        } finally {
            c.disconnect()
        }
    }

    override fun freePort(): Int = ServerSocket(0).use { it.localPort }

    override fun socksAccepts(port: Int, user: String, pass: String): Boolean = runCatching {
        Socket().use { sock ->
            sock.connect(InetSocketAddress("127.0.0.1", port), 2_000)
            sock.soTimeout = 2_000
            val out = sock.getOutputStream()
            val input = sock.getInputStream()
            out.write(byteArrayOf(5, 1, 2)) // SOCKS5, one method: user name / password
            out.flush()
            if (input.read() != 5 || input.read() != 2) return@use false
            val u = user.toByteArray()
            val pw = pass.toByteArray()
            out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(pw.size.toByte()) + pw)
            out.flush()
            input.read() == 1 && input.read() == 0
        }
    }.getOrDefault(false)
}
