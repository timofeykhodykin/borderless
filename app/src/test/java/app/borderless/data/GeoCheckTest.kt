package app.borderless.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress

class GeoCheckTest {
    private fun varint(v: Int): ByteArray { val out = java.io.ByteArrayOutputStream(); var x = v; while (x >= 0x80) { out.write((x and 0x7F) or 0x80); x = x ushr 7 }; out.write(x); return out.toByteArray() }
    private fun field(n: Int, bytes: ByteArray) = byteArrayOf((n shl 3 or 2).toByte()) + varint(bytes.size) + bytes
    private fun vfield(n: Int, v: Int) = byteArrayOf((n shl 3).toByte()) + varint(v)
    private fun entry(code: String, items: List<ByteArray>, extra: ByteArray = ByteArray(0)) =
        field(1, field(1, code.toByteArray()) + items.fold(ByteArray(0)) { a, i -> a + field(2, i) } + extra)
    private fun site(type: Int, value: String) = vfield(1, type) + field(2, value.toByteArray())
    private fun ip(cidr: String) = field(1, InetAddress.getByName(cidr.substringBefore('/')).address) + vfield(2, cidr.substringAfter('/').toInt())
    private fun sites(raw: ByteArray) = DatFile.rules(DatFile.Kind.SITES, DatFile.read(raw)!!.categories[0])
    private fun ips(raw: ByteArray) = DatFile.rules(DatFile.Kind.ADDRESSES, DatFile.read(raw)!!.categories[0])

    private val zoneTlds = setOf("zz", "xn--p1ai")

    @Test
    fun ordinarySitesPassUnchanged() {
        val raw = entry("ZZ-1", listOf(site(2, "shop.zz"), site(3, "www.a.example"), site(2, "zz"), site(1, "^img\\d+\\.a\\.zz$"), site(0, "bankzz")))
        val r = GeoCheck.sites(raw, null) { it in zoneTlds }
        assertNull(r.refused)
        assertTrue(r.dropped.isEmpty())
        assertArrayEquals(raw, r.raw)
    }

    @Test
    fun broadSitesAreLeftOut() {
        val raw = entry("ZZ-1", listOf(
            site(2, "shop.zz"), site(2, "com"), site(0, "com"), site(0, "exam"), site(1, "."), site(1, "\\.(com|net)$"),
            site(1, "(unclosed"), site(2, "xn--p1ai"), site(3, "com"),
        ))
        val r = GeoCheck.sites(raw, null) { it in zoneTlds }
        assertNull(r.refused)
        assertEquals(listOf("domain:com", "keyword:com", "keyword:exam", "regexp:.", "regexp:\\.(com|net)$", "regexp:(unclosed"), r.dropped)
        // "full:com" is the single name "com", not everything under it.
        assertEquals(listOf("domain:shop.zz", "domain:xn--p1ai", "full:com"), sites(r.raw!!))
    }

    @Test
    fun suddenGrowthIsRefused() {
        val old = entry("ZZ-1", (1..10).map { site(2, "s$it.zz") })
        val grown = entry("ZZ-1", (1..2000).map { site(2, "s$it.zz") })
        val r = GeoCheck.sites(grown, old) { it in zoneTlds }
        assertNotNull(r.refused)
        assertNull(r.raw)
        // Small categories may grow freely; large ones up to double.
        assertNull(GeoCheck.sites(entry("ZZ-1", (1..900).map { site(2, "s$it.zz") }), old) { it in zoneTlds }.refused)
        assertNull(GeoCheck.sites(entry("ZZ-1", (1..3900).map { site(2, "s$it.zz") }), grown) { it in zoneTlds }.refused)
    }

