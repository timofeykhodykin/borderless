package app.borderless.data

import app.borderless.config.LinkParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleStreamTest {
    private fun single(link: String) = SingleStream.detect(LinkParser.parse(link).outbounds)

    private val base = "vless://11111111-1111-1111-1111-111111111111@a.example:443?type=xhttp&path=%2Fx"

    @Test
    fun explicitModes() {
        assertTrue(single("$base&security=tls&sni=x.com&mode=stream-one#a"))
        assertTrue(single("$base&security=tls&sni=x.com&mode=stream-up#a"))
        assertFalse(single("$base&security=tls&sni=x.com&mode=packet-up#a"))
        assertFalse(single("$base&security=reality&sni=x.com&pbk=AAAA&mode=packet-up#a"))
    }

    @Test
    fun autoFollowsXray() {
        // "auto": stream-one (or stream-up) with Reality, packet-up otherwise.
        assertTrue(single("$base&security=reality&sni=x.com&pbk=AAAA#a"))
        assertTrue(single("$base&security=reality&sni=x.com&pbk=AAAA&mode=auto#a"))
        assertFalse(single("$base&security=tls&sni=x.com#a"))
        assertFalse(single("$base&security=tls&sni=x.com&mode=auto#a"))
    }

    @Test
    fun otherTransportsAndJson() {
        assertFalse(single("vless://11111111-1111-1111-1111-111111111111@a.example:443?security=reality&sni=x.com&pbk=AAAA&type=tcp#a"))
        assertFalse(single("trojan://pw@a.example:443?security=tls&sni=x.com&type=ws&path=%2F#t"))
        val json = """{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"a.example","port":443,"users":[{"id":"11111111-1111-1111-1111-111111111111","encryption":"none"}]}]},
            "streamSettings":{"network":"xhttp","security":"tls","xhttpSettings":{"path":"/x","mode":"stream-one"}}}]}"""
        assertTrue(single(json))
    }
}
