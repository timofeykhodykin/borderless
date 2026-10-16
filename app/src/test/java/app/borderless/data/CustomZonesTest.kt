package app.borderless.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomZonesTest {
    @Test
    fun entriesBecomeRules() {
        assertEquals("domain:example.com", ZoneFile.entry("example.com"))
        assertEquals("domain:example.com", ZoneFile.entry("*.Example.COM."))
        assertEquals("domain:example.com", ZoneFile.entry("https://example.com/path?q=1"))
        assertEquals("full:api.example.com", ZoneFile.entry("full:api.example.com"))
        assertEquals("domain:xn--d1acufc.xn--p1ai", ZoneFile.entry("домен.рф"))
        assertEquals("keyword:video", ZoneFile.entry("keyword:Video"))
        assertEquals("regexp:^a+\\.example\\.com$", ZoneFile.entry("regexp:^a+\\.example\\.com$"))
        assertEquals("geosite:category-ads", ZoneFile.entry("geosite:category-ads"))
        assertEquals("10.0.0.0/8", ZoneFile.entry("10.0.0.0/8"))
        assertEquals("2001:db8::/32", ZoneFile.entry("2001:DB8::/32"))
        // Hosts files.
        assertEquals("domain:ads.example.com", ZoneFile.entry("0.0.0.0 ads.example.com"))
        // Not sites or addresses: the core would refuse them.
        assertNull(ZoneFile.entry("300.1.1.1"))
        assertNull(ZoneFile.entry("10.0.0.0/40"))
        assertNull(ZoneFile.entry("regexp:(("))
        assertNull(ZoneFile.entry("not a domain"))
        assertNull(ZoneFile.entry("bad_label-.com"))
        assertNull(ZoneFile.entry("1.2.3"))
    }

    @Test
    fun fileRoundTrip() {
        val text = """
            # a comment
            name: Work
            intranet.example   # first list: direct
            10.1.0.0/16
            [server]
            other.example
            [apps]
            com.example.app
            not an app
            [direct]
            ???
        """.trimIndent()
        val p = ZoneFile.parse(text)
        assertEquals("Work", p.name)
        assertEquals(listOf("domain:intranet.example", "10.1.0.0/16"), p.direct)
        assertEquals(listOf("domain:other.example"), p.server)
        assertEquals(listOf("com.example.app"), p.apps)
        assertEquals(listOf("not an app", "???"), p.skipped)
        val z = CustomZone("u-1", p.name!!, p.direct, p.server, p.apps)
        val again = ZoneFile.parse(ZoneFile.write(z))
        assertEquals(p.copy(skipped = emptyList()), again)
    }

    @Test
    fun plainListsLoad() {
        val p = ZoneFile.parse("a.example\nb.example\n\n1.2.3.4\n")
        assertNull(p.name)
        assertEquals(listOf("domain:a.example", "domain:b.example", "1.2.3.4"), p.direct)
    }

    @Test
    fun regionSplitsSitesAndAddresses() {
        val r = CustomZone("u-1", "W", direct = listOf("domain:a.example", "1.2.3.0/24", "geoip:xx"), server = listOf("full:b.example", "2001:db8::/32")).toRegion()
        assertEquals(listOf("domain:a.example"), r.directDomains)
        assertEquals(listOf("1.2.3.0/24", "geoip:xx"), r.directIps)
        assertEquals(listOf("full:b.example"), r.serverDomains)
        assertEquals(listOf("2001:db8::/32"), r.serverIps)
        assertTrue(r.custom)
        assertEquals("W", r.title)
    }

    @Test
    fun coverage() {
        assertTrue(ZoneConflicts.covers("domain:example.com", "domain:api.example.com"))
        assertTrue(ZoneConflicts.covers("domain:example.com", "full:example.com"))
        assertFalse(ZoneConflicts.covers("domain:api.example.com", "domain:example.com"))
        assertFalse(ZoneConflicts.covers("domain:ample.com", "domain:example.com"))
        assertTrue(ZoneConflicts.covers("10.0.0.0/8", "10.1.2.3"))
        assertTrue(ZoneConflicts.covers("10.0.0.0/8", "10.1.0.0/16"))
        assertFalse(ZoneConflicts.covers("10.1.0.0/16", "10.0.0.0/8"))
        assertTrue(ZoneConflicts.covers("keyword:tube", "domain:video.tube.example"))
    }

    @Test
    fun conflictsFollowTheRuleOrder() {
        val a = CustomZone("u-a", "A", direct = listOf("domain:shop.example", "domain:news.example", "10.1.2.3"), apps = listOf("com.example.app"))
        val b = CustomZone("u-b", "B", server = listOf("domain:example", "10.0.0.0/8"))
        val c = ZoneConflicts.of(a, listOf(a, b), ownDirect = emptyList(), ownServer = listOf("news.example"), appsTunnel = setOf("com.example.app"))
        assertEquals(
            listOf(
                ZoneConflicts.Conflict("domain:shop.example", ZoneConflicts.Reason.ZONE_SERVER, "B"),
                ZoneConflicts.Conflict("domain:news.example", ZoneConflicts.Reason.OWN_SERVER),
                ZoneConflicts.Conflict("10.1.2.3", ZoneConflicts.Reason.ZONE_SERVER, "B"),
                ZoneConflicts.Conflict("com.example.app", ZoneConflicts.Reason.OWN_APP_TUNNEL),
            ),
            c,
        )
        // Through the server wins: B's own entries have nothing above them but the user's direct list.
        assertEquals(
            listOf(ZoneConflicts.Conflict("domain:example", ZoneConflicts.Reason.OWN_DIRECT)),
            ZoneConflicts.of(b, listOf(a, b), ownDirect = listOf("example"), ownServer = emptyList(), appsTunnel = emptySet()),
        )
    }

    @Test
    fun otherListFormats() {
        // Clash / Surge, AdBlock, domain-list-community.
        assertEquals("domain:a.example", ZoneFile.entry("DOMAIN-SUFFIX,a.example"))
        assertEquals("full:b.example", ZoneFile.entry("DOMAIN,b.example,extra"))
        assertEquals("keyword:tube", ZoneFile.entry("DOMAIN-KEYWORD,tube"))
        assertEquals("10.0.0.0/8", ZoneFile.entry("IP-CIDR,10.0.0.0/8,no-resolve"))
        assertNull(ZoneFile.entry("PROCESS-NAME,app"))
        assertEquals("domain:c.example", ZoneFile.entry("  - '+.c.example'"))
        assertEquals("domain:d.example", ZoneFile.entry("||d.example^"))
        assertEquals("domain:e.example", ZoneFile.entry("domain:e.example @ads"))
        assertEquals("regexp:^a{1,3}\\.example$", ZoneFile.entry("regexp:^a{1,3}\\.example$"))
        assertNull(ZoneFile.entry("include:other-list"))
        val clash = ZoneFile.parse("payload:\n  - '+.f.example'\n  - DOMAIN,g.example\n")
        assertEquals(listOf("domain:f.example", "full:g.example"), clash.direct)
        assertFalse(clash.sectioned)
        assertTrue(ZoneFile.parse("[server]\nh.example").sectioned)
        // sing-box rule-set source.
        val sb = ZoneFile.parse("""{"version":2,"rules":[{"domain":["i.example"],"domain_suffix":[".j.example"],"ip_cidr":["10.1.0.0/16"]}]}""")
        assertEquals(listOf("full:i.example", "domain:j.example", "10.1.0.0/16"), sb.direct)
    }

    /** A tiny geosite and geoip file in Xray's format. */
    @Test
    fun datFiles() {
        fun varint(v: Int): ByteArray { val out = java.io.ByteArrayOutputStream(); var x = v; while (x >= 0x80) { out.write((x and 0x7F) or 0x80); x = x ushr 7 }; out.write(x); return out.toByteArray() }
        fun field(n: Int, bytes: ByteArray) = byteArrayOf((n shl 3 or 2).toByte()) + varint(bytes.size) + bytes
        fun vfield(n: Int, v: Int) = byteArrayOf((n shl 3).toByte()) + varint(v)
        fun entry(code: String, items: List<ByteArray>) = field(1, field(1, code.toByteArray()) + items.fold(ByteArray(0)) { a, i -> a + field(2, i) })
        val sites = entry("IR", listOf(vfield(1, 2) + field(2, "a.ir".toByteArray()), field(2, "word".toByteArray()), vfield(1, 3) + field(2, "full.example".toByteArray()))) +
            entry("ADS", listOf(vfield(1, 2) + field(2, "ads.example".toByteArray())))
        val p = DatFile.read(sites)!!
        assertEquals(DatFile.Kind.SITES, p.kind)
        assertEquals(listOf("IR" to 3, "ADS" to 1), p.categories.map { it.code to it.size })
        assertEquals(listOf("domain:a.ir", "keyword:word", "full:full.example"), DatFile.rules(p.kind, p.categories[0]))
        val ips = entry("IR", listOf(field(1, byteArrayOf(10, 2, 0, 0)) + vfield(2, 16), field(1, ByteArray(16).also { it[0] = 0x20; it[1] = 0x01; it[2] = 0x0d; it[3] = 0xb8.toByte() }) + vfield(2, 32)))
        val q = DatFile.read(ips)!!
        assertEquals(DatFile.Kind.ADDRESSES, q.kind)
        assertEquals(listOf("10.2.0.0/16", "2001:db8:0:0:0:0:0:0/32"), DatFile.rules(q.kind, q.categories[0]))
        assertNull(DatFile.read("example.com\n".toByteArray()))
    }
}
