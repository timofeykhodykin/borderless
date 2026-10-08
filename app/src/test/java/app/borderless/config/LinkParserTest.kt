package app.borderless.config

import app.borderless.data.AppSettings
import app.borderless.data.Regions
import app.borderless.data.AppRules
import app.borderless.data.Countries
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LinkParserTest {

    private fun JsonObject.obj(k: String) = this[k]!!.jsonObject
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

    @Test
    fun vlessReality() {
        val p = LinkParser.parse(
            "vless://11111111-2222-3333-4444-555555555555@203.0.113.7:443?security=reality&encryption=none&pbk=PUBKEY_abc-123&headerType=&fp=chrome&spx=%2F&type=tcp&flow=xtls-rprx-vision&sni=www.example.com&sid=07d2a82ab62311e2#DE%20Germany%20%C2%B7%20test"
        )
        assertEquals("DE Germany · test", p.name)
        assertEquals("VLESS · Reality", p.protocol)
        val o = p.outbounds.first()
        assertEquals("vless", o.str("protocol"))
        assertEquals("xtls-rprx-vision", o.obj("settings").str("flow"))
        val reality = o.obj("streamSettings").obj("realitySettings")
        assertEquals("www.example.com", reality.str("serverName"))
        assertEquals("PUBKEY_abc-123", reality.str("publicKey"))
        assertEquals("/", reality.str("spiderX"))
        assertEquals("DE", Countries.detect(p.name))
    }

    @Test
    fun vlessXhttpTls() {
        val p = LinkParser.parse(
            "vless://11111111-2222-3333-4444-555555555555@example.org:443?mode=stream-up&path=%2Fassets%2Fabc&security=tls&alpn=h2%2Chttp%2F1.1&encryption=none&insecure=0&host=example.org&fp=chrome&type=xhttp&allowInsecure=0&sni=example.org#%F0%9F%87%A9%F0%9F%87%AA%20xhttp"
        )
        val ss = p.outbounds.first().obj("streamSettings")
        assertEquals("xhttp", ss.str("network"))
        assertEquals("stream-up", ss.obj("xhttpSettings").str("mode"))
        assertEquals("/assets/abc", ss.obj("xhttpSettings").str("path"))
        assertEquals(listOf("h2", "http/1.1"), ss.obj("tlsSettings")["alpn"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertNull(ss.obj("tlsSettings")["allowInsecure"])
        assertEquals("DE", Countries.detect(p.name))
    }

    @Test
    fun vlessGrpcAndHttpUpgrade() {
        val grpc = LinkParser.parse("vless://id@example.org:443?security=tls&type=grpc&serviceName=svc1&sni=example.org#g")
        assertEquals("svc1", grpc.outbounds.first().obj("streamSettings").obj("grpcSettings").str("serviceName"))
        val hu = LinkParser.parse("vless://id@example.org:443?path=%2Fhu%2Fx&security=tls&type=httpupgrade&host=example.org#h")
        assertEquals("/hu/x", hu.outbounds.first().obj("streamSettings").obj("httpupgradeSettings").str("path"))
    }

    @Test
    fun trojanWs() {
        val p = LinkParser.parse("trojan://pass_word@example.org:443?path=%2Fws%2Fx&security=tls&host=example.org&type=ws&sni=example.org#%F0%9F%87%B8%F0%9F%87%AA%20trojan")
        val o = p.outbounds.first()
        assertEquals("trojan", o.str("protocol"))
        assertEquals("pass_word", o.obj("settings").str("password"))
        assertEquals("/ws/x", o.obj("streamSettings").obj("wsSettings").str("path"))
        assertEquals("SE", Countries.detect(p.name))
    }

    @Test
    fun trojanDefaultsToTls() {
        val o = LinkParser.parse("trojan://pw@example.org:443#x").outbounds.first()
        assertEquals("tls", o.obj("streamSettings").str("security"))
        assertEquals("example.org", o.obj("streamSettings").obj("tlsSettings").str("serverName"))
    }

    @Test
    fun vmessBase64() {
        val json = """{"v":"2","ps":"Нидерланды","add":"example.org","port":"8443","id":"uuid","aid":"0","scy":"auto","net":"ws","type":"none","host":"cdn.example.org","path":"/ray","tls":"tls","sni":""}"""
        val p = LinkParser.parse("vmess://" + Base64.getEncoder().encodeToString(json.toByteArray()))
        assertEquals("Нидерланды", p.name)
        assertEquals(8443, p.port)
        val ss = p.outbounds.first().obj("streamSettings")
        assertEquals("cdn.example.org", ss.obj("tlsSettings").str("serverName"))
        assertEquals("NL", Countries.detect(p.name))
    }

    @Test
    fun shadowsocksVariants() {
        val sip002 = LinkParser.parse("ss://" + Base64.getUrlEncoder().withoutPadding().encodeToString("chacha20-ietf-poly1305:secret".toByteArray()) + "@1.2.3.4:8388#Tokyo")
        assertEquals("chacha20-ietf-poly1305", sip002.outbounds.first().obj("settings").str("method"))
        assertEquals("JP", Countries.detect(sip002.name))
        val plain2022 = LinkParser.parse("ss://2022-blake3-aes-128-gcm:a2V5%2Bkey%3D@1.2.3.4:443#x")
        assertEquals("a2V5+key=", plain2022.outbounds.first().obj("settings").str("password"))
        val legacy = LinkParser.parse("ss://" + Base64.getEncoder().encodeToString("aes-256-gcm:pw@5.6.7.8:9000".toByteArray()) + "#legacy")
        assertEquals("5.6.7.8", legacy.host)
        assertEquals(9000, legacy.port)
    }

    @Test
    fun hysteria2() {
        val p = LinkParser.parse("hysteria2://auth%40x@example.org:443,20000-30000/?sni=real.example.org&obfs=salamander&obfs-password=ob&insecure=1#hy")
        val o = p.outbounds.first()
        assertEquals("hysteria", o.str("protocol"))
        val ss = o.obj("streamSettings")
        assertEquals("auth@x", ss.obj("hysteriaSettings").str("auth"))
        assertEquals("real.example.org", ss.obj("tlsSettings").str("serverName"))
        assertEquals("salamander", (ss.obj("finalmask")["udp"] as JsonArray).first().jsonObject.str("type"))
        assertEquals("443,20000-30000", ss.obj("finalmask").obj("quicParams").obj("udpHop").str("ports"))
    }

    @Test
    fun wireguard() {
        val p = LinkParser.parse("wireguard://cHJpdmF0ZQ%3D%3D@162.159.192.1:2408?publickey=cHVibGlj&address=172.16.0.2%2F32%2C2606%3A4700%3A%3A1%2F128&reserved=1%2C2%2C3&mtu=1280#warp")
        val s = p.outbounds.first().obj("settings")
        assertEquals("cHJpdmF0ZQ==", s.str("secretKey"))
        assertEquals(2, s["address"]!!.jsonArray.size)
        assertEquals("162.159.192.1:2408", s["peers"]!!.jsonArray.first().jsonObject.str("endpoint"))
    }

    @Test
    fun extractsFromBase64Subscription() {
        val body = Base64.getEncoder().encodeToString("vless://a@h:1?type=tcp#1\ntrojan://b@h:2#2\n".toByteArray())
        assertEquals(2, LinkParser.extractLinks(body).size)
    }

    @Test
    fun jsonConfigWithChain() {
        val cfg = """{"remarks":"chain","outbounds":[
            {"tag":"main","protocol":"vless","settings":{"vnext":[{"address":"a.example","port":443,"users":[{"id":"x"}]}]},
             "streamSettings":{"network":"tcp","security":"reality","sockopt":{"dialerProxy":"hop"}}},
            {"tag":"hop","protocol":"shadowsocks","settings":{"servers":[{"address":"b.example","port":1,"method":"aes-128-gcm","password":"p"}]}},
            {"tag":"direct","protocol":"freedom"}]}"""
        val p = LinkParser.parse(LinkParser.extractLinks(cfg).single())
        assertEquals("chain", p.name)
        assertEquals(2, p.outbounds.size)
        assertEquals("proxy", p.outbounds[0].str("tag"))
        assertEquals("a.example", p.host)
    }

    @Test
    fun unsupportedSchemes() {
        val e = runCatching { LinkParser.parse("tuic://x@h:1#t") }.exceptionOrNull()
        assertTrue(e is UnsupportedLinkException)
    }

    @Test
    fun configRouting() {
        val outbounds = LinkParser.parse("vless://id@1.2.3.4:443?type=tcp&security=reality&pbk=k#x").outbounds
        val full = Json.parseToJsonElement(XrayConfig.build(XrayConfig.Mode.PROXY, outbounds, AppSettings())).jsonObject
        val rules = full.obj("routing")["rules"]!!.jsonArray.map { it.jsonObject }
        // Routing rules apply only to traffic from the TUN, so health checks always use the proxy.
        assertTrue(rules.filter { it["domain"] != null }.all { it["inboundTag"]!!.jsonArray.first().jsonPrimitive.content == "tun" })
        assertEquals("proxy", full["outbounds"]!!.jsonArray.first().jsonObject.str("tag"))
        val direct = Json.parseToJsonElement(XrayConfig.build(XrayConfig.Mode.DIRECT, emptyList(), AppSettings())).jsonObject
        assertEquals("direct", direct["outbounds"]!!.jsonArray.first().jsonObject.str("tag"))
    }

    @Test
    fun zonesRouting() {
        val outbounds = LinkParser.parse("vless://id@1.2.3.4:443?type=tcp&security=reality&pbk=k#x").outbounds
        fun rules(s: AppSettings) = Json.parseToJsonElement(XrayConfig.build(XrayConfig.Mode.PROXY, outbounds, s)).jsonObject
            .obj("routing")["rules"]!!.jsonArray.map { it.jsonObject }
        fun mentions(s: AppSettings, v: String) = rules(s).any { r -> listOf("domain", "ip").any { k -> r[k]?.jsonArray?.any { it.jsonPrimitive.content == v } == true } }
        // Whatever zones regions.json has: a new install has them all on; with none, only private addresses go direct.
        val zone = Regions.all.first()
        assertEquals(Regions.all.map { it.code }.toSet(), AppSettings.fresh().regions)
        val none = AppSettings.fresh().copy(regions = emptySet())
        assertFalse(mentions(none, zone.directIps.first()))
        assertTrue(mentions(none, "geosite:private"))
        // A zone turned on brings its lists (direct sites and addresses, and the ones for the server).
        val on = AppSettings.fresh().copy(regions = setOf(zone.code))
        assertTrue(mentions(on, zone.directIps.first()))
        assertTrue(mentions(on, zone.directDomains.first()))
        assertTrue(mentions(on, zone.serverDomains.first()))
        // Direct names are resolved by the zone's resolver, else a public one; the user's own wins.
        assertEquals(zone.dns, XrayConfig.RegionRules(on).dns)
        assertEquals("1.1.1.1", XrayConfig.RegionRules(none).dns.first())
        assertEquals(listOf("9.9.9.9"), XrayConfig.RegionRules(on.copy(directDns = "9.9.9.9")).dns)
        // Apps of the zone are recognised by the same file (whole names and fragments are stored hashed).
        assertTrue(zone.apps.matches(zone.apps.prefixes.first() + "example"))
        assertFalse(zone.apps.matches("org.example.unrelated"))
        val pkg = "com.example.someapp"
        assertTrue(AppRules(packages = setOf(Regions.hash(pkg))).matches(pkg))
        assertTrue(AppRules(markers = listOf("7:" + Regions.hash("someapp"))).matches("org.other.someapp.lite"))
        assertFalse(AppRules(markers = listOf("7:" + Regions.hash("someapp"))).matches("org.other.app"))
    }


    @Test
    fun countries() {
        assertEquals("FR", Countries.detect("🇫🇷 Париж, Франция, Extra"))
        assertEquals("DE", Countries.detect("Франкфурт-на-Майне"))
        assertEquals("US", Countries.detect("New York 01"))
        assertEquals("NL", Countries.detect("[NL] fast"))
        assertNull(Countries.detect("m2eow"))
        assertNull(Countries.detect("tg-1234567890 grpc"))
    }

    @Test
    fun chainOutboundsCannotTakeOverAppTags() {
        // A chain outbound tagged "direct" would otherwise replace the app's own direct outbound.
        val json = """
            {"outbounds":[
              {"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"a.example","port":443,"users":[{"id":"u"}]}]},
               "streamSettings":{"sockopt":{"dialerProxy":"direct"}}},
              {"tag":"direct","protocol":"vless","settings":{"vnext":[{"address":"evil.example","port":443,"users":[{"id":"x"}]}]}}
            ]}
        """.trimIndent()
        val outs = LinkParser.parse(json).outbounds
        val tags = outs.map { it["tag"].toString().trim('"') }
        assertTrue("direct" !in tags)
        assertTrue(tags.first() == "proxy")
        assertTrue(outs.first().toString().contains("chain-0"))
    }
}
