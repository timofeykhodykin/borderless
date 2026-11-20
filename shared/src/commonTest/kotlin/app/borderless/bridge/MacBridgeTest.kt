package app.borderless.bridge

import app.borderless.data.ServerBuild
import app.borderless.data.ShareFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacBridgeTest {
    private val vless = "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=tcp&security=tls&sni=example.com#Frankfurt%201"

    @Test
    fun serverInfoUsesTheProxyEndpointInsteadOfDnsOrRealityNames() {
        val config = """{
          "dns":{"servers":["https://one.one.one.one/dns-query"]},
          "outbounds":[
            {"tag":"direct","protocol":"freedom"},
            {"tag":"proxy","protocol":"vless",
              "settings":{"vnext":[{"address":"entry.example.com","port":8443,"users":[{"id":"u"}]}]},
              "streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"cover.example.com"}}}
          ]} """
        val p = Json.decodeFromString(MacBridge.ServerInfo.serializer(), MacBridge.serverInfo(config))
        assertEquals("entry.example.com", p.host)
        assertEquals(8443, p.port)
        assertTrue(p.protocol.contains("Reality"))
    }

    @Test
    fun serverInfoSupportsFlatJsonSettingsAndOrdinaryLinks() {
        val config = """{"outbounds":[{"protocol":"vless","settings":{"address":"entry.example.com","port":443,"id":"u"}}]}"""
        val p = Json.decodeFromString(MacBridge.ServerInfo.serializer(), MacBridge.serverInfo(config))
        assertEquals("entry.example.com", p.host)
        assertEquals(443, p.port)
        val link = Json.decodeFromString(MacBridge.ServerInfo.serializer(), MacBridge.serverInfo(vless))
        assertEquals("example.com", link.host)
        assertEquals(443, link.port)
        assertEquals("{}", MacBridge.serverInfo("not a link"))
    }

    @Test
    fun serverIdsAreThoseOfEarlierReleases() {
        // SHA-1 of "<subscription>|<link without its name>", first 8 bytes (Python's hashlib gives the same).
        assertEquals("0c001ab4cf854621", ServerBuild.idFor("vless://11111111-2222-3333-4444-555555555555@example.com:443?type=tcp#Name", null))
        assertEquals("f534382c61a29aa5", ServerBuild.idFor("trojan://examplePass@198.51.100.7:443#x", "sub1"))
    }

    @Test
    fun parsesLinksSubscriptionsAndProblems() {
        val text = "$vless\nhttps://sub.example.com/token\nhttp://plain.example.com/x\ntuic://a@b.example:1\n"
        val p = Json.decodeFromString(MacBridge.Parsed.serializer(), MacBridge.parse(text))
        val g = p.groups.single()
        assertNull(g.name)
        val s = g.servers.single()
        assertEquals("Frankfurt 1", s.name)
        assertNull(s.country) // IP lookup takes precedence over the name hint.
        assertEquals(listOf("https://sub.example.com/token"), g.subscriptions.map { it.url })
        assertEquals(2, p.problems.size)
        // Without a platform the explanation is the string's name.
        assertTrue(p.problems.any { it.text == "imp_sub_http" } && p.problems.any { it.text == "imp_unsupported" })
    }

    @Test
    fun readsBundles() {
        val bundle = ShareFormat.encode(ShareFormat.Bundle(groups = listOf(
            ShareFormat.G(name = "Work", servers = listOf(ShareFormat.S(vless, off = true))),
        )))
        val p = Json.decodeFromString(MacBridge.Parsed.serializer(), MacBridge.parse(bundle))
        val g = p.groups.single()
        assertEquals("Work", g.name)
        assertTrue(g.servers.single().hidden)
    }

    @Test
    fun shareBundlesDecodeOnTheSharedSide() {
        // The Mac's share link must be the Android one: `ShareFormat.decode` reads it (audit A4 — a zlib stream
        // instead of raw deflate read as "damaged" on Android).
        val groups = Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(MacBridge.ShareGroup.serializer()),
            listOf(
                MacBridge.ShareGroup(
                    name = "Work",
                    servers = listOf(MacBridge.ShareServer(vless, off = true)),
                    subs = listOf(MacBridge.ShareSub("https://sub.example.com/token", "Sub", listOf(ShareFormat.key(vless)))),
                ),
                MacBridge.ShareGroup(default = true),
            ),
        )
        val link = MacBridge.encodeShare(groups)
        assertTrue(link.startsWith(ShareFormat.PREFIX), link)
        val b = ShareFormat.decode(link)
        assertNotNull(b)
        assertEquals("Work", b.groups[0].name)
        assertEquals(vless, b.groups[0].servers.single().l)
        assertTrue(b.groups[0].servers.single().off)
        assertEquals(listOf(ShareFormat.key(vless)), b.groups[0].subs.single().hidden)
        assertTrue(b.groups[1].default)
        assertEquals("", MacBridge.encodeShare("not json"))
    }

    @Test
    fun scrubsTheGivenHosts() {
        // The hosts come from the given links (the app's own list — the shared repo never runs in the app).
        val text = "dial example.com:443 i/o timeout, ping 203.0.113.7 ok"
        assertEquals("dial server-1:443 i/o timeout, ping ip-1 ok", MacBridge.scrub(text, true, listOf(vless)))
        assertTrue("example.com" in MacBridge.scrub(text, false, emptyList()))
    }

    @Test
    fun buildsTheCoreConfigBoundToTheInterface() {
        val r = Json.decodeFromString(MacBridge.Config.serializer(), MacBridge.config(vless, "bdiface0", "198.51.100.53"))
        assertNull(r.error)
        val config = Json.parseToJsonElement(assertNotNull(r.config)).jsonObject
        val proxy = config["outbounds"]!!.jsonArray.map { it.jsonObject }.first { it["tag"]?.jsonPrimitive?.content == "proxy" }
        assertEquals("bdiface0", (proxy["streamSettings"]!!.jsonObject["sockopt"] as JsonObject)["interface"]!!.jsonPrimitive.content)
        assertTrue("198.51.100.53" in config.toString())
        val bad = Json.decodeFromString(MacBridge.Config.serializer(), MacBridge.config("nonsense", "en0", "192.0.2.53"))
        assertNotNull(bad.error)
    }
}

class MacBridgeOutputTest {
    @Test
    fun emptyListsAreWritten() {
        // Swift decodes these keys; a bundle that imports cleanly has no problems, and the key must still be there.
        val out = MacBridge.parse("vless://11111111-2222-3333-4444-555555555555@example.com:443?type=tcp#x")
        assertTrue("\"problems\":[]" in out, out)
    }
}
