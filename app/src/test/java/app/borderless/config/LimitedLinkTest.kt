package app.borderless.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LimitedLinkTest {
    @Test
    fun colonForm() {
        val r = LimitedLink.parse("wdtt://203.0.113.7:56000:56001:9000:examplePass0001:exampleCallIdentifier")
        assertEquals("203.0.113.7", r.host)
        assertEquals(56000, r.port)
        assertEquals("examplePass0001", r.password)
        assertEquals(listOf("exampleCallIdentifier"), r.calls)
        assertEquals("203.0.113.7:56000", r.peer)
    }

    @Test
    fun connectForm() {
        val r = LimitedLink.parse(
            "wdtt://connect?v=1&host=203.0.113.7&dtls=56010&wg=56001&local=9000&password=examplePass0001" +
                "&hashes=call1%2Ccall2&name=My%20server&max_workers=18"
        )
        assertEquals("203.0.113.7", r.host)
        assertEquals(56010, r.port)
        assertEquals("examplePass0001", r.password)
        assertEquals(listOf("call1", "call2"), r.calls)
        assertEquals("My server", r.name)
        assertNull(r.streams)
        assertEquals(18, r.maxWorkers)
    }

    @Test
    fun withDeviceKeepsEveryForm() {
        val connect = "wdtt://connect?v=1&host=203.0.113.7&dtls=56000&wg=56001&local=9000&password=examplePass0001&hashes=call1#Mine"
        val c = LimitedLink.withDevice(connect, "phone 1")
        assertTrue(c.endsWith("&device=phone%201#Mine"))
        assertEquals("phone 1", LimitedLink.parse(c).deviceId)
        assertEquals("Mine", LimitedLink.parse(c).name)
        // A second share replaces the id instead of adding another.
        val again = LimitedLink.withDevice(c, "phone-2")
        assertEquals(1, Regex("device=").findAll(again).count())
        assertEquals("phone-2", LimitedLink.parse(again).deviceId)

        val colon = LimitedLink.withDevice("wdtt://203.0.113.7:56000:56001:9000:pw:call1,call2#Old", "d1")
        assertTrue(colon.startsWith("wdtt://connect?v=1&host=203.0.113.7&dtls=56000&wg=56001&local=9000&password=pw&hashes=call1%2Ccall2&device=d1"))
        LimitedLink.parse(colon).let {
            assertEquals(listOf("call1", "call2"), it.calls)
            assertEquals("pw", it.password)
            assertEquals("d1", it.deviceId)
            assertEquals("Old", it.name)
        }

        val q = LimitedLink.withDevice("qwdtt://config?peer=1.2.3.4:5600&pass=pw&hashes=a&deviceId=old", "new")
        assertEquals("new", LimitedLink.parse(q).deviceId)
        assertTrue("deviceId=old" !in q)

        val payload = Base64.getEncoder().encodeToString("""{"peer":"5.6.7.8:56000","pass":"pw","hashes":"c"}""".toByteArray())
        val j = LimitedLink.withDevice("wdtt://$payload", "jd")
        assertEquals("jd", LimitedLink.parse(j).deviceId)
        assertEquals(listOf("c"), LimitedLink.parse(j).calls)

        assertEquals(connect, LimitedLink.withDevice(connect, " "))
        assertEquals("vless://x@example.com:443", LimitedLink.withDevice("vless://x@example.com:443", "d"))
    }

    @Test
    fun connectFormIpv6AndDefaultPort() {
        val v6 = LimitedLink.parse("wdtt://connect?v=1&host=%5B2001%3Adb8%3A%3A1%5D&dtls=56000&wg=56001&local=9000&password=p&hashes=a")
        assertEquals("2001:db8::1", v6.host)
        assertEquals("[2001:db8::1]:56000", v6.peer)
        val bare = LimitedLink.parse("wdtt://connect?v=1&host=host.example&password=p&hashes=a")
        assertEquals(56000, bare.port)
        assertThrows(UnsupportedLinkException::class.java) {
            LimitedLink.parse("wdtt://connect?v=1&host=host.example&dtls=56000&password=p&hashes=")
        }
    }

    @Test
    fun colonFormWithNameAndSeveralCalls() {
        val r = LimitedLink.parse("wdtt://host.example:56000:56001:9000:pw:call1,call2#My reserve")
        assertEquals(listOf("call1", "call2"), r.calls)
        assertEquals("My reserve", r.name)
    }

    @Test
    fun qwdttForm() {
        val r = LimitedLink.parse("qwdtt://config?peer=1.2.3.4:5600&pass=pw&hashes=a,b&workers=18&name=x")
        assertEquals("1.2.3.4", r.host)
        assertEquals(5600, r.port)
        assertEquals("pw", r.password)
        assertEquals(listOf("a", "b"), r.calls)
        assertEquals(18, r.streams)
        assertEquals("x", r.name)
    }

    @Test
    fun qwdttBareHostGetsDefaultPort() {
        val r = LimitedLink.parse("qwdtt://config?peer=host.example&pass=pw&hashes=a")
        assertEquals("host.example", r.host)
        assertEquals(56000, r.port)
    }

    @Test
    fun jsonForm() {
        val payload = Base64.getEncoder().encodeToString(
            """{"peer":"5.6.7.8:56000","pass":"pw","hashes":"c,d","workers":27,"name":"j"}""".toByteArray()
        )
        val r = LimitedLink.parse("wdtt://$payload")
        assertEquals("5.6.7.8", r.host)
        assertEquals(listOf("c", "d"), r.calls)
        assertEquals(27, r.streams)
        assertEquals("j", r.name)
    }

    @Test
    fun callJoinLinksReducedToId() {
        assertEquals(listOf("abc"), LimitedLink.calls("https://calls.example/call/join/abc?x=1"))
        assertEquals(listOf("abc", "def"), LimitedLink.calls("abc, def"))
        // A plain link that is not a call-join URL is dropped.
        assertTrue(LimitedLink.calls("https://example.com/page").isEmpty())
    }

    @Test
    fun matchesScheme() {
        assertTrue(LimitedLink.matches("wdtt://x"))
        assertTrue(LimitedLink.matches("qwdtt://config?x"))
        assertTrue(!LimitedLink.matches("vless://x"))
    }

    @Test
    fun badLinksRejected() {
        assertThrows(UnsupportedLinkException::class.java) { LimitedLink.parse("wdtt://host:56000:56001:9000") }
        assertThrows(UnsupportedLinkException::class.java) { LimitedLink.parse("qwdtt://config?peer=1.2.3.4:5600") }
    }

    @Test
    fun outboundPointsAtLocalPort() {
        val wg = WgConfig("priv", listOf("10.0.0.2/32"), "pub", null, 1280, 25)
        val o = LimitedLink.outbound(wg, 9123).toString()
        assertTrue("127.0.0.1:9123" in o)
        assertTrue("wireguard" in o)
        assertTrue("priv" in o)
    }
}

