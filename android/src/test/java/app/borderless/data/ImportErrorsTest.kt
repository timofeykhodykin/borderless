package app.borderless.data

import app.borderless.net.AndroidNet
import app.borderless.net.HttpStatus
import app.borderless.net.NetError
import app.borderless.net.TooBig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportErrorsTest {
    @Test
    fun recognisesWhatCameInstead() {
        assertEquals(ImportErrors.BodyKind.EMPTY, ImportErrors.kindOf("  "))
        assertEquals(ImportErrors.BodyKind.HTML, ImportErrors.kindOf("<!DOCTYPE html><html><body>Login</body></html>"))
        assertEquals(ImportErrors.BodyKind.CLASH, ImportErrors.kindOf("mixed-port: 7890\nproxies:\n  - name: a\n    type: vless\n"))
        assertEquals(ImportErrors.BodyKind.SING_BOX, ImportErrors.kindOf("""{"outbounds":[{"type":"vless","tag":"a","server":"h"}]}"""))
        assertEquals(ImportErrors.BodyKind.HAPP, ImportErrors.kindOf("happ://crypt3/AAAA"))
        assertEquals(ImportErrors.BodyKind.UNKNOWN, ImportErrors.kindOf("something else"))
    }

    @Test
    fun excerptsAreShort() {
        assertEquals("vless://host.example:443", ImportErrors.excerpt("vless://uuid@host.example:443?security=reality#Name"))
        assertEquals("Xray JSON", ImportErrors.excerpt("""{"outbounds":[]}"""))
        assertEquals("plain words", ImportErrors.excerpt("plain words"))
    }

    @Test
    fun noConnectionIsNotAProblem() {
        // Can't reach the provider at all: likely no internet now (the JVM's exceptions as AndroidNet reports them).
        fun net(e: Throwable) = AndroidNet.classify(e)
        assertTrue(ImportErrors.unreachable(net(java.net.UnknownHostException("sub.example"))))
        assertTrue(ImportErrors.unreachable(net(java.net.SocketTimeoutException("timeout"))))
        assertTrue(ImportErrors.unreachable(net(java.net.ConnectException("Failed to connect"))))
        assertTrue(ImportErrors.unreachable(net(javax.net.ssl.SSLHandshakeException("connection reset"))))
        assertTrue(ImportErrors.unreachable(net(java.net.SocketException("Connection reset"))))
        // The provider answered: real problems.
        assertFalse(ImportErrors.unreachable(HttpStatus(404)))
        assertFalse(ImportErrors.unreachable(TooBig(10_000_000)))
        assertFalse(ImportErrors.unreachable(SubscriptionFormatException(ImportErrors.BodyKind.HTML)))
        assertFalse(ImportErrors.unreachable(SubscriptionLinksException("bad links")))
        // What kind of failure each one is.
        assertEquals(NetError.Kind.DNS, (net(java.net.UnknownHostException("x")) as NetError).kind)
        assertEquals(NetError.Kind.TLS, (net(javax.net.ssl.SSLHandshakeException("x")) as NetError).kind)
        assertEquals(NetError.Kind.UNREACHABLE, (net(java.net.SocketTimeoutException("x")) as NetError).kind)
        assertEquals(NetError.Kind.OTHER, (net(java.net.SocketException("Connection reset")) as NetError).kind)
    }
}
