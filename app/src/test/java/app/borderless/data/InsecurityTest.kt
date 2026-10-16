package app.borderless.data

import app.borderless.config.LinkParser
import app.borderless.config.UnsupportedLinkException
import app.borderless.data.Insecurity.Issue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InsecurityTest {
    private fun of(link: String) = Insecurity.issues(LinkParser.parse(link).outbounds)

    @Test
    fun protectedConfigs() {
        assertEquals(emptySet<Issue>(), of("vless://id@h.example:443?type=tcp&security=reality&pbk=k&sni=a.com#x"))
        assertEquals(emptySet<Issue>(), of("vless://id@h.example:443?type=ws&security=tls&sni=a.com#x"))
        assertEquals(emptySet<Issue>(), of("trojan://pw@h.example:443?sni=a.com#x"))
        assertEquals(emptySet<Issue>(), of("ss://" + java.util.Base64.getEncoder().encodeToString("aes-128-gcm:pw".toByteArray()) + "@h.example:8388#x"))
        assertEquals(emptySet<Issue>(), of("hysteria2://pw@h.example:443?sni=a.com#x"))
        // A pinned certificate makes allowInsecure harmless (the link parser drops it then anyway).
        assertEquals(emptySet<Issue>(), of("vless://id@h.example:443?type=tcp&security=tls&allowInsecure=1&pcs=AB#x"))
    }

    @Test
    fun unprotectedConfigs() {
        assertEquals(setOf(Issue.NO_ENCRYPTION), of("vless://id@h.example:80?type=ws&security=none#x"))
        assertEquals(setOf(Issue.NO_ENCRYPTION), of("socks://dXNlcjpwYXNz@h.example:1080#x"))
        assertEquals(setOf(Issue.NO_CERT_CHECK), of("vless://id@h.example:443?type=ws&security=tls&allowInsecure=1#x"))
        assertEquals(setOf(Issue.NO_CERT_CHECK), of("hysteria2://pw@h.example:443?insecure=1#x"))
    }

    @Test
    fun jsonWithDirectExitIsRefused() {
        val freedom = """{"outbounds":[{"tag":"proxy","protocol":"freedom"},{"tag":"direct","protocol":"freedom"}]}"""
        assertThrows(UnsupportedLinkException::class.java) { LinkParser.parse(freedom) }
        val chain = """{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"address":"h","port":443,"id":"x","encryption":"none"},
            "streamSettings":{"security":"tls","sockopt":{"dialerProxy":"d"}}},{"tag":"d","protocol":"freedom"}]}"""
        assertThrows(UnsupportedLinkException::class.java) { LinkParser.parse(chain) }
    }
}
