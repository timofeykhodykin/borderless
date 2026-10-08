package app.borderless.data

import app.borderless.config.LinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolCostTest {
    private fun cls(link: String) = ProtocolCost.classify(LinkParser.parse(link).outbounds)

    @Test
    fun classesAndOrder() {
        val vision = cls("vless://11111111-1111-1111-1111-111111111111@a.example:443?security=reality&sni=x.com&pbk=AAAA&fp=chrome&flow=xtls-rprx-vision&type=tcp#a")
        val grpc = cls("vless://11111111-1111-1111-1111-111111111111@a.example:443?security=tls&sni=x.com&type=grpc&serviceName=s#b")
        val hy2 = cls("hysteria2://pass@a.example:443?sni=x.com#c")
        assertEquals("VLESS Vision · TCP", vision.key)
        assertEquals("VLESS · gRPC", grpc.key)
        assertEquals("Hysteria2", hy2.key)
        assertEquals("Trojan · WS", cls("trojan://pw@a.example:443?security=tls&sni=x.com&type=ws&path=%2F#t").key)
        assertTrue(vision.prior < grpc.prior && grpc.prior < hy2.prior)
    }

    @Test
    fun measurementsReplaceEstimatesAsTrafficGrows() {
        val priors = mapOf("A" to 1.0, "B" to 1.5, "C" to 1.2)
        // A and B measured with lots of traffic: B is really 3× A on this phone.
        val measured = mapOf("A" to ProtocolCost.Measured(500L shl 20, 50_000), "B" to ProtocolCost.Measured(500L shl 20, 150_000))
        val f = ProtocolCost.factors(priors, measured)
        assertEquals(3.0, f.getValue("B") / f.getValue("A"), 1e-6)
        assertEquals(1.2, f.getValue("C"), 1e-9) // unmeasured: the estimate
        // Too little traffic: estimates stay.
        assertEquals(priors, ProtocolCost.factors(priors, mapOf("A" to ProtocolCost.Measured(1L shl 20, 9_000))))
    }

    @Test
    fun weightingKeepsConnectionFirst() {
        // Cheaper but a bit slower wins; much slower never does.
        assertTrue(ProtocolCost.adjusted(120, 1.0, 1.0, 100) < ProtocolCost.adjusted(100, 1.4, 1.0, 100))
        assertTrue(ProtocolCost.adjusted(400, 1.0, 1.0, 100) > ProtocolCost.adjusted(100, 1.6, 1.0, 100))
    }
}