    @Test
    fun addresses() {
        val raw = entry("ZZ", listOf(ip("5.8.0.0/16"), ip("0.0.0.0/1"), ip("128.0.0.0/1"), ip("2a00::/12"), ip("2a02:6b8::/29")))
        val r = GeoCheck.addresses(raw, null, local = false)
        assertNull(r.refused)
        assertEquals(listOf("0.0.0.0/1", "128.0.0.0/1", "2a00:0:0:0:0:0:0:0/12"), r.dropped)
        assertEquals(listOf("5.8.0.0/16", "2a02:6b8:0:0:0:0:0:0/29"), ips(r.raw!!))
        // Many /8s: more than 1/8 of IPv4.
        assertNotNull(GeoCheck.addresses(entry("ZZ", (1..40).map { ip("$it.0.0.0/8") }), null, local = false).refused)
        // Everything *outside* the ranges.
        assertEquals("inverse match", GeoCheck.addresses(entry("ZZ", listOf(ip("5.8.0.0/16")), vfield(3, 1)), null, local = false).refused)
        // Growth by more than double and by more than a /8 worth.
        val old = entry("ZZ", listOf(ip("5.0.0.0/9")))
        assertNotNull(GeoCheck.addresses(entry("ZZ", listOf(ip("5.0.0.0/9"), ip("6.0.0.0/8"), ip("7.0.0.0/8"))), old, local = false).refused)
        assertNull(GeoCheck.addresses(entry("ZZ", listOf(ip("5.0.0.0/9"), ip("6.0.0.0/12"))), old, local = false).refused)
    }

    @Test
    fun localAddressesOnlyReserved() {
        val raw = entry("PRIVATE", listOf(ip("10.0.0.0/8"), ip("224.0.0.0/3"), ip("::/127"), ip("fc00::/7"), ip("8.8.8.0/24"), ip("0.0.0.0/1")))
        val r = GeoCheck.addresses(raw, null, local = true)
        assertNull(r.refused)
        assertEquals(listOf("10.0.0.0/8", "224.0.0.0/3", "0:0:0:0:0:0:0:0/127", "fc00:0:0:0:0:0:0:0/7"), ips(r.raw!!))
        assertEquals(2, r.dropped.size)
        // The local names: one-label names only (the pattern matches no ordinary name).
        val names = entry("PRIVATE", listOf(site(2, "local"), site(2, "lan"), site(1, "^[a-z]([a-z0-9-]{0,61}[a-z0-9])?$"), site(2, "com")))
        assertEquals(listOf("domain:com"), GeoCheck.sites(names, null) { it in GeoCheck.LOCAL_TLDS }.dropped)
    }

    @Test
    fun zoneTldsFromRegions() {
        // The zones' own and their listed extra names pass; others don't.
        assertTrue(Regions.bareTld("xn--p1ai"))
        assertTrue(Regions.bareTld("su"))
        assertFalse(Regions.bareTld("com"))
        val direct = Regions.directCategories()
        assertTrue("PRIVATE" in direct["geoip.dat"]!! && "PRIVATE" in direct["geosite.dat"]!!)
        assertFalse("CATEGORY-ADS-ALL" in direct["geosite.dat"]!!)
    }

    /**
     * Dev check on real files: `BORDERLESS_GEO=<dir with geoip.dat, geosite.dat>` (e.g. copied off the emulator) —
     * every direct category must pass untouched.
     */
    @Test
    fun realLists() {
        val dir = System.getenv("BORDERLESS_GEO")?.let(::File) ?: return
        val direct = Regions.directCategories()
        for (name in listOf("geoip.dat", "geosite.dat")) {
            for (e in GeoFile.entries(File(dir, name).readBytes())) {
                val code = e.code.uppercase()
                if (code !in direct[name]!!) continue
                val local = code == "PRIVATE"
                val r = if (name == "geoip.dat") GeoCheck.addresses(e.raw, e.raw, local)
                else GeoCheck.sites(e.raw, e.raw) { if (local) it in GeoCheck.LOCAL_TLDS else Regions.bareTld(it) }
                println("$name $code: dropped ${r.dropped.take(10)} refused ${r.refused}")
                assertNull(r.refused)
                assertTrue("$code ${r.dropped}", r.dropped.isEmpty())
            }
        }
    }
}
