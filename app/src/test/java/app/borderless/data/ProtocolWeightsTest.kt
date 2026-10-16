package app.borderless.data

import app.borderless.config.LinkParser
import app.borderless.data.ProtocolWeights.Trait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolWeightsTest {
    private fun traits(link: String) = ProtocolWeights.traits(LinkParser.parse(link).outbounds)

    private val id = "11111111-1111-1111-1111-111111111111"

    @Test
    fun linksGetTheirTraits() {
        // Vision over Reality: nothing to weigh (share links get a browser fingerprint by default).
        assertEquals(emptySet<Trait>(), traits("vless://$id@a.example:443?security=reality&sni=x.com&pbk=AAAA&type=tcp&flow=xtls-rprx-vision#a"))
        // gRPC links get health checks every 60 s of silence (LinkParser), which wake the radio.
        assertEquals(setOf(Trait.ONE_CONNECTION, Trait.GRPC_HEALTH_CHECK), traits("vless://$id@a.example:443?security=tls&sni=x.com&type=grpc&serviceName=s#a"))
        assertEquals(setOf(Trait.MANY_REQUESTS), traits("vless://$id@a.example:443?security=tls&sni=x.com&type=xhttp&path=%2Fx#a"))
        assertEquals(setOf(Trait.SINGLE_STREAM), traits("vless://$id@a.example:443?security=tls&sni=x.com&type=xhttp&path=%2Fx&mode=stream-one#a"))
        assertEquals(setOf(Trait.UDP, Trait.QUIC_KEEPALIVE), traits("hysteria2://pw@a.example:443?sni=x.com#h"))
        assertTrue(Trait.PLAIN_SHADOWSOCKS in traits("ss://YWVzLTI1Ni1nY206cHc@a.example:8388#s"))
    }

    @Test
    fun jsonFlags() {
        val json = """{"outbounds":[{"protocol":"vless","mux":{"enabled":true},"settings":{"vnext":[{"address":"a.example","port":443,"users":[{"id":"$id","encryption":"none"}]}]},
            "streamSettings":{"network":"ws","security":"tls","tlsSettings":{"serverName":"x.com"}}}]}"""
        // mux puts everything into one connection; TLS without a fingerprint.
        assertEquals(setOf(Trait.ONE_CONNECTION, Trait.NO_FINGERPRINT), traits(json))
    }

    @Test
    fun weightsCombineWithinLimits() {
        val w = ProtocolWeights.stability(setOf(Trait.SINGLE_STREAM, Trait.UDP, Trait.ONE_CONNECTION, Trait.QUIC_KEEPALIVE))
        assertEquals(ProtocolWeights.MAX_FACTOR, w.factor, 1e-9)
        assertEquals(ProtocolWeights.MAX_EXTRA_MS, w.extraMs)
        assertEquals(setOf(Trait.SINGLE_STREAM, Trait.UDP, Trait.ONE_CONNECTION), w.traits)
        assertEquals(1.4, ProtocolWeights.energy(setOf(Trait.QUIC_KEEPALIVE, Trait.UDP)), 1e-9)
        assertEquals(1.0, ProtocolWeights.energy(emptySet()), 1e-9)
        assertEquals(170.0, ProtocolWeights.stability(setOf(Trait.ONE_CONNECTION)).apply(100.0), 1e-9)
    }
}
