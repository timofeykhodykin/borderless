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
    fun measurementsReplaceEstimatesAsWindowsGrow() {
        val priors = mapOf("A" to 1.0, "B" to 1.5, "C" to 1.2)
        // A and B measured over the same traffic bands: B really costs 3× A on this phone.
        val windows = (0 until 100).flatMap { i ->
            val bytes = (1L shl 20) * (1 + i % 8)
            listOf(ProtocolCost.Window("A", bytes, 1_000), ProtocolCost.Window("B", bytes, 3_000))
        }
        val f = ProtocolCost.factors(priors, windows)
        assertEquals(3.0, f.getValue("B") / f.getValue("A"), 1e-6)
        assertEquals(1.2, f.getValue("C"), 1e-9) // unmeasured: the estimate
        // Too few windows: estimates stay.
        assertEquals(priors, ProtocolCost.factors(priors, windows.take(10)))
    }

    @Test
    fun classesAreComparedAtTheSameTraffic() {
        // CPU depends on the traffic band, not on the class; A happened to carry more bytes per window.
        // CPU per GB would call B ten times dearer; at the same traffic they cost the same.
        val windows = (0 until 40).flatMap {
            listOf(
                ProtocolCost.Window("A", 100L shl 20, 30_000), ProtocolCost.Window("A", 512L shl 10, 3_000),
                ProtocolCost.Window("B", 100L shl 20, 30_000), ProtocolCost.Window("B", 512L shl 10, 3_000),
                ProtocolCost.Window("B", 512L shl 10, 3_000), ProtocolCost.Window("B", 512L shl 10, 3_000),
            )
        }
        val rel = ProtocolCost.relative(windows)
        assertEquals(1.0, rel.getValue("A").first, 1e-9)
        assertEquals(1.0, rel.getValue("B").first, 1e-9)
    }

    @Test
    fun weightingKeepsConnectionFirst() {
        // Cheaper but a bit slower wins; much slower never does.
        assertTrue(ProtocolCost.adjusted(120, 1.0, 1.0, 100) < ProtocolCost.adjusted(100, 1.4, 1.0, 100))
        assertTrue(ProtocolCost.adjusted(400, 1.0, 1.0, 100) > ProtocolCost.adjusted(100, 1.6, 1.0, 100))
    }
}
