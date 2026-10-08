package app.borderless.data

import app.borderless.config.LinkParser
import org.junit.Assert.assertEquals
import org.junit.Test

class SupportStatsTest {
    private fun f(link: String) = SupportStats.features(link, LinkParser.parse(link).outbounds)

    @Test
    fun featuresOfLinks() {
        assertEquals(
            setOf("p:vless", "f:link", "t:tcp", "s:reality", "s:vision"),
            f("vless://11111111-1111-1111-1111-111111111111@a.example:443?security=reality&sni=x.com&pbk=AAAA&fp=chrome&flow=xtls-rprx-vision&type=tcp#a"),
        )
        assertEquals(setOf("p:trojan", "f:link", "t:ws", "s:tls"), f("trojan://pw@a.example:443?security=tls&sni=x.com&type=ws&path=%2F#b"))
        // Hysteria2 has no Xray transport of its own to list (its TLS may or may not be spelled out).
        assertEquals(setOf("p:hysteria", "f:link"), f("hysteria2://pass@a.example:443?sni=x.com#c") - "s:tls")
    }
}