class WgConfigTest {
    private val config = """
        [Interface]
        PrivateKey = cHJpdmF0ZQ==
        Address = 10.66.66.2/32
        DNS = 1.1.1.1
        MTU = 1420

        [Peer]
        PublicKey = cHVibGlj
        AllowedIPs = 0.0.0.0/0
        Endpoint = 127.0.0.1:9000
        PersistentKeepalive = 25
    """.trimIndent()

    @Test
    fun parsesInterfaceAndFirstPeer() {
        val wg = WgConfig.parse(config)!!
        assertEquals("cHJpdmF0ZQ==", wg.privateKey)
        assertEquals("cHVibGlj", wg.publicKey)
        assertEquals(listOf("10.66.66.2/32"), wg.addresses)
        assertEquals(25, wg.keepAlive)
        // The core wraps packets once more: never above the server's 1280.
        assertEquals(1280, wg.mtu)
    }

    @Test
    fun missingKeyIsNull() {
        assertNull(WgConfig.parse("[Interface]\nAddress = 10.0.0.2/32\n[Peer]\nEndpoint = 127.0.0.1:9000"))
    }

    @Test
    fun onlyFirstPeerCounts() {
        val wg = WgConfig.parse(config + "\n\n[Peer]\nPublicKey = c2Vjb25k\n")!!
        assertEquals("cHVibGlj", wg.publicKey)
    }
}
